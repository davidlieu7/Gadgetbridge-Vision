/*  Copyright (C) 2026 Struchkov Mark, José Rebelo, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.ridehub

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

/**
 * RideHub (<https://ridehub.bike>), a hosted cycling community platform. Unlike Endurain and
 * Wanderer it is not self-hosted, so there is a single address and nothing to ask the user beyond
 * their upload token.
 */
object RideHubService : WorkoutUploadTarget {
    override val id = "ridehub"
    override val nameRes = R.string.service_name_ridehub
    override val authStyle = AuthStyle.API_TOKEN

    /** Hosted at one address, so there is no instance for the user to name. */
    override val serverPrefKey: String? = null

    override val service = WorkoutUploadStore.SERVICE_RIDEHUB

    override val capabilities = UploadCapabilities(
        // FIT rather than GPX: the platform analyses heart rate, power and cadence, and a GPX
        // carries none of them. A FIT is synthesized from the summary when there is no track, so
        // the service is offered for any workout.
        formats = listOf(WorkoutPayloadFormat.FIT),
        // The import endpoint is all there is: it answers with the existing activity's id when the
        // same one is sent again, which makes retrying safe but leaves no way to change a track.
        activity = setOf(RemoteOp.CREATE),
        metadata = emptySet(),
        media = emptySet()
    )

    override fun isLoggedIn(context: Context) = RideHubTokenManager(context).isLoggedIn()

    override fun showSetup(fragment: Fragment) {
        RideHubSetupBottomSheet().show(fragment.parentFragmentManager, "ridehub_setup")
    }

    // The token is issued and revoked on the server, so there is nothing to tell it about.
    override fun logout(context: Context): Boolean {
        RideHubTokenManager(context).clearTokens()
        return true
    }

    override fun activityUrl(context: Context, remoteActivityId: String): String =
        "${RideHubApiClient.BASE_URL}/activities/$remoteActivityId"

    override fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File
    ): WorkoutUploader.UploadResult =
        RideHubApiClient(RideHubTokenManager(context)).uploadActivity(payload)
}
