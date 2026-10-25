/*  Copyright (C) 2026 Gadgetbridge

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.interceptors

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSmartProto.Smart
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.GarminPrefs
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.GarminSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.ProtobufResponseEvent
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.GarminHttpRequest
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.GarminHttpResponse
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.HttpHandler
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectClientProvider
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Serves CGM data to Connect IQ apps (CGM+, its datafield, or any xDrip+
 * watch companion) that fetch the xDrip+/Juggluco web-server URL, e.g.
 * http://127.0.0.1:17580/sgv.json or http://127.0.0.1:7147/sgv.json.
 *
 * With the vendor app the phone executes those requests itself. Under
 * Gadgetbridge the watch sends the URL over the GFDI HTTP channel, so this
 * interceptor matches requests to the xDrip/Juggluco server ports (17580 and
 * 7147) and answers them locally on the phone — no internet access required.
 *
 * Opt-in (off by default): Health Connect, enabled by the "xDrip+ Health
 * Connect Proxy" switch in the Garmin experimental settings
 * (key [GarminPrefs.PREF_CGM_HC_PROXY]). Any xDrip+ compatible CGM source
 * (e.g. Juggluco) writes its readings to Health Connect, so the watch app's
 * xDrip+ endpoints are rendered directly from the [BloodGlucoseRecord] and
 * [NutritionRecord] entries — the same xDrip/Nightscout JSON the watch app
 * already parses, with no socket to the Juggluco process. This sidesteps the
 * cross-app loopback problem (socket failed: EPERM) that a direct proxy to
 * Juggluco's local server hits on some devices.
 *
 * Endpoints served from Health Connect: /sgv.json (glucose, from
 * [BloodGlucoseRecord]) and /treatments.json + /api/treatments.json
 * (carbs, from [NutritionRecord]). Health Connect has no insulin record type
 * in any SDK version, so insulin boluses cannot be reconstructed from it —
 * carb treatments are emitted with "insulin":null, exactly as Juggluco marks
 * a carb-only entry.
 *
 * There is no proxy fallback. With the switch off (the default),
 * [supports] returns false and the request falls through to the firewall,
 * which blocks the loopback and returns a clean "no data" response. With
 * the switch on but Health Connect unavailable, the read permission not
 * granted, no fresh data, or an unknown path, [handle] answers with a 502
 * whose body names the reason (hc_no_data). No internet permission is
 * required for the served endpoints.
 *
 * Troubleshooting: the watch app surfaces any 502 as "invalid data format",
 * so a 502 usually means the Health Connect read produced no servable data
 * — most commonly the read permission is not granted. Grant it in
 * Gadgetbridge (Settings → Health Connect → enable blood glucose sync, and
 * carb/nutrition for treatments) and make sure the CGM source (e.g.
 * Juggluco) is running and writing fresh records to Health Connect. The
 * exact cause is logged on the line just before the 502 and carried in the
 * 502 body's "message" field.
 *
 * Medical disclaimer: the values served are for personal information only.
 * This is not a medical device and is not intended for medical treatment,
 * diagnosis, or as a substitute for professional medical advice.
 *
 * The handler is asynchronous: [handle] returns an incomplete [GarminHttpResponse]
 * immediately (so the GFDI protocol thread is never blocked) and completes the
 * response on the main dispatcher, mirroring the [HealthConnectInterceptor] pattern.
 */
class JugglucoInterceptor(private val deviceSupport: GarminSupport) : HttpInterceptor {

    override fun supports(request: GarminHttpRequest): Boolean {
        // Opt-in: only claim the request when the xDrip+ Health Connect Proxy
        // is enabled for this device. With it off the request falls through to
        // the firewall, which blocks the loopback and returns a clean "no data"
        // response (no data served, no socket opened).
        if (!hcProxyEnabled()) return false
        // Ports 17580 (Juggluco) and 7147 (xDrip+) are the xDrip/Nightscout
        // web-server ports and are not used by anything else on the phone, so
        // match any host on either port. The CGM+ datafield may be pointed at
        // 127.0.0.1 (loopback, the common xDrip watch setup) or at the phone's
        // LAN IP; both must be captured and answered.
        return request.uri.port == JUGGLUCO_PORT || request.uri.port == XDRIp_PORT
    }

    override fun handle(request: GarminHttpRequest): GarminHttpResponse? {
        SCOPE.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    // Health Connect is the only source. serveFromHealthConnect
                    // returns an HcResult whose response is null when there is
                    // nothing to serve (no HC client, permission not granted, no
                    // records, stale, or an unknown path); the reason names the
                    // cause so the 502 body is self-documenting (the watch app
                    // surfaces any 502 as "invalid data format").
                    val result = serveFromHealthConnect(request)
                    result.response ?: jsonResponse(
                        502,
                        JsonObject().apply {
                            addProperty("ok", false)
                            addProperty("error", "hc_no_data")
                            addProperty("message", "no data served from Health Connect: " + (result.reason ?: "unknown"))
                        }.toString().toByteArray(Charsets.UTF_8)
                    )
                }
                sendResponseOnMain(request, response)
            } catch (t: Throwable) {
                // Catch Throwable (not just Exception): an uncaught Error here
                // would kill the whole Gadgetbridge process.
                LOG.error("CGM request failed: {}", request.uri, t)
                sendResponseOnMain(
                    request,
                    jsonResponse(
                        502,
                        JsonObject().apply {
                            addProperty("ok", false)
                            addProperty("error", "juggluco_unavailable")
                            addProperty("message", t.javaClass.simpleName + ": " + (t.message ?: "no message"))
                        }.toString().toByteArray(Charsets.UTF_8)
                    )
                )
            }
        }

        val incomplete = GarminHttpResponse()
        incomplete.setComplete(false)
        return incomplete
    }

    /**
     * Dispatch the protobuf response onto the main thread. The GFDI communicator's
     * [sendMessage] is not safe to call from an arbitrary background dispatcher
     * thread; the proven [FirewallInterceptor] sends its async response from a
     * Binder/main thread context. Mirroring that avoids the process-level crash.
     */
    private suspend fun sendResponseOnMain(request: GarminHttpRequest, response: GarminHttpResponse) =
        withContext(Dispatchers.Main) {
            val service = HttpHandler.createSuccessResponse(request, response)
                ?: HttpHandler.createErrorResponse(request)
            val smart = Smart.newBuilder().setConnectIqHttpService(service).build()
            deviceSupport.evaluateGBDeviceEvent(
                ProtobufResponseEvent(smart, request.messageRequestId)
            )
        }

    /**
     * Outcome of serving an xDrip+ endpoint from Health Connect. [response] is
     * the rendered 200 body, or null when there is nothing to serve; when
     * null, [reason] carries a human-readable cause (unavailable, permission
     * not granted, no records, stale, unknown path) so the caller can surface
     * a self-documenting 502.
     */
    private data class HcResult(
        val response: GarminHttpResponse?,
        val reason: String? = null
    )

    /**
     * Serve the requested xDrip+ endpoint from Health Connect, or return a
     * null [HcResult.response] (with a [HcResult.reason]) so the caller
     * responds with a self-documenting 502 (hc_no_data).
     *
     * Dispatches by path: /sgv.json (glucose) and the treatments endpoints
     * (carbs). Unknown paths, a missing Health Connect client, an ungranted
     * read permission, and no fresh data all yield a 502; the reason names
     * which, so logcat and the 502 body both say why.
     */
    private suspend fun serveFromHealthConnect(request: GarminHttpRequest): HcResult {
        val client = getHcClient()
        if (client == null) {
            LOG.debug("Serving 502 (hc_no_data): Health Connect unavailable")
            return HcResult(null, "Health Connect unavailable")
        }
        return when (request.path) {
            PATH_SGV_JSON -> serveSgv(client, request)
            PATH_TREATMENTS_JSON, PATH_TREATMENTS -> serveTreatments(client, request, v3 = false)
            PATH_API_TREATMENTS_JSON, PATH_API_TREATMENTS -> serveTreatments(client, request, v3 = true)
            else -> HcResult(null, "unknown path ${request.path}")
        }
    }

    /**
     * Render /sgv.json from Health Connect glucose records.
     *
     * Returns a null [HcResult.response] (mapped to a 502) when: read
     * permission not granted, no records in the window, or the newest record
     * is too old (sensor off); the [HcResult.reason] names which.
     *
     * The JSON mirrors Juggluco's watchserver /sgv.json output exactly:
     * newest-first, entries at least [interval] seconds apart, "sgv" in mg/dL,
     * "delta" the 5-minute change, "direction" from the mg/dL-per-minute rate,
     * and "units_hint" on the first (newest) entry.
     */
    private suspend fun serveSgv(client: HealthConnectClient, request: GarminHttpRequest): HcResult {
        val readPermission = HealthPermission.getReadPermission(BloodGlucoseRecord::class)
        if (readPermission !in client.permissionController.getGrantedPermissions()) {
            LOG.info("Serving 502 (hc_no_data): Health Connect permission {} not granted", readPermission)
            return HcResult(null, "Health Connect permission $readPermission not granted")
        }

        val count = request.query["count"]?.toIntOrNull()?.coerceIn(1, MAX_COUNT) ?: 1
        val intervalSec = request.query["interval"]?.toIntOrNull()?.coerceIn(30, 3600) ?: DEFAULT_INTERVAL_SEC
        val brief = request.query["brief_mode"] == "true"

        val now = Instant.now()
        val windowStart = now.minusSeconds(count.toLong() * intervalSec + REFRESH_SLACK_SEC)
        val result = client.readRecords(
            ReadRecordsRequest(
                recordType = BloodGlucoseRecord::class,
                timeRangeFilter = TimeRangeFilter.between(windowStart, now),
                ascendingOrder = false,
                pageSize = count
            )
        )
        val records = result.records.filterIsInstance<BloodGlucoseRecord>()
        if (records.isEmpty()) {
            LOG.info("Serving 502 (hc_no_data): no glucose records in Health Connect")
            return HcResult(null, "no glucose records in Health Connect")
        }

        val newestAgeSec = Duration.between(records.first().time, now).toSeconds()
        if (newestAgeSec > MAX_STALE_SEC) {
            LOG.info("Serving 502 (hc_no_data): newest glucose record is {}s old (> {}s)",
                newestAgeSec, MAX_STALE_SEC)
            return HcResult(null, "newest glucose record is ${newestAgeSec}s old (> ${MAX_STALE_SEC}s)")
        }

        // Mirror Juggluco's getitems(): newest first, each entry at least
        // `interval` seconds older than the previous one.
        val kept = ArrayList<BloodGlucoseRecord>(count)
        var lastTime: Instant? = null
        for (record in records) {
            val last = lastTime
            if (last == null || Duration.between(record.time, last).toMillis() >= intervalSec * 1000L) {
                kept.add(record)
                lastTime = record.time
                if (kept.size == count) break
            }
        }

        val body = renderSgvJson(records, kept, brief)
        LOG.info("Serving sgv.json from Health Connect: {} entries, newest {}s old, brief={}",
            kept.size, newestAgeSec, brief)
        return HcResult(jsonResponse(200, body))
    }

    /**
     * Render the xDrip+ treatments endpoint from Health Connect carb records.
     *
     * Health Connect has no insulin record type in any SDK version, so only
     * carb treatments can be reconstructed; they are emitted with "insulin":
     * null (v2) or simply "carbs" (v3), exactly as Juggluco marks a carb-only
     * entry. When the read permission is not granted the request is answered
     * with a 502. Once granted, an empty carb history yields a valid empty
     * treatments response rather than a 502, keeping the Health Connect source
     * authoritative.
     */
    private suspend fun serveTreatments(
        client: HealthConnectClient,
        request: GarminHttpRequest,
        v3: Boolean
    ): HcResult {
        val readPermission = HealthPermission.getReadPermission(NutritionRecord::class)
        if (readPermission !in client.permissionController.getGrantedPermissions()) {
            LOG.info("Serving 502 (hc_no_data): Health Connect permission {} not granted", readPermission)
            return HcResult(null, "Health Connect permission $readPermission not granted")
        }

        val now = Instant.now()
        val windowStart = now.minusSeconds(TREATMENT_WINDOW_SEC)
        val result = client.readRecords(
            ReadRecordsRequest(
                recordType = NutritionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(windowStart, now),
                ascendingOrder = false,
                pageSize = MAX_TREATMENTS
            )
        )
        // Carb entries only: a NutritionRecord with a positive carbohydrate
        // mass is a carb treatment. (Insulin is not a Health Connect type.)
        val carbRecords = result.records
            .filterIsInstance<NutritionRecord>()
            .filter { (it.totalCarbohydrate?.inGrams ?: 0.0) > 0.0 }

        val body = renderTreatmentsJson(carbRecords, v3)
        LOG.info(
            "Serving {} from Health Connect: {} carb entries (insulin not an HC type)",
            if (v3) "/api/treatments.json" else "/treatments.json", carbRecords.size
        )
        return HcResult(jsonResponse(200, body))
    }

    /**
     * Build the xDrip/Nightscout treatments JSON from Health Connect carb
     * records, byte-compatible with Juggluco's watchserver output.
     *
     * v2 (/treatments.json) is a bare array; v3 (/api/treatments.json) is
     * {"status":200,"result":[...]}. A carb entry carries "carbs":<g> and
     * "insulin":null (v2) — the exact shape Juggluco uses for a carb-only
     * treatment. Timestamps are ISO-8601 UTC with a ".000Z" suffix, matching
     * Juggluco's gmtime_r() output.
     */
    private fun renderTreatmentsJson(records: List<NutritionRecord>, v3: Boolean): ByteArray {
        val array = JsonArray()
        records.forEachIndexed { index, record ->
            val carbs = record.totalCarbohydrate?.inGrams ?: 0.0
            val ms = record.startTime.toEpochMilli()
            val isoUtc = record.startTime.atZone(ZoneOffset.UTC).format(TREATMENT_DATE_FORMAT)
            val item = JsonObject()
            if (v3) {
                item.addProperty("app", "Juggluco")
                item.addProperty("eventType", "<none>")
                item.addProperty("created_at", isoUtc)
                item.addProperty("date", ms)
                item.addProperty("carbs", carbs)
                item.addProperty("utcOffset", 0)
                item.addProperty("identifier", "GadgetbridgeHC#$index")
                item.addProperty("srvModified", ms)
                item.addProperty("srvCreated", ms)
            } else {
                item.addProperty("_id", "GadgetbridgeHC#$index")
                item.addProperty("date", ms)
                item.addProperty("eventType", "<none>")
                item.addProperty("enteredBy", "Juggluco")
                item.addProperty("created_at", isoUtc)
                item.addProperty("carbs", carbs)
                item.add("insulin", JsonNull.INSTANCE)
            }
            array.add(item)
        }
        val payload = if (v3) {
            JsonObject().apply {
                addProperty("status", 200)
                add("result", array)
            }.toString()
        } else {
            array.toString()
        }
        return payload.toByteArray(Charsets.UTF_8)
    }

    /**
     * Build the xDrip/Nightscout sgv.json array from Health Connect records,
     * byte-compatible with Juggluco's watchserver output:
     *
     * brief mode (what CGM+ requests):
     *   [{"date":ms,"sgv":mgdL,"delta":5min,"direction":"Flat","noise":1,"units_hint":"mmol"}, ...]
     * full mode:
     *   [{"_id":...,"device":"Juggluco","dateString":...,"sysTime":...,"date":ms,"sgv":mgdL,
     *     "delta":5min,"direction":"Flat","noise":1,"filtered":mgdL*1000,"unfiltered":mgdL*1000,
     *     "rssi":100,"type":"sgv","units_hint":"mmol"}, ...]
     *
     * [all] is the full newest-first record list (used for delta references);
     * [kept] is the interval-deduplicated subset that is emitted.
     */
    private fun renderSgvJson(
        all: List<BloodGlucoseRecord>,
        kept: List<BloodGlucoseRecord>,
        brief: Boolean
    ): ByteArray {
        val array = JsonArray()
        kept.forEachIndexed { index, record ->
            val mgdL = record.level.inMilligramsPerDeciliter
            val sgv = Math.round(mgdL).toInt()

            // 5-minute delta: Juggluco reports delta = rate * 5 where rate is
            // mg/dL per minute. Approximate the rate with the chord to the
            // next older record (the one Juggluco's internal history would use).
            val allIndex = all.indexOf(record)
            val reference = if (allIndex in 0 until all.size - 1) {
                val ref = all[allIndex + 1]
                if (Duration.between(ref.time, record.time).toMillis() <= MAX_DELTA_WINDOW_MS) ref else null
            } else null
            val delta: Double
            val direction: String
            if (reference != null) {
                val minutes = Duration.between(reference.time, record.time).toMillis() / 60000.0
                val rate = (mgdL - reference.level.inMilligramsPerDeciliter) / minutes
                delta = rate * 5.0
                direction = directionName(rate)
            } else {
                delta = 0.0
                direction = ""
            }

            val item = JsonObject()
            if (!brief) {
                item.addProperty("_id", "GadgetbridgeHC#$index")
                item.addProperty("device", "Juggluco")
                val dateStr = record.time.atZone(ZoneId.systemDefault()).format(DATE_FORMAT)
                item.addProperty("dateString", dateStr)
                item.addProperty("sysTime", dateStr)
            }
            item.addProperty("date", record.time.toEpochMilli())
            item.addProperty("sgv", sgv)
            item.addProperty("delta", delta)
            item.addProperty("direction", direction)
            item.addProperty("noise", 1)
            if (!brief) {
                item.addProperty("filtered", sgv.toLong() * 1000L)
                item.addProperty("unfiltered", sgv.toLong() * 1000L)
                item.addProperty("rssi", 100)
                item.addProperty("type", "sgv")
            }
            if (index == 0) {
                item.addProperty("units_hint", UNITS_HINT)
            }
            array.add(item)
        }
        return array.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Juggluco's getdeltaindex(): rate in mg/dL per minute.
     */
    private fun directionName(rate: Double): String = when {
        rate.isNaN() -> ""
        rate >= DOUBLE_THRESHOLD -> "DoubleUp"
        rate >= 2.0 -> "SingleUp"
        rate >= 1.0 -> "FortyFiveUp"
        rate > -1.0 -> "Flat"
        rate > -2.0 -> "FortyFiveDown"
        rate > -DOUBLE_THRESHOLD -> "SingleDown"
        else -> "DoubleDown"
    }

    /**
     * Whether the opt-in "xDrip+ Health Connect Proxy" is enabled for this device.
     * Read live on every request so the toggle takes effect without a
     * reconnect; goes through [experimentalSettingEnabled], so it also
     * respects the global experimental-settings master switch, like the rest
     * of that settings screen.
     */
    private fun hcProxyEnabled(): Boolean {
        val device = deviceSupport.getDevice() ?: return false
        return deviceSupport.getCoordinator().experimentalSettingEnabled(device, GarminPrefs.PREF_CGM_HC_PROXY)
    }

    private fun jsonResponse(status: Int, body: ByteArray): GarminHttpResponse {
        val response = GarminHttpResponse()
        response.status = status
        response.headers["content-type"] = "application/json"
        response.body = body
        return response
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(JugglucoInterceptor::class.java)
        private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Juggluco web server port (xDrip/Nightscout protocol). */
        private const val JUGGLUCO_PORT = 17580
        /** xDrip+ web server port (same protocol, different default port). */
        private const val XDRIp_PORT = 7147

        private const val PATH_SGV_JSON = "/sgv.json"
        private const val PATH_TREATMENTS = "/treatments"
        private const val PATH_TREATMENTS_JSON = "/treatments.json"
        private const val PATH_API_TREATMENTS = "/api/treatments"
        private const val PATH_API_TREATMENTS_JSON = "/api/treatments.json"
        private const val DEFAULT_INTERVAL_SEC = 300
        private const val MAX_COUNT = 300
        /** Treatments are discrete events; look back 24 h for recent carbs. */
        private const val TREATMENT_WINDOW_SEC = 24L * 60L * 60L
        private const val MAX_TREATMENTS = 100
        /** Slacked window so a few entries fit even with polling gaps. */
        private const val REFRESH_SLACK_SEC = 3600L
        /** If the newest HC record is older than this, the sensor is off — serve 502. */
        private const val MAX_STALE_SEC = 900L
        /** Ignore delta references farther apart than this (45 min). */
        private const val MAX_DELTA_WINDOW_MS = 45L * 60L * 1000L
        private const val DOUBLE_THRESHOLD = 3.0
        /** Match the user's Juggluco display setting (mmol/L region). */
        private const val UNITS_HINT = "mmol"

        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

        /** UTC ISO-8601 with a fixed ".000Z", matching Juggluco's gmtime_r() treatment timestamps. */
        private val TREATMENT_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000'Z'")

        @Volatile
        private var cachedHcClient: HealthConnectClient? = null

        private fun getHcClient(): HealthConnectClient? {
            cachedHcClient?.let { return it }
            val context = GBApplication.getContext() ?: return null
            val client = HealthConnectClientProvider.healthConnectInit(context)
            if (client != null) {
                cachedHcClient = client
            }
            return client
        }
    }
}
