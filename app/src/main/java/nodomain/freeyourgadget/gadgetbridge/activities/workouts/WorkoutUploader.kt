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
        /** The service took the workout for the first time. */
        object Uploaded : SyncOutcome

        /**
         * The remote activity was brought in line with the workout. [refusal] is the localized
         * reason for the part of the sync the service would not do, null when all of it went
         * through.
         */
        data class Resynced(val refusal: String?) : SyncOutcome

        /** The service already holds the workout as it stands, so nothing was sent. */
        object AlreadyInStep : SyncOutcome

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
        val action = syncActionFor(row, sourceHash)
        val remoteId = row?.remoteActivityId
        if (verifyRemote && action != SyncAction.UPLOAD && remoteId != null && target.capabilities.canLookUp) {
            renewExpiredCredentials(context, target)
            when (target.lookupRemote(context, remoteId)) {
                RemoteLookup.GONE -> {
                    LOG.info("Service {} no longer holds activity {} of summary {}, uploading it again",
                        target.service, remoteId, id)
                    // Dropped first so that a failed upload leaves a failed first attempt, which the
                    // background worker picks up again, rather than a row naming an activity that
                    // no longer exists.
                    WorkoutUploadStore.delete(id, target.service)
                    return uploadNew(context, gbDevice, provider, target, summary, sourceHash, summaryData)
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
     * What to do about [row], the service's last word on this workout, now that the workout
     * fingerprints as [sourceHash].
     *
     * A service holds the activity only once an upload has succeeded, so anything else means
     * starting over: never uploaded, or an upload that failed. A row that succeeded is left alone
     * when the workout has not changed since and nothing was left outstanding, which is what stops
     * asking twice from leaving a second activity behind.
     */
    @JvmStatic
    fun syncActionFor(row: WorkoutUpload?, sourceHash: String): SyncAction = when {
        row == null || row.status != WorkoutUploadStore.STATUS_SUCCESS -> SyncAction.UPLOAD
        row.sourceHash == sourceHash && row.lastError == null -> SyncAction.ALREADY_IN_STEP
        else -> SyncAction.RESYNC
    }

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
            WorkoutUploadStore.recordFailure(id, target.service, result.reason)
            return SyncOutcome.Failed(result.reason)
        }
        // A photo the service could have taken and did not leaves both fingerprints unset, so the
        // next run comes back for it rather than treating the workout as fully in step. A service
        // that holds no media has nothing to come back for.
        val photoLanded = summary.headerPhoto == null ||
                RemoteOp.CREATE !in target.capabilities.media ||
                result.photoMediaId != null
        WorkoutUploadStore.recordSuccess(
            id, target.service, result.remoteActivityId,
            if (photoLanded) WorkoutUploadStore.photoHashOf(summary.headerPhoto) else null,
            result.photoMediaId,
            if (photoLanded) sourceHash else null,
            WorkoutUploadStore.fileHashOf(payload),
            summaryHasTrack(summary)
        )
        return SyncOutcome.Uploaded
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
        // A service can take a workout without naming what it became, and then there is nothing to
        // point a later change at. Recording the current fingerprint says so once, instead of
        // leaving the workout looking unfinished and bringing every later sync back to it.
        var remoteId = row.remoteActivityId ?: run {
            WorkoutUploadStore.recordSuccess(
                id, target.service, null, row.photoHash, row.photoMediaId,
                sourceHash, row.payloadHash, row.hadTrack
            )
            return SyncOutcome.AlreadyInStep
        }
        renewExpiredCredentials(context, target)
        var photoHash = row.photoHash
        var photoMediaId = row.photoMediaId
        var payloadHash = row.payloadHash
        var hadTrack = row.hadTrack
        var refusal: String? = null
        // Set when something the service could have taken did not get through, so the fingerprint
        // is left as it was and the next run comes back to it.
        var pending = false

        // The track goes first: a service that can only update one by re-creating the activity
        // gives back a new activity id, and the photo and metadata belong on the replacement.
        var recreated = false
        // A service with no way to change a track is not asked to build a payload it cannot use.
        val payload = if (target.capabilities.activity.canChange()) {
            buildPayload(context, gbDevice, provider, target, summary, summaryData)
        } else {
            null
        }
        val newPayloadHash = payload?.let { WorkoutUploadStore.fileHashOf(it) }
        if (payload != null && newPayloadHash != row.payloadHash) {
            when (val update = target.updateTrack(context, remoteId, payload, summary, row)) {
                is TrackUpdateResult.Updated -> {
                    payloadHash = newPayloadHash
                    hadTrack = summaryHasTrack(summary)
                }

                is TrackUpdateResult.Recreated -> {
                    payloadHash = newPayloadHash
                    hadTrack = summaryHasTrack(summary)
                    remoteId = update.remoteActivityId
                    recreated = true
                    photoMediaId = update.photoMediaId
                    // The replacement is uploaded from the current workout, so it carries the
                    // current photo, but only once the media id comes back.
                    if (summary.headerPhoto == null || update.photoMediaId != null) {
                        photoHash = WorkoutUploadStore.photoHashOf(summary.headerPhoto)
                    } else {
                        pending = true
                    }
                }

                // A deliberate refusal will not resolve itself, so the fingerprints advance and
                // the user is told once rather than on every sync.
                is TrackUpdateResult.Refused -> refusal = update.message

                is TrackUpdateResult.Failed -> {
                    // A transient failure: keep the old fingerprints so the next run retries.
                    LOG.warn("Track update to service {} failed for summary {}", target.service, id)
                    return SyncOutcome.Failed(null)
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
                    context, remoteId, summary.headerPhoto?.let { File(it) }, row.photoMediaId
                )
                if (sync.success) {
                    photoHash = newPhotoHash
                    photoMediaId = sync.mediaId
                } else {
                    LOG.warn("Photo sync to service {} failed for summary {}", target.service, id)
                    pending = true
                }
            }
        }

        if (!recreated && target.capabilities.editableMetadata) {
            // A replacement activity is created with the right name and type already.
            target.updateMetadata(context, remoteId, summary)
        }

        // A transient failure has already returned by here, so the only reason left is a refusal
        // the user has to resolve, which the row carries until a later sync goes through.
        WorkoutUploadStore.recordSuccess(
            id, target.service, remoteId, photoHash, photoMediaId,
            if (pending) row.sourceHash else sourceHash, payloadHash, hadTrack, refusal
        )
        return SyncOutcome.Resynced(refusal)
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
     * activity, null on failure. [photoMediaId] is the media entry the header photo was uploaded
     * as, null when there was no photo or the photo step failed. [reason] is a localized failure
     * explanation, null on success.
     */
    data class UploadResult(
        val success: Boolean,
        val remoteActivityId: String?,
        val reason: String?,
        val photoMediaId: Int? = null
    )

    /**
     * Outcome of a header photo sync. [mediaId] is the media entry now holding the photo, null
     * when the photo was removed or the upload failed.
     */
    data class PhotoSyncResult(val success: Boolean, val mediaId: Int?)

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
