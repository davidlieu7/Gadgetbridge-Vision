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
package nodomain.freeyourgadget.gadgetbridge.externalservices.endurain

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.externalservices.AuthStyle
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalService
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import org.slf4j.LoggerFactory

object EndurainService : ExternalService {
    override val id = "endurain"
    override val nameRes = R.string.pref_category_endurain
    override val authStyle = AuthStyle.LOGIN

    /**
     * Version the server reported when it was last asked, for the settings summary. Kept here
     * rather than on a screen so it survives the screen being closed and reopened.
     */
    @Volatile
    var serverVersion: String? = null

    override fun isLoggedIn(context: Context) =
        serverUrl() != null && EndurainTokenManager(context).isLoggedIn()

    override fun statusSummary(context: Context): CharSequence {
        val server = serverUrl()
        val tokenManager = EndurainTokenManager(context)
        var summary = if (tokenManager.isLoggedIn() && server != null) {
            context.getString(
                R.string.endurain_logged_in_refresh_token,
                server,
                DateTimeUtils.parseTimeStamp(tokenManager.getRefreshTokenExpiresAt())
            )
        } else {
            context.getString(R.string.endurain_not_logged_in_integration_disabled)
        }
        serverVersion?.let { summary += context.getString(R.string.endurain_server_version, it) }
        return summary
    }

    /** Renews an access token that ran out, and asks the server for its version. */
    override fun refreshStatus(context: Context, onRefreshed: () -> Unit) {
        val server = serverUrl() ?: return
        val tokenManager = EndurainTokenManager(context.applicationContext)
        if (tokenManager.isLoggedIn() && tokenManager.isAccessTokenExpired()) {
            tokenManager.performTokenRefresh(server) { onRefreshed() }
        }
        Thread {
            serverVersion = try {
                EndurainApiClient(server, tokenManager).fetchVersion()
            } catch (e: Exception) {
                LOG.error("Fetching the Endurain server version failed", e)
                null
            }
            onRefreshed()
        }.start()
    }

    override fun showSetup(fragment: Fragment) {
        EndurainSetupBottomSheet().show(fragment.parentFragmentManager, "endurain_setup")
    }

    /**
     * The session may well be gone on the server already, and a user who cannot reach it still has
     * to be able to disconnect, so the credentials go either way and the logout counts as done.
     */
    override fun logout(context: Context): Boolean {
        val tokenManager = EndurainTokenManager(context)
        serverUrl()?.let { server ->
            if (!EndurainApiClient(server, tokenManager).logout()) {
                LOG.warn("Could not tell Endurain about the logout")
            }
        }
        tokenManager.clearTokens()
        return true
    }

    /**
     * Re-creating an activity to push a changed track destroys one the user may have been looking
     * at, so it is offered as a choice rather than done silently.
     */
    override fun onSettingsScreenCreated(fragment: PreferenceFragmentCompat) {
        val screen = fragment.preferenceScreen
        val replaceOnTrackChange = SwitchPreferenceCompat(screen.context).apply {
            key = GBPrefs.ENDURAIN_REPLACE_ON_TRACK_CHANGE
            setDefaultValue(false)
            layoutResource = R.layout.preference_checkbox
            setIcon(R.drawable.ic_warning)
            setTitle(R.string.pref_endurain_replace_on_track_change_title)
            setSummary(R.string.pref_endurain_replace_on_track_change_summary)
        }
        screen.addPreference(replaceOnTrackChange)
        // Set once it is in the hierarchy: a dependency named on a preference that is not yet
        // attached is resolved against an empty one, and throws.
        replaceOnTrackChange.dependency = autoUploadPrefKey
    }

    private val LOG = LoggerFactory.getLogger(EndurainService::class.java)
}
