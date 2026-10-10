package nodomain.freeyourgadget.gadgetbridge.model

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.export.FitExporter
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack.SegmentIntensity
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.RecordData
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.Event
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.EventType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.AbstractFitRecord
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitLap
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Date

class FitActivityTrackProviderIntervalTest {

    private class Lap(val start: Long, val intensity: Int?, val distance: Double? = null)

    /** Encodes one record per second over [seconds] and the given laps, then parses the file back. */
    private fun track(seconds: Int, vararg laps: Lap): ActivityTrack {
        val records = ArrayList<RecordData>()
        for (i in 0 until seconds) {
            records.add(
                AbstractFitRecord.Builder()
                    .setTimestamp(START + i)
                    .setHeartRate(120)
                    .build(LMT_RECORD)
            )
        }
        for (lap in laps) {
            val builder = FitLap.Builder()
                .setTimestamp(lap.start)
                .setStartTime(lap.start)
                .setEvent(Event.LAP)
                .setEventType(EventType.STOP)
            lap.intensity?.let { builder.setIntensity(it) }
            lap.distance?.let { builder.setTotalDistance(it) }
            records.add(builder.build(LMT_LAP))
        }
        val fit = FitFile.parseIncoming(FitFile(records).outgoingMessage)
        return FitActivityTrackProvider().getActivityTrack(BaseActivitySummary(), fit)!!
    }

    private fun ActivityTrack.intensities() = segmentInfos.map { it.intensity }

    @Test
    fun workAndRestLaps_becomeActiveAndRestSegments() {
        val track = track(
            30,
            Lap(START, 0, 1000.0),
            Lap(START + 10, 1, 0.0),
            Lap(START + 20, 0, 980.0),
        )
        assertEquals(listOf(SegmentIntensity.ACTIVE, SegmentIntensity.REST, SegmentIntensity.ACTIVE), track.intensities())
        assertEquals(listOf(10, 10, 10), track.segments.map { it.size })
        assertEquals(listOf(1000, 0, 980), track.segmentInfos.map { it.distanceMeters })
    }

    @Test
    fun recoveryAndIntervalLaps_areRestAndActive() {
        val track = track(20, Lap(START, 5), Lap(START + 10, 4))
        assertEquals(listOf(SegmentIntensity.ACTIVE, SegmentIntensity.REST), track.intensities())
    }

    @Test
    fun autoLapsWithoutRest_stayUnknown() {
        val track = track(30, Lap(START, 0), Lap(START + 10, 0), Lap(START + 20, 0))
        assertEquals(List(3) { SegmentIntensity.UNKNOWN }, track.intensities())
    }

    @Test
    fun warmupAndCooldown_areKept() {
        val track = track(30, Lap(START, 2), Lap(START + 10, 1), Lap(START + 20, 3))
        assertEquals(listOf(SegmentIntensity.WARMUP, SegmentIntensity.REST, SegmentIntensity.COOLDOWN), track.intensities())
    }

    @Test
    fun warmupAndCooldownWithoutRest_leaveActiveLapsUnknown() {
        val track = track(30, Lap(START, 2), Lap(START + 10, 0), Lap(START + 20, 3))
        assertEquals(listOf(SegmentIntensity.WARMUP, SegmentIntensity.UNKNOWN, SegmentIntensity.COOLDOWN), track.intensities())
    }

    @Test
    fun lapsStartingBetweenRecords_openTheLastOne() {
        // the 0 s lap at START + 10 has no record of its own; the record at START + 10 opens the lap after it
        val track = track(20, Lap(START, 0), Lap(START + 10, 1), Lap(START + 10, 0))
        assertEquals(listOf(SegmentIntensity.ACTIVE, SegmentIntensity.ACTIVE), track.intensities())
        assertEquals(listOf(10, 10), track.segments.map { it.size })
    }

    @Test
    fun exportedIntervals_comeBackFromTheExport() {
        val source = ActivityTrack()
        val phases = listOf(SegmentIntensity.WARMUP, SegmentIntensity.ACTIVE, SegmentIntensity.REST, SegmentIntensity.ACTIVE, SegmentIntensity.COOLDOWN)
        phases.forEachIndexed { index, intensity ->
            val info = ActivityTrack.SegmentInfo(intensity)
            if (index == 0) source.setCurrentSegmentInfo(info) else source.startNewSegment(info)
            for (i in 0 until 20) {
                source.addTrackPoint(ActivityPoint(Date((START + index * 20 + i) * 1000L)).apply { heartRate = 120 })
            }
        }
        val summary = BaseActivitySummary().apply {
            startTime = Date(START * 1000L)
            endTime = Date((START + 100) * 1000L)
            activityKind = ActivityKind.ROWING_MACHINE.code
        }
        val out = ByteArrayOutputStream()
        FitExporter().performExport(source, summary, null, out)

        val back = FitActivityTrackProvider().getActivityTrack(summary, FitFile.parseIncoming(out.toByteArray()))!!
        assertEquals(phases, back.intensities())
        assertEquals(List(5) { 20 }, back.segments.map { it.size })
    }

    companion object {
        private const val START = 1_100_000_000L
        private const val LMT_RECORD = 1
        private const val LMT_LAP = 2
    }
}
