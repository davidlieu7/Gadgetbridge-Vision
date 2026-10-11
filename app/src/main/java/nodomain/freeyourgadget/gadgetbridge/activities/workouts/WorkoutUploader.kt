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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload
import nodomain.freeyourgadget.gadgetbridge.export.FitExporter
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceCalls
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider
import nodomain.freeyourgadget.gadgetbridge.util.ActivitySummaryUtils
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Exports one workout and drives whichever services hold it, used by the workout detail screen and
 * by the background auto-upload worker so the two behave identically.
 *
 * Nothing here knows which services exist: what a service accepts and what it can still change is
 * read off its [WorkoutUploadTarget.capabilities], and the requests themselves live in the service.
 *
 * All methods are blocking and safe to call off the main thread.
 */
object WorkoutUploader {
    private val LOG = LoggerFactory.getLogger(WorkoutUploader::class.java)

    /**
     * A workout's display name, falling back to the localized activity-kind label when the
     * summary carries no user-set name (most devices, e.g. Xiaomi/Mi Band, never set one).
     */
    fun nameFor(context: Context, summary: BaseActivitySummary): String =
        summary.name ?: ActivityKind.fromCode(summary.activityKind).getLabel(context)

    /**
     * Builds a `.fit` file for [summary] in the cache directory and returns it.
     *
     * FIT-native devices (Garmin, iGPSPORT) keep the original .fit at rawDetailsPath, which is
     * copied verbatim. For any other device the FIT is synthesized from the summary (and the
     * activity track, if one is available).
     *
     * [summaryData] supplies the session-level fields of the exported FIT (distance, calories,
     * average heart rate, pool length). Callers that have already parsed it pass it in to avoid
     * re-parsing; when omitted it is read off [summary].
     *
     * Blocking: call from an IO context.
     */
    fun buildFitFile(
        context: Context,
        gbDevice: GBDevice,
        summary: BaseActivitySummary,
        summaryData: ActivitySummaryData? = null
    ): File {
        val effectiveSummaryData = summaryData ?: summary.summaryData?.let {
            try {
                ActivitySummaryData.fromJson(it)
            } catch (e: Exception) {
                LOG.warn("Failed to parse stored summary data for summary {}", summary.id, e)
                null
            }
        }
        val fileName = ActivitySummaryUtils.getExportBaseName(context, summary) + ".fit"
        val cacheSubDir = File(context.cacheDir, "raw")
        cacheSubDir.mkdirs()
        val outFile = File(cacheSubDir, fileName)

        val rawFit = FitExporter.resolveRawFitFile(summary)
        if (rawFit != null) {
            rawFit.copyTo(outFile, overwrite = true)
        } else {
            val activityTrackProvider = gbDevice.deviceCoordinator
                .getActivityTrackProvider(gbDevice, context)
            val track = try {
                ActivitySummaryUtils.resolveExportableTrack(activityTrackProvider, summary)
            } catch (e: Exception) {
                LOG.warn("Failed to load activity track for FIT export", e)
                null
            }
            FitExporter().performExport(track, summary, effectiveSummaryData, outFile)
        }
        return outFile
    }

    /**
     * Whether [summary] carries a GPS track, either as an attached gpx file or as the hasGps
     * flag its parser set. Mirrors what the detail screen uses to decide whether to offer the
     * map and the gpx actions.
     */
    fun summaryHasTrack(summary: BaseActivitySummary): Boolean {
        val summaryData = summary.summaryData?.let {
            try {
                ActivitySummaryData.fromJson(it)
            } catch (e: Exception) {
                null
            }
        }
        if (summaryData != null && summaryData.hasGps()) {
            return true
        }
        val gpxTrack = summary.gpxTrack ?: return false
        val existing = FileUtils.tryFixPath(File(gpxTrack))
        return existing != null && existing.canRead()
    }

    /** What a sync of one workout to one service did. */
    sealed interface SyncOutcome {
        /**
         * The service took the workout for the first time. [refusal] is the localized reason for
         * a part of it the service would not take, null when all of it went through.
         */
        data class Uploaded(val refusal: String? = null) : SyncOutcome

        /**
         * The remote activity was brought in line with the workout. [refusal] is the localized
         * reason for the part of the sync the service would not do, null when all of it went
         * through.
         */
        data class Resynced(val refusal: String?) : SyncOutcome

        /** The service already holds the workout as it stands, so nothing was sent. */
        object AlreadyInStep : SyncOutcome

        /**
         * The service took the workout and has not imported it yet, so there is no activity to
         * bring in line; see [WorkoutUploadTarget.resolveRemoteId].
         */
        object AwaitingImport : SyncOutcome

        /** The workout cannot be expressed in the format this service takes. */
        object NotApplicable : SyncOutcome

        /** [reason] is a localized explanation, null when the failure was transient. */
        data class Failed(val reason: String?) : SyncOutcome
    }

    /**
     * Sends [summary] to [target], doing whichever of the two the recorded state calls for: a
     * first upload when the service does not hold the workout, and a re-sync of what changed
     * since when it does. A service that already holds it as it stands is left alone, so asking
     * for an upload twice does not leave a second activity behind.
     *
     * With [verifyRemote], a service that can tell ([UploadCapabilities.canLookUp]) is first asked
     * whether it still holds the activity the row names, and one deleted there is uploaded afresh.
     * That costs a request, so it is meant for an upload the user asked for, not for every
     * background run.
     *
     * [summaryData] supplies the exported session fields; see [buildFitFile].
     *
     * Blocking: call from an IO context.
     */
    fun syncWorkout(
        context: Context,
        gbDevice: GBDevice,
        provider: ActivityTrackProvider?,
        target: WorkoutUploadTarget,
        summary: BaseActivitySummary,
        summaryData: ActivitySummaryData? = null,
        verifyRemote: Boolean = false
    ): SyncOutcome {
        val id = summary.id ?: return SyncOutcome.NotApplicable
        val sourceHash = WorkoutUploadStore.sourceHashOf(summary)
        val row = WorkoutUploadStore.rowsForSummaries(listOf(id))[id]?.get(target.service)
            ?.takeIf { WorkoutUploadTargets.holds(context, target, it) }
        val action = syncActionFor(row, sourceHash)
        val remoteId = row?.remoteActivityId
        if (verifyRemote && action != SyncAction.UPLOAD && remoteId != null && target.capabilities.canLookUp) {
            renewExpiredCredentials(context, target)
            when (target.lookupRemote(context, remoteId)) {
                RemoteLookup.GONE -> {
                    LOG.info("Service {} no longer holds activity {} of summary {}, uploading it again",
                        target.service, remoteId, id)
                    val outcome = uploadNew(context, gbDevice, provider, target, summary, sourceHash, summaryData)
                    if (outcome !is SyncOutcome.Uploaded) {
                        // The row would otherwise still name the deleted activity.
                        recordWithoutId(
                            target, row, sourceHash,
                            context.getString(R.string.service_remote_deleted, context.getString(target.nameRes))
                        )
                    }
                    return outcome
                }

                RemoteLookup.FAILED -> return SyncOutcome.Failed(null)
                RemoteLookup.PRESENT -> Unit
            }
        }
        return when (action) {
            SyncAction.UPLOAD ->
                uploadNew(context, gbDevice, provider, target, summary, sourceHash, summaryData)

            SyncAction.ALREADY_IN_STEP -> SyncOutcome.AlreadyInStep

            SyncAction.RESYNC -> resyncWorkout(
                context, gbDevice, provider, target, summary, row!!, sourceHash, summaryData
            )
        }
    }

    /**
     * An access token that lasts minutes is expired far more often than not by the time a workout
     * is sent, so it is renewed before talking to [target] rather than spending a request to find
     * out. A renewal that fails is left for the request itself to report.
     */
    private fun renewExpiredCredentials(context: Context, target: WorkoutUploadTarget) {
        if (target.credentialsExpired(context) && !target.refreshCredentials(context)) {
            LOG.warn("Could not renew the credentials of service {}", target.service)
        }
    }

    /** What sending a workout to a service amounts to, given what that service already holds. */
    enum class SyncAction { UPLOAD, RESYNC, ALREADY_IN_STEP }

    /**
     * Whether the track [target] holds may be brought in line with [summary].
     *
     * A service that writes the new track onto the activity it holds loses nothing by it, so the
     * user is opted in. One that can only replace the activity gives it a new address and drops
     * whatever the service hung off the old one, so that is opted out of and asked for.
     *
     * Either way it goes ahead unasked when it is purely additive: the activity went up without a
     * track and now has one, which is the case of a workout from a watch that records none with a
     * route attached afterwards.
     */
    @JvmStatic
    fun mayPushTrackUpdate(
        target: WorkoutUploadTarget,
        summary: BaseActivitySummary,
        row: WorkoutUpload
    ): Boolean {
        val capabilities = target.capabilities
        if (!capabilities.activity.canChange()) {
            return false
        }
        val trackAdded = row.hadTrack != true && summaryHasTrack(summary)
        if (trackAdded) {
            return true
        }
        return GBApplication.getPrefs()
            .getBoolean(target.syncUpdatesPrefKey, !capabilities.trackChangeIsDestructive)
    }

    /**
     * What to do about [row], the service's last word on this workout, now that the workout
     * fingerprints as [sourceHash].
     *
     * A service holds the activity only once an upload has succeeded, so anything else means
     * starting over: never uploaded, or an upload that failed. A row that succeeded is left alone
     * when the workout has not changed since and nothing was left outstanding, which is what stops
     * asking twice from leaving a second activity behind.
     *
     * A success with no activity id and an error is an upload the service took and holds nothing
     * for any more (see [isLost]), so asking for it is uploading it again.
     */
    @JvmStatic
    fun syncActionFor(row: WorkoutUpload?, sourceHash: String): SyncAction = when {
        row == null || row.status != WorkoutUploadStore.STATUS_SUCCESS -> SyncAction.UPLOAD
        isLost(row) -> SyncAction.UPLOAD
        row.sourceHash == sourceHash && row.lastError == null -> SyncAction.ALREADY_IN_STEP
        else -> SyncAction.RESYNC
    }

    /**
     * Whether [row] is an upload the service took and then holds nothing for: an import it
     * rejected, or an activity deleted on its side. The row stays successful so the background
     * worker does not send the workout again on every run, and keeps the reason for the user; only
     * an upload they ask for sends it again.
     *
     * No other row carries an error without an id: a refused photo is only recorded against an
     * activity the service named.
     */
    @JvmStatic
    fun isLost(row: WorkoutUpload): Boolean =
        row.status == WorkoutUploadStore.STATUS_SUCCESS &&
                row.remoteActivityId == null && row.lastError != null

    /**
     * Whether [row] is an upload the service took without naming what it became, and has not been
     * asked about since; see [WorkoutUploadTarget.resolveRemoteId].
     */
    @JvmStatic
    fun isAwaitingRemoteId(row: WorkoutUpload): Boolean =
        row.status == WorkoutUploadStore.STATUS_SUCCESS &&
                row.remoteActivityId == null && row.sourceHash == null && row.lastError == null

    /**
     * Fingerprint of the file [target] is sent for [summary], as stored in `payloadHash`. A
     * service whose metadata comes from the file has the name and type folded in, unless sending
     * the file again would cost the activity its address.
     */
    @JvmStatic
    fun payloadHashFor(target: WorkoutUploadTarget, summary: BaseActivitySummary): String =
        WorkoutUploadStore.trackHashOf(
            summary,
            target.capabilities.metadataComesFromFile &&
                    !target.capabilities.trackChangeIsDestructive
        )

    /** Uploads [summary] to [target] for the first time. Blocking: call from an IO context. */
    fun uploadNew(
        context: Context,
        gbDevice: GBDevice,
        provider: ActivityTrackProvider?,
        target: WorkoutUploadTarget,
        summary: BaseActivitySummary,
        sourceHash: String,
        summaryData: ActivitySummaryData? = null
    ): SyncOutcome {
        val id = summary.id ?: return SyncOutcome.NotApplicable
        val payload = buildPayload(context, gbDevice, provider, target, summary, summaryData)
            ?: return SyncOutcome.NotApplicable
        renewExpiredCredentials(context, target)
        val result = target.upload(context, summary, payload)
        if (!result.success) {
            LOG.warn("Upload of summary {} to service {} failed: {}", id, target.service, result.reason)
            WorkoutUploadStore.recordFailure(id, target.service, result.reason, target.accountKey(context))
            return SyncOutcome.Failed(result.reason)
        }
        // A photo the service could have taken and did not leaves both fingerprints unset, so the
        // next run comes back for it rather than treating the workout as fully in step. A service
        // that holds no media has nothing to come back for.
        val photoLanded = summary.headerPhoto == null ||
                RemoteOp.CREATE !in target.capabilities.media ||
                result.photoMediaRef != null
        // A photo refused for a reason only the user can fix is not worth retrying on every run:
        // the workout counts as in step, the reason is kept for the user, and the photo fingerprint
        // stays unset so the next sync that is asked for tries it again. Without an activity id
        // there is no refusal to keep; see isLost.
        val awaitingId = result.remoteActivityId == null
        val photoRefused = !photoLanded && result.photoRefusal != null && !awaitingId
        // An upload that named no activity leaves the source fingerprint unset too, so the next
        // run asks the service what the file became.
        WorkoutUploadStore.recordSuccess(
            id, target.service, result.remoteActivityId,
            if (photoLanded) WorkoutUploadStore.photoHashOf(summary.headerPhoto) else null,
            result.photoMediaRef,
            if ((photoLanded || photoRefused) && !awaitingId) sourceHash else null,
            payloadHashFor(target, summary),
            summaryHasTrack(summary),
            if (photoRefused) result.photoRefusal else null,
            target.accountKey(context)
        )
        if (awaitingId) {
            scheduleRemoteIdCheck(context, target)
        }
        return SyncOutcome.Uploaded(if (photoRefused) result.photoRefusal else null)
    }

    /** See [RemoteIdResolveWorker]. */
    private fun scheduleRemoteIdCheck(context: Context, target: WorkoutUploadTarget) {
        try {
            RemoteIdResolveWorker.schedule(context, target.service)
        } catch (e: IllegalStateException) {
            // WorkManager is not initialised; the next sync asks instead.
            LOG.warn("Could not schedule asking service {} for the activity ids", target.service, e)
        }
    }

    /**
     * Pushes to [target] whatever changed about [summary] since its last upload: the header photo,
     * the name and type, and the track. Each of those is skipped when the service cannot express
     * it, which is why the fingerprints are still stored on a partial sync: they record what the
     * remote activity has been brought in line with, not what changed locally.
     *
     * Blocking: call from an IO context.
     */
    fun resyncWorkout(
        context: Context,
        gbDevice: GBDevice,
        provider: ActivityTrackProvider?,
        target: WorkoutUploadTarget,
        summary: BaseActivitySummary,
        row: WorkoutUpload,
        sourceHash: String,
        summaryData: ActivitySummaryData? = null
    ): SyncOutcome {
        val id = summary.id ?: return SyncOutcome.NotApplicable
        var remoteId = row.remoteActivityId ?: run {
            if (isAwaitingRemoteId(row)) {
                renewExpiredCredentials(context, target)
                when (val resolution = target.resolveRemoteId(context, summary, row)) {
                    is RemoteIdResolution.Resolved -> {
                        LOG.info("Upload of summary {} to service {} became {}",
                            id, target.service, resolution.remoteActivityId)
                        return@run resolution.remoteActivityId
                    }

                    is RemoteIdResolution.Pending -> return SyncOutcome.AwaitingImport
                    is RemoteIdResolution.Failed -> return SyncOutcome.Failed(null)
                    is RemoteIdResolution.Rejected -> {
                        LOG.warn("Service {} did not import summary {}: {}",
                            target.service, id, resolution.reason)
                        recordWithoutId(target, row, sourceHash, resolution.reason)
                        return SyncOutcome.Failed(resolution.reason)
                    }

                    is RemoteIdResolution.Unknown -> {}
                }
            }
            // Nothing to point a later change at: a service that never names what an upload
            // became, or one that holds nothing for it any more. Recording the current fingerprint
            // says so once, keeping whatever the user was told, instead of bringing every later
            // sync back to it.
            recordWithoutId(target, row, sourceHash, row.lastError)
            return SyncOutcome.AlreadyInStep
        }
        renewExpiredCredentials(context, target)
        var photoHash = row.photoHash
        var photoMediaRef = row.photoMediaRef
        var payloadHash = row.payloadHash
        var hadTrack = row.hadTrack
        var refusal: String? = null
        // Set when something the service could have taken did not get through, so the fingerprint
        // is left as it was and the next run comes back to it.
        var pending = false

        // The track goes first: a service that can only update one by re-creating the activity
        // gives back a new activity id, and the photo and metadata belong on the replacement.
        var recreated = false
        var awaitingId = false
        // A service that takes the name out of the file has to be sent the file again to hear
        // about a rename, unless sending it would cost the activity its address.
        val newTrackHash = payloadHashFor(target, summary)
        // A service with no way to change a track is not asked to build a payload it cannot use,
        // and neither is a workout whose track is the one the service already holds.
        val trackChanged =
            target.capabilities.activity.canChange() && newTrackHash != row.payloadHash
        val mayPushTrack = trackChanged && mayPushTrackUpdate(target, summary, row)
        val payload = if (mayPushTrack) {
            buildPayload(context, gbDevice, provider, target, summary, summaryData)
        } else {
            null
        }
        if (trackChanged && !mayPushTrack) {
            // The only way this service can take the new track destroys the activity, and the user
            // has not agreed to that. Saying so once is better than asking again on every sync.
            refusal = context.getString(
                R.string.workout_upload_status_track_change_declined,
                context.getString(target.nameRes)
            )
        } else if (payload != null) {
            val update = if (target.capabilities.trackChangeIsDestructive) {
                recreate(context, target, remoteId, payload, summary, row)
            } else {
                target.updateTrack(context, remoteId, payload, summary, row)
            }
            when (update) {
                is TrackUpdateResult.Updated -> {
                    payloadHash = newTrackHash
                    hadTrack = summaryHasTrack(summary)
                }

                is TrackUpdateResult.Recreated -> {
                    payloadHash = newTrackHash
                    hadTrack = summaryHasTrack(summary)
                    // A replacement the service names only once imported is asked for on the
                    // next run, which finds the new file by this payload hash.
                    update.remoteActivityId?.let { remoteId = it } ?: run { awaitingId = true }
                    recreated = true
                    photoMediaRef = update.photoMediaRef
                    // The replacement is uploaded from the current workout, so it carries the
                    // current photo, but only once the media id comes back.
                    if (summary.headerPhoto == null || update.photoMediaRef != null) {
                        photoHash = WorkoutUploadStore.photoHashOf(summary.headerPhoto)
                    } else if (update.photoRefusal != null) {
                        // The replacement has no photo; see the photo refusal below.
                        photoHash = null
                        refusal = refusal ?: update.photoRefusal
                    } else {
                        pending = true
                    }
                }

                // A deliberate refusal will not resolve itself, so the fingerprints advance and
                // the user is told once rather than on every sync.
                is TrackUpdateResult.Refused -> refusal = update.message

                is TrackUpdateResult.Gone -> return recordGone(context, target, row, sourceHash, remoteId)

                is TrackUpdateResult.Failed -> {
                    // A transient failure: keep the old fingerprints so the next run retries.
                    LOG.warn("Track update to service {} failed for summary {}: {}",
                        target.service, id, update.reason)
                    return SyncOutcome.Failed(update.reason)
                }
            }
        }

        val newPhotoHash = WorkoutUploadStore.photoHashOf(summary.headerPhoto)
        if (!recreated && newPhotoHash != row.photoHash) {
            val canSync = target.capabilities.canSyncMedia(
                hadPhoto = row.photoHash != null,
                hasPhoto = summary.headerPhoto != null
            )
            if (!canSync) {
                // Nothing this service can do about the photo, so the fingerprint advances rather
                // than bringing every later sync back to a step that cannot go through.
                photoHash = newPhotoHash
            } else {
                val sync = target.syncPhoto(
                    context, remoteId, summary.headerPhoto?.let { File(it) }, row.photoMediaRef
                )
                if (sync.success) {
                    photoHash = newPhotoHash
                    photoMediaRef = sync.mediaRef
                } else if (sync.refusal != null) {
                    // Not retried on its own, for the reason given in uploadNew; the photo
                    // fingerprint stays as it was so the next sync that is asked for tries again.
                    photoMediaRef = sync.mediaRef
                    refusal = refusal ?: sync.refusal
                } else {
                    LOG.warn("Photo sync to service {} failed for summary {}", target.service, id)
                    pending = true
                }
            }
        }

        // A replacement activity is created with the right name and type already.
        val metadata = if (!recreated && target.capabilities.editableMetadata) {
            target.updateMetadata(context, remoteId, summary)
        } else {
            MetadataUpdateResult.Updated
        }
        if (metadata is MetadataUpdateResult.Gone) {
            return recordGone(context, target, row, sourceHash, remoteId)
        }
        if (metadata is MetadataUpdateResult.Failed) {
            LOG.warn("Metadata update to service {} failed for summary {}: {}",
                target.service, id, metadata.reason)
            pending = true
        }

        // What went through is recorded either way. A transient failure keeps the old fingerprint
        // so the next run retries; a refusal the user has to resolve is carried by the row until a
        // later sync goes through.
        WorkoutUploadStore.recordSuccess(
            id, target.service, if (awaitingId) null else remoteId, photoHash, photoMediaRef,
            when {
                awaitingId -> null
                pending -> row.sourceHash
                else -> sourceHash
            },
            payloadHash, hadTrack, if (awaitingId) null else refusal,
            target.accountKey(context) ?: row.remoteAccount
        )
        if (awaitingId) {
            scheduleRemoteIdCheck(context, target)
        }
        return if (metadata is MetadataUpdateResult.Failed) {
            SyncOutcome.Failed(metadata.reason)
        } else {
            SyncOutcome.Resynced(refusal)
        }
    }

    /**
     * Records that [remoteActivityId] was deleted on [target]'s side. It is not brought back on its
     * own: the user is told once, and an upload they ask for sends the workout again (see [isLost]).
     */
    private fun recordGone(
        context: Context,
        target: WorkoutUploadTarget,
        row: WorkoutUpload,
        sourceHash: String,
        remoteActivityId: String
    ): SyncOutcome {
        val message = context.getString(R.string.service_remote_deleted, context.getString(target.nameRes))
        LOG.info("Activity {} of summary {} is gone from service {}",
            remoteActivityId, row.summaryId, target.service)
        recordWithoutId(target, row, sourceHash, message)
        return SyncOutcome.Resynced(message)
    }

    /**
     * Records [row] as an upload with no activity id to act on, in step with [sourceHash] so the
     * worker leaves it alone. [lastError] set makes it lost; see [isLost].
     */
    private fun recordWithoutId(
        target: WorkoutUploadTarget,
        row: WorkoutUpload,
        sourceHash: String,
        lastError: String?
    ) {
        WorkoutUploadStore.recordSuccess(
            row.summaryId, target.service, null, row.photoHash, row.photoMediaRef,
            sourceHash, row.payloadHash, row.hadTrack, lastError, row.remoteAccount
        )
    }

    /**
     * Brings the track of [remoteActivityId] in line by replacing the activity, for a service
     * that offers no way to write a new track onto one it holds. What the service can move off
     * the old activity is read first and handed to the upload of the new one; when something the
     * user added there cannot be moved, the activity is left as it is and the user told why.
     */
    internal fun recreate(
        context: Context,
        target: WorkoutUploadTarget,
        remoteActivityId: String,
        payload: File,
        summary: BaseActivitySummary,
        row: WorkoutUpload
    ): TrackUpdateResult {
        val snapshot = target.snapshotRemote(context, remoteActivityId, row)
        val carry = when (snapshot) {
            is RemoteSnapshot.Failed -> return TrackUpdateResult.Failed()
            is RemoteSnapshot.Found -> {
                if (snapshot.losesUserContent) {
                    LOG.info("Not re-creating {} on service {}, it carries edits made there",
                        remoteActivityId, target.service)
                    return TrackUpdateResult.Refused(
                        context.getString(R.string.service_remote_edited, context.getString(target.nameRes))
                    )
                }
                snapshot.carry
            }

            is RemoteSnapshot.Gone -> null
        }
        val stillThere = snapshot is RemoteSnapshot.Found
        val createFirst = target.recreateOrder == RecreateOrder.CREATE_THEN_DELETE
        if (stillThere && !createFirst && !target.deleteRemote(context, remoteActivityId)) {
            LOG.warn("Could not delete {} on service {}, leaving it as it was",
                remoteActivityId, target.service)
            return TrackUpdateResult.Failed()
        }
        val result = target.upload(context, summary, payload, carry)
        val newId = result.remoteActivityId
        if (!result.success) {
            if (stillThere && !createFirst) {
                // The activity is already gone, so the workout has no online copy until the next
                // sync retries it.
                LOG.error("Re-upload of summary {} to service {} failed after {} was deleted",
                    summary.id, target.service, remoteActivityId)
            }
            return TrackUpdateResult.Failed(result.reason)
        }
        if (stillThere && createFirst && !target.deleteRemote(context, remoteActivityId)) {
            // The replacement is recorded all the same: a leftover duplicate can be removed by
            // hand, whereas forgetting the replacement would upload a third copy next time.
            LOG.warn("Replaced {} on service {} with {} but could not delete the old one",
                remoteActivityId, target.service, newId)
        }
        LOG.info("Re-created {} on service {} as {} for summary {}",
            remoteActivityId, target.service, newId ?: "an activity it names once imported", summary.id)
        return TrackUpdateResult.Recreated(newId, result.photoMediaRef, result.photoRefusal)
    }

    /**
     * Exports [summary] in the format [target] accepts, or null when the workout cannot be
     * expressed in it. A FIT is synthesized from the summary alone when there is no track; a GPX
     * needs one, which is why a service that stores tracks skips trackless workouts outright.
     */
    private fun buildPayload(
        context: Context,
        gbDevice: GBDevice,
        provider: ActivityTrackProvider?,
        target: WorkoutUploadTarget,
        summary: BaseActivitySummary,
        summaryData: ActivitySummaryData?
    ): File? {
        return when (target.capabilities.formatFor(summary)) {
            WorkoutPayloadFormat.FIT -> try {
                buildFitFile(context, gbDevice, summary, summaryData)
            } catch (e: Exception) {
                LOG.warn("Could not build FIT for summary {}", summary.id, e)
                null
            }

            WorkoutPayloadFormat.GPX -> provider?.let {
                ActivitySummaryUtils.getShareableGpxFile(it, summary)
            }

            null -> null
        }
    }

    /**
     * Outcome of an upload attempt. [remoteActivityId] is the id the service assigned to the new
     * activity, null on failure. [photoMediaRef] is the service's id for the media entry the
     * header photo was uploaded as, null when there was no photo or the photo step failed.
     * [reason] is a localized failure explanation, null on success. [photoRefusal] is the localized
     * reason the service would not take the photo when that is for the user to fix, such as the
     * login not allowing media, rather than a failure the next run may get past.
     */
    data class UploadResult(
        val success: Boolean,
        val remoteActivityId: String?,
        val reason: String?,
        val photoMediaRef: String? = null,
        val photoRefusal: String? = null
    )

    /**
     * Outcome of a header photo sync. [mediaRef] is the service's id for the media entry now
     * holding the photo, null when the photo was removed or the upload failed. [refusal] is set in
     * place of success when the service would not take the photo for a reason the user has to fix;
     * see [UploadResult.photoRefusal].
     */
    data class PhotoSyncResult(val success: Boolean, val mediaRef: String?, val refusal: String? = null)

    /**
     * Runs a service's callback-style upload to completion, answering a failure the user can read
     * when it never comes back. Blocking: call from an IO context.
     */
    fun awaitUpload(
        context: Context,
        timeoutSeconds: Long = DEFAULT_UPLOAD_TIMEOUT_SECONDS,
        start: ((UploadResult) -> Unit) -> Unit
    ): UploadResult = ExternalServiceCalls.await(timeoutSeconds, start)
        ?: UploadResult(false, null, context.getString(R.string.auto_upload_timed_out))

    const val DEFAULT_UPLOAD_TIMEOUT_SECONDS = 90L
}
