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
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteOp
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.UploadCapabilities
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutPayloadFormat
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadStore
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadTarget
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.externalservices.AuthStyle
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceTokenRefreshWorker
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import org.slf4j.LoggerFactory
import java.io.File

/**
 * FitTrackee (<https://fittrackee.org>), a self-hosted outdoor activity tracker.
 *
 * It takes both FIT and GPX, but either way the file has to carry a route: a FIT without position
 * records is refused with "no valid segments with GPS found in fit file", so a workout from a watch
 * that records no position cannot go there at all.
 */
object FitTrackeeService : WorkoutUploadTarget {
    override val id = "fittrackee"
    override val nameRes = R.string.service_name_fittrackee
    override val authStyle = AuthStyle.LOGIN

    override val service = WorkoutUploadStore.SERVICE_FITTRACKEE

    override val capabilities = UploadCapabilities(
        // FIT first: it carries heart rate and cadence, which a GPX does not.
        formats = listOf(WorkoutPayloadFormat.FIT, WorkoutPayloadFormat.GPX),
        requiresRoute = true,
        // A workout's file cannot be replaced, so a changed track means deleting it and uploading
        // again, which gives the workout a new id.
        activity = setOf(RemoteOp.CREATE, RemoteOp.REPLACE, RemoteOp.DELETE),
        metadata = setOf(RemoteOp.UPDATE),
        media = emptySet()
    )

    override fun isLoggedIn(context: Context): Boolean {
        val tokenManager = FitTrackeeTokenManager(context)
        return tokenManager.getClientId() != null && tokenManager.isLoggedIn()
    }

    override fun showSetup(fragment: Fragment) {
        FitTrackeeSetupBottomSheet().show(fragment.parentFragmentManager, "fittrackee_setup")
    }

    // The application the tokens belong to stays registered on the instance, so nothing is told.
    override fun logout(context: Context): Boolean {
        FitTrackeeTokenManager(context).clearTokens()
        return true
    }

    override fun scheduleBackgroundWork(context: Context) {
        ExternalServiceTokenRefreshWorker.schedule(context, this)
    }

    override fun refreshCredentials(context: Context): Boolean {
        val server = serverUrl() ?: return false
        return FitTrackeeApiClient(server, FitTrackeeTokenManager(context)).refreshTokens()
    }

    /**
     * The browser comes back with an authorization code, which is traded for tokens here so the
     * setup wizard only has to hear whether it worked.
     */
    override fun onLoginCallback(context: Context, uri: Uri) {
        val code = uri.getQueryParameter("code")
        val tokenManager = FitTrackeeTokenManager(context)
        val verifier = tokenManager.getCodeVerifier()
        val server = serverUrl()
        val success = if (code != null && verifier != null && server != null) {
            FitTrackeeApiClient(server, tokenManager).exchangeCode(code, verifier)
        } else {
            LOG.error("Login callback missing the code, the verifier or the server address")
            false
        }
        if (success) {
            tokenManager.clearCodeVerifier()
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(
            Intent(LOGIN_CALLBACK_ACTION).putExtra("success", success)
        )
    }

    override fun activityUrl(context: Context, remoteActivityId: String): String? {
        val server = serverUrl() ?: return null
        return "${server.trimEnd('/')}/workouts/$remoteActivityId"
    }

    override fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File
    ): WorkoutUploader.UploadResult {
        val server = serverUrl() ?: return WorkoutUploader.UploadResult(false, null, null)
        val result = FitTrackeeApiClient(server, FitTrackeeTokenManager(context))
            .uploadWorkout(payload, sportIdFor(summary))
        if (result.success && result.remoteActivityId != null) {
            updateMetadata(context, result.remoteActivityId, summary)
        }
        return result
    }

    override fun updateMetadata(
        context: Context,
        remoteActivityId: String,
        summary: BaseActivitySummary
    ): Boolean {
        val server = serverUrl() ?: return false
        return FitTrackeeApiClient(server, FitTrackeeTokenManager(context)).updateWorkout(
            remoteActivityId,
            WorkoutUploader.nameFor(context, summary),
            sportIdFor(summary)
        )
    }

    /**
     * The sport the workout is filed under. These are the ids of the sports FitTrackee seeds a new
     * instance with, which is all a client can go on: they are rows in the instance's own table
     * and an administrator can add more.
     */
    private fun sportIdFor(summary: BaseActivitySummary): Int =
        when (ActivityKind.fromCode(summary.activityKind)) {
            ActivityKind.CYCLING, ActivityKind.OUTDOOR_CYCLING, ActivityKind.INDOOR_CYCLING,
            ActivityKind.ROAD_BIKE, ActivityKind.GRAVEL_BIKE, ActivityKind.CYCLO_CROSS -> 1

            ActivityKind.BIKE_COMMUTE -> 2
            ActivityKind.HIKING, ActivityKind.MOUNTAIN_HIKE, ActivityKind.TRAIL_HIKE -> 3
            ActivityKind.MOUNTAIN_BIKE, ActivityKind.CYCLING_DOWNHILL -> 4

            ActivityKind.RUNNING, ActivityKind.OUTDOOR_RUNNING, ActivityKind.INDOOR_RUNNING,
            ActivityKind.STREET_RUNNING, ActivityKind.TRACK_RUN, ActivityKind.INDOOR_TRACK_RUNNING,
            ActivityKind.ULTRA_RUN, ActivityKind.TREADMILL, ActivityKind.VIRTUAL_RUN -> 5

            ActivityKind.WALKING, ActivityKind.OUTDOOR_WALKING, ActivityKind.INDOOR_WALKING,
            ActivityKind.RACE_WALKING -> 6

            ActivityKind.E_MOUNTAIN_BIKE -> 7
            ActivityKind.TRAIL_RUN, ActivityKind.CROSS_COUNTRY_RUNNING -> 8
            ActivityKind.ROWING, ActivityKind.ROWING_MACHINE -> 11
            ActivityKind.BIKE_TOUR -> 17
            ActivityKind.SWIMMING_OPENWATER -> 16
            ActivityKind.KAYAKING -> 19
            ActivityKind.CANOEING -> 20
            ActivityKind.STAND_UP_PADDLEBOARDING, ActivityKind.PADDLING -> 23
            else -> DEFAULT_SPORT_ID
        }

    private val LOG = LoggerFactory.getLogger(FitTrackeeService::class.java)

    /** Where the setup wizard listens for the outcome of the browser login. */
    const val LOGIN_CALLBACK_ACTION = "nodomain.freeyourgadget.gadgetbridge.FITTRACKEE_CALLBACK"

    /** Cycling, the first sport every instance is seeded with. */
    private const val DEFAULT_SPORT_ID = 1
}
