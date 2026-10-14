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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException

/**
 * Holds the OAuth2 client credentials and tokens for FitTrackee, plus the state of an
 * authorization that has been started in the browser but not completed yet.
 */
// FIXME MasterKey/EncryptedSharedPreferences is deprecated - we should move to Tink
@Suppress("DEPRECATION")
class FitTrackeeTokenManager(context: Context) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(FitTrackeeTokenManager::class.java)
        private const val FILE = "fittrackee_tokens"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = createPreferences(context)

    private fun createPreferences(context: Context): SharedPreferences {
        return try {
            create(context)
        } catch (e: GeneralSecurityException) {
            LOG.warn("Unable to decrypt fittrackee token preferences, resetting them instead", e)
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit(commit = true) { clear() }
            create(context)
        }
    }

    private fun create(context: Context) = EncryptedSharedPreferences.create(
        context,
        FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Remembers an authorization started in the browser, until the callback completes it. */
    fun savePendingAuthorization(
        server: String,
        clientId: String,
        clientSecret: String,
        codeVerifier: String,
        state: String
    ) {
        prefs.edit {
            putString("pending_server", server)
            putString("pending_client_id", clientId)
            putString("pending_client_secret", clientSecret)
            putString("pending_code_verifier", codeVerifier)
            putString("pending_state", state)
        }
    }

    data class PendingAuthorization(
        val server: String,
        val clientId: String,
        val clientSecret: String,
        val codeVerifier: String,
        val state: String
    )

    fun getPendingAuthorization(): PendingAuthorization? {
        return PendingAuthorization(
            prefs.getString("pending_server", null) ?: return null,
            prefs.getString("pending_client_id", null) ?: return null,
            prefs.getString("pending_client_secret", null) ?: return null,
            prefs.getString("pending_code_verifier", null) ?: return null,
            prefs.getString("pending_state", null) ?: return null
        )
    }

    fun clearPendingAuthorization() {
        prefs.edit {
            remove("pending_server")
            remove("pending_client_id")
            remove("pending_client_secret")
            remove("pending_code_verifier")
            remove("pending_state")
        }
    }

    /** Stores a token response. [expiresAtMillis] is when the access token stops being valid. */
    fun saveTokens(
        clientId: String,
        clientSecret: String,
        accessToken: String,
        refreshToken: String?,
        expiresAtMillis: Long
    ) {
        prefs.edit {
            putString("client_id", clientId)
            putString("client_secret", clientSecret)
            putString("access_token", accessToken)
            putString("refresh_token", refreshToken)
            putLong("expires_at", expiresAtMillis)
        }
    }

    fun getClientId(): String? = prefs.getString("client_id", null)
    fun getClientSecret(): String? = prefs.getString("client_secret", null)
    fun getAccessToken(): String? = prefs.getString("access_token", null)
    fun getRefreshToken(): String? = prefs.getString("refresh_token", null)
    fun getExpiresAt(): Long = prefs.getLong("expires_at", 0L)

    fun isAccessTokenExpired(marginMillis: Long = 60_000L): Boolean =
        System.currentTimeMillis() + marginMillis >= getExpiresAt()

    fun saveUsername(username: String) {
        prefs.edit { putString("username", username) }
    }

    fun getUsername(): String? = prefs.getString("username", null)

    fun clearTokens() {
        prefs.edit { clear() }
    }

    fun isLoggedIn(): Boolean = getAccessToken() != null
}
