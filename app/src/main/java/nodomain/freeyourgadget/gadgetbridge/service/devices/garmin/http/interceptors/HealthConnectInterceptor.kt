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
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSmartProto.Smart
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.GarminSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.ProtobufResponseEvent
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.GarminHttpRequest
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.GarminHttpResponse
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.HttpHandler
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectClientProvider
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectWatchDataType
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves Health Connect data to Connect IQ apps on Garmin watches.
 *
 * Watches with the GFDI HTTP capability can issue HTTP requests that Gadgetbridge
 * answers on the phone side. This interceptor answers a fixed sentinel URL so that
 * watch faces and other Connect IQ apps can read the latest Health Connect records
 * without any internet access and without the Garmin Connect app:
 *
 *     GET https://health.gadgetbridge.org/hc/latest?type=blood_glucose[&count=N]
 *
 * The handler is asynchronous: [handle] returns an incomplete [GarminHttpResponse]
 * immediately (so the GFDI protocol thread is never blocked) and completes the
 * response on a background dispatcher once the Health Connect query finishes,
 * mirroring the [FirewallInterceptor] pattern. The Health Connect client is cached
 * and pre-warmed at device-connect time so the first request is fast.
 */
class HealthConnectInterceptor(private val deviceSupport: GarminSupport) : HttpInterceptor {

    init {
        // Pre-warm the Health Connect client in the background so the first
        // watch request does not pay the one-time client-init cost.
        SCOPE.launch {
            try {
                getCachedClient()
                LOG.info("Health Connect client pre-warmed")
            } catch (t: Throwable) {
                // Catch Throwable (not just Exception): an uncaught Error here
                // would kill the whole Gadgetbridge process.
                LOG.debug("Health Connect pre-warm failed (will retry on first request)", t)
            }
        }
    }

    override fun supports(request: GarminHttpRequest): Boolean {
        return request.domain == DOMAIN && request.path == PATH_LATEST
    }

    override fun handle(request: GarminHttpRequest): GarminHttpResponse? {
        // Run the query off the GFDI thread; return an incomplete response so
        // HttpHandler treats this as an async request.
        SCOPE.launch {
            try {
                val response = handleInternal(request)
                sendResponseOnMain(request, response)
            } catch (t: Throwable) {
                // Catch Throwable (not just Exception): an uncaught Error here
                // would kill the whole Gadgetbridge process.
                LOG.error("Failed to handle health connect request", t)
                writeCrashLog(t)
                sendResponseOnMain(
                    request,
                    jsonResponse(
                        500,
                        JsonObject().apply {
                            addProperty("ok", false)
                            addProperty("error", "internal_error")
                            addProperty("message", t.javaClass.simpleName + ": " + (t.message ?: "no message"))
                        }
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

    private fun writeCrashLog(t: Throwable) {
        try {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            // App-specific external dir is always writable (scoped storage, API 30+).
            // The /sdcard/ root is NOT writable for an app targeting SDK 35.
            val ctx = GBApplication.getContext()
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val file = java.io.File(dir, "hcglucose_crash.log")
            file.writeText(
                "HealthConnectInterceptor crash at ${Instant.now()} dir=${dir.absolutePath}\n" +
                    sw.toString()
            )
        } catch (e: Exception) {
            LOG.debug("Failed to write crash log", e)
        }
    }

    private suspend fun handleInternal(request: GarminHttpRequest): GarminHttpResponse =
        withContext(Dispatchers.IO) {
            val type = request.query["type"]
            val dataType = HealthConnectWatchDataType.byName(type)
            if (dataType == null) {
                LOG.warn("Unknown health connect type: {}", type)
                return@withContext jsonResponse(
                    400,
                    JsonObject().apply {
                        addProperty("ok", false)
                        addProperty("error", "unknown_type")
                        addProperty("type", type ?: "")
                    }
                )
            }

            val client = getCachedClient()
            if (client == null) {
                LOG.warn("Health Connect SDK unavailable")
                return@withContext jsonResponse(
                    503,
                    JsonObject().apply {
                        addProperty("ok", false)
                        addProperty("error", "health_connect_unavailable")
                    }
                )
            }

            val count = request.query["count"]?.toIntOrNull()?.coerceIn(1, MAX_RECORD_COUNT) ?: 1

            val granted = client.permissionController.getGrantedPermissions()
            if (dataType.readPermission !in granted) {
                LOG.info("Health Connect read permission not granted for {}", dataType.name)
                return@withContext jsonResponse(
                    403,
                    JsonObject().apply {
                        addProperty("ok", false)
                        addProperty("error", "permission_denied")
                        addProperty("type", dataType.name)
                    }
                )
            }

            val query = ReadRecordsRequest(
                recordType = dataType.recordClass,
                timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now()),
                ascendingOrder = false,
                pageSize = count
            )
            val result = client.readRecords(query)

            val records = JsonArray()
            for (record in result.records) {
                val extracted = dataType.extract(record) ?: continue
                records.add(
                    JsonObject().apply {
                        addProperty("value", extracted.value)
                        addProperty("unit", extracted.unit)
                        addProperty("timestamp_ms", recordTimestamp(record))
                        extracted.extra?.let { add("extra", it) }
                    }
                )
            }

            val latest = records.firstOrNull()?.asJsonObject
            jsonResponse(
                200,
                JsonObject().apply {
                    addProperty("ok", true)
                    addProperty("type", dataType.name)
                    if (latest != null) {
                        add("value", latest.get("value"))
                        add("unit", latest.get("unit"))
                        add("timestamp_ms", latest.get("timestamp_ms"))
                        addProperty(
                            "age_seconds",
                            (System.currentTimeMillis() - latest["timestamp_ms"].asLong) / 1000
                        )
                        latest.get("extra")?.let { add("extra", it) }
                    } else {
                        add("value", JsonNull.INSTANCE)
                    }
                    // Only include the full records array when explicitly requested,
                    // to keep the default response small over the BLE channel.
                    if (count > 1) {
                        add("records", records)
                    }
                }
            )
        }

    private fun jsonResponse(status: Int, body: JsonElement): GarminHttpResponse {
        val response = GarminHttpResponse()
        response.status = status
        response.headers["content-type"] = "application/json"
        response.body = body.toString().toByteArray(StandardCharsets.UTF_8)
        return response
    }

    private fun recordTimestamp(record: Record): Long {
        val methods = timestampMethods.getOrPut(record.javaClass) {
            val byName = record.javaClass.methods.associateBy { it.name }
            TimeMethods(
                time = byName["getTime"]?.takeIf { it.parameterCount == 0 },
                start = byName["getStartTime"]?.takeIf { it.parameterCount == 0 }
            )
        }
        val instant = methods.time?.invoke(record) as? Instant
            ?: methods.start?.invoke(record) as? Instant
        return instant?.toEpochMilli() ?: 0L
    }

    private data class TimeMethods(val time: Method?, val start: Method?)

    companion object {
        private val LOG = LoggerFactory.getLogger(HealthConnectInterceptor::class.java)
        private val timestampMethods = ConcurrentHashMap<Class<*>, TimeMethods>()
        private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var cachedClient: HealthConnectClient? = null

        private fun getCachedClient(): HealthConnectClient? {
            cachedClient?.let { return it }
            val context = GBApplication.getContext() ?: return null
            val client = HealthConnectClientProvider.healthConnectInit(context)
            if (client != null) {
                cachedClient = client
            }
            return client
        }

        const val DOMAIN = "health.gadgetbridge.org"
        const val PATH_LATEST = "/hc/latest"
        private const val MAX_RECORD_COUNT = 24
    }
}
