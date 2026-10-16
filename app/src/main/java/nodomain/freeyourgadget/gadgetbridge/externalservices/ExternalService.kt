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
package nodomain.freeyourgadget.gadgetbridge.externalservices

import android.content.Context
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceFragmentCompat
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R

/**
 * How the user proves who they are to a service, which decides the wording of the row that starts
 * setup. The flow behind it is the service's own business.
 */
enum class AuthStyle(
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int
) {
    /** An account on the service, entered through the service's own login flow. */
    LOGIN(R.string.pref_service_log_in_title, R.string.pref_service_log_in_summary),

    /** A token the user creates on the service and pastes in. */
    API_TOKEN(R.string.pref_service_api_token_title, R.string.pref_service_api_token_summary)
}

/**
 * One service outside Gadgetbridge that the user connects their account to, described by what it is
 * called, how it is set up and how to tell whether it is ready to be used.
 *
 * Implementations are stateless singletons registered once in [ExternalServiceRegistry]; anything
 * they need to remember lives in the preferences their [id] namespaces.
 */
interface ExternalService {
    /**
     * Stable identifier. It namespaces this service's preference keys and routes its setup result
     * back, so changing one would orphan the settings of every user who had it set up.
     */
    val id: String

    /** The service's own name, for anything that lists services to the user. */
    @get:StringRes
    val nameRes: Int

    val authStyle: AuthStyle

    /**
     * Preference holding the address of the user's instance, or null for a service that lives at
     * one address it hosts itself and has nothing to ask.
     */
    val serverPrefKey: String?
        get() = "${id}_server"

    /** Preference holding whether workouts go to this service without being asked for. */
    val autoUploadPrefKey: String
        get() = "${id}_auto_upload_enabled"

    /** Address of the user's instance, or null when they have not given one. */
    fun serverUrl(): String? =
        serverPrefKey?.let { GBApplication.getPrefs().preferences.getString(it, null) }

    /**
     * Whether the user has finished setting this service up: an address, for a self-hosted one,
     * and credentials that are held and still usable.
     */
    fun isLoggedIn(context: Context): Boolean

    /** One line saying where the user stands with this service, for its settings rows. */
    fun statusSummary(context: Context): CharSequence

    /**
     * Fetches whatever [statusSummary] shows that the service has to be asked for, calling
     * [onRefreshed] on a background thread each time something new arrived. Called when the
     * service's settings screen opens.
     */
    fun refreshStatus(context: Context, onRefreshed: () -> Unit) {}

    /** Shows this service's setup UI, which reports back on [externalServiceLoginResultKey]. */
    fun showSetup(fragment: Fragment)

    /**
     * Forgets the credentials, telling the service first when it has somewhere to say so. Answers
     * whether that went through; the credentials are dropped locally either way, so a user who
     * cannot reach the server is not stuck logged in. Blocking: call off the main thread.
     */
    fun logout(context: Context): Boolean

    /**
     * Adds whatever this service owns beyond the rows every settings screen already has. Called
     * once the shared rows are in place, so a preference declaring a dependency on one of them
     * finds it.
     */
    fun onSettingsScreenCreated(fragment: PreferenceFragmentCompat) {}
}

/**
 * Key a service's setup UI reports its outcome on, as a boolean extra named `success`. One
 * convention for every service, so the settings screen listens without knowing which is which.
 */
fun externalServiceLoginResultKey(id: String): String = "external_service_login_result_$id"
