package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class WorkoutDistanceTest {

    private val baseTs = 1_700_000_040L
    private val workoutStart = Instant.ofEpochSecond(baseTs)
    private val workoutEnd = Instant.ofEpochSecond(baseTs + 30 * 60)
    private val window = WorkoutWindow(workoutStart, workoutEnd)

    private fun sample(endTs: Long, distanceCm: Int): ActivitySample =
        object : ActivitySample {
            override fun getTimestamp(): Int = endTs.toInt()
            override fun getProvider(): nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider<*>? = null
            override fun getRawKind(): Int = ActivityKind.UNKNOWN.code
            override fun getKind(): ActivityKind = ActivityKind.UNKNOWN
            override fun getRawIntensity(): Int = 0
            override fun getIntensity(): Float = 0f
            override fun getSteps(): Int = 0
            override fun getDistanceCm(): Int = distanceCm
            override fun getActiveCalories(): Int = 0
            override fun getHeartRate(): Int = 0
            override fun setHeartRate(value: Int) {}
        }

    /** One sample per minute of the workout, each carrying [cmPerMinute]. */
    private fun workoutMinutes(cmPerMinute: Int): List<ActivitySample> =
        (1..30).map { sample(baseTs + it * 60L, cmPerMinute) }

    private fun summaryWithDistance(meters: Number): ActivitySummaryData =
        ActivitySummaryData().apply {
            add(ActivitySummaryEntries.DISTANCE_METERS, meters, ActivitySummaryEntries.UNIT_METERS)
        }

    @Test
    fun summaryDistanceWinsOverUndercountingSamples() {
        // Mi Band 9 Active run: summary says 4040 m, the per-minute stream holds half of that.
        val samples = workoutMinutes(cmPerMinute = 6733)
        val meters = RecordedWorkoutSyncer.workoutDistanceMeters(summaryWithDistance(4040), samples, window)
        assertEquals(4040.0, meters, 0.001)
    }

    @Test
    fun summaryDistanceUsedWhenSamplesCarryNoDistance() {
        // Garmin pool swim: the per-minute stream does not advance during the swim.
        val samples = workoutMinutes(cmPerMinute = 0)
        val meters = RecordedWorkoutSyncer.workoutDistanceMeters(summaryWithDistance(1025), samples, window)
        assertEquals(1025.0, meters, 0.001)
    }

    @Test
    fun samplesInsideWindowUsedWhenSummaryHasNoDistance() {
        val samples = workoutMinutes(cmPerMinute = 1000) +
            sample(baseTs, 50_000) +
            sample(baseTs + 31 * 60L, 50_000)
        assertEquals(300.0, RecordedWorkoutSyncer.workoutDistanceMeters(ActivitySummaryData(), samples, window), 0.001)
        assertEquals(300.0, RecordedWorkoutSyncer.workoutDistanceMeters(null, samples, window), 0.001)
    }

    @Test
    fun excludeWorkoutWindowsDropsOnlyMinutesOverlappingAWorkout() {
        val before = sample(baseTs, 100) // minute ends exactly at workout start
        val inside = sample(baseTs + 60, 100)
        val straddlingEnd = sample(baseTs + 30 * 60L + 30, 100)
        val after = sample(baseTs + 31 * 60L, 100) // minute starts exactly at workout end

        val kept = DistanceSyncer.excludeWorkoutWindows(listOf(before, inside, straddlingEnd, after), listOf(window))

        assertEquals(listOf(before, after), kept)
    }

    @Test
    fun excludeWorkoutWindowsKeepsAllWithoutWorkouts() {
        val samples = workoutMinutes(cmPerMinute = 100)
        assertEquals(samples, DistanceSyncer.excludeWorkoutWindows(samples, emptyList()))
    }

    @Test
    fun distanceClientRecordIdMatchesPerMinuteRecord() {
        val device = Device(type = Device.TYPE_WATCH, manufacturer = "Xiaomi", model = "Band")
        val metadata = Metadata.activelyRecorded(device)
        val endTs = baseTs + 60

        val record = DistanceSyncer.convertMinute(Instant.ofEpochSecond(endTs), listOf(sample(endTs, 100)), ZoneOffset.UTC, metadata, "dev", 1L)!!

        assertEquals(record.metadata.clientRecordId, distanceClientRecordId(device, endTs))
    }
}
