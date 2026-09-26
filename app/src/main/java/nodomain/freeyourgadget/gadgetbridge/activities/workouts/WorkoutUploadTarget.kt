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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalService
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceRegistry
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import java.io.File

/**
 * File format a service accepts when an activity is uploaded. A GPX is a route, so it can only be
 * written for a workout that has one; a FIT is synthesized from the summary alone when it has not.
 */
enum class WorkoutPayloadFormat(val needsTrack: Boolean) { FIT(false), GPX(true) }

/** What a service will let be done to one of the things it holds. */
enum class RemoteOp {
    /** Put it there in the first place. */
    CREATE,

    /** Change it where it stands, keeping its identity and anything the user did to it. */
    UPDATE,

    /** Change it only by removing it and putting a new one there, so its identity changes. */
    REPLACE,

    /** Take it away and leave nothing behind. */
    DELETE,

    /** Tell whether it is still there; see [WorkoutUploadTarget.lookupRemote]. */
    READ
}

/** Whether a service still holds an activity it was given. [FAILED] means it could not be asked. */
enum class RemoteLookup { PRESENT, GONE, FAILED }

/** Whether these operations amount to a way of making something match the local workout. */
fun Set<RemoteOp>.canChange(): Boolean = RemoteOp.UPDATE in this || RemoteOp.REPLACE in this

/**
 * What a service will do with an activity, beyond taking it in the first place. The engine asks
 * this before it does any work, so a step the service has no way of taking costs nothing to skip
 * rather than an export and a request that fail on every sync.
 */
data class UploadCapabilities(
    /** Accepted formats, most preferred first. */
    val formats: List<WorkoutPayloadFormat>,

    /** The activity, which is to say the track inside it. */
    val activity: Set<RemoteOp>,

    /** Its name and activity kind. */
    val metadata: Set<RemoteOp>,

    /** Its header photo. */
    val media: Set<RemoteOp>,

    /**
     * Whether the service takes [BaseActivitySummary] at all, beyond having a format for it. A
     * service that maps what it is given wants a route even in a format that can be written
     * without one (see [REQUIRES_ROUTE]); another may want a distance, or a sport it knows.
     *
     * It has to answer from what the summary stores, never from anything that can differ between
     * two runs, for the reason given on [formatFor].
     */
    val accepts: (BaseActivitySummary) -> Boolean = { true }
) {
    /**
     * Format to export [summary] in, or null when the service can take no form of this workout.
     *
     * The choice turns on nothing but whether the workout has a track, and has to stay that way:
     * a re-sync is triggered by the exported file's fingerprint and no column records which format
     * was sent, so a choice that could differ between two runs would re-upload on every sync.
     */
    fun formatFor(summary: BaseActivitySummary): WorkoutPayloadFormat? {
        if (!accepts(summary)) {
            return null
        }
        val hasTrack = WorkoutUploader.summaryHasTrack(summary)
        return formats.firstOrNull { !it.needsTrack || hasTrack }
    }

    /** Whether this service can take [summary] at all. */
    fun appliesTo(summary: BaseActivitySummary): Boolean = formatFor(summary) != null

    /** Whether the name and activity kind can be pushed onto an activity that already exists. */
    val editableMetadata: Boolean get() = RemoteOp.UPDATE in metadata

    /** Whether the service can be asked if it still holds an activity, once deleted there. */
    val canLookUp: Boolean get() = RemoteOp.READ in activity

    /**
     * Whether the service reads an activity's name and type out of the file it was given, which is
     * what a service offering no way to edit them afterwards does. A rename then only reaches it
     * by sending the file again, so the name belongs in what is compared against what it holds.
     */
    val metadataComesFromFile: Boolean get() = metadata.isEmpty()

    /**
     * Whether pushing a changed track costs the activity its identity, because the service offers
     * no way to write a new track onto the one it holds. What the user loses is the address of the
     * activity and anything the service hangs off it, which is why they are asked first.
     */
    val trackChangeIsDestructive: Boolean
        get() = RemoteOp.UPDATE !in activity && RemoteOp.REPLACE in activity

    /**
     * Whether the service can bring its copy of the header photo in line, which depends on which
     * way it changed: a service that takes a photo on upload and offers nothing afterwards can
     * neither replace nor remove one.
     */
    fun canSyncMedia(hadPhoto: Boolean, hasPhoto: Boolean): Boolean = when {
        hadPhoto && !hasPhoto -> RemoteOp.DELETE in media
        !hadPhoto && hasPhoto -> RemoteOp.CREATE in media
        else -> media.canChange()
    }

    companion object {
        /**
         * For a service that refuses a workout that went nowhere, whatever it is written as, and
         * answers a file without a route with an error rather than a bare workout.
         */
        @JvmField
        val REQUIRES_ROUTE: (BaseActivitySummary) -> Boolean = { WorkoutUploader.summaryHasTrack(it) }
    }
}

/**
 * Which half of re-creating an activity goes first. Creating first means the workout never has no
 * online copy, and lets a service move what hangs off the old activity onto the new one before it
 * goes; deleting first is for a service that refuses or mishandles a second activity at the same
 * start time.
 */
enum class RecreateOrder { CREATE_THEN_DELETE, DELETE_THEN_CREATE }

/** What a service holds under an activity id, read before that activity is re-created. */
sealed interface RemoteSnapshot {
    /** Deleted on the service's side: the re-create is a plain upload with nothing to delete. */
    object Gone : RemoteSnapshot

    /** Could not be read this time; the next run retries. */
    object Failed : RemoteSnapshot

    /**
     * The activity is there. [carry] is whatever the service can move onto the replacement, in a
     * form only that service reads, handed back to [WorkoutUploadTarget.upload]. [losesUserContent]
     * says the user added something there that cannot be carried over, so re-creating would
     * destroy it.
     */
    data class Found(val carry: Any?, val losesUserContent: Boolean) : RemoteSnapshot
}

/** Outcome of bringing the track of an already-uploaded activity in line with the local workout. */
sealed interface TrackUpdateResult {
    /** The remote activity now carries the current track, under the id it already had. */
    object Updated : TrackUpdateResult

    /**
     * The service could only do it by re-creating the activity, so the caller stores the new ids.
     */
    data class Recreated(val remoteActivityId: String, val photoMediaRef: String?) : TrackUpdateResult

    /**
     * The service could have done it and deliberately did not, for a reason the user has to
     * resolve. [message] is localized and is shown once rather than on every sync.
     */
    data class Refused(val message: String) : TrackUpdateResult

    /** It did not go through this time; the next run retries. */
    object Failed : TrackUpdateResult
}

/**
 * An [ExternalService] that takes workouts, described by what it accepts and by what it can still
 * change on an activity after the initial upload. [WorkoutUploadWorker] drives every service
 * through this, so supporting another one means adding an implementation rather than another
 * branch.
 */
interface WorkoutUploadTarget : ExternalService {
    /**
     * Value stored in `WorkoutUpload.service`. Persisted, so see the registry in
     * [WorkoutUploadStore] before choosing one.
     */
    val service: Int

    /** What this service accepts, and what it can still change once it holds an activity. */
    val capabilities: UploadCapabilities

    /**
     * Whether the service refuses to touch an activity that was edited on its own side, so a
     * change made here cannot overwrite one made there. Only a service that has a way of telling
     * can promise it, and the settings screen says so where it holds.
     */
    val keepsRemoteEdits: Boolean get() = false

    /**
     * Notification id used to report a failure. Derived from [service], which is unique and never
     * reused, so two services cannot end up overwriting each other's notification.
     */
    val failureNotificationId: Int
        get() = FAILURE_NOTIFICATION_ID_BASE + service

    /** Whether the user asked for workouts to be uploaded to this service automatically. */
    fun isAutoUploadEnabled(prefs: GBPrefs): Boolean = prefs.getBoolean(autoUploadPrefKey, false)

    /** Localized confirmation that this service took the workout. */
    fun uploadedMessage(context: Context): String =
        context.getString(R.string.service_upload_succeeded, context.getString(nameRes))

    /**
     * Localized "the upload to this service failed" message, carrying [reason] when the service
     * gave one. Used both for the toast after an upload the user asked for and for the
     * notification an auto-upload failure raises.
     */
    fun uploadFailedMessage(context: Context, reason: String?): String =
        reason?.let { context.getString(R.string.service_upload_failed, context.getString(nameRes), it) }
            ?: context.getString(R.string.service_upload_failed_generic, context.getString(nameRes))

    /**
     * Web address of the activity [remoteActivityId] on the service, for opening it in a browser,
     * or null when it cannot be built because the service is not set up.
     */
    fun activityUrl(context: Context, remoteActivityId: String): String?

    fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File
    ): WorkoutUploader.UploadResult

    /**
     * Uploads [summary] as the replacement of an activity being re-created, applying [carry], what
     * [snapshotRemote] read off the activity it replaces (null when there was none to read).
     */
    fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File,
        carry: Any?
    ): WorkoutUploader.UploadResult = upload(context, summary, payload)

    /**
     * Names the account activities are uploaded to, so an activity id recorded under one account
     * is never acted on under another: an id means nothing, or another user's activity, once the
     * user points the service at a different server or signs in as someone else. Read on every
     * sync, so it must not touch the network. Null when it cannot tell, which matches anything.
     */
    fun accountKey(context: Context): String? =
        serverUrl()?.trim()?.trimEnd('/')?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Which half of a re-create goes first; see [RecreateOrder]. */
    val recreateOrder: RecreateOrder get() = RecreateOrder.DELETE_THEN_CREATE

    /**
     * Reads what the service holds under [remoteActivityId] before it is re-created. Called only
     * when [UploadCapabilities.trackChangeIsDestructive]. [row] is what was last uploaded there,
     * so what Gadgetbridge put on the activity can be told from what the user added.
     */
    fun snapshotRemote(context: Context, remoteActivityId: String, row: WorkoutUpload): RemoteSnapshot =
        RemoteSnapshot.Found(null, false)

    /** Deletes [remoteActivityId] as part of re-creating it. Blocking. */
    fun deleteRemote(context: Context, remoteActivityId: String): Boolean = false

    /**
     * Pushes the name and activity kind onto an activity that already exists remotely. Called only
     * when [UploadCapabilities.editableMetadata].
     */
    fun updateMetadata(
        context: Context,
        remoteActivityId: String,
        summary: BaseActivitySummary
    ): Boolean = false

    /**
     * Writes [payload] as the new track of the remote activity, keeping its id. Called only when
     * [RemoteOp.UPDATE] is in [UploadCapabilities.activity]; a service that can only replace an
     * activity is re-created by [WorkoutUploader] through [snapshotRemote], [upload] and
     * [deleteRemote] instead. [row] is what was last uploaded.
     */
    fun updateTrack(
        context: Context,
        remoteActivityId: String,
        payload: File,
        summary: BaseActivitySummary,
        row: WorkoutUpload
    ): TrackUpdateResult = TrackUpdateResult.Failed

    /**
     * Brings the header photo of an existing remote activity in line with the local workout, which
     * is called only when [UploadCapabilities.media]. [photoFile] is null when the photo was
     * removed. [previousMediaRef] is the media entry the last sync left there, in the form this
     * service handed it back.
     */
    fun syncPhoto(
        context: Context,
        remoteActivityId: String,
        photoFile: File?,
        previousMediaRef: String?
    ): WorkoutUploader.PhotoSyncResult = WorkoutUploader.PhotoSyncResult(false, previousMediaRef)

    companion object {
        /** Clear of the ids the rest of the app posts notifications under. */
        const val FAILURE_NOTIFICATION_ID_BASE = 4710
    }
}

/** Every registered service that takes workouts. */
object WorkoutUploadTargets {
    val ALL: List<WorkoutUploadTarget>
        get() = ExternalServiceRegistry.services.filterIsInstance<WorkoutUploadTarget>()

    /**
     * Targets the user has finished setting up, whether or not they take workouts on their own.
     * This is the set a workout can be sent to at all, so it is what the screens showing upload
     * state answer against.
     */
    @JvmStatic
    fun configured(context: Context): List<WorkoutUploadTarget> =
        ALL.filter { it.isLoggedIn(context) }

    /**
     * Targets set up and asked to take every synced workout without being told to, which is the
     * set the background worker drives.
     */
    @JvmStatic
    fun autoUploadEnabled(context: Context, prefs: GBPrefs): List<WorkoutUploadTarget> =
        configured(context).filter { it.isAutoUploadEnabled(prefs) }

    /**
     * Whether [row] describes an activity held by the account [target] is signed in to now. A row
     * left by another account is treated as if the workout had never been sent, so nothing is
     * shown, updated or deleted through an id that belongs elsewhere. Either side unknown matches.
     */
    @JvmStatic
    fun holds(context: Context, target: WorkoutUploadTarget, row: WorkoutUpload): Boolean {
        val recorded = row.remoteAccount ?: return true
        val current = target.accountKey(context) ?: return true
        return recorded == current
    }

    /** [rows] keyed by service, without those another account left; see [holds]. */
    @JvmStatic
    fun currentAccountRows(context: Context, rows: Map<Int, WorkoutUpload>): Map<Int, WorkoutUpload> {
        val targetsByService = ALL.associateBy { it.service }
        return rows.filter { (service, row) ->
            targetsByService[service]?.let { holds(context, it, row) } ?: true
        }
    }
}
