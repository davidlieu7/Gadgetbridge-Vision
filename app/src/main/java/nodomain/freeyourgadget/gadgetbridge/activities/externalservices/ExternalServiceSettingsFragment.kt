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
package nodomain.freeyourgadget.gadgetbridge.activities.externalservices

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadTarget
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.canChange
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalService
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceRegistry
import nodomain.freeyourgadget.gadgetbridge.externalservices.externalServiceLoginResultKey
import nodomain.freeyourgadget.gadgetbridge.util.GB

/**
 * Settings for one [ExternalService]. The rows every service has are declared once in
 * `external_service_preferences.xml` and named here after the service, so adding a service costs
 * no preference file and no strings of its own.
 */
class ExternalServiceSettingsFragment : AbstractPreferenceFragment() {
    private val service: ExternalService by lazy {
        val id = requireArguments().getString(ARG_SERVICE_ID)
        requireNotNull(ExternalServiceRegistry.byId(id.orEmpty())) { "Unknown external service id: $id" }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.external_service_preferences, rootKey)

        val name = getString(service.nameRes)
        preferenceScreen.title = name
        findPreference<Preference>(KEY_STATUS)?.title = getString(R.string.pref_service_status_title, name)
        findPreference<Preference>(KEY_LOG_IN)?.apply {
            title = getString(service.authStyle.titleRes, name)
            summary = getString(service.authStyle.summaryRes, name)
            setOnPreferenceClickListener {
                service.showSetup(this@ExternalServiceSettingsFragment)
                true
            }
        }
        findPreference<Preference>(KEY_LOG_OUT)?.apply {
            title = getString(R.string.pref_service_log_out_title, name)
            setOnPreferenceClickListener {
                logOut()
                true
            }
        }
        addAutoUploadPreference(name)

        parentFragmentManager.setFragmentResultListener(
            externalServiceLoginResultKey(service.id),
            this
        ) { _, bundle ->
            if (bundle.getBoolean("success", false)) {
                refresh()
                refreshStatus()
            }
        }

        service.onSettingsScreenCreated(this)
        refresh()
        refreshStatus()
    }

    private fun refreshStatus() {
        service.refreshStatus(requireContext()) {
            activity?.runOnUiThread {
                if (isAdded) refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Only a service that takes workouts has anything to upload automatically. */
    private fun addAutoUploadPreference(name: String) {
        val target = service as? WorkoutUploadTarget ?: return
        preferenceScreen.addPreference(
            SwitchPreferenceCompat(preferenceScreen.context).apply {
                key = target.autoUploadPrefKey
                setDefaultValue(false)
                layoutResource = R.layout.preference_checkbox
                setIcon(R.drawable.ic_file_upload)
                title = getString(R.string.pref_service_auto_upload_title, name)
                summary = getString(R.string.pref_service_auto_upload_summary, name)
            }
        )
        addSyncUpdatesPreference(target, name)
    }

    /**
     * Whether changes made after a workout went up are sent on. Offered by any service that can
     * change an activity it holds, and left out by one that cannot, which has nothing to decide.
     *
     * A service that can only replace the activity says so and starts off, since replacing costs
     * the activity its address; one that writes onto the activity it holds starts on.
     */
    private fun addSyncUpdatesPreference(target: WorkoutUploadTarget, name: String) {
        if (!target.capabilities.activity.canChange()) {
            return
        }
        val destructive = target.capabilities.trackChangeIsDestructive
        val preference = SwitchPreferenceCompat(preferenceScreen.context).apply {
            key = target.syncUpdatesPrefKey
            setDefaultValue(!destructive)
            layoutResource = R.layout.preference_checkbox
            setIcon(if (destructive) R.drawable.ic_warning else R.drawable.ic_sync)
            title = getString(R.string.pref_service_sync_updates_title, name)
            summary = listOfNotNull(
                if (destructive) {
                    getString(R.string.pref_service_sync_updates_destructive_summary, name)
                } else {
                    getString(R.string.pref_service_sync_updates_summary, name)
                },
                if (target.keepsRemoteEdits) {
                    getString(R.string.pref_service_sync_updates_remote_edits_kept, name)
                } else {
                    null
                }
            ).joinToString(" ")
        }
        preferenceScreen.addPreference(preference)
    }

    private fun logOut() {
        lifecycleScope.launch {
            val context = requireContext()
            val confirmed = withContext(Dispatchers.IO) { service.logout(context) }
            if (confirmed) {
                GB.toast(getString(R.string.service_logged_out), Toast.LENGTH_SHORT, GB.INFO)
            } else {
                GB.toast(
                    getString(R.string.service_logged_out_unconfirmed, getString(service.nameRes)),
                    Toast.LENGTH_LONG,
                    GB.WARN
                )
            }
            refresh()
        }
    }

    private fun refresh() {
        val context = requireContext()
        val configured = service.isLoggedIn(context)
        findPreference<Preference>(KEY_STATUS)?.summary = service.statusSummary(context)
        findPreference<Preference>(KEY_LOG_IN)?.isVisible = !configured
        findPreference<Preference>(KEY_LOG_OUT)?.isVisible = configured
        // What to send, and when, only makes sense once the service is set up. Sending later
        // changes is not tied to auto-upload: it decides what a manual upload does too.
        findPreference<Preference>(service.autoUploadPrefKey)?.isEnabled = configured
        findPreference<Preference>(service.syncUpdatesPrefKey)?.isEnabled = configured
    }

    companion object {
        const val ARG_SERVICE_ID = "external_service_id"
        private const val KEY_STATUS = "pref_key_external_service_status"
        private const val KEY_LOG_IN = "pref_key_external_service_log_in"
        private const val KEY_LOG_OUT = "pref_key_external_service_log_out"
    }
}
