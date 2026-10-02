/*  Copyright (C) 2026 Sam Jeffery, José Rebelo, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.dreeve

import android.net.Uri
import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceHttp
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

/** One entry of Dreeve's file import log. [activityId] is set only once the import succeeded. */
data class DreeveImport(
    val filename: String,
    val status: String,
    val activityId: String?,
    val errorMessage: String?
)

/** What a request about one file import came to; [Found.import] is null when there is none. */
sealed interface DreeveImportLookup {
    data class Found(val import: DreeveImport?) : DreeveImportLookup
    object Failed : DreeveImportLookup
}

/** What `GET /api/v1/activities/{id}` answered. */
sealed interface DreeveActivityLookup {
    data class Found(val activity: JSONObject) : DreeveActivityLookup
    object Gone : DreeveActivityLookup
    object Failed : DreeveActivityLookup
}

/**
 * Dreeve's API, authenticated with a key the user generates on their instance. Every error is a
 * JSON `{error, message}`.
 *
 * An upload is queued and imported by the next import run, so it names no activity. What the file
 * became is read back from the import log by the name it was uploaded under.
 */
class DreeveApiClient(private val baseUrl: String, private val tokenManager: DreeveTokenManager) {
    private fun buildHeaders(): Map<String, String> {
        val token = tokenManager.getApiToken() ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    private fun url(path: String): Uri = "${baseUrl.trimEnd('/')}$path".toUri()

    private fun request(uri: Uri, method: String = "GET", json: JSONObject? = null) =
        InternetUtils.doStringRequestWithStatus(
            uri = uri,
            method = method,
            requestHeaders = buildHeaders() +
                    (if (json != null) mapOf("Content-Type" to "application/json") else emptyMap()),
            body = json?.toString()
        ).also {
            if (it.statusCode !in 200..299) {
                LOG.warn("Dreeve {} {} answered {}: {}", method, uri.path, it.statusCode,
                    ExternalServiceHttp.failureMessage(it.statusCode, it.body, null))
            }
        }

    /**
     * Asks whether [apiToken] is accepted. It is passed in rather than read back, because during
     * setup it has deliberately not been stored yet.
     */
    fun checkToken(apiToken: String): Pair<Boolean, String?> {
        val context = GBApplication.getContext()
        // The status endpoint answers with the server's version once the token is accepted, and
        // with canUpload false when the server imports from Strava and refuses every file (409).
        return ExternalServiceHttp.checkToken(
            context = context,
            uri = url("/api/v1/status"),
            token = apiToken,
            validateResponse = { response -> response.has("version") },
            invalidTokenMessage = context.getString(R.string.toast_error_invalid_api_key),
            rejectReason = { response ->
                if (response.optBoolean("canUpload", true)) null
                else context.getString(R.string.dreeve_error_uploads_disabled)
            },
        )
    }

    /** Queues [file] for import under [uploadName]. Succeeds with no activity id; see the class. */
    fun uploadActivity(file: File, uploadName: String): WorkoutUploader.UploadResult =
        ExternalServiceHttp.uploadFile(
            uri = url("/api/v1/activity/upload"),
            file = file,
            headers = buildHeaders(),
            fileName = uploadName,
            unauthorizedMessage = GBApplication.getContext().getString(R.string.toast_error_invalid_api_key),
        )

    /**
     * The latest import of the file uploaded as [filename]. The filter applies only to files
     * already imported: every queued file is listed whatever it is called, so the name is matched
     * here. Queued files come first and imports newest first, so the first match is the latest.
     */
    fun findFileImport(filename: String): DreeveImportLookup {
        val uri = url("/api/v1/file-imports").buildUpon()
            .appendQueryParameter("filters[filename]", filename)
            .appendQueryParameter("pagination[size]", "100")
            .build()
        val response = request(uri)
        if (response.statusCode !in 200..299 || response.body == null) {
            return DreeveImportLookup.Failed
        }
        return try {
            DreeveImportLookup.Found(parseFileImports(response.body).firstOrNull { it.filename == filename })
        } catch (e: Exception) {
            LOG.warn("Could not read the Dreeve file imports", e)
            DreeveImportLookup.Failed
        }
    }

    fun getActivity(activityId: String): DreeveActivityLookup {
        val response = request(url("/api/v1/activities/${Uri.encode(activityId)}"))
        return when (response.statusCode) {
            in 200..299 -> try {
                DreeveActivityLookup.Found(JSONObject(response.body!!))
            } catch (e: Exception) {
                LOG.warn("Could not read Dreeve activity {}", activityId, e)
                DreeveActivityLookup.Failed
            }

            404 -> DreeveActivityLookup.Gone
            else -> DreeveActivityLookup.Failed
        }
    }

    /**
     * Activities that started on the days from [from] to [to], both `yyyy-MM-dd` and inclusive.
     * Null when they could not be read.
     */
    fun listActivities(from: String, to: String): List<JSONObject>? {
        val uri = url("/api/v1/activities").buildUpon()
            .appendQueryParameter("filters[from]", from)
            .appendQueryParameter("filters[to]", to)
            .appendQueryParameter("pagination[size]", "100")
            .build()
        val response = request(uri)
        if (response.statusCode !in 200..299 || response.body == null) return null
        return try {
            val activities = JSONObject(response.body).getJSONArray("activities")
            (0 until activities.length()).map { activities.getJSONObject(it) }
        } catch (e: Exception) {
            LOG.warn("Could not read the Dreeve activities", e)
            null
        }
    }

    /**
     * Sets the name of [activityId], and its sport when [sportType] is not null. Dreeve refuses a
     * sport type it does not know with a 400, so that is retried once with the name alone. An
     * activity that no longer exists has nothing left to update and counts as done.
     */
    fun updateActivity(activityId: String, name: String, sportType: String?): Boolean {
        val uri = url("/api/v1/activities/${Uri.encode(activityId)}")
        val body = JSONObject().put("name", name)
        sportType?.let { body.put("sportType", it) }
        val status = request(uri, "PATCH", body).statusCode
        if (status == 400 && sportType != null) {
            return updateActivity(activityId, name, null)
        }
        return status in 200..299 || status == 404
    }

    /** Deletes [activityId]; one that is already gone counts as deleted. */
    fun deleteActivity(activityId: String): Boolean {
        val status = request(url("/api/v1/activities/${Uri.encode(activityId)}"), "DELETE").statusCode
        return status in 200..299 || status == 404
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DreeveApiClient::class.java)

        /** The entries of a `GET /api/v1/file-imports` answer, in the order the server gave. */
        fun parseFileImports(body: String): List<DreeveImport> {
            val imports = JSONObject(body).getJSONArray("fileImports")
            return (0 until imports.length()).map { i ->
                val entry = imports.getJSONObject(i)
                DreeveImport(
                    filename = entry.getString("filename"),
                    status = entry.getString("status"),
                    activityId = entry.optStringOrNull("activityId"),
                    errorMessage = entry.optStringOrNull("errorMessage")
                )
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
