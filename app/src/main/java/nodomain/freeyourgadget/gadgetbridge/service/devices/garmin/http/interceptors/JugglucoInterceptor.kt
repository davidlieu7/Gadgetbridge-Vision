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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Serves CGM data to Connect IQ apps (CGM+, its datafield, or any nDrip+
 * watch companion) that fetch the xDrip+/Juggluco web-server URL, e.g.
 * http://127.0.0.1:17580/sgv.json or http://127.0.0.1:7147/sgv.json.
 *
 * With the vendor app the phone executes those requests itself. Under
 * Gadgetbridge the watch sends the URL over the GFDI HTTP channel, so this
 * interceptor matches requests to the xDrip/Juggluco server ports (17580 and
 * 7147) and answers them locally on the phone — no internet access required.
 *
 * Primary source (opt-in, off by default): Health Connect, enabled by the
 * "nDrip+ Health Connect Proxy" switch in the Garmin experimental settings
 * (key [GarminPrefs.PREF_CGM_HC_PROXY]). Any nDrip+ compatible CGM source
 * (e.g. Juggluco) writes its readings to Health Connect, so the watch app's
 * nDrip+ endpoints are rendered directly from the [BloodGlucoseRecord] and
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
 * Fallback: with the switch off (the default), or when Health Connect is
 * unavailable, lacks the read permission, or has no fresh data, the request
 * is proxied to the local nDrip+ server (Juggluco on 17580, xDrip+ on 7147)
 * at the port the watch requested. Other nDrip endpoints always go to the
 * proxy.
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
                    // nDrip+ Health Connect Proxy (opt-in, off by default) first;
                    // the Juggluco proxy is the default path and the fallback.
                    (if (hcProxyEnabled()) serveFromHealthConnect(request) else null) ?: fetchLocally(request)
                }
                sendResponseOnMain(request, response)
            } catch (t: Throwable) {
                // Catch Throwable (not just Exception): an uncaught Error here
                // would kill the whole Gadgetbridge process.
                LOG.error("Juggluco proxy request failed: {}", request.uri, t)
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
     * Serve the requested nDrip+ endpoint from Health Connect, or return null
     * to fall through to the local nDrip+ proxy.
     *
     * Dispatches by path: /sgv.json (glucose) and the treatments endpoints
     * (carbs). Unknown paths, a missing Health Connect client, and an ungranted
     * read permission all fall through to the proxy.
     */
    private suspend fun serveFromHealthConnect(request: GarminHttpRequest): GarminHttpResponse? {
        val client = getHcClient()
        if (client == null) {
            LOG.debug("Health Connect unavailable, falling back to nDrip proxy")
            return null
        }
        return when (request.path) {
            PATH_SGV_JSON -> serveSgv(client, request)
            PATH_TREATMENTS_JSON, PATH_TREATMENTS -> serveTreatments(client, request, v3 = false)
            PATH_API_TREATMENTS_JSON, PATH_API_TREATMENTS -> serveTreatments(client, request, v3 = true)
            else -> null
        }
    }

    /**
     * Render /sgv.json from Health Connect glucose records.
     *
     * Returns null when the request should fall through to the proxy: read
     * permission not granted, no records in the window, or the newest record
     * is too old (sensor off).
     *
     * The JSON mirrors Juggluco's watchserver /sgv.json output exactly:
     * newest-first, entries at least [interval] seconds apart, "sgv" in mg/dL,
     * "delta" the 5-minute change, "direction" from the mg/dL-per-minute rate,
     * and "units_hint" on the first (newest) entry.
     */
    private suspend fun serveSgv(client: HealthConnectClient, request: GarminHttpRequest): GarminHttpResponse? {
        val readPermission = HealthPermission.getReadPermission(BloodGlucoseRecord::class)
        if (readPermission !in client.permissionController.getGrantedPermissions()) {
            LOG.info("Health Connect permission {} not granted, falling back to nDrip proxy", readPermission)
            return null
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
            LOG.info("No glucose records in Health Connect, falling back to nDrip proxy")
            return null
        }

        val newestAgeSec = Duration.between(records.first().time, now).toSeconds()
        if (newestAgeSec > MAX_STALE_SEC) {
            LOG.info("Newest glucose record is {}s old (> {}s), falling back to nDrip proxy",
                newestAgeSec, MAX_STALE_SEC)
            return null
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
        return jsonResponse(200, body)
    }

    /**
     * Render the nDrip+ treatments endpoint from Health Connect carb records.
     *
     * Health Connect has no insulin record type in any SDK version, so only
     * carb treatments can be reconstructed; they are emitted with "insulin":
     * null (v2) or simply "carbs" (v3), exactly as Juggluco marks a carb-only
     * entry. When the read permission is not granted the request falls through
     * to the proxy (so Juggluco's own treatments, including insulin, can still
     * be served). Once granted, an empty carb history yields a valid empty
     * treatments response rather than a 502, keeping the HC proxy authoritative.
     */
    private suspend fun serveTreatments(
        client: HealthConnectClient,
        request: GarminHttpRequest,
        v3: Boolean
    ): GarminHttpResponse? {
        val readPermission = HealthPermission.getReadPermission(NutritionRecord::class)
        if (readPermission !in client.permissionController.getGrantedPermissions()) {
            LOG.info("Health Connect permission {} not granted, falling back to nDrip proxy", readPermission)
            return null
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
        return jsonResponse(200, body)
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
     * Whether the opt-in "nDrip+ Health Connect Proxy" is enabled for this device.
     * Read live on every request so the toggle takes effect without a
     * reconnect; goes through [experimentalSettingEnabled], so it also
     * respects the global experimental-settings master switch, like the rest
     * of that settings screen.
     */
    private fun hcProxyEnabled(): Boolean {
        val device = deviceSupport.getDevice() ?: return false
        return deviceSupport.getCoordinator().experimentalSettingEnabled(device, GarminPrefs.PREF_CGM_HC_PROXY)
    }

    private fun fetchLocally(request: GarminHttpRequest): GarminHttpResponse {
        val uri = request.uri
        var target = "http://127.0.0.1:${uri.port}${uri.path}"
        val query = uri.encodedQuery
        if (!query.isNullOrEmpty()) {
            target += "?" + query
        }

        LOG.debug("Proxying CGM request to Juggluco: {}", target)

        val connection = URL(target).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = request.method

        request.headers.forEach { (name, value) ->
            if (name.lowercase() in SKIP_HEADERS) return@forEach
            try {
                connection.setRequestProperty(name, value)
            } catch (e: IllegalArgumentException) {
                LOG.debug("Skipping header {} with value {}", name, value, e)
            }
        }

        val body = request.bodyToSend
        if (body != null && body.isNotEmpty()) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }

        val status = connection.responseCode
        val response = GarminHttpResponse()
        response.status = status
        response.headers["content-type"] = connection.contentType ?: "application/json"

        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        response.body = stream?.use { readAllBytes(it) } ?: ByteArray(0)

        if (response.body.size > MAX_LOG_BODY) {
            LOG.info("Juggluco response: status={} bytes={}", status, response.body.size)
        }

        connection.disconnect()
        return response
    }

    private fun jsonResponse(status: Int, body: ByteArray): GarminHttpResponse {
        val response = GarminHttpResponse()
        response.status = status
        response.headers["content-type"] = "application/json"
        response.body = body
        return response
    }

    private fun readAllBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(JugglucoInterceptor::class.java)
        private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Juggluco web server port (xDrip/Nightscout protocol). */
        private const val JUGGLUCO_PORT = 17580
        /** xDrip+ web server port (same protocol, different default port). */
        private const val XDRIp_PORT = 7147
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 10000
        private const val MAX_LOG_BODY = 2048

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
        /** If the newest HC record is older than this, the sensor is off — use the proxy. */
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

        // Headers that must not be forwarded verbatim to the local server.
        // accept-encoding is dropped so the response is always plain (identity).
        private val SKIP_HEADERS = setOf("host", "connection", "accept-encoding", "content-length")

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
