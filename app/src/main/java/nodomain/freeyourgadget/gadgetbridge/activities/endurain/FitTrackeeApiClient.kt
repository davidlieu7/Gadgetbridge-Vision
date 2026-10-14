/*  Copyright (C) 2026 Arnau Mora

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
package nodomain.freeyourgadget.gadgetbridge.activities.endurain

import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder

/**
 * Client for the FitTrackee API (<https://docs.fittrackee.org/en/api/index.html>).
 *
 * Every method blocks on network I/O, so call them off the main thread.
 */
class FitTrackeeApiClient(
    private val baseUrl: String,
    private val tokenManager: FitTrackeeTokenManager
) {
    companion object {
        const val REDIRECT_URI = "app://gadgetbridge/fittrackee/callback"
        const val SCOPES = "profile:read workouts:write"

        private val LOG = LoggerFactory.getLogger(FitTrackeeApiClient::class.java)

        private fun form(fields: Map<String, String>) = fields.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

        /** Browser URL where the user approves the access. */
        fun authorizationUrl(
            baseUrl: String,
            clientId: String,
            state: String,
            codeChallenge: String
        ): String = "${baseUrl.trimEnd('/')}/profile/apps/authorize?" + form(
            mapOf(
                "client_id" to clientId,
                "response_type" to "code",
                "scope" to SCOPES,
                "redirect_uri" to REDIRECT_URI,
                "state" to state,
                "code_challenge" to codeChallenge,
                "code_challenge_method" to "S256"
            )
        )

        /** Error message from a FitTrackee error document, if the body is one. */
        private fun errorMessage(body: String?): String? = try {
            body?.let { JSONObject(it).let { j -> j.optString("message").ifEmpty { j.optString("error") } } }
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private val base get() = baseUrl.trimEnd('/')

    /**
     * Exchanges an authorization code (or a refresh token) for tokens and stores them.
     * Returns null on success, otherwise a reason to show the user.
     */
    private fun requestToken(
        clientId: String,
        clientSecret: String,
        fields: Map<String, String>
    ): String? {
        var networkReason: String? = null
        val response = InternetUtils.doStringRequestWithStatus(
            uri = "$base/api/oauth/token".toUri(),
            method = "POST",
            requestHeaders = InternetUtils.headersWithUserAgent(
                mapOf("Content-Type" to "application/x-www-form-urlencoded")
            ),
            body = form(fields + mapOf("client_id" to clientId, "client_secret" to clientSecret)),
            onError = { networkReason = it }
        )
        val code = response.statusCode
        val json = try {
            response.body?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        }
        val access = json?.optString("access_token").orEmpty()
        if (code == null || code !in 200..299 || access.isEmpty()) {
            LOG.error("FitTrackee token request failed: {} {}", code, response.body)
            return networkReason ?: errorMessage(response.body) ?: code?.let { "HTTP $it" }
        }
        val expiresAt = if (json!!.has("expires_at") && json.optLong("expires_at") > 0) {
            json.getLong("expires_at") * 1000
        } else {
            System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        }
        tokenManager.saveTokens(
            clientId,
            clientSecret,
            access,
            json.optString("refresh_token").ifEmpty { null } ?: tokenManager.getRefreshToken(),
            expiresAt
        )
        return null
    }

    /** Completes an authorization: trades [code] for tokens. Null on success, else a reason. */
    fun exchangeCode(clientId: String, clientSecret: String, code: String, codeVerifier: String) =
        requestToken(
            clientId, clientSecret, mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to codeVerifier,
                "redirect_uri" to REDIRECT_URI
            )
        )

    /** Refreshes the access token when it is about to expire. Returns the token to use. */
    private fun validAccessToken(): String? {
        if (tokenManager.isAccessTokenExpired()) {
            val refresh = tokenManager.getRefreshToken()
            val clientId = tokenManager.getClientId()
            val clientSecret = tokenManager.getClientSecret()
            if (refresh == null || clientId == null || clientSecret == null) {
                return null
            }
            val failure = requestToken(
                clientId, clientSecret,
                mapOf("grant_type" to "refresh_token", "refresh_token" to refresh)
            )
            if (failure != null) {
                LOG.warn("FitTrackee token refresh failed: {}", failure)
                return null
            }
        }
        return tokenManager.getAccessToken()
    }

    private fun authHeaders(): MutableMap<String, String>? {
        val token = validAccessToken() ?: return null
        return mutableMapOf("Authorization" to "Bearer $token")
    }

    /** Username of the authorized account, or null when it cannot be read. */
    fun fetchUsername(): String? {
        val headers = authHeaders() ?: return null
        val json = InternetUtils.doJsonRequest(uri = "$base/api/auth/profile".toUri(), requestHeaders = headers)
        return json?.optJSONObject("data")?.optJSONObject("user")?.optString("username")
            ?.takeIf { it.isNotEmpty() }
    }

    /** Sports available to the account, label to id. */
    fun fetchSports(): Map<String, Int> {
        val headers = authHeaders() ?: return emptyMap()
        val sports = InternetUtils.doJsonRequest(uri = "$base/api/sports".toUri(), requestHeaders = headers)
            ?.optJSONObject("data")?.optJSONArray("sports") ?: return emptyMap()
        val result = mutableMapOf<String, Int>()
        for (i in 0 until sports.length()) {
            val sport = sports.optJSONObject(i) ?: continue
            if (sport.optBoolean("is_active_for_user", true)) {
                result[sport.optString("label")] = sport.optInt("id")
            }
        }
        return result
    }

    /**
     * Uploads a workout [file]. Returns the new workout's short id, or null with a reason.
     * [sportId] and [title] are optional; without them FitTrackee derives them from the file.
     */
    fun uploadWorkout(file: File, sportId: Int?, title: String?): Pair<String?, String?> {
        val headers = authHeaders()
            ?: return null to "FitTrackee authorization expired, please log in again"
        val data = JSONObject().apply {
            sportId?.let { put("sport_id", it) }
            title?.let { put("title", it) }
        }
        var result: Pair<String?, String?> = null to null
        InternetUtils.uploadBinaryFile(
            uri = "$base/api/workouts".toUri(),
            file = file,
            requestHeaders = InternetUtils.headersWithUserAgent(headers),
            formFields = mapOf("data" to data.toString())
        ) { success, statusCode, responseText, reason ->
            result = if (success && statusCode != null && statusCode in 200..299 && responseText != null) {
                try {
                    val json = JSONObject(responseText).getJSONObject("data")
                    val workouts = json.optJSONArray("workouts")
                    val id = workouts?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
                    if (id != null) {
                        LOG.info("Uploaded workout to FitTrackee, id {}", id)
                        id to null
                    } else {
                        val errored = json.optJSONArray("errored_workouts")?.optJSONObject(0)
                        null to (errored?.optString("error") ?: "No workout was created")
                    }
                } catch (e: Exception) {
                    LOG.error("Unexpected FitTrackee upload response: {}", responseText, e)
                    null to "Unexpected response"
                }
            } else {
                val message = errorMessage(responseText) ?: reason ?: statusCode?.let { "HTTP $it" }
                LOG.error("FitTrackee upload failed: {}", message)
                null to message
            }
        }
        return result
    }

    /** Sets the title and sport of the existing workout [workoutId]. */
    fun updateWorkout(workoutId: String, sportId: Int?, title: String?): Boolean {
        val headers = authHeaders() ?: return false
        headers["Content-Type"] = "application/json"
        val body = JSONObject().apply {
            sportId?.let { put("sport_id", it) }
            title?.let { put("title", it) }
        }
        if (body.length() == 0) return true
        val response = InternetUtils.doStringRequestWithStatus(
            uri = "$base/api/workouts/$workoutId".toUri(),
            method = "PATCH",
            requestHeaders = InternetUtils.headersWithUserAgent(headers),
            body = body.toString()
        )
        val ok = response.statusCode != null && response.statusCode in 200..299
        if (!ok) LOG.warn("FitTrackee workout update failed: {} {}", response.statusCode, response.body)
        return ok
    }

    /** Verifies the server answers; returns a reason on failure, null on success. */
    fun checkServerReachable(): String? {
        var networkReason: String? = null
        val response = InternetUtils.doStringRequestWithStatus(
            uri = "$base/api/ping".toUri(),
            onError = { networkReason = it }
        )
        return if (response.statusCode == null) {
            networkReason ?: InternetUtils.connectFailureReason(GBApplication.getContext(), baseUrl)
        } else null
    }
}
