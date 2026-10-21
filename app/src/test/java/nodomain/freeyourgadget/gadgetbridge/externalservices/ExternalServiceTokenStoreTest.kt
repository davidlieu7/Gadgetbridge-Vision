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
package nodomain.freeyourgadget.gadgetbridge.externalservices

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Robolectric has no Android Keystore, so the stores here use a software AES key of the same size.
 */
class ExternalServiceTokenStoreTest : TestBase() {
    private var legacyMasterKeyDeleted = false

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()

    private fun oauthStore(withKey: SecretKey = key) = object : OAuthTokenStore(context, FILE) {
        override fun secretKey() = withKey
        override fun deleteLegacyMasterKey() {
            legacyMasterKeyDeleted = true
        }
    }

    private fun apiStore(withKey: SecretKey = key) = object : ApiTokenStore(context, FILE) {
        override fun secretKey() = withKey
        override fun deleteLegacyMasterKey() {
            legacyMasterKeyDeleted = true
        }
    }

    private fun stored(): SharedPreferences = context.getSharedPreferences(FILE + "_keystore", Context.MODE_PRIVATE)

    @Test
    fun oauthTokensSurviveANewStoreInstance() {
        val now = (System.currentTimeMillis() / 1000).toInt()
        oauthStore().saveTokens("access-secret", "refresh-secret", 900, now + 86400)

        val store = oauthStore()
        assertEquals("access-secret", store.getAccessToken())
        assertEquals("refresh-secret", store.getRefreshToken())
        assertTrue(store.getAccessTokenExpiresAt() in now + 900..now + 905)
        assertEquals(now + 86400, store.getRefreshTokenExpiresAt())
        assertTrue(store.isLoggedIn())
    }

    @Test
    fun apiTokenRoundTripsAndClears() {
        apiStore().saveToken("wanderer_key_abc")
        assertEquals("wanderer_key_abc", apiStore().getApiToken())

        apiStore().clearTokens()
        assertNull(apiStore().getApiToken())
        assertTrue(stored().all.isEmpty())
    }

    @Test
    fun storedValuesHoldNoPlaintext() {
        oauthStore().saveTokens("access-secret", "refresh-secret", 900, 900)

        val raw = stored().all
        assertEquals(4, raw.size)
        for (value in raw.values) {
            val text = value as String
            assertFalse(text.contains("secret"))
            assertNull(text.toIntOrNull())
        }
    }

    @Test
    fun aValueMovedToAnotherEntryDoesNotDecrypt() {
        oauthStore().saveTokens("access-secret", "refresh-secret", 900, 900)
        val access = stored().getString("access_token", null)
        stored().edit(commit = true) { putString("refresh_token", access) }

        assertNull(oauthStore().getRefreshToken())
        assertTrue(stored().all.isEmpty())
    }

    @Test
    fun valuesUnderALostKeyAreClearedInsteadOfThrowing() {
        oauthStore().saveTokens("access-secret", "refresh-secret", 900, 900)

        val store = oauthStore(newKey())
        assertNull(store.getAccessToken())
        assertFalse(store.isLoggedIn())
        assertTrue(stored().all.isEmpty())

        store.saveTokens("fresh-access", "fresh-refresh", 900, 900)
        assertEquals("fresh-access", store.getAccessToken())
    }

    @Test
    fun corruptValueReadsAsAbsent() {
        stored().edit(commit = true) { putString("api_token", "not base64 at all!") }

        assertNull(apiStore().getApiToken())
        assertTrue(stored().all.isEmpty())
    }

    @Test
    fun legacyEncryptedSharedPreferencesFileIsDropped() {
        val legacy = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        legacy.edit(commit = true) {
            putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", "12a4")
            putString("ATx3", "AUv2")
        }

        assertNull(apiStore().getApiToken())
        assertTrue(legacy.all.isEmpty())
        assertTrue(legacyMasterKeyDeleted)
    }

    @Test
    fun noLegacyFileLeavesTheMasterKeyAlone() {
        apiStore().saveToken("wanderer_key_abc")
        assertFalse(legacyMasterKeyDeleted)
    }

    companion object {
        private const val FILE = "test_tokens"
    }
}
