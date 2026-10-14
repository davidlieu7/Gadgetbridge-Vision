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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Collects the server and the OAuth2 app credentials, then sends the user to the FitTrackee
 * authorization page. [FitTrackeeOAuthCallbackActivity] finishes the login.
 */
class FitTrackeeSetupBottomSheet : BottomSheetDialogFragment() {
    private val LOG = LoggerFactory.getLogger(FitTrackeeSetupBottomSheet::class.java)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fittrackee_bottomsheet_setup_wizard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val serverInput = view.findViewById<EditText>(R.id.fittrackee_server_name)
        val clientIdInput = view.findViewById<EditText>(R.id.fittrackee_client_id)
        val clientSecretInput = view.findViewById<EditText>(R.id.fittrackee_client_secret)
        val loginButton = view.findViewById<Button>(R.id.login_button)

        view.findViewById<TextView>(R.id.fittrackee_setup_hint).text =
            getString(R.string.fittrackee_setup_hint, FitTrackeeApiClient.REDIRECT_URI)

        serverInput.setText(
            GBApplication.getPrefs().preferences
                .getString("fittrackee_server", "")
        )

        loginButton.setOnClickListener {
            val server = serverInput.text.toString().trim().trimEnd('/')
            val clientId = clientIdInput.text.toString().trim()
            val clientSecret = clientSecretInput.text.toString().trim()
            if (server.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty()) {
                GB.toast(getString(R.string.fittrackee_setup_missing_information), Toast.LENGTH_SHORT, GB.WARN)
                return@setOnClickListener
            }
            if (!server.startsWith("https://") && !server.startsWith("http://")) {
                GB.toast(getString(R.string.fittrackee_setup_invalid_server), Toast.LENGTH_SHORT, GB.WARN)
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            val tokenManager = FitTrackeeTokenManager(requireContext())
            Thread {
                val failure = FitTrackeeApiClient(server, tokenManager).checkServerReachable()
                activity?.runOnUiThread {
                    loginButton.isEnabled = true
                    if (failure != null) {
                        GB.toast(
                            failure.ifEmpty { InternetUtils.connectFailureReason(requireContext(), server) },
                            Toast.LENGTH_LONG,
                            GB.ERROR
                        )
                        return@runOnUiThread
                    }
                    val pkce = PkceHelper()
                    val verifier = pkce.generateCodeVerifier()
                    val state = UUID.randomUUID().toString()
                    tokenManager.savePendingAuthorization(server, clientId, clientSecret, verifier, state)
                    val url = FitTrackeeApiClient.authorizationUrl(
                        server, clientId, state, pkce.generateCodeChallenge(verifier)
                    )
                    LOG.info("Launching FitTrackee authorization for {}", server)
                    try {
                        CustomTabsIntent.Builder().build().launchUrl(requireContext(), url.toUri())
                        dismiss()
                    } catch (e: Exception) {
                        LOG.error("Unable to open the browser", e)
                        GB.toast(
                            getString(R.string.endurain_failed_to_open_browser, e.message),
                            Toast.LENGTH_LONG,
                            GB.ERROR
                        )
                    }
                }
            }.start()
        }
    }
}
