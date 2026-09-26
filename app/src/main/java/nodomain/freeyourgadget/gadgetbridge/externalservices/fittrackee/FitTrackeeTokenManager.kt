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

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.externalservices.OAuthTokenStore

/**
 * FitTrackee credentials. The client id and secret belong here with the tokens rather than in the
 * ordinary preferences: the user creates them on their own instance and they authorize the app as
 * surely as the tokens do.
 */
class FitTrackeeTokenManager(context: Context) : OAuthTokenStore(context, "fittrackee_tokens") {
    fun saveClient(clientId: String, clientSecret: String) {
        preferences.edit {
            putString(KEY_CLIENT_ID, clientId)
            putString(KEY_CLIENT_SECRET, clientSecret)
        }
    }

    fun getClientId(): String? = preferences.getString(KEY_CLIENT_ID, null)

    fun getClientSecret(): String? = preferences.getString(KEY_CLIENT_SECRET, null)

    /**
     * Kept between opening the browser and the code coming back, and dropped only once it has
     * been traded in: a code that fails to exchange can be asked for again, and would have nothing
     * to be checked against if the verifier were spent on the first attempt.
     */
    fun saveCodeVerifier(verifier: String) {
        preferences.edit { putString(KEY_CODE_VERIFIER, verifier) }
    }

    fun getCodeVerifier(): String? = preferences.getString(KEY_CODE_VERIFIER, null)

    fun clearCodeVerifier() {
        preferences.edit { remove(KEY_CODE_VERIFIER) }
    }

    /** The signed-in user's name and profile timezone, as last read from the server. */
    fun saveProfile(username: String?, timezone: String?) {
        preferences.edit {
            username?.let { putString(KEY_USERNAME, it) }
            timezone?.let { putString(KEY_TIMEZONE, it) }
        }
    }

    fun getUsername(): String? = preferences.getString(KEY_USERNAME, null)

    fun getTimezone(): String? = preferences.getString(KEY_TIMEZONE, null)

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_CODE_VERIFIER = "code_verifier"
        private const val KEY_USERNAME = "username"
        private const val KEY_TIMEZONE = "timezone"
    }
}
