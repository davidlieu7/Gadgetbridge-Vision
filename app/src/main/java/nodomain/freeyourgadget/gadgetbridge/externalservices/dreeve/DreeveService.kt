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
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.MetadataUpdateResult
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RecreateOrder
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteIdResolution
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteOp
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteSnapshot
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.UploadCapabilities
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutPayloadFormat
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadStore
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadTarget
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload
import nodomain.freeyourgadget.gadgetbridge.externalservices.AuthStyle
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.GarminSport
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

object DreeveService : WorkoutUploadTarget {
    private val LOG = LoggerFactory.getLogger(DreeveService::class.java)

    override val id = "dreeve"
    override val nameRes = R.string.service_name_dreeve
    override val authStyle = AuthStyle.API_TOKEN

    override val service = WorkoutUploadStore.SERVICE_DREEVE

    override val capabilities = UploadCapabilities(
        formats = listOf(WorkoutPayloadFormat.FIT),
        // No endpoint writes a new track onto an activity, so a changed track replaces it.
        activity = setOf(RemoteOp.CREATE, RemoteOp.REPLACE, RemoteOp.DELETE),
        // PATCH /api/v1/activities/{id} takes the name and the sport type.
        metadata = setOf(RemoteOp.UPDATE),
        media = emptySet()
    )

    // Dreeve skips a file whose start time and sport match an activity it already holds, so the
    // old activity has to go before its replacement is imported.
    override val recreateOrder = RecreateOrder.DELETE_THEN_CREATE

    override fun isLoggedIn(context: Context) = DreeveTokenManager(context).isLoggedIn()

    override fun showSetup(fragment: Fragment) {
        DreeveSetupBottomSheet().show(fragment.parentFragmentManager, "dreeve_setup")
    }

    // The API token is issued and revoked on the server, so there is nothing to tell it about.
    override fun logout(context: Context): Boolean {
        DreeveTokenManager(context).clearTokens()
        return true
    }

    override fun activityUrl(context: Context, remoteActivityId: String): String? {
        val server = serverUrl() ?: return null
        return "${server.trimEnd('/')}/activities/$remoteActivityId"
    }

    private fun client(context: Context): DreeveApiClient? =
        serverUrl()?.let { DreeveApiClient(it, DreeveTokenManager(context)) }

    override fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File
    ): WorkoutUploader.UploadResult {
        val client = client(context) ?: return WorkoutUploader.UploadResult(false, null, null)
        val summaryId = summary.id ?: return WorkoutUploader.UploadResult(false, null, null)
        val name = uploadFileName(summaryId, WorkoutUploader.payloadHashFor(this, summary))
        return client.uploadActivity(payload, name)
    }

    override fun resolveRemoteId(
        context: Context,
        summary: BaseActivitySummary,
        row: WorkoutUpload
    ): RemoteIdResolution {
        val payloadHash = row.payloadHash ?: return RemoteIdResolution.Unknown
        val client = client(context) ?: return RemoteIdResolution.Failed
        return when (val lookup = client.findFileImport(uploadFileName(row.summaryId, payloadHash))) {
            is DreeveImportLookup.Failed -> RemoteIdResolution.Failed
            is DreeveImportLookup.Found -> resolutionOf(
                lookup.import,
                context.getString(R.string.service_import_failed, context.getString(nameRes))
            ) { skippedMatch(client, summary) }
        }
    }

    /**
     * The activity a skipped file duplicates, found among those Dreeve holds around the workout's
     * start; see [matchStart].
     */
    private fun skippedMatch(client: DreeveApiClient, summary: BaseActivitySummary): String? {
        val start = localStart(summary.startTime)
        val activities = client.listActivities(
            start.minusDays(1).toLocalDate().toString(),
            start.plusDays(1).toLocalDate().toString()
        ) ?: return null
        return matchStart(activities, start)
    }

    override fun snapshotRemote(
        context: Context,
        remoteActivityId: String,
        row: WorkoutUpload
    ): RemoteSnapshot {
        val client = client(context) ?: return RemoteSnapshot.Failed
        return when (val lookup = client.getActivity(remoteActivityId)) {
            is DreeveActivityLookup.Gone -> RemoteSnapshot.Gone
            is DreeveActivityLookup.Failed -> RemoteSnapshot.Failed
            is DreeveActivityLookup.Found -> RemoteSnapshot.Found(
                null,
                losesUserContent = carriesUserContent(lookup.activity)
            )
        }
    }

    override fun deleteRemote(context: Context, remoteActivityId: String): Boolean =
        client(context)?.deleteActivity(remoteActivityId) ?: false

    override fun updateMetadata(
        context: Context,
        remoteActivityId: String,
        summary: BaseActivitySummary
    ): MetadataUpdateResult {
        val client = client(context) ?: return MetadataUpdateResult.Failed()
        return client.updateActivity(
            remoteActivityId,
            WorkoutUploader.nameFor(context, summary),
            sportTypeFor(ActivityKind.fromCode(summary.activityKind))
        )
    }

    /**
     * Whether [activity] carries something the user added on Dreeve, which would not survive the
     * activity being replaced. Gadgetbridge never writes a description or gear, and a file import
     * sets no gear, so either one there is the user's.
     */
    internal fun carriesUserContent(activity: JSONObject): Boolean {
        fun has(key: String) = !activity.isNull(key) && activity.optString(key).isNotBlank()
        return has("description") || has("gearId")
    }

    /**
     * The name a workout's file is uploaded under, which is how its import is found again. It
     * carries the payload fingerprint the upload is recorded with, so a replacement uploaded after
     * a track change is never mistaken for the file it replaces.
     */
    internal fun uploadFileName(summaryId: Long, payloadHash: String): String =
        "gadgetbridge-$summaryId-${payloadHash.take(12)}.fit"

    /**
     * What [import] says became of an upload. [failedReason] stands in for a failed import that
     * gave no message; [skippedMatch] finds the activity a skipped file duplicates.
     */
    internal fun resolutionOf(
        import: DreeveImport?,
        failedReason: String,
        skippedMatch: () -> String?
    ): RemoteIdResolution = when (import?.status) {
        null -> RemoteIdResolution.Unknown
        "queued" -> RemoteIdResolution.Pending
        "success" -> import.activityId?.let { RemoteIdResolution.Resolved(it) } ?: RemoteIdResolution.Unknown
        "failed" -> RemoteIdResolution.Rejected(import.errorMessage ?: failedReason)
        "skipped" -> skippedMatch()?.let { RemoteIdResolution.Resolved(it) } ?: RemoteIdResolution.Unknown
        else -> RemoteIdResolution.Unknown
    }

    internal fun localStart(startTime: Date, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(startTime.toInstant(), zone)

    /**
     * The id of the one activity in [activities] that started at [start], or null when there is
     * none or more than one.
     *
     * `startDateLocal` is not always in the phone's timezone: Dreeve takes the local time a FIT
     * file carries, but converts a GPX file's UTC start to the server's timezone, which can be hours
     * away from the phone's for the same instant. Minutes and seconds survive any whole-hour offset,
     * so those have to agree and the hours only be within a day of each other.
     */
    internal fun matchStart(activities: List<JSONObject>, start: LocalDateTime): String? {
        val matches = activities.filter { activity ->
            val local = try {
                LocalDateTime.parse(activity.optString("startDateLocal"), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            } catch (e: Exception) {
                return@filter false
            }
            local.minute == start.minute && local.second == start.second &&
                    Duration.between(local, start).abs() <= Duration.ofHours(14)
        }
        if (matches.size != 1) {
            LOG.info("{} Dreeve activities start at {}, not linking a skipped import", matches.size, start)
            return null
        }
        return matches.single().optString("id").takeIf { it.isNotEmpty() }
    }

    /**
     * The sport type Dreeve gives a FIT file exported for [kind], so a changed kind lands as the
     * same sport a fresh import would. Null when the FIT carries the generic sport, which Dreeve
     * resolves from the file's sport name, or no sport at all: the name is then sent alone.
     */
    internal fun sportTypeFor(kind: ActivityKind): String? =
        GarminSport.fromActivityKind(kind)
            .map { sportTypeFor(it.type, it.subtype) }
            .orElse(null)

    /** Dreeve's `FitSportType::resolve` for FIT [sport] and [subSport], generic left out. */
    internal fun sportTypeFor(sport: Int, subSport: Int): String? = when (sport) {
        1 -> when (subSport) {
            3 -> "TrailRun"
            1, 45, 58 -> "VirtualRun"
            else -> "Run"
        }

        2 -> when (subSport) {
            8, 9, 11, 123 -> "MountainBikeRide"
            46 -> "GravelRide"
            6, 5, 58 -> "VirtualRide"
            28, 47, 127 -> "EBikeRide"
            12, 88 -> "Handcycle"
            else -> "Ride"
        }

        4 -> when (subSport) {
            14 -> "VirtualRow"
            15 -> "Elliptical"
            16 -> "StairStepper"
            6, 5 -> "VirtualRide"
            1 -> "VirtualRun"
            27 -> "Walk"
            44 -> "Pilates"
            else -> "Workout"
        }

        5 -> if (subSport == 18) "OpenWaterSwim" else "Swim"
        6 -> "Basketball"
        7 -> "Soccer"
        8 -> "Tennis"
        10 -> when (subSport) {
            20 -> "WeightTraining"
            43 -> "Yoga"
            44 -> "Pilates"
            70 -> "HighIntensityIntervalTraining"
            else -> "Workout"
        }

        11 -> "Walk"
        12 -> "NordicSki"
        13 -> if (subSport == 37) "BackcountrySki" else "AlpineSki"
        14 -> if (subSport == 37) "BackcountrySki" else "Snowboard"
        15 -> if (subSport == 14) "VirtualRow" else "Rowing"
        16, 17 -> "Hike"
        19, 88 -> "Canoeing"
        21 -> "EBikeRide"
        25 -> "Golf"
        69 -> "Workout"
        30 -> "InlineSkate"
        31 -> "RockClimbing"
        32 -> "Sail"
        33 -> "IceSkate"
        35 -> "Snowshoe"
        37 -> "StandUpPaddling"
        38 -> "Surfing"
        39 -> "Wakeboarding"
        41 -> "Kayaking"
        43 -> "Windsurf"
        44 -> "Kitesurf"
        48 -> "StairStepper"
        62 -> "HighIntensityIntervalTraining"
        64 -> when (subSport) {
            84 -> "Pickleball"
            85 -> "Padel"
            94 -> "Squash"
            95 -> "Badminton"
            96 -> "Racquetball"
            97 -> "TableTennis"
            else -> "Workout"
        }

        65, 66 -> "Wheelchair"
        71 -> "Cricket"
        75 -> "Volleyball"
        83 -> "Dance"
        else -> null
    }
}
