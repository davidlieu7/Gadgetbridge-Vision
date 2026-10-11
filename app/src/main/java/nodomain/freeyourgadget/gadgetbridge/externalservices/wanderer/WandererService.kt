/*  Copyright (C) 2026 Dany Mestas, José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.wanderer

import android.content.Context
import androidx.fragment.app.Fragment
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.MetadataUpdateResult
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteLookup
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteOp
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.TrackUpdateResult
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.UploadCapabilities
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutPayloadFormat
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadStore
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadTarget
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload
import nodomain.freeyourgadget.gadgetbridge.externalservices.AuthStyle
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceCalls
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import org.slf4j.LoggerFactory
import java.io.File

object WandererService : WorkoutUploadTarget {
    override val id = "wanderer"
    override val nameRes = R.string.service_name_wanderer
    override val authStyle = AuthStyle.API_TOKEN

    override val service = WorkoutUploadStore.SERVICE_WANDERER

    override val capabilities = UploadCapabilities(
        // Wanderer stores trails, so there is nothing to upload without a track.
        formats = listOf(WorkoutPayloadFormat.GPX),
        // trail/form/{id} writes the new track onto the existing trail, so its id and whatever the
        // user did to it survive.
        activity = setOf(RemoteOp.CREATE, RemoteOp.UPDATE, RemoteOp.READ),
        // trail/form/{id} also takes the fields beside the file, so a name can be written on
        // its own without sending the track again.
        metadata = setOf(RemoteOp.UPDATE),
        media = emptySet()
    )

    override fun isLoggedIn(context: Context) =
        serverUrl() != null && WandererTokenManager(context).isLoggedIn()

    override fun showSetup(fragment: Fragment) {
        WandererSetupBottomSheet().show(fragment.parentFragmentManager, "wanderer_setup")
    }

    // The API token is issued and revoked on the server, so there is nothing to tell it about.
    override fun logout(context: Context): Boolean {
        WandererTokenManager(context).clearTokens()
        return true
    }


    /**
     * A trail page is namespaced by the handle of the account that owns it, and a handle that does
     * not resolve answers 500 rather than falling back to the trail, so there is no link to give
     * until the handle is known.
     */
    override fun activityUrl(context: Context, remoteActivityId: String): String? {
        val server = serverUrl() ?: return null
        val handle = WandererTokenManager(context).getHandle() ?: return null
        return "${server.trimEnd('/')}/trail/view/@$handle/$remoteActivityId"
    }

    override fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File
    ): WorkoutUploader.UploadResult {
        val server = serverUrl()
            ?: return WorkoutUploader.UploadResult(false, null, null)
        return WorkoutUploader.awaitUpload(context) { done ->
            WandererApiClient(server, WandererTokenManager(context)).uploadActivity(payload) { newId, message ->
                if (newId != null && message == null) {
                    LOG.info("Uploaded GPX to Wanderer, ID {}", newId)
                    done(WorkoutUploader.UploadResult(true, newId, null))
                } else {
                    done(WorkoutUploader.UploadResult(false, null, message))
                }
            }
        }
    }

    override fun updateMetadata(
        context: Context,
        remoteActivityId: String,
        summary: BaseActivitySummary
    ): MetadataUpdateResult {
        val server = serverUrl() ?: return MetadataUpdateResult.Failed()
        val result = ExternalServiceCalls.await<WandererWriteResult>(WorkoutUploader.DEFAULT_UPLOAD_TIMEOUT_SECONDS) { done ->
            WandererApiClient(server, WandererTokenManager(context))
                .updateTrailFields(
                    remoteActivityId,
                    mapOf("name" to WorkoutUploader.nameFor(context, summary))
                ) { done(it) }
        }
        return when (result) {
            is WandererWriteResult.Done -> MetadataUpdateResult.Updated
            is WandererWriteResult.Gone -> MetadataUpdateResult.Gone
            is WandererWriteResult.Failed -> MetadataUpdateResult.Failed(result.reason)
            null -> MetadataUpdateResult.Failed()
        }
    }

    override fun updateTrack(
        context: Context,
        remoteActivityId: String,
        payload: File,
        summary: BaseActivitySummary,
        row: WorkoutUpload
    ): TrackUpdateResult {
        val server = serverUrl() ?: return TrackUpdateResult.Failed()
        val result = ExternalServiceCalls.await<WandererWriteResult>(WorkoutUploader.DEFAULT_UPLOAD_TIMEOUT_SECONDS) { done ->
            WandererApiClient(server, WandererTokenManager(context))
                .updateActivityFile(remoteActivityId, payload, statsOf(summary)) { done(it) }
        }
        return when (result) {
            is WandererWriteResult.Done -> TrackUpdateResult.Updated
            is WandererWriteResult.Gone -> TrackUpdateResult.Gone
            is WandererWriteResult.Failed -> TrackUpdateResult.Failed(result.reason)
            null -> TrackUpdateResult.Failed()
        }
    }

    override fun lookupRemote(context: Context, remoteActivityId: String): RemoteLookup {
        val server = serverUrl() ?: return RemoteLookup.FAILED
        return when (WandererApiClient(server, WandererTokenManager(context)).trailExists(remoteActivityId)) {
            true -> RemoteLookup.PRESENT
            false -> RemoteLookup.GONE
            null -> RemoteLookup.FAILED
        }
    }

    /**
     * Trail statistics to send alongside a replacement track; see
     * [WandererApiClient.updateActivityFile]. Entries whose value the summary does not carry are
     * left out, for the server to keep or derive.
     */
    internal fun statsOf(summary: BaseActivitySummary): Map<String, String> {
        val data = summary.summaryData?.let {
            try {
                ActivitySummaryData.fromJson(it)
            } catch (e: Exception) {
                LOG.warn("Failed to parse stored summary data for summary {}", summary.id, e)
                null
            }
        } ?: return emptyMap()

        val stats = mutableMapOf<String, String>()
        data.getNumber(ActivitySummaryEntries.DISTANCE_METERS, null)
            ?.let { stats["distance"] = it.toString() }
        data.getNumber(ActivitySummaryEntries.ACTIVE_SECONDS, null)
            ?.let { stats["duration"] = it.toString() }
        data.getNumber(ActivitySummaryEntries.ELEVATION_GAIN, null)
            ?.let { stats["elevation_gain"] = it.toString() }
        data.getNumber(ActivitySummaryEntries.ELEVATION_LOSS, null)
            ?.let { stats["elevation_loss"] = it.toString() }
        return stats
    }

    private val LOG = LoggerFactory.getLogger(WandererService::class.java)
}
