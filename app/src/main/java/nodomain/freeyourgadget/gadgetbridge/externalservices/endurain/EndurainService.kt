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
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteOp
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteLookup
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
        // which gives the activity a new id. See updateTrack.
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

    // recreateActivity compares the remote activity against what was uploaded and gives up when
    // it no longer matches.
    override val keepsRemoteEdits = true

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
                            done(WorkoutUploader.UploadResult(true, newId.toString(), null, mediaId))
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
    ): Boolean {
        val activityId = remoteActivityId.toIntOrNull() ?: return false
        val server = serverUrl() ?: return false
        val tokenManager = EndurainTokenManager(context)
        val apiClient = EndurainApiClient(server, tokenManager)
        val kind = ActivityKind.fromCode(summary.activityKind)
        val name = WorkoutUploader.nameFor(context, summary)

        return ExternalServiceCalls.await<Boolean>(WorkoutUploader.DEFAULT_UPLOAD_TIMEOUT_SECONDS) { done ->
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
        } == true
    }

    /**
     * Endurain can edit an activity's metadata but never its track, so the only way to push a
     * changed track is to delete the activity and upload it again under a new id.
     *
     * That is done unconditionally only when it is purely additive: the activity was uploaded
     * without a track and now has one, which is the case of a workout synced from a watch that
     * records none, with a GPX attached afterwards. Any other change re-creates an activity the
     * user may have been looking at for a while, so it needs their explicit consent.
     */
    override fun updateTrack(
        context: Context,
        remoteActivityId: String,
        payload: File,
        summary: BaseActivitySummary,
        row: WorkoutUpload
    ): TrackUpdateResult {
        val (result, refusal) = recreateActivity(context, summary, remoteActivityId, payload)
        if (result?.remoteActivityId != null) {
            return TrackUpdateResult.Recreated(result.remoteActivityId, result.photoMediaId)
        }
        return when (refusal) {
            RecreateRefusal.REMOTE_EDITED ->
                TrackUpdateResult.Refused(
                    context.getString(R.string.service_remote_edited, context.getString(nameRes))
                )

            else -> TrackUpdateResult.Failed
        }
    }

    /** Why a re-create was not carried out, for the message shown to the user. Null means it was. */
    private enum class RecreateRefusal { REMOTE_EDITED, FAILED }

    /**
     * Re-creates the activity [remoteActivityId] from [payload], because Endurain has no endpoint
     * that replaces an activity's track: `PUT /api/v1/activities/edit` takes metadata only.
     * Deleting and re-uploading is therefore the only way to push a track, and the reason this
     * operation is destructive at all. An endpoint that replaced a track in place would remove the
     * need for the whole function.
     *
     * Refuses when the remote activity carries anything the user wrote there (a description,
     * private notes or a gear assignment), since re-creating would destroy it. Whatever the API
     * does let us carry over is read first and restored afterwards, but the activity id changes,
     * so links to it break and anything Endurain later attaches to an activity id would be lost.
     *
     * The re-upload reuses the workout's start time. Endurain wedges a start time that already
     * carries two activities, and every upload at it fails with a 500 from then on, so a workout
     * uploaded by hand more than once cannot be re-created at all:
     * <https://codeberg.org/endurain-project/endurain/issues/858>.
     *
     * Blocking; call off the main thread.
     */
    private fun recreateActivity(
        context: Context,
        summary: BaseActivitySummary,
        remoteActivityId: String,
        payload: File
    ): Pair<WorkoutUploader.UploadResult?, RecreateRefusal?> {
        val activityId = remoteActivityId.toIntOrNull()
            ?: return null to RecreateRefusal.FAILED
        val server = serverUrl() ?: return null to RecreateRefusal.FAILED
        val apiClient = EndurainApiClient(server, EndurainTokenManager(context))

        val details = when (val lookup = apiClient.getActivityDetails(activityId)) {
            is EndurainActivityLookup.Failed -> return null to RecreateRefusal.FAILED

            // Deleted on the server since we uploaded it. Nothing to preserve and nothing to
            // delete, so this becomes a plain upload that adopts a new id.
            is EndurainActivityLookup.Gone -> null

            is EndurainActivityLookup.Found -> {
                if (lookup.details.hasUserContent) {
                    LOG.info("Not re-creating Endurain activity {}, it carries user edits", activityId)
                    return null to RecreateRefusal.REMOTE_EDITED
                }
                val media = apiClient.listActivityMedia(activityId)
                    ?: return null to RecreateRefusal.FAILED
                if (media.size > 1) {
                    LOG.info("Not re-creating Endurain activity {}, it carries extra media", activityId)
                    return null to RecreateRefusal.REMOTE_EDITED
                }
                if (!apiClient.deleteActivity(activityId)) {
                    return null to RecreateRefusal.FAILED
                }
                lookup.details
            }
        }

        val result = upload(context, summary, payload)
        if (!result.success || result.remoteActivityId == null) {
            // The activity is already gone at this point, so the workout is left with no online
            // copy until the next sync retries it.
            LOG.error("Re-upload of summary {} failed after its activity was deleted", summary.id)
            return null to RecreateRefusal.FAILED
        }
        if (details != null) {
            result.remoteActivityId.toIntOrNull()?.let {
                apiClient.restoreActivityDetails(
                    it,
                    ActivityKind.fromCode(summary.activityKind),
                    WorkoutUploader.nameFor(context, summary),
                    details
                )
            }
        }
        return result to null
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

    /**
     * Deletes the media entry a previous sync created ([previousMediaId]) and uploads [photoFile],
     * which is null when the photo was removed locally. Media the user attached on the server is
     * left alone, since only the entry Gadgetbridge created is deleted.
     */
    override fun syncPhoto(
        context: Context,
        remoteActivityId: String,
        photoFile: File?,
        previousMediaId: Int?
    ): WorkoutUploader.PhotoSyncResult {
        val failed = WorkoutUploader.PhotoSyncResult(false, previousMediaId)
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
                                done(WorkoutUploader.PhotoSyncResult(mediaId != null, mediaId))
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
