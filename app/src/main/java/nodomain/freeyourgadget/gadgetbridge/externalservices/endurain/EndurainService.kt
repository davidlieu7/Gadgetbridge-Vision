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
package nodomain.freeyourgadget.gadgetbridge.externalservices.endurain

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.fragment.app.Fragment
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.MetadataUpdateResult
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteOp
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RecreateOrder
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteLookup
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteSnapshot
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.UploadCapabilities
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutPayloadFormat
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadStore
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadTarget
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload
import nodomain.freeyourgadget.gadgetbridge.externalservices.AuthStyle
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceCalls
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceTokenRefreshWorker
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import org.slf4j.LoggerFactory
import java.io.File

object EndurainService : WorkoutUploadTarget {
    override val id = "endurain"
    override val nameRes = R.string.service_name_endurain
    override val authStyle = AuthStyle.LOGIN

    override val service = WorkoutUploadStore.SERVICE_ENDURAIN

    override val capabilities = UploadCapabilities(
        // A FIT can be synthesized from the summary alone, so a trackless workout still uploads.
        formats = listOf(WorkoutPayloadFormat.FIT),
        // No endpoint replaces an activity's track, so changing one means delete plus re-upload,
        // which gives the activity a new id. See snapshotRemote.
        activity = setOf(RemoteOp.CREATE, RemoteOp.REPLACE, RemoteOp.DELETE, RemoteOp.READ),
        metadata = setOf(RemoteOp.UPDATE),
        // Media is listed, deleted and uploaded, so a photo changes by way of a new media entry.
        media = setOf(RemoteOp.CREATE, RemoteOp.REPLACE, RemoteOp.DELETE)
    )

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
                R.string.service_logged_in_refresh_token,
                server,
                DateTimeUtils.parseTimeStamp(tokenManager.getRefreshTokenExpiresAt())
            )
        } else {
            context.getString(R.string.service_not_logged_in)
        }
        serverVersion?.let { summary += context.getString(R.string.service_server_version, it) }
        return summary
    }

    override fun showSetup(fragment: Fragment) {
        EndurainSetupBottomSheet().show(fragment.parentFragmentManager, "endurain_setup")
    }

    override fun logout(context: Context): Boolean {
        val server = serverUrl()
        val tokenManager = EndurainTokenManager(context)
        if (server == null) {
            tokenManager.clearTokens()
            return true
        }
        return try {
            EndurainApiClient(server, tokenManager).logout()
        } catch (e: Exception) {
            // The session may well be gone on the server already, and a user who cannot reach it
            // still has to be able to disconnect, so the credentials go either way.
            tokenManager.clearTokens()
            true
        }
    }

    /** The refresh token expires, so it is renewed on a schedule rather than at the next upload. */
    override fun scheduleBackgroundWork(context: Context) {
        ExternalServiceTokenRefreshWorker.schedule(context, this)
    }

    // snapshotRemote compares the remote activity against what was uploaded and gives up when
    // it no longer matches.
    override val keepsRemoteEdits = true

    override fun credentialsExpired(context: Context): Boolean =
        EndurainTokenManager(context).isAccessTokenExpired()

    override fun refreshCredentials(context: Context): Boolean {
        val server = serverUrl() ?: return false
        return ExternalServiceCalls.await<Boolean>(WorkoutUploader.DEFAULT_UPLOAD_TIMEOUT_SECONDS) { done ->
            EndurainTokenManager(context).performTokenRefresh(server) { done(it) }
        } == true
    }

    /**
     * Endurain brokers the identity provider itself, so the link comes back naming a session on
     * the server rather than an authorization code, and the setup wizard collects the tokens.
     */
    override fun onLoginCallback(context: Context, uri: Uri) {
        val sessionId = uri.getQueryParameter("session_id")
        val result = Intent(SSO_CALLBACK_ACTION).apply {
            putExtra("session_id", sessionId)
            putExtra("success", sessionId != null)
        }
        if (sessionId == null) {
            LOG.error("SSO callback carried no session id")
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(result)
    }

    override fun activityUrl(context: Context, remoteActivityId: String): String? {
        val server = serverUrl() ?: return null
        return "${server.trimEnd('/')}/activity/$remoteActivityId"
    }

    /**
     * Uploads [payload], then sets the activity type, name and header photo on the activity it
     * created. Those last steps are best-effort: they run only after a successful upload and their
     * failure does not change the result.
     */
    override fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File
    ): WorkoutUploader.UploadResult {
        val server = serverUrl() ?: return WorkoutUploader.UploadResult(false, null, null)
        val tokenManager = EndurainTokenManager(context)
        val apiClient = EndurainApiClient(server, tokenManager)
        val kind = ActivityKind.fromCode(summary.activityKind)
        val name = WorkoutUploader.nameFor(context, summary)

        return WorkoutUploader.awaitUpload(context) { done ->
            tokenManager.performTokenRefresh(server) {
                LOG.info("Uploading workout '{}' (type {}) to Endurain", name, kind)
                apiClient.uploadActivity(payload) { newId, reason ->
                    if (newId == null) {
                        done(WorkoutUploader.UploadResult(false, null, reason))
                        return@uploadActivity
                    }
                    try {
                        apiClient.editActivity(newId, kind, name)
                    } catch (e: Exception) {
                        LOG.warn("Endurain editActivity failed for id {}", newId, e)
                    }
                    val photoPath = summary.headerPhoto
                    if (photoPath == null) {
                        done(WorkoutUploader.UploadResult(true, newId.toString(), null))
                        return@uploadActivity
                    }
                    try {
                        apiClient.uploadActivityPhoto(newId, File(photoPath)) { mediaId ->
                            done(WorkoutUploader.UploadResult(true, newId.toString(), null, mediaId?.toString()))
                        }
                    } catch (e: Exception) {
                        LOG.warn("Endurain uploadActivityPhoto failed for id {}", newId, e)
                        done(WorkoutUploader.UploadResult(true, newId.toString(), null))
                    }
                }
            }
        }
    }

    override fun updateMetadata(
        context: Context,
        remoteActivityId: String,
        summary: BaseActivitySummary
    ): MetadataUpdateResult {
        val activityId = remoteActivityId.toIntOrNull() ?: return MetadataUpdateResult.Failed()
        val server = serverUrl() ?: return MetadataUpdateResult.Failed()
        val tokenManager = EndurainTokenManager(context)
        val apiClient = EndurainApiClient(server, tokenManager)
        val kind = ActivityKind.fromCode(summary.activityKind)
        val name = WorkoutUploader.nameFor(context, summary)

        val edited = ExternalServiceCalls.await<Boolean>(WorkoutUploader.DEFAULT_UPLOAD_TIMEOUT_SECONDS) { done ->
            tokenManager.performTokenRefresh(server) {
                Thread {
                    done(
                        try {
                            apiClient.editActivity(activityId, kind, name)
                        } catch (e: Exception) {
                            LOG.warn("Endurain editActivity failed for id {}", activityId, e)
                            false
                        }
                    )
                }.start()
            }
        }
        return if (edited == true) MetadataUpdateResult.Updated else MetadataUpdateResult.Failed()
    }

    /**
     * Endurain can edit an activity's metadata but never its track (`PUT /api/v1/activities/edit`
     * takes metadata only), so a changed track is pushed by re-creating the activity, which
     * [WorkoutUploader] drives through this, [upload] and [deleteRemote].
     *
     * Refuses when the remote activity carries anything the user wrote there (a description,
     * private notes or a gear assignment) or media beyond the header photo, since re-creating
     * would destroy it. The rest of what the API exposes is carried over, but the activity id
     * changes, so links to it break.
     *
     * The re-upload reuses the workout's start time. Endurain wedges a start time that already
     * carries two activities, and every upload at it fails with a 500 from then on, so a workout
     * uploaded by hand more than once cannot be re-created at all, and the old activity has to go
     * before the new one is created:
     * <https://codeberg.org/endurain-project/endurain/issues/858>.
     */
    override fun snapshotRemote(
        context: Context,
        remoteActivityId: String,
        row: WorkoutUpload
    ): RemoteSnapshot {
        val activityId = remoteActivityId.toIntOrNull() ?: return RemoteSnapshot.Failed
        val server = serverUrl() ?: return RemoteSnapshot.Failed
        val apiClient = EndurainApiClient(server, EndurainTokenManager(context))
        return when (val lookup = apiClient.getActivityDetails(activityId)) {
            is EndurainActivityLookup.Failed -> RemoteSnapshot.Failed
            is EndurainActivityLookup.Gone -> RemoteSnapshot.Gone
            is EndurainActivityLookup.Found -> {
                val media = apiClient.listActivityMedia(activityId) ?: return RemoteSnapshot.Failed
                RemoteSnapshot.Found(
                    lookup.details,
                    losesUserContent = lookup.details.hasUserContent || media.size > 1
                )
            }
        }
    }

    override val recreateOrder = RecreateOrder.DELETE_THEN_CREATE

    override fun deleteRemote(context: Context, remoteActivityId: String): Boolean {
        val activityId = remoteActivityId.toIntOrNull() ?: return false
        val server = serverUrl() ?: return false
        return EndurainApiClient(server, EndurainTokenManager(context)).deleteActivity(activityId)
    }

    override fun lookupRemote(context: Context, remoteActivityId: String): RemoteLookup {
        val activityId = remoteActivityId.toIntOrNull() ?: return RemoteLookup.FAILED
        val server = serverUrl() ?: return RemoteLookup.FAILED
        return when (EndurainApiClient(server, EndurainTokenManager(context)).getActivityDetails(activityId)) {
            is EndurainActivityLookup.Found -> RemoteLookup.PRESENT
            is EndurainActivityLookup.Gone -> RemoteLookup.GONE
            is EndurainActivityLookup.Failed -> RemoteLookup.FAILED
        }
    }

    /** Uploads the replacement, then restores on it what [snapshotRemote] read off the original. */
    override fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File,
        carry: Any?
    ): WorkoutUploader.UploadResult {
        val result = upload(context, summary, payload)
        val details = carry as? EndurainActivityDetails ?: return result
        val newId = result.remoteActivityId?.toIntOrNull() ?: return result
        val server = serverUrl() ?: return result
        EndurainApiClient(server, EndurainTokenManager(context)).restoreActivityDetails(
            newId,
            ActivityKind.fromCode(summary.activityKind),
            WorkoutUploader.nameFor(context, summary),
            details
        )
        return result
    }

    /**
     * Deletes the media entry a previous sync created ([previousMediaRef], an Endurain media id)
     * and uploads [photoFile], which is null when the photo was removed locally. Media the user
     * attached on the server is left alone, since only the entry Gadgetbridge created is deleted.
     */
    override fun syncPhoto(
        context: Context,
        remoteActivityId: String,
        photoFile: File?,
        previousMediaRef: String?
    ): WorkoutUploader.PhotoSyncResult {
        val failed = WorkoutUploader.PhotoSyncResult(false, previousMediaRef)
        val previousMediaId = previousMediaRef?.toIntOrNull()
        val activityId = remoteActivityId.toIntOrNull()
        if (activityId == null) {
            LOG.warn("Cannot sync photo: non-numeric Endurain activity id '{}'", remoteActivityId)
            return failed
        }
        val server = serverUrl() ?: return failed
        val tokenManager = EndurainTokenManager(context)
        val apiClient = EndurainApiClient(server, tokenManager)

        val result = ExternalServiceCalls.await<WorkoutUploader.PhotoSyncResult>(
            WorkoutUploader.DEFAULT_UPLOAD_TIMEOUT_SECONDS
        ) { done ->
            tokenManager.performTokenRefresh(server) {
                Thread {
                    try {
                        var removed = previousMediaId == null
                        if (previousMediaId != null) {
                            removed = apiClient.deleteActivityMedia(activityId, previousMediaId)
                            if (!removed) {
                                // Already gone server-side counts as removed, so a photo replaced
                                // after a manual deletion still goes through.
                                val remaining = apiClient.listActivityMedia(activityId)
                                removed = remaining != null && remaining.none { it.id == previousMediaId }
                            }
                        }
                        if (photoFile == null) {
                            done(WorkoutUploader.PhotoSyncResult(removed, null))
                        } else {
                            apiClient.uploadActivityPhoto(activityId, photoFile) { mediaId ->
                                done(WorkoutUploader.PhotoSyncResult(mediaId != null, mediaId?.toString()))
                            }
                        }
                    } catch (e: Exception) {
                        LOG.error("Endurain photo sync failed for activity {}", activityId, e)
                    }
                }.start()
            }
        }
        return result ?: failed
    }

    private val LOG = LoggerFactory.getLogger(EndurainService::class.java)

    /** Where the setup wizard listens for the outcome of a browser login. */
    const val SSO_CALLBACK_ACTION = "nodomain.freeyourgadget.gadgetbridge.ENDURAIN_SSO_CALLBACK"
}
