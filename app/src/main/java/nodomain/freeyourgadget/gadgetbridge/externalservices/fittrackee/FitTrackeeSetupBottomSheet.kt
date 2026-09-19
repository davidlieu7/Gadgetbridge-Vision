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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.externalservices.PkceHelper
import nodomain.freeyourgadget.gadgetbridge.externalservices.externalServiceLoginResultKey
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

/**
 * Collects the address of the user's FitTrackee instance and the credentials of the application
 * they registered on it, then sends them to their browser to approve it.
 *
 * The approval happens in the browser because that is where they are already signed in; the app
 * never sees their password. What comes back is handled by [FitTrackeeService.onLoginCallback],
 * which reports here.
 */
class FitTrackeeSetupBottomSheet : BottomSheetDialogFragment() {
    private lateinit var serverInput: EditText
    private lateinit var clientIdInput: EditText
    private lateinit var clientSecretInput: EditText

    private val callbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra("success", false)
            if (!success) {
                GB.toast(getString(R.string.fittrackee_login_failed), Toast.LENGTH_LONG, GB.ERROR)
                return
            }
            parentFragmentManager.setFragmentResult(
                externalServiceLoginResultKey(FitTrackeeService.id),
                Bundle().apply { putBoolean("success", true) }
            )
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fittrackee_bottomsheet_setup_wizard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        serverInput = view.findViewById(R.id.fittrackee_server_name)
        clientIdInput = view.findViewById(R.id.fittrackee_client_id)
        clientSecretInput = view.findViewById(R.id.fittrackee_client_secret)
        serverInput.setText(FitTrackeeService.serverUrl().orEmpty())

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            callbackReceiver,
            IntentFilter(FitTrackeeService.LOGIN_CALLBACK_ACTION)
        )

        view.findViewById<Button>(R.id.save_button).setOnClickListener { startApproval() }
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(callbackReceiver)
        super.onDestroyView()
    }

    private fun startApproval() {
        val server = serverInput.text.toString().trim()
        val clientId = clientIdInput.text.toString().trim()
        val clientSecret = clientSecretInput.text.toString().trim()
        if (server.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty()) {
            GB.toast(getString(R.string.wanderer_setup_missing_information), Toast.LENGTH_SHORT, GB.WARN)
            return
        }

        val tokenManager = FitTrackeeTokenManager(requireContext())
        val verifier = PkceHelper().generateCodeVerifier()
        tokenManager.saveClient(clientId, clientSecret)
        tokenManager.saveCodeVerifier(verifier)
        FitTrackeeService.serverPrefKey?.let { key ->
            GBApplication.getPrefs().preferences.edit { putString(key, server) }
        }

        val url = FitTrackeeApiClient(server, tokenManager)
            .authorizationUrl(clientId, PkceHelper().generateCodeChallenge(verifier))
        LOG.info("Sending the user to approve the app on {}", server)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            LOG.error("Could not open the browser for the FitTrackee login", e)
            GB.toast(getString(R.string.endurain_failed_to_open_browser), Toast.LENGTH_LONG, GB.ERROR)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(FitTrackeeSetupBottomSheet::class.java)
    }
}
