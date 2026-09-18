/*  Copyright (C) 2026 Sam Jeffery, José Rebelo, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.dreeve

import android.content.Context
import androidx.fragment.app.Fragment
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteOp
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.UploadCapabilities
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutPayloadFormat
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadStore
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadTarget
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.externalservices.AuthStyle
import java.io.File

object DreeveService : WorkoutUploadTarget {
    override val id = "dreeve"
    override val nameRes = R.string.pref_category_dreeve
    override val authStyle = AuthStyle.API_TOKEN

    override val service = WorkoutUploadStore.SERVICE_DREEVE

    override val capabilities = UploadCapabilities(
        formats = listOf(WorkoutPayloadFormat.FIT),
        // The upload endpoint is all there is, and it answers 202 without naming the activity the
        // file will become, so there is nothing to point a later change at.
        // TODO: add UPDATE/DELETE and media once https://github.com/dreeveapp/dreeve/issues/2705
        //  (activity id after upload) and https://github.com/dreeveapp/dreeve/issues/2706 (edit and
        //  delete endpoints) land.
        activity = setOf(RemoteOp.CREATE),
        metadata = emptySet(),
        media = emptySet()
    )

    override fun isLoggedIn(context: Context) = DreeveTokenManager(context).isLoggedIn()

    override fun showSetup(fragment: Fragment) {
        DreeveSetupBottomSheet().show(fragment.parentFragmentManager, "dreeve_setup")
    }

    // The API token is issued and revoked on the server, so there is nothing to tell it about.
    override fun logout(context: Context): Boolean {
        DreeveTokenManager(context).clearTokens()
        return true
    }

    // Dreeve names no activity on upload, so there is nothing to link to.
    // TODO: link to the activity once https://github.com/dreeveapp/dreeve/issues/2705 lands.
    override fun activityUrl(context: Context, remoteActivityId: String): String? = null

    override fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File
    ): WorkoutUploader.UploadResult {
        val server = serverUrl() ?: return WorkoutUploader.UploadResult(false, null, null)
        return DreeveApiClient(server, DreeveTokenManager(context)).uploadActivity(payload)
    }
}
