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
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServicePhotos
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder

/** What `GET /api/workouts/<id>` answered. */
sealed interface FitTrackeeWorkoutLookup {
    data class Found(val workout: JSONObject) : FitTrackeeWorkoutLookup
    object Gone : FitTrackeeWorkoutLookup
    object Failed : FitTrackeeWorkoutLookup
}

/**
 * FitTrackee's API. Authentication is OAuth2 with PKCE against an application the user registers
 * on their own instance, which is why the client id and secret are theirs rather than ours.
 *
 * Both workout creation endpoints load their body straight into a fixed set of fields and answer
 * an unknown key with a 500, so only keys the endpoint documents are ever sent.
 */
class FitTrackeeApiClient(
    private val baseUrl: String,
    private val tokenManager: FitTrackeeTokenManager
) {
    private fun authHeaders(): Map<String, String> {
        val token = tokenManager.getAccessToken() ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    private fun url(path: String) = "${baseUrl.trimEnd('/')}$path".toUri()

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
            uri = url("/api/oauth/token"),
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

    private fun request(path: String, method: String = "GET", json: JSONObject? = null) =
        retryingUnauthorized({ it.statusCode == 401 }) {
            InternetUtils.doStringRequestWithStatus(
                uri = url(path),
                method = method,
                requestHeaders = authHeaders() +
                        (if (json != null) mapOf("Content-Type" to "application/json") else emptyMap()),
                body = json?.toString()
            )
        }.also {
            if (it.statusCode !in 200..299) {
                LOG.warn("FitTrackee {} {} answered {}: {}", method, path, it.statusCode, messageOf(it.body))
            }
        }

    /** Whether the stored access token is still accepted. */
    fun checkAccess(): Boolean = fetchProfile() != null

    /**
     * Reads the signed-in user's name and timezone, and keeps them in [tokenManager]: the name
     * identifies the account uploads go to, the timezone is what a workout date is read in.
     * Blocking; null when the profile could not be read.
     */
    fun fetchProfile(): JSONObject? {
        val response = request("/api/auth/profile")
        if (response.statusCode !in 200..299) return null
        return try {
            JSONObject(response.body ?: return null).getJSONObject("data").also {
                tokenManager.saveProfile(
                    it.optString("username").takeIf { name -> name.isNotEmpty() },
                    it.optString("timezone").takeIf { tz -> tz.isNotEmpty() && tz != "null" }
                )
            }
        } catch (e: Exception) {
            LOG.warn("Could not read the FitTrackee profile", e)
            null
        }
    }

    /**
     * Creates a workout from [file]. [data] is the `data` form field: `sport_id` and whichever
     * optional keys the endpoint takes. The new workout's id is nested in the answer rather than
     * being a field of it, so it is read out of `data.workouts[0]`.
     */
    fun uploadWorkout(file: File, data: JSONObject): WorkoutUploader.UploadResult {
        var result = WorkoutUploader.UploadResult(false, null, null)
        try {
            var rejected = false
            InternetUtils.uploadBinaryFile(
                uri = url("/api/workouts"),
                file = file,
                requestHeaders = authHeaders(),
                method = "POST",
                formFields = mapOf("data" to data.toString())
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
                return uploadWorkout(file, data)
            }
        } catch (e: Exception) {
            LOG.error("FitTrackee upload errored", e)
            return WorkoutUploader.UploadResult(false, null, e.localizedMessage)
        }
        return result
    }

    /**
     * Creates a workout that has no file, from figures alone (`POST /api/workouts/no_gpx`). Such a
     * workout can never be given a file afterwards.
     */
    fun createWorkoutWithoutFile(body: JSONObject): WorkoutUploader.UploadResult {
        val response = try {
            request("/api/workouts/no_gpx", "POST", body)
        } catch (e: Exception) {
            LOG.error("FitTrackee upload without a file errored", e)
            return WorkoutUploader.UploadResult(false, null, e.localizedMessage)
        }
        val text = response.body
        if (response.statusCode !in 200..299 || text == null) {
            val message = messageOf(text) ?: response.statusCode?.let { "HTTP $it" }
            LOG.error("FitTrackee upload without a file failed: {}", message)
            return WorkoutUploader.UploadResult(false, null, message)
        }
        return WorkoutUploader.UploadResult(true, workoutIdOf(text), null)
    }

    fun getWorkout(workoutId: String): FitTrackeeWorkoutLookup {
        val response = request("/api/workouts/$workoutId")
        return when (response.statusCode) {
            in 200..299 -> try {
                FitTrackeeWorkoutLookup.Found(
                    JSONObject(response.body!!).getJSONObject("data").getJSONArray("workouts")
                        .getJSONObject(0)
                )
            } catch (e: Exception) {
                LOG.warn("Could not read FitTrackee workout {}", workoutId, e)
                FitTrackeeWorkoutLookup.Failed
            }

            404 -> FitTrackeeWorkoutLookup.Gone
            else -> FitTrackeeWorkoutLookup.Failed
        }
    }

    /** Sets the title and sport of a workout that already exists. Blocking. */
    fun updateWorkout(workoutId: String, title: String, sportId: Int): Boolean {
        val body = JSONObject().put("title", title).put("sport_id", sportId)
        return request("/api/workouts/$workoutId", "PATCH", body).statusCode in 200..299
    }

    /**
     * Attaches the media [mediaIds] to [workoutId], on top of what it already carries. The list
     * must not be empty: FitTrackee reads an empty `media_attachment_ids` as "delete every media
     * item of this workout".
     */
    fun attachMedia(workoutId: String, mediaIds: List<String>): Boolean {
        require(mediaIds.isNotEmpty())
        val body = JSONObject().put("media_attachment_ids", JSONArray(mediaIds))
        return request("/api/workouts/$workoutId", "PATCH", body).statusCode in 200..299
    }

    fun deleteWorkout(workoutId: String): Boolean =
        request("/api/workouts/$workoutId", "DELETE").statusCode in 200..299

    /**
     * Uploads [photo] as a media item that belongs to no workout yet, and answers its id, or null
     * when it did not go through. Needs the `media:write` scope. Blocking.
     */
    fun uploadMedia(photo: File): String? = try {
        ExternalServicePhotos.withAcceptedFormat(photo, ALLOWED_PHOTO_EXTENSIONS) { file ->
            file?.let { postMedia(it, retry = true) }
        }
    } catch (e: Exception) {
        LOG.error("FitTrackee media upload errored", e)
        null
    }

    private fun postMedia(file: File, retry: Boolean): String? {
        var mediaId: String? = null
        var rejected = false
        InternetUtils.uploadBinaryFile(
            uri = url("/api/media"),
            file = file,
            requestHeaders = authHeaders(),
            method = "POST"
        ) { success, statusCode, responseText, reason ->
            if (success && statusCode in 200..299 && responseText != null) {
                mediaId = try {
                    JSONObject(responseText).getString("id")
                } catch (e: Exception) {
                    LOG.warn("FitTrackee answered a media upload without an id", e)
                    null
                }
            } else {
                rejected = statusCode == 401
                LOG.error("FitTrackee media upload failed: {}",
                    messageOf(responseText) ?: reason ?: statusCode?.let { "HTTP $it" })
            }
        }
        if (rejected && retry && refreshTokens()) {
            return postMedia(file, retry = false)
        }
        return mediaId
    }

    /** Deletes a media item. One that is already gone counts as deleted. Blocking. */
    fun deleteMedia(mediaId: String): Boolean =
        request("/api/media/$mediaId", "DELETE").statusCode.let { it in 200..299 || it == 404 }

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

        /** `media:write` is its own scope: `workouts:write` does not cover uploading a photo. */
        const val SCOPE = "workouts:read workouts:write profile:read media:write"

        /** What `POST /api/media` accepts, by file extension. */
        private val ALLOWED_PHOTO_EXTENSIONS = setOf("gif", "jpeg", "jpg", "png", "webp")

        private const val DEFAULT_ACCESS_LIFETIME_SECONDS = 864000

        /** Ten years: long enough to mean "until the server refuses it". */
        private const val REFRESH_LIFETIME_SECONDS = 10 * 365 * 24 * 60 * 60
    }
}
