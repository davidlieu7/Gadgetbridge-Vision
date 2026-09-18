/*  Copyright (C) 2026 Struchkov Mark, José Rebelo, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.ridehub

import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceHttp
import java.io.File

class RideHubApiClient(private val tokenManager: RideHubTokenManager) {
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
        // The endpoint answers {"ok": true, "athlete": {...}} when the token is good.
        return ExternalServiceHttp.checkToken(
            context = context,
            uri = "$BASE_URL/api/activities/import/ping".toUri(),
            token = apiToken,
            validateResponse = { response -> response.has("ok") },
            invalidTokenMessage = context.getString(R.string.toast_error_invalid_api_key),
        )
    }

    /**
     * Uploads one activity file. RideHub reads the format off the file name, so the extension has
     * to survive; a repeated upload of the same activity answers with the id it already has.
     */
    fun uploadActivity(file: File): WorkoutUploader.UploadResult {
        return ExternalServiceHttp.uploadFile(
            uri = "$BASE_URL/api/activities/import".toUri(),
            file = file,
            headers = buildHeaders(),
        )
    }

    companion object {
        const val BASE_URL = "https://ridehub.bike"
    }
}
