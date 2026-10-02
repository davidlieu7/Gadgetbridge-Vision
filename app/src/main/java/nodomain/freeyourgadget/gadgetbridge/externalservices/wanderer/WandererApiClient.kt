/*  Copyright (C) 2026 Arjan Schrijver

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.wanderer

import android.content.Context
import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceHttp
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

/** What a write to an existing trail came to. */
sealed interface WandererWriteResult {
    object Done : WandererWriteResult

    /** No trail has that id any more: it was deleted on the server. */
    object Gone : WandererWriteResult

    /** [reason] is a message for the user, null when there is nothing better than a retry. */
    data class Failed(val reason: String?) : WandererWriteResult
}

class WandererApiClient(
    private val baseUrl: String,
    private val tokenManager: WandererTokenManager
) {
    private val LOG = LoggerFactory.getLogger(WandererApiClient::class.java)

    /**
     * Build headers with authentication tokens
     */
    private fun buildHeaders(): MutableMap<String, String> {
        val headers: MutableMap<String, String> = mutableMapOf()

        tokenManager.getApiToken()?.let { token ->
            headers["Authorization"] = "Bearer $token"
        }

        return headers
    }

    /**
     * Verifies both that the server is reachable and that [apiToken] is valid, by listing the
     * account's API tokens, which only an accepted key can do. [apiToken] is passed in explicitly
     * because during setup it is not persisted yet. [callback] fires with (reachable, reason):
     * reason is null on success, otherwise a localized, user-facing explanation.
     */
    fun checkServerReachable(apiToken: String, callback: (reachable: Boolean, reason: String?) -> Unit) {
        Thread {
            val context = GBApplication.getContext()
            try {
                // networkFailureReason() (via onError) is more specific than connectFailureReason:
                // it distinguishes could-not-resolve-host / refused / timed-out / TLS.
                var networkReason: String? = null
                val response = InternetUtils.doStringRequestWithStatus(
                    uri = "$baseUrl/api/v1/api-token".toUri(),
                    requestHeaders = mapOf("Authorization" to "Bearer $apiToken"),
                    onError = { reason -> networkReason = reason }
                )
                val status = response.statusCode
                when {
                    status == null ->
                        callback(false, networkReason ?: InternetUtils.connectFailureReason(context, baseUrl))

                    status in 200..299 && hasItems(response.body) -> callback(true, null)

                    isRefusedToken(status, response.body) ->
                        callback(false, context.getString(R.string.toast_error_invalid_api_key))

                    // Not a Wanderer API at this address, such as a base path that is off.
                    else -> callback(false, context.getString(R.string.toast_error_unexpected_response))
                }
            } catch (e: Exception) {
                LOG.error("Wanderer reachability check failed", e)
                callback(false, InternetUtils.connectFailureReason(context, baseUrl))
            }
        }.start()
    }

    private fun hasItems(body: String?): Boolean = try {
        body != null && JSONObject(body).has("items")
    } catch (_: Exception) {
        false
    }

    /**
     * Resolves the handle of the account [apiToken] belongs to, or null when it cannot be read.
     *
     * Wanderer exposes no "current user" endpoint, so this goes the long way round: the token list
     * carries the owner's user id, and the user record carries the name the handle is built from.
     * [apiToken] is passed in explicitly because during setup it is not persisted yet.
     */
    fun fetchUserHandle(apiToken: String, callback: (String?) -> Unit) {
        Thread {
            try {
                val headers = mutableMapOf("Authorization" to "Bearer $apiToken")
                val tokens = InternetUtils.doJsonRequest(
                    uri = "$baseUrl/api/v1/api-token".toUri(),
                    requestHeaders = headers
                )
                val userId = tokens?.optJSONArray("items")?.optJSONObject(0)?.optString("user")
                if (userId.isNullOrEmpty()) {
                    LOG.warn("Wanderer token list carries no user id, cannot resolve the handle")
                    callback(null)
                    return@Thread
                }
                val user = InternetUtils.doJsonRequest(
                    uri = "$baseUrl/api/v1/user/$userId".toUri(),
                    requestHeaders = headers
                )
                val username = user?.optString("username")
                callback(if (username.isNullOrEmpty()) null else username)
            } catch (e: Exception) {
                LOG.error("Failed to resolve the Wanderer handle", e)
                callback(null)
            }
        }.start()
    }

    /**
     * Replaces the track of the existing trail [trailId] with [file], keeping the trail id and
     * everything the user set on it.
     *
     * The endpoint is multipart with the track under `gpx`. Wanderer 0.20.0 reads the trail id from
     * the body rather than the path (a body without it answers 500), and keeps the previous
     * track's distance, duration and elevation unless they are sent. From 0.21.0 the path decides,
     * a body id has to match it, and whatever figures are not sent are derived from the new track.
     * The id and [stats] are sent either way: both versions accept them, and the summary's figures
     * are the ones to show.
     *
     * `POST /api/v1/trail/{id}/file` does nothing on 0.20.0 and is an alias of this one from 0.21.0.
     */
    fun updateActivityFile(
        trailId: String,
        file: File,
        stats: Map<String, String> = emptyMap(),
        callback: (WandererWriteResult) -> Unit
    ) {
        Thread {
            try {
                InternetUtils.uploadBinaryFile(
                    uri = "$baseUrl/api/v1/trail/form/$trailId".toUri(),
                    file = file,
                    requestHeaders = buildHeaders(),
                    fileFieldName = "gpx",
                    formFields = mapOf("id" to trailId) + stats
                ) { success, statusCode, responseText, reason ->
                    val result = writeResultOf(
                        GBApplication.getContext(), success, statusCode, responseText, reason
                    )
                    logWrite("Replacing the track of", trailId, result)
                    callback(result)
                }
            } catch (e: Exception) {
                LOG.error("Trail update error", e)
                callback(WandererWriteResult.Failed(e.localizedMessage))
            }
        }.start()
    }

    /**
     * Writes [fields] onto the trail without sending a track. The same form endpoint takes the
     * fields on their own, and leaves everything it is not given alone.
     */
    fun updateTrailFields(
        trailId: String,
        fields: Map<String, String>,
        callback: (WandererWriteResult) -> Unit
    ) {
        Thread {
            try {
                InternetUtils.sendMultipartForm(
                    uri = "$baseUrl/api/v1/trail/form/$trailId".toUri(),
                    formFields = mapOf("id" to trailId) + fields,
                    requestHeaders = buildHeaders()
                ) { success, statusCode, responseText, reason ->
                    val result = writeResultOf(
                        GBApplication.getContext(), success, statusCode, responseText, reason
                    )
                    logWrite("Updating", trailId, result)
                    callback(result)
                }
            } catch (e: Exception) {
                LOG.error("Trail update error", e)
                callback(WandererWriteResult.Failed(e.localizedMessage))
            }
        }.start()
    }

    private fun logWrite(what: String, trailId: String, result: WandererWriteResult) {
        when (result) {
            is WandererWriteResult.Done -> LOG.info("{} Wanderer trail {} went through", what, trailId)
            is WandererWriteResult.Gone -> LOG.info("{} Wanderer trail {}: it no longer exists", what, trailId)
            is WandererWriteResult.Failed -> LOG.error("{} Wanderer trail {} failed: {}", what, trailId, result.reason)
        }
    }

    /**
     * Uploads [file] as a new trail. [callback] fires with (trail id, reason): the id on success,
     * otherwise a message for the user when there is one.
     */
    fun uploadActivity(file: File, callback: (String?, String?) -> Unit) {
        Thread {
            try {
                InternetUtils.uploadBinaryFile(
                    uri = "$baseUrl/api/v1/trail/upload".toUri(),
                    file = file,
                    requestHeaders = buildHeaders(),
                    method = "PUT"
                ) { success, statusCode, responseText, reason ->
                    if (success && statusCode != null && statusCode in 200..299 && responseText != null) {
                        LOG.debug("Response {} from Wanderer: {}", statusCode, responseText)
                        callback(JSONObject(responseText).getString("id"), null)
                    } else {
                        val message = failureMessage(GBApplication.getContext(), statusCode, responseText, reason)
                        LOG.error("Activity upload failed: {}", message)
                        callback(null, message)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Activity upload error", e)
                callback(null, null)
            }
        }.start()
    }

    companion object {
        /**
         * Whether Wanderer refused the API key. A request with no key is answered 401, while a key
         * that is unknown or revoked is answered 500 with PocketBase's message, on 0.20.0 and 0.21.0
         * alike.
         */
        internal fun isRefusedToken(statusCode: Int?, responseText: String?): Boolean =
            statusCode == 401 || statusCode == 403 ||
                    (statusCode == 500 && responseText?.contains("Invalid or revoked API token") == true)

        /** What to tell the user about a request Wanderer did not carry out. */
        internal fun failureMessage(
            context: Context,
            statusCode: Int?,
            responseText: String?,
            reason: String?
        ): String? =
            if (isRefusedToken(statusCode, responseText)) {
                context.getString(R.string.toast_error_invalid_api_key)
            } else {
                ExternalServiceHttp.failureMessage(statusCode, responseText, reason)
            }

        /** What a write to an existing trail came to. The form endpoint answers 404 for an unknown trail id. */
        internal fun writeResultOf(
            context: Context,
            success: Boolean,
            statusCode: Int?,
            responseText: String?,
            reason: String?
        ): WandererWriteResult = when {
            success && statusCode != null && statusCode in 200..299 -> WandererWriteResult.Done
            statusCode == 404 -> WandererWriteResult.Gone
            else -> WandererWriteResult.Failed(failureMessage(context, statusCode, responseText, reason))
        }
    }
}
