/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.fittrackee

import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder

/**
 * FitTrackee's API. Authentication is OAuth2 with PKCE against an application the user registers
 * on their own instance, which is why the client id and secret are theirs rather than ours.
 */
class FitTrackeeApiClient(
    private val baseUrl: String,
    private val tokenManager: FitTrackeeTokenManager
) {
    private fun authHeaders(): Map<String, String> {
        val token = tokenManager.getAccessToken() ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    /**
     * Page to send the user to so they can approve the app. They are already signed in there in
     * their browser, which is the point of going through it rather than asking for a password.
     */
    fun authorizationUrl(clientId: String, codeChallenge: String): String {
        val query = listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "scope" to SCOPE,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256"
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "${baseUrl.trimEnd('/')}/profile/apps/authorize?$query"
    }

    /** Trades the code the browser came back with for a token pair. Blocking. */
    fun exchangeCode(code: String, codeVerifier: String): Boolean = requestTokens(
        listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to codeVerifier
        )
    )

    /** Renews the token pair with the refresh token. Blocking. */
    fun refreshTokens(): Boolean {
        val refreshToken = tokenManager.getRefreshToken() ?: return false
        return requestTokens(
            listOf("grant_type" to "refresh_token", "refresh_token" to refreshToken)
        )
    }

    private fun requestTokens(params: List<Pair<String, String>>): Boolean {
        val clientId = tokenManager.getClientId() ?: return false
        val clientSecret = tokenManager.getClientSecret() ?: return false
        val body = (params + listOf("client_id" to clientId, "client_secret" to clientSecret))
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val response = InternetUtils.doStringRequestWithStatus(
            uri = "${baseUrl.trimEnd('/')}/api/oauth/token".toUri(),
            method = "POST",
            requestHeaders = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = body
        )
        val text = response.body
        if (response.statusCode !in 200..299 || text == null) {
            LOG.error("FitTrackee token request failed: {} {}", response.statusCode, text)
            return false
        }
        return try {
            val json = JSONObject(text)
            tokenManager.saveTokens(
                json.getString("access_token"),
                json.getString("refresh_token"),
                json.optInt("expires_in", DEFAULT_ACCESS_LIFETIME_SECONDS),
                // FitTrackee does not say when the refresh token stops working, so it is treated
                // as lasting until it is refused, which shows up as a failed refresh.
                REFRESH_LIFETIME_SECONDS
            )
            true
        } catch (e: Exception) {
            LOG.error("Could not read the FitTrackee token response", e)
            false
        }
    }

    /**
     * Runs [call], and if the server refused the credentials, renews them once and runs it again.
     * An access token is refused long before it expires whenever the server has revoked it, which
     * FitTrackee does to the old one each time it issues a replacement, so an unexpired token is
     * not the same as an accepted one.
     */
    private fun <T> retryingUnauthorized(unauthorized: (T) -> Boolean, call: () -> T): T {
        val first = call()
        if (!unauthorized(first) || !refreshTokens()) return first
        LOG.info("FitTrackee refused the access token, renewed it and asked again")
        return call()
    }

    /** Whether the stored access token is still accepted. */
    fun checkAccess(): Boolean {
        val response = InternetUtils.doStringRequestWithStatus(
            uri = "${baseUrl.trimEnd('/')}/api/auth/profile".toUri(),
            requestHeaders = authHeaders()
        )
        return response.statusCode in 200..299
    }

    /**
     * Uploads one workout. The new workout's id is nested in the answer rather than being a field
     * of it, so it is read out of `data.workouts[0]`.
     */
    fun uploadWorkout(file: File, sportId: Int): WorkoutUploader.UploadResult {
        var result = WorkoutUploader.UploadResult(false, null, null)
        try {
            var rejected = false
            InternetUtils.uploadBinaryFile(
                uri = "${baseUrl.trimEnd('/')}/api/workouts".toUri(),
                file = file,
                requestHeaders = authHeaders(),
                method = "POST",
                formFields = mapOf("data" to JSONObject().put("sport_id", sportId).toString())
            ) { success, statusCode, responseText, reason ->
                result = if (success && statusCode in 200..299 && responseText != null) {
                    WorkoutUploader.UploadResult(true, workoutIdOf(responseText), null)
                } else {
                    rejected = statusCode == 401
                    val message = messageOf(responseText) ?: reason ?: statusCode?.let { "HTTP $it" }
                    LOG.error("FitTrackee upload failed: {}", message)
                    WorkoutUploader.UploadResult(false, null, message)
                }
            }
            if (rejected && refreshTokens()) {
                LOG.info("FitTrackee refused the access token, renewed it and sent the workout again")
                return uploadWorkout(file, sportId)
            }
        } catch (e: Exception) {
            LOG.error("FitTrackee upload errored", e)
            return WorkoutUploader.UploadResult(false, null, e.localizedMessage)
        }
        return result
    }

    /** Sets the title and sport of a workout that already exists. Blocking. */
    fun updateWorkout(workoutId: String, title: String, sportId: Int): Boolean {
        val body = JSONObject().put("title", title).put("sport_id", sportId).toString()
        val status = retryingUnauthorized({ it == 401 }) {
            InternetUtils.doStringRequestWithStatus(
                uri = "${baseUrl.trimEnd('/')}/api/workouts/$workoutId".toUri(),
                method = "PATCH",
                requestHeaders = authHeaders() + ("Content-Type" to "application/json"),
                body = body
            ).statusCode
        }
        return status in 200..299
    }

    fun deleteWorkout(workoutId: String): Boolean {
        val status = retryingUnauthorized({ it == 401 }) {
            InternetUtils.doStringRequestWithStatus(
                uri = "${baseUrl.trimEnd('/')}/api/workouts/$workoutId".toUri(),
                method = "DELETE",
                requestHeaders = authHeaders()
            ).statusCode
        }
        return status in 200..299
    }

    private fun workoutIdOf(responseText: String): String? = try {
        JSONObject(responseText)
            .getJSONObject("data")
            .getJSONArray("workouts")
            .getJSONObject(0)
            .getString("id")
    } catch (e: Exception) {
        LOG.warn("FitTrackee answered without a workout id", e)
        null
    }

    private fun messageOf(responseText: String?): String? = try {
        responseText?.let { JSONObject(it).optString("message").takeIf { m -> m.isNotEmpty() } }
    } catch (e: Exception) {
        null
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(FitTrackeeApiClient::class.java)

        /**
         * FitTrackee only accepts a redirect under http, https, app or myapp, so the app's own
         * scheme cannot be used here. The host still names the service, which is what routes the
         * link back to it.
         */
        const val REDIRECT_URI = "app://fittrackee/oauth/callback"

        const val SCOPE = "workouts:read workouts:write profile:read"

        private const val DEFAULT_ACCESS_LIFETIME_SECONDS = 864000

        /** Ten years: long enough to mean "until the server refuses it". */
        private const val REFRESH_LIFETIME_SECONDS = 10 * 365 * 24 * 60 * 60
    }
}
