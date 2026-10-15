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

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

/**
 * Receives the redirect of the FitTrackee authorization and trades the code for tokens.
 */
class FitTrackeeOAuthCallbackActivity : AbstractGBActivity() {
    private val LOG = LoggerFactory.getLogger(FitTrackeeOAuthCallbackActivity::class.java)

    /**
     * Bring the settings screen back to the front, away from the browser. This must happen after the
     * tokens have been stored, so that the screen's onResume shows the up-to-date login state.
     */
    private fun showSettings() {
        startActivity(Intent(applicationContext, OnlineFitnessTrackersPreferencesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        val tokenManager = FitTrackeeTokenManager(applicationContext)
        val pending = tokenManager.getPendingAuthorization()
        val code = uri?.getQueryParameter("code")
        if (uri == null || pending == null || code == null || uri.getQueryParameter("state") != pending.state) {
            LOG.error("Invalid FitTrackee authorization callback: {}", uri)
            tokenManager.clearPendingAuthorization()
            Toast.makeText(this, getString(R.string.fittrackee_login_failed, "invalid callback"), Toast.LENGTH_LONG).show()
            showSettings()
            finish()
            return
        }

        val appContext = applicationContext
        Thread {
            val client = FitTrackeeApiClient(pending.server, tokenManager)
            val failure = client.exchangeCode(pending.clientId, pending.clientSecret, code, pending.codeVerifier)
            tokenManager.clearPendingAuthorization()
            if (failure == null) {
                client.fetchUsername()?.let { tokenManager.saveUsername(it) }
                GBApplication.getPrefs().preferences.edit {
                    putString("fittrackee_server", pending.server)
                }
            }
            runOnUiThread {
                if (failure == null) {
                    GB.toast(appContext.getString(R.string.fittrackee_login_successful), Toast.LENGTH_LONG, GB.INFO)
                } else {
                    GB.toast(appContext.getString(R.string.fittrackee_login_failed, failure), Toast.LENGTH_LONG, GB.ERROR)
                }
                showSettings()
                finish()
            }
        }.start()
    }
}
