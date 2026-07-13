package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.records.ExerciseSegment
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack.SegmentInfo
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack.SegmentIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date

/**
 * Unit tests for the ActivityTrack-segment -> HC lap/segment mapping in [RecordedWorkoutSyncer].
 * These cover the pure mapping helpers (buildSanitisedSegmentBounds + buildLaps + buildSegments);
 * the >= 2 emit guard lives at the call site in processDetailedWorkout.
 */
class RecordedWorkoutSyncerLapsSegmentsTest {

    private val start: Instant = Instant.parse("2026-05-26T21:00:00Z")
    private val end: Instant = start.plusSeconds(960) // 16 min
    private val device = "test-device"

    private fun point(secondsFromStart: Long): ActivityPoint =
        ActivityPoint(Date.from(start.plusSeconds(secondsFromStart)))

    /** Build a track from (intensity, distanceMeters?, firstSec, lastSec) segment descriptors. */
    private fun trackOf(vararg segs: SegDesc): ActivityTrack {
        val track = ActivityTrack()
        segs.forEachIndexed { i, s ->
            val info = SegmentInfo(s.intensity, s.distanceMeters, null)
            if (i == 0) {
                track.setCurrentSegmentInfo(info)
            } else {
                track.startNewSegment(info)
            }
            track.addTrackPoint(point(s.firstSec))
            if (s.lastSec != s.firstSec) {
                track.addTrackPoint(point(s.lastSec))
            }
        }
        return track
    }

    private data class SegDesc(
        val intensity: SegmentIntensity,
        val distanceMeters: Int?,
        val firstSec: Long,
        val lastSec: Long
    )

    private fun bounds(track: ActivityTrack) =
        RecordedWorkoutSyncer.buildSanitisedSegmentBounds(track, start, end, device)

    // [ACTIVE 0-420 d=1750][REST 420-540][ACTIVE 540-960]
    private fun intervalTrack() = trackOf(
        SegDesc(SegmentIntensity.ACTIVE, 1750, 0, 420),
        SegDesc(SegmentIntensity.REST, null, 420, 540),
        SegDesc(SegmentIntensity.ACTIVE, 1800, 540, 960)
    )

    @Test
    fun laps_dropRest_keepActiveWithLength() {
        val laps = RecordedWorkoutSyncer.buildLaps(bounds(intervalTrack()))
        assertEquals(2, laps.size)
        assertEquals(start, laps[0].startTime)
        assertEquals(start.plusSeconds(420), laps[0].endTime)
        assertEquals(1750.0, laps[0].length!!.inMeters, 0.001)
        assertEquals(start.plusSeconds(540), laps[1].startTime)
        assertEquals(end, laps[1].endTime)
        assertEquals(1800.0, laps[1].length!!.inMeters, 0.001)
    }

    @Test
    fun segments_keepAll_typed() {
        val segments = RecordedWorkoutSyncer.buildSegments(bounds(intervalTrack()))
        assertEquals(3, segments.size)
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN, segments[0].segmentType)
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST, segments[1].segmentType)
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN, segments[2].segmentType)
        // contiguous, sorted, non-overlapping, within session bounds
        assertEquals(start, segments[0].startTime)
        assertEquals(segments[0].endTime, segments[1].startTime)
        assertEquals(segments[1].endTime, segments[2].startTime)
        assertEquals(end, segments[2].endTime)
        assertTrue(!segments[0].startTime.isBefore(start))
        assertTrue(!segments[2].endTime.isAfter(end))
    }

    @Test
    fun unknownIntensity_keptAsUnknownInBothLists() {
        // Garmin-style plain distance laps: no explicit intensity -> UNKNOWN, all kept.
        val track = trackOf(
            SegDesc(SegmentIntensity.UNKNOWN, null, 0, 300),
            SegDesc(SegmentIntensity.UNKNOWN, null, 300, 600),
            SegDesc(SegmentIntensity.UNKNOWN, null, 600, 900)
        )
        val b = bounds(track)
        assertEquals(3, RecordedWorkoutSyncer.buildLaps(b).size)
        val segments = RecordedWorkoutSyncer.buildSegments(b)
        assertEquals(3, segments.size)
        assertTrue(segments.all { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN })
    }

    @Test
    fun singleSegment_oneBound() {
        val track = trackOf(SegDesc(SegmentIntensity.ACTIVE, null, 0, 960))
        val b = bounds(track)
        assertEquals(1, b.size) // call-site >= 2 guard then suppresses emission
        assertEquals(1, RecordedWorkoutSyncer.buildLaps(b).size)
        assertEquals(1, RecordedWorkoutSyncer.buildSegments(b).size)
    }

    @Test
    fun degenerateSegment_dropped() {
        // Middle segment is a single point (firstSec == lastSec) -> start == end -> dropped.
        val track = trackOf(
            SegDesc(SegmentIntensity.ACTIVE, null, 0, 300),
            SegDesc(SegmentIntensity.ACTIVE, null, 300, 300),
            SegDesc(SegmentIntensity.ACTIVE, null, 300, 900)
        )
        val b = bounds(track)
        assertEquals(2, b.size)
        assertEquals(2, RecordedWorkoutSyncer.buildSegments(b).size)
    }

    @Test
    fun lapWithoutDistance_hasNullLength() {
        val track = trackOf(
            SegDesc(SegmentIntensity.ACTIVE, null, 0, 300),
            SegDesc(SegmentIntensity.ACTIVE, null, 300, 900)
        )
        val laps = RecordedWorkoutSyncer.buildLaps(bounds(track))
        assertEquals(2, laps.size)
        assertNull(laps[0].length)
        assertNull(laps[1].length)
    }

    @Test
    fun emptyTrack_noBounds() {
        // Fresh track has a single empty segment -> no usable bounds.
        val b = bounds(ActivityTrack())
        assertTrue(b.isEmpty())
        assertTrue(RecordedWorkoutSyncer.buildLaps(b).isEmpty())
        assertTrue(RecordedWorkoutSyncer.buildSegments(b).isEmpty())
    }

    @Test
    fun outOfWindowSegment_clampedIntoBounds() {
        // A segment straddling the session end gets clamped to `end`.
        val track = trackOf(
            SegDesc(SegmentIntensity.ACTIVE, null, 0, 300),
            SegDesc(SegmentIntensity.ACTIVE, null, 300, 1200) // 1200s > 960s session end
        )
        val segments = RecordedWorkoutSyncer.buildSegments(bounds(track))
        assertEquals(2, segments.size)
        assertEquals(end, segments[1].endTime)
    }
}
