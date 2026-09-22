package nodomain.freeyourgadget.gadgetbridge.devices.veepoo;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryTableBuilder;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryValue;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.export.GPXExporter;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VeepooActivityTrackProviderTest extends TestBase {
    // Per-minute values of the walk captured on 2026-08-23 (0xD4 seq 473/474):
    // 443 s, 373 m, 430 steps, 23 kcal.
    private static final long START_MS = 1_700_000_000_000L;

    private static ActivitySummaryData buildPerMinuteSummary(final int[][] rows) {
        final ActivitySummaryData summary = new ActivitySummaryData();
        summary.add(ActivitySummaryEntries.DISTANCE_METERS, 373, ActivitySummaryEntries.UNIT_METERS);
        final ActivitySummaryTableBuilder perMinute = new ActivitySummaryTableBuilder(
                ActivitySummaryEntries.GROUP_LAPS, "per_minute_header",
                Arrays.asList("minute", "heartRate", "steps", "distanceMeters", "caloriesBurnt"));
        for (final int[] row : rows) {
            perMinute.addRow("minute_" + row[0], Arrays.asList(
                    new ActivitySummaryValue(row[0], ActivitySummaryEntries.UNIT_NONE),
                    new ActivitySummaryValue(row[1] > 0 ? row[1] : null, ActivitySummaryEntries.UNIT_BPM),
                    new ActivitySummaryValue(row[2] > 0 ? row[2] : null, ActivitySummaryEntries.UNIT_STEPS),
                    new ActivitySummaryValue(row[3] > 0 ? row[3] : null, ActivitySummaryEntries.UNIT_METERS),
                    new ActivitySummaryValue(row[4] > 0 ? row[4] / 1000f : null, ActivitySummaryEntries.UNIT_KCAL)));
        }
        perMinute.addToSummaryData(summary);
        return summary;
    }

    private static BaseActivitySummary buildWorkout(final ActivitySummaryData summary) {
        final BaseActivitySummary workout = new BaseActivitySummary();
        workout.setName("Walk");
        workout.setStartTime(new Date(START_MS));
        workout.setEndTime(new Date(START_MS + 443_000L));
        workout.setSummaryData(summary.toJson());
        return workout;
    }

    private static final int[][] WALK_ROWS = {
            {1, 106, 78, 67, 4178},
            {2, 125, 83, 72, 4446},
            {3, 134, 52, 52, 3160},
            {4, 121, 89, 77, 4767},
            {5, 120, 38, 33, 2036},
            {6, 104, 0, 0, 0}, // pause minute: only HR recorded
            {7, 120, 54, 47, 2892},
            {8, 115, 29, 25, 1554},
    };

    @Test
    public void testPerMinutePointsWithoutGpx() {
        final BaseActivitySummary workout = buildWorkout(buildPerMinuteSummary(WALK_ROWS));

        final ActivityTrack track = new VeepooActivityTrackProvider().getActivityTrack(workout);
        assertNotNull(track);
        final List<ActivityPoint> points = track.getAllPoints();
        assertEquals(8, points.size());

        final int[] expectedHr = {106, 125, 134, 121, 120, 104, 120, 115};
        for (int i = 0; i < points.size(); i++) {
            final ActivityPoint point = points.get(i);
            assertEquals(START_MS + i * 60_000L, point.getTime().getTime());
            assertEquals(expectedHr[i], point.getHeartRate());
            assertNull(point.getLocation());
        }

        // Speed is the per-minute distance delta divided by 60 s.
        assertEquals(67 / 60f, points.get(0).getSpeed(), 1e-3f);
        assertEquals(72 / 60f, points.get(1).getSpeed(), 1e-3f);
        assertEquals(52 / 60f, points.get(2).getSpeed(), 1e-3f);
        assertEquals(77 / 60f, points.get(3).getSpeed(), 1e-3f);
        assertEquals(33 / 60f, points.get(4).getSpeed(), 1e-3f);
        assertEquals(-1f, points.get(5).getSpeed(), 0f); // no distance in the pause minute
        assertEquals(47 / 60f, points.get(6).getSpeed(), 1e-3f);
        assertEquals(25 / 60f, points.get(7).getSpeed(), 1e-3f);
    }

    @Test
    public void testPrunedColumnsAreMappedByHeaderName() {
        // Steps and calories all zero: the table builder drops those columns on save, so the
        // provider must map the remaining cells by header name, not by position.
        final int[][] rows = {
                {1, 110, 0, 80, 0},
                {2, 120, 0, 96, 0},
        };
        final ActivitySummaryData summary = buildPerMinuteSummary(rows);
        assertFalse("steps column should have been pruned", hasColumn(summary, "steps"));

        final ActivityTrack track = new VeepooActivityTrackProvider()
                .getActivityTrack(buildWorkout(summary));
        assertNotNull(track);
        final List<ActivityPoint> points = track.getAllPoints();
        assertEquals(2, points.size());
        assertEquals(110, points.get(0).getHeartRate());
        assertEquals(120, points.get(1).getHeartRate());
        assertEquals(80 / 60f, points.get(0).getSpeed(), 1e-3f);
        assertEquals(96 / 60f, points.get(1).getSpeed(), 1e-3f);
    }

    private static boolean hasColumn(final ActivitySummaryData summary, final String name) {
        final Object header = summary.get("per_minute_header");
        if (!(header instanceof nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryTableRowEntry row)) {
            return false;
        }
        for (final ActivitySummaryValue value : row.getColumns()) {
            if (name.equals(String.valueOf(value.value()))) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testGapBetweenRowsSpreadsTheDelta() {
        // Minute 2 is missing entirely (band stored no record for it): the 70 m of minute 3
        // cover two minutes, so the speed must be halved.
        final int[][] rows = {
                {1, 110, 40, 35, 1000},
                {3, 120, 60, 70, 2000},
        };
        final ActivityTrack track = new VeepooActivityTrackProvider()
                .getActivityTrack(buildWorkout(buildPerMinuteSummary(rows)));
        assertNotNull(track);
        final List<ActivityPoint> points = track.getAllPoints();
        assertEquals(2, points.size());
        assertEquals(35 / 60f, points.get(0).getSpeed(), 1e-3f);
        assertEquals(70 / 120f, points.get(1).getSpeed(), 1e-3f);
        assertEquals(START_MS + 2 * 60_000L, points.get(1).getTime().getTime());
    }

    @Test
    public void testReturnsNullWithoutPerMinuteData() {
        final ActivitySummaryData summary = new ActivitySummaryData();
        summary.add(ActivitySummaryEntries.DISTANCE_METERS, 373, ActivitySummaryEntries.UNIT_METERS);
        assertNull(new VeepooActivityTrackProvider().getActivityTrack(buildWorkout(summary)));

        final BaseActivitySummary blank = buildWorkout(new ActivitySummaryData());
        blank.setSummaryData(null);
        assertNull(new VeepooActivityTrackProvider().getActivityTrack(blank));
    }

    @Test
    public void testGpxPointsAreEnrichedWithPerMinuteData() throws Exception {
        final File gpxFile = File.createTempFile("veepoo_test", ".gpx");
        assertTrue(gpxFile.delete());
        final ActivityTrack gpxTrack = new ActivityTrack();
        gpxTrack.setBaseTime(new Date(START_MS));
        gpxTrack.setName("Walk");
        gpxTrack.addTrackPoint(pointAt(START_MS + 10_000L, 52.5200, 13.4050));
        gpxTrack.addTrackPoint(pointAt(START_MS + 70_000L, 52.5210, 13.4060));
        new GPXExporter().performExport(gpxTrack, gpxFile, null);

        final BaseActivitySummary workout = buildWorkout(buildPerMinuteSummary(WALK_ROWS));
        workout.setGpxTrack(gpxFile.getAbsolutePath());

        try {
            final ActivityTrack track = new VeepooActivityTrackProvider().getActivityTrack(workout);
            assertNotNull(track);
            final List<ActivityPoint> points = track.getAllPoints();
            assertEquals(2, points.size());

            for (final ActivityPoint point : points) {
                assertNotNull(point.getLocation());
            }
            // First point falls into minute 1, the second into minute 2.
            assertEquals(106, points.get(0).getHeartRate());
            assertEquals(125, points.get(1).getHeartRate());
            // The first point has no preceding fix, so it keeps the per-minute speed; the second
            // is derived from the 60 s GPS delta between the two fixes.
            assertEquals(67 / 60f, points.get(0).getSpeed(), 1e-3f);
            final double meters = GPSCoordinate.distanceHaversine(
                    new GPSCoordinate(13.4050, 52.5200), new GPSCoordinate(13.4060, 52.5210));
            assertEquals(meters / 60f, points.get(1).getSpeed(), 1e-3f);
        } finally {
            Files.deleteIfExists(gpxFile.toPath());
        }
    }

    private static ActivityPoint pointAt(final long timeMs, final double lat, final double lon) {
        final ActivityPoint point = new ActivityPoint(new Date(timeMs));
        point.setLocation(new GPSCoordinate(lon, lat));
        return point;
    }
}
