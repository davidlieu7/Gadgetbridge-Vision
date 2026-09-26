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
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RecreateOrder
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
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalServiceTokenRefreshWorker
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * FitTrackee (<https://fittrackee.org>), a self-hosted outdoor activity tracker.
 *
 * A workout with a track goes up as its file. One without a track goes up as bare figures
 * (`POST /api/workouts/no_gpx`), which FitTrackee takes only with a sport, a distance and a
 * duration; its FIT importer refuses a file with no position records.
 *
 * A workout's file cannot be replaced and a workout without one can never be given one, so any
 * change of track re-creates the workout. The replacement is created first and takes over the old
 * workout's media, notes and settings, then the old one is deleted, which is what leaves the photos
 * the user added on the server in place: FitTrackee moves an attached media item to whichever
 * workout names it last, and deletes a workout's media together with the workout.
 */
object FitTrackeeService : WorkoutUploadTarget {
    override val id = "fittrackee"
    override val nameRes = R.string.service_name_fittrackee
    override val authStyle = AuthStyle.LOGIN

    override val service = WorkoutUploadStore.SERVICE_FITTRACKEE

    override val capabilities = UploadCapabilities(
        // FIT first: it carries heart rate and cadence, which a GPX does not. A workout without a
        // track still exports one, which upload() leaves unsent.
        formats = listOf(WorkoutPayloadFormat.FIT, WorkoutPayloadFormat.GPX),
        activity = setOf(RemoteOp.CREATE, RemoteOp.REPLACE, RemoteOp.DELETE),
        metadata = setOf(RemoteOp.UPDATE),
        // A media item is uploaded on its own, attached to the workout, and deleted by id.
        media = setOf(RemoteOp.CREATE, RemoteOp.REPLACE, RemoteOp.DELETE),
        accepts = { summary ->
            WorkoutUploader.summaryHasTrack(summary) || figuresOf(summary) != null
        }
    )

    override val recreateOrder = RecreateOrder.CREATE_THEN_DELETE

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

    override fun credentialsExpired(context: Context): Boolean =
        FitTrackeeTokenManager(context).isAccessTokenExpired()

    override fun refreshCredentials(context: Context): Boolean {
        val server = serverUrl() ?: return false
        return FitTrackeeApiClient(server, FitTrackeeTokenManager(context)).refreshTokens()
    }

    /**
     * The instance and the user on it. Null until the profile has been read once, rather than the
     * instance alone, which would not match what was recorded once the name is known.
     */
    override fun accountKey(context: Context): String? {
        val instance = super.accountKey(context) ?: return null
        val username = FitTrackeeTokenManager(context).getUsername() ?: return null
        return "$instance#$username"
    }

    /**
     * The browser comes back with an authorization code, which is traded for tokens here so the
     * setup wizard only has to hear whether it worked.
     *
     * Called on the main thread, where the requests cannot run: the Internet Helper answers on
     * the main looper, so a request blocking it would wait for a reply that can never arrive.
     */
    override fun onLoginCallback(context: Context, uri: Uri) {
        val code = uri.getQueryParameter("code")
        val tokenManager = FitTrackeeTokenManager(context)
        val verifier = tokenManager.getCodeVerifier()
        val server = serverUrl()
        if (code == null || verifier == null || server == null) {
            LOG.error("Login callback missing the code, the verifier or the server address")
            broadcastLoginResult(context, false)
            return
        }
        Thread {
            val apiClient = FitTrackeeApiClient(server, tokenManager)
            val success = apiClient.exchangeCode(code, verifier)
            if (success) {
                tokenManager.clearCodeVerifier()
                if (apiClient.fetchProfile() == null) {
                    LOG.warn("Logged in to FitTrackee but could not read the profile")
                }
            }
            broadcastLoginResult(context, success)
        }.start()
    }

    private fun broadcastLoginResult(context: Context, success: Boolean) {
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
    ): WorkoutUploader.UploadResult = upload(context, summary, payload, null)

    /**
     * Uploads the header photo, then creates the workout naming it together with whatever
     * [carry] moves over from a workout being replaced. A photo that does not go up leaves the
     * workout without one, and the next sync comes back for it; one the login is not allowed to
     * upload is reported as a refusal instead.
     */
    override fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File,
        carry: Any?
    ): WorkoutUploader.UploadResult {
        val server = serverUrl() ?: return WorkoutUploader.UploadResult(false, null, null)
        val apiClient = FitTrackeeApiClient(server, FitTrackeeTokenManager(context))
        if (!apiClient.allows(FitTrackeeApiClient.SCOPE_WORKOUTS_WRITE)) {
            LOG.warn("FitTrackee login lacks {}, not uploading summary {}",
                FitTrackeeApiClient.SCOPE_WORKOUTS_WRITE, summary.id)
            return WorkoutUploader.UploadResult(
                false, null,
                context.getString(R.string.fittrackee_scope_missing, FitTrackeeApiClient.SCOPE_WORKOUTS_WRITE)
            )
        }
        val kept = carry as? KeptContent
        val hasTrack = WorkoutUploader.summaryHasTrack(summary)

        val figures = if (hasTrack) null else figuresOf(summary)
        if (!hasTrack && figures == null) {
            return WorkoutUploader.UploadResult(false, null, null)
        }
        val sportId = sportIdOf(apiClient, summary) ?: return WorkoutUploader.UploadResult(false, null, null)

        val photo = summary.headerPhoto?.let { apiClient.uploadMedia(File(it)) }
        val photoId = (photo as? FitTrackeeMediaResult.Done)?.mediaId
        val mediaIds = listOfNotNull(photoId) + kept?.mediaIds.orEmpty()

        val body = JSONObject()
            .put("sport_id", sportId)
            .put("title", WorkoutUploader.nameFor(context, summary).take(MAX_TITLE_LENGTH))
        if (mediaIds.isNotEmpty()) {
            body.put("media_attachment_ids", JSONArray(mediaIds))
        }
        kept?.applyTo(body, withFile = hasTrack)

        val result = if (figures != null) {
            figures.applyTo(body, timezoneOf(apiClient))
            apiClient.createWorkoutWithoutFile(body)
        } else {
            apiClient.uploadWorkout(payload, body)
        }
        if (!result.success || result.remoteActivityId == null) {
            // The photo belongs to no workout, and FitTrackee does not clear such media on its own.
            photoId?.let { apiClient.deleteMedia(it) }
            return result
        }
        return result.copy(
            photoMediaRef = photoId,
            photoRefusal = if (photo == FitTrackeeMediaResult.NotAllowed) mediaNotAllowed(context) else null
        )
    }

    private fun mediaNotAllowed(context: Context) = context.getString(R.string.fittrackee_media_not_allowed)

    override fun updateMetadata(
        context: Context,
        remoteActivityId: String,
        summary: BaseActivitySummary
    ): Boolean {
        val server = serverUrl() ?: return false
        val apiClient = FitTrackeeApiClient(server, FitTrackeeTokenManager(context))
        val sportId = sportIdOf(apiClient, summary) ?: return false
        return apiClient.updateWorkout(
            remoteActivityId,
            WorkoutUploader.nameFor(context, summary).take(MAX_TITLE_LENGTH),
            sportId
        )
    }

    /**
     * What moves onto the replacement: the text, equipment and visibility settings the user may
     * have set on the server, and every media item except the photo Gadgetbridge put there, which
     * the replacement gets afresh from the workout. Comments and likes cannot be moved: deleting
     * the workout deletes its likes and leaves its comments attached to no workout, so a workout
     * that has either is not re-created.
     */
    override fun snapshotRemote(
        context: Context,
        remoteActivityId: String,
        row: WorkoutUpload
    ): RemoteSnapshot {
        val server = serverUrl() ?: return RemoteSnapshot.Failed
        val apiClient = FitTrackeeApiClient(server, FitTrackeeTokenManager(context))
        return when (val lookup = apiClient.getWorkout(remoteActivityId)) {
            is FitTrackeeWorkoutLookup.Failed -> RemoteSnapshot.Failed
            is FitTrackeeWorkoutLookup.Gone -> RemoteSnapshot.Gone
            is FitTrackeeWorkoutLookup.Found -> {
                val comments = apiClient.countComments(remoteActivityId) ?: return RemoteSnapshot.Failed
                RemoteSnapshot.Found(
                    KeptContent.of(lookup.workout, row.photoMediaRef),
                    losesUserContent = hasReactions(lookup.workout, comments)
                )
            }
        }
    }

    /** Whether [workout], with [comments] comments, has likes or comments a re-create would lose. */
    internal fun hasReactions(workout: JSONObject, comments: Int): Boolean =
        workout.optInt("likes_count", 0) > 0 || comments > 0

    override fun deleteRemote(context: Context, remoteActivityId: String): Boolean {
        val server = serverUrl() ?: return false
        return FitTrackeeApiClient(server, FitTrackeeTokenManager(context)).deleteWorkout(remoteActivityId)
    }

    /**
     * Deletes the photo a previous sync attached ([previousMediaRef], a FitTrackee media id) and
     * attaches [photoFile] in its place. Media the user attached on the server is left alone.
     */
    override fun syncPhoto(
        context: Context,
        remoteActivityId: String,
        photoFile: File?,
        previousMediaRef: String?
    ): WorkoutUploader.PhotoSyncResult {
        val failed = WorkoutUploader.PhotoSyncResult(false, previousMediaRef)
        val server = serverUrl() ?: return failed
        val apiClient = FitTrackeeApiClient(server, FitTrackeeTokenManager(context))
        if (previousMediaRef != null) {
            when (apiClient.deleteMedia(previousMediaRef)) {
                is FitTrackeeMediaResult.Done -> {}
                is FitTrackeeMediaResult.NotAllowed ->
                    return WorkoutUploader.PhotoSyncResult(false, previousMediaRef, mediaNotAllowed(context))

                is FitTrackeeMediaResult.Failed -> return failed
            }
        }
        if (photoFile == null) {
            return WorkoutUploader.PhotoSyncResult(true, null)
        }
        val mediaId = when (val upload = apiClient.uploadMedia(photoFile)) {
            is FitTrackeeMediaResult.Done -> upload.mediaId ?: return WorkoutUploader.PhotoSyncResult(false, null)
            is FitTrackeeMediaResult.NotAllowed ->
                return WorkoutUploader.PhotoSyncResult(false, null, mediaNotAllowed(context))

            is FitTrackeeMediaResult.Failed -> return WorkoutUploader.PhotoSyncResult(false, null)
        }
        if (!apiClient.attachMedia(remoteActivityId, listOf(mediaId))) {
            apiClient.deleteMedia(mediaId)
            return WorkoutUploader.PhotoSyncResult(false, null)
        }
        return WorkoutUploader.PhotoSyncResult(true, mediaId)
    }

    /** The profile timezone, which FitTrackee reads a workout date without a file in. */
    private fun timezoneOf(apiClient: FitTrackeeApiClient): TimeZone {
        val name = apiClient.fetchProfile()?.optString("timezone")?.takeIf { it.isNotEmpty() && it != "null" }
        return name?.let { TimeZone.getTimeZone(it) } ?: run {
            LOG.warn("FitTrackee profile has no timezone, sending the workout date in UTC")
            TimeZone.getTimeZone("UTC")
        }
    }

    /** What a workout without a file is made of. */
    internal data class Figures(
        val start: Date,
        val durationSeconds: Int,
        val distanceKm: Double,
        val ascent: Double?,
        val descent: Double?
    ) {
        fun applyTo(body: JSONObject, timezone: TimeZone) {
            val format = SimpleDateFormat(WORKOUT_DATE_FORMAT, Locale.ROOT).apply { timeZone = timezone }
            body.put("workout_date", format.format(start))
                .put("duration", durationSeconds)
                .put("distance", distanceKm)
            // Both or neither: FitTrackee refuses one without the other.
            if (ascent != null && descent != null) {
                body.put("ascent", ascent).put("descent", descent)
            }
        }
    }

    /**
     * The figures a workout without a track is sent as, or null when FitTrackee would refuse it:
     * it needs a sport it knows, a distance and a duration, none of them zero.
     */
    internal fun figuresOf(summary: BaseActivitySummary): Figures? {
        if (sportLabelFor(summary) == null) return null
        val data = summary.summaryData?.let {
            try {
                ActivitySummaryData.fromJson(it)
            } catch (e: Exception) {
                null
            }
        } ?: return null
        val meters = data.getNumber(ActivitySummaryEntries.DISTANCE_METERS, null)?.toDouble()
        if (meters == null || meters <= 0) return null
        val seconds = data.getNumber(ActivitySummaryEntries.ACTIVE_SECONDS, null)?.toInt()
            ?: ((summary.endTime.time - summary.startTime.time) / 1000).toInt()
        if (seconds <= 0) return null
        return Figures(
            summary.startTime,
            seconds,
            meters / 1000.0,
            data.getNumber(ActivitySummaryEntries.ELEVATION_GAIN, null)?.toDouble(),
            data.getNumber(ActivitySummaryEntries.ELEVATION_LOSS, null)?.toDouble()
        )
    }

    /** What [snapshotRemote] read off a workout for its replacement to take over. */
    internal data class KeptContent(val fields: JSONObject, val fileFields: JSONObject, val mediaIds: List<String>) {
        /** Puts these on [body], leaving out what a workout without a file does not take. */
        fun applyTo(body: JSONObject, withFile: Boolean) {
            fields.keys().forEach { body.put(it, fields.get(it)) }
            if (withFile) {
                fileFields.keys().forEach { body.put(it, fileFields.get(it)) }
            }
        }

        companion object {
            /** Keys both creation endpoints take. */
            private val SHARED_KEYS = listOf("description", "notes", "workout_visibility", "media_visibility")

            /** Keys only an upload with a file takes. */
            private val FILE_KEYS = listOf("map_visibility", "analysis_visibility")

            fun of(workout: JSONObject, ownPhoto: String?): KeptContent {
                val fields = JSONObject()
                SHARED_KEYS.forEach { key -> nonEmpty(workout, key)?.let { fields.put(key, it) } }
                workout.optJSONArray("equipments")?.let { equipments ->
                    val ids = (0 until equipments.length())
                        .mapNotNull { equipments.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() } }
                    if (ids.isNotEmpty()) fields.put("equipment_ids", JSONArray(ids))
                }
                val fileFields = JSONObject()
                FILE_KEYS.forEach { key -> nonEmpty(workout, key)?.let { fileFields.put(key, it) } }
                val media = workout.optJSONArray("media_attachments")
                val mediaIds = (0 until (media?.length() ?: 0))
                    .mapNotNull { media?.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() } }
                    .filter { it != ownPhoto }
                return KeptContent(fields, fileFields, mediaIds)
            }

            private fun nonEmpty(workout: JSONObject, key: String): String? =
                if (workout.isNull(key)) null else workout.optString(key).takeIf { it.isNotEmpty() }
        }
    }

    /**
     * The id on this instance of the sport [summary] is filed under. A workout with a track whose
     * kind has no match, or whose sport the instance lacks, goes under [DEFAULT_SPORT_LABEL]; one
     * without a track is not sent at all then. Null when there is nothing to file it under or the
     * sports could not be read.
     */
    private fun sportIdOf(apiClient: FitTrackeeApiClient, summary: BaseActivitySummary): Int? {
        val sports = apiClient.fetchSportIds() ?: return null
        val label = sportLabelFor(summary)
        label?.let { sports[it] }?.let { return it }
        if (label != null) LOG.warn("FitTrackee instance has no sport '{}'", label)
        return if (WorkoutUploader.summaryHasTrack(summary)) sports[DEFAULT_SPORT_LABEL] else null
    }

    /**
     * The label of the FitTrackee sport the workout is filed under, or null when FitTrackee has
     * none that fits. Labels are what `GET /api/sports` answers, as seeded by FitTrackee's
     * migrations.
     */
    internal fun sportLabelFor(summary: BaseActivitySummary): String? =
        when (ActivityKind.fromCode(summary.activityKind)) {
            ActivityKind.CYCLING, ActivityKind.OUTDOOR_CYCLING,
            ActivityKind.ROAD_BIKE, ActivityKind.GRAVEL_BIKE, ActivityKind.CYCLO_CROSS -> "Cycling (Sport)"

            ActivityKind.BIKE_COMMUTE -> "Cycling (Transport)"
            ActivityKind.HIKING, ActivityKind.MOUNTAIN_HIKE, ActivityKind.TRAIL_HIKE -> "Hiking"
            ActivityKind.MOUNTAIN_BIKE, ActivityKind.CYCLING_DOWNHILL -> "Mountain Biking"

            ActivityKind.RUNNING, ActivityKind.OUTDOOR_RUNNING, ActivityKind.INDOOR_RUNNING,
            ActivityKind.STREET_RUNNING, ActivityKind.TRACK_RUN, ActivityKind.INDOOR_TRACK_RUNNING,
            ActivityKind.ULTRA_RUN, ActivityKind.TREADMILL, ActivityKind.VIRTUAL_RUN -> "Running"

            ActivityKind.WALKING, ActivityKind.OUTDOOR_WALKING, ActivityKind.INDOOR_WALKING,
            ActivityKind.RACE_WALKING -> "Walking"

            ActivityKind.E_MOUNTAIN_BIKE -> "Mountain Biking (Electric)"
            ActivityKind.TRAIL_RUN, ActivityKind.CROSS_COUNTRY_RUNNING -> "Trail"
            ActivityKind.SKIING, ActivityKind.BACKCOUNTRY_SKIING -> "Skiing (Alpine)"
            ActivityKind.CROSS_COUNTRY_SKIING -> "Skiing (Cross Country)"
            ActivityKind.ROWING, ActivityKind.ROWING_MACHINE -> "Rowing"
            ActivityKind.SNOWSHOE -> "Snowshoes"
            ActivityKind.INDOOR_CYCLING, ActivityKind.SPINNING -> "Cycling (Virtual)"
            ActivityKind.MOUNTAINEERING -> "Mountaineering"
            ActivityKind.PARAGLIDING -> "Paragliding"
            ActivityKind.SWIMMING_OPENWATER -> "Open Water Swimming"
            ActivityKind.BIKE_TOUR -> "Cycling (Trekking)"
            ActivityKind.KAYAKING -> "Kayaking"
            ActivityKind.CANOEING -> "Canoeing"
            ActivityKind.WINDSURFING -> "Windsurfing"
            ActivityKind.STAND_UP_PADDLEBOARDING, ActivityKind.PADDLING -> "Standup Paddleboarding"
            ActivityKind.TENNIS -> "Tennis (Outdoor)"
            ActivityKind.PADEL -> "Padel (Outdoor)"
            ActivityKind.ICE_SKATING -> "Ice Skating"
            ActivityKind.INLINE_SKATING, ActivityKind.ROLLER_SKATING -> "Inline Skating"
            else -> null
        }

    private val LOG = LoggerFactory.getLogger(FitTrackeeService::class.java)

    /** Where the setup wizard listens for the outcome of the browser login. */
    const val LOGIN_CALLBACK_ACTION = "nodomain.freeyourgadget.gadgetbridge.FITTRACKEE_CALLBACK"

    /** The first sport every instance is seeded with. */
    private const val DEFAULT_SPORT_LABEL = "Cycling (Sport)"

    private const val MAX_TITLE_LENGTH = 255

    /** How `POST /api/workouts/no_gpx` reads `workout_date`, in the user's profile timezone. */
    private const val WORKOUT_DATE_FORMAT = "yyyy-MM-dd HH:mm"
}
