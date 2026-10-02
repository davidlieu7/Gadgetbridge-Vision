/*  Copyright (C) 2026 José Rebelo, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices

import android.content.Context
import android.net.Uri
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The two requests every token-authenticated service makes the same way: asking whether a token is
 * accepted, and posting an activity file. A service whose API needs more than this calls
 * [InternetUtils] itself.
 *
 * Callers are already on a background dispatcher, so nothing here starts a thread.
 */
object ExternalServiceHttp {
    private val LOG = LoggerFactory.getLogger(ExternalServiceHttp::class.java)

    /**
     * Asks [uri] whether [token] is accepted, treating a response [validateResponse] recognises as
     * proof that it is. Answers (accepted, reason), where reason is a message to show the user when
     * it is not: [invalidTokenMessage] when the server answered and refused, why the server could
     * not be reached when it did not answer at all, and whatever [rejectReason] names when the
     * token is accepted but the server still cannot be used.
     */
    fun checkToken(
        context: Context,
        uri: Uri,
        token: String,
        validateResponse: (JSONObject) -> Boolean,
        invalidTokenMessage: String,
        rejectReason: (JSONObject) -> String? = { null },
    ): Pair<Boolean, String?> {
        return try {
            var networkReason: String? = null
            val response = InternetUtils.doJsonRequest(
                uri = uri,
                requestHeaders = mapOf("Authorization" to "Bearer $token"),
                onError = { reason -> networkReason = reason }
            )
            when {
                response == null ->
                    false to (networkReason ?: InternetUtils.connectFailureReason(context, uri.toString()))

                !validateResponse(response) -> false to invalidTokenMessage

                else -> rejectReason(response)?.let { false to it } ?: (true to null)
            }
        } catch (e: Exception) {
            LOG.error("Reachability check for {} failed", uri, e)
            false to InternetUtils.connectFailureReason(context, uri.toString())
        }
    }

    /**
     * What to tell the user about a request that did not succeed: [unauthorizedMessage] for a 401
     * when the caller has one, then the service's own `message`, which says what was actually
     * wrong, then the network [reason], then the bare status.
     */
    fun failureMessage(
        statusCode: Int?,
        responseText: String?,
        reason: String?,
        unauthorizedMessage: String? = null,
    ): String? {
        if (statusCode == 401 && unauthorizedMessage != null) {
            return unauthorizedMessage
        }
        val serverMessage = try {
            responseText?.let { JSONObject(it).optString("message").takeIf { m -> m.isNotEmpty() } }
        } catch (_: Exception) {
            null
        }
        return serverMessage ?: reason ?: statusCode?.let { "HTTP $it" }
    }

    /**
     * Posts [file] to [uri] as [fileName] and reads the id the service gave the new activity out
     * of [idField]. Only a 2xx counts as success; see [failureMessage] for what a failure reports.
     */
    fun uploadFile(
        uri: Uri,
        file: File,
        headers: Map<String, String>,
        method: String = "POST",
        idField: String = "id",
        fileName: String = file.name,
        unauthorizedMessage: String? = null,
    ): WorkoutUploader.UploadResult {
        return try {
            var result = WorkoutUploader.UploadResult(false, null, null)
            InternetUtils.uploadBinaryFile(
                uri = uri,
                file = file,
                requestHeaders = headers,
                method = method,
                fileName = fileName,
            ) { success, statusCode, responseText, reason ->
                result = if (success && statusCode != null && statusCode in 200..299 && responseText != null) {
                    val json = JSONObject(responseText)
                    val id = if (json.has(idField)) json.get(idField).toString() else null
                    WorkoutUploader.UploadResult(true, id, null)
                } else {
                    val message = failureMessage(statusCode, responseText, reason, unauthorizedMessage)
                    LOG.error("Upload to {} failed: {}", uri, message)
                    WorkoutUploader.UploadResult(false, null, message)
                }
            }
            result
        } catch (e: Exception) {
            LOG.error("Upload to {} errored", uri, e)
            WorkoutUploader.UploadResult(false, null, e.localizedMessage)
        }
    }
}
