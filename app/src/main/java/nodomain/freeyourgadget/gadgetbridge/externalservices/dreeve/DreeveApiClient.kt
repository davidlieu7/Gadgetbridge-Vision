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

import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceHttp
import java.io.File

class DreeveApiClient(private val baseUrl: String, private val tokenManager: DreeveTokenManager) {
    private fun buildHeaders(): Map<String, String> {
        val token = tokenManager.getApiToken() ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
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
            uri = "$baseUrl/api/v1/status".toUri(),
            token = apiToken,
            validateResponse = { response -> response.has("version") },
            invalidTokenMessage = context.getString(R.string.toast_error_invalid_api_key),
            rejectReason = { response ->
                if (response.optBoolean("canUpload", true)) null
                else context.getString(R.string.dreeve_error_uploads_disabled)
            },
        )
    }

    /**
     * Uploads one activity. Dreeve takes the file for processing and answers 202 without naming
     * the activity it will become, so there is no id to record.
     *
     * TODO: record the activity id once https://github.com/dreeveapp/dreeve/issues/2705 lands.
     */
    fun uploadActivity(file: File): WorkoutUploader.UploadResult =
        ExternalServiceHttp.uploadFile(
            uri = "$baseUrl/api/v1/activity/upload".toUri(),
            file = file,
            headers = buildHeaders(),
        )
}
