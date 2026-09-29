/*  Copyright (C) 2026 Gadgetbridge Contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 or later;
    see the LICENSE file for details.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;

public class VeepooPhoneTrackMetricsTest {

    // A track heading north keeps the math simple: one degree of latitude is ~111,320 m.
    private static final double METERS_PER_LAT_DEGREE = 111_320d;
    private static final long START_MS = 1_700_000_000_000L;
    private static final long SEGMENT_MS = 5_000L;

    /** A straight north track; each entry is the distance covered in one 5 s segment (0 = standstill). */
    private static List<ActivityPoint> track5s(final double... segmentsMeters) {
        final List<ActivityPoint> points = new ArrayList<>(segmentsMeters.length + 1);
        long timeMs = START_MS;
        double latitude = 48d;
        points.add(pointAt(timeMs, latitude));
        for (double meters : segmentsMeters) {
            timeMs += SEGMENT_MS;
            latitude += meters / METERS_PER_LAT_DEGREE;
            points.add(pointAt(timeMs, latitude));
        }
        return points;
    }

    private static ActivityPoint pointAt(final long timeMs, final double latitude) {
        final ActivityPoint point = new ActivityPoint(new Date(timeMs));
        point.setLocation(new GPSCoordinate(2d, latitude));
        return point;
    }

    private static double[] moving(int count, double metersPerSegment) {
        final double[] segments = new double[count];
        java.util.Arrays.fill(segments, metersPerSegment);
        return segments;
    }

    @Test
    public void constantSpeedTrackYieldsMatchingMaxAndMin() {
        // 20 m per 5 s segment is 4 m/s = 14.4 km/h
        final double[] range = VeepooDeviceSupport.trackSpeedRangeKmH(track5s(moving(6, 20d)));

        assertEquals(14.4, range[0], 0.2);
        assertEquals(14.4, range[1], 0.2);
    }

    @Test
    public void standstillsDoNotLowerTheMinimumSpeed() {
        final double[] segments = new double[6 + 5 + 6];
        java.util.Arrays.fill(segments, 0, 6, 20d);
        // segments 6..10 are the standstill (default 0), then running again
        java.util.Arrays.fill(segments, 11, segments.length, 20d);
        final double[] range = VeepooDeviceSupport.trackSpeedRangeKmH(track5s(segments));

        assertEquals(14.4, range[0], 0.2);
        assertEquals(14.4, range[1], 0.2); // standstill segments must not count as "moving at 0"
    }

    @Test
    public void stationaryTrackHasNoUsableSpeed() {
        assertNull(VeepooDeviceSupport.trackSpeedRangeKmH(track5s(new double[10])));
    }

    @Test
    public void phoneTrackMetricsReplaceTheBandsDistanceDerivedValues() {
        // The band claims a step-estimated 3 km with an 8:00/km pace; the phone says otherwise.
        final ActivitySummaryData summary = new ActivitySummaryData();
        summary.add(ActivitySummaryEntries.ACTIVE_SECONDS, 1_800L, ActivitySummaryEntries.UNIT_SECONDS);
        summary.add(ActivitySummaryEntries.DISTANCE_METERS, 3_000, ActivitySummaryEntries.UNIT_METERS);
        summary.add(ActivitySummaryEntries.PACE_AVG_SECONDS_KM, 480, ActivitySummaryEntries.UNIT_SECONDS_PER_KM);
        summary.add(ActivitySummaryEntries.SPEED_AVG, 6.0, ActivitySummaryEntries.UNIT_KMPH);
        summary.add(ActivitySummaryEntries.SPEED_MAX, 7.0, ActivitySummaryEntries.UNIT_KMPH);
        summary.add(ActivitySummaryEntries.SPEED_MIN, 5.0, ActivitySummaryEntries.UNIT_KMPH);

        // 360 segments x 5 s at 4 m/s = 1800 s over ~7200 m
        final List<ActivityPoint> track = track5s(moving(360, 20d));
        final Date stopTime = new Date(track.get(track.size() - 1).getTime().getTime());
        VeepooDeviceSupport.applyPhoneTrackMetrics(summary, track, new Date(START_MS), stopTime);

        assertEquals(7_200d, summary.getNumber(ActivitySummaryEntries.DISTANCE_METERS, 0d).doubleValue(), 80d);
        assertEquals(250d, summary.getNumber(ActivitySummaryEntries.PACE_AVG_SECONDS_KM, 0d).doubleValue(), 2d);
        assertEquals(14.4, summary.getNumber(ActivitySummaryEntries.SPEED_AVG, 0d).doubleValue(), 0.3);
        assertEquals(14.4, summary.getNumber(ActivitySummaryEntries.SPEED_MAX, 0d).doubleValue(), 0.3);
        assertEquals(14.4, summary.getNumber(ActivitySummaryEntries.SPEED_MIN, 0d).doubleValue(), 0.3);
    }

    @Test
    public void missingActiveTimeFallsBackToTheSessionWindow() {
        final ActivitySummaryData summary = new ActivitySummaryData();
        summary.add(ActivitySummaryEntries.DISTANCE_METERS, 3_000, ActivitySummaryEntries.UNIT_METERS);
        summary.add(ActivitySummaryEntries.PACE_AVG_SECONDS_KM, 480, ActivitySummaryEntries.UNIT_SECONDS_PER_KM);

        // 120 segments x 5 s = 600 s over ~2400 m, so the pace is 600 / 2.4 = 250 s/km
        final List<ActivityPoint> track = track5s(moving(120, 20d));
        final Date stopTime = new Date(track.get(track.size() - 1).getTime().getTime());
        VeepooDeviceSupport.applyPhoneTrackMetrics(summary, track, new Date(START_MS), stopTime);

        assertEquals(250d, summary.getNumber(ActivitySummaryEntries.PACE_AVG_SECONDS_KM, 0d).doubleValue(), 2d);
        assertEquals(14.4, summary.getNumber(ActivitySummaryEntries.SPEED_AVG, 0d).doubleValue(), 0.3);
    }

    @Test
    public void aTrackWithoutMovementLeavesTheSummaryUntouched() {
        final ActivitySummaryData summary = new ActivitySummaryData();
        summary.add(ActivitySummaryEntries.ACTIVE_SECONDS, 600L, ActivitySummaryEntries.UNIT_SECONDS);
        summary.add(ActivitySummaryEntries.DISTANCE_METERS, 3_000, ActivitySummaryEntries.UNIT_METERS);
        final String before = summary.toString();

        VeepooDeviceSupport.applyPhoneTrackMetrics(summary, track5s(new double[20]),
                new Date(START_MS), new Date(START_MS + 100_000L));

        assertEquals(before, summary.toString());
    }

    @Test
    public void removeDropsAnEntryAndReportsItsPresence() {
        final ActivitySummaryData summary = new ActivitySummaryData();
        summary.add(ActivitySummaryEntries.SPEED_MAX, 7.0, ActivitySummaryEntries.UNIT_KMPH);

        assertTrue(summary.remove(ActivitySummaryEntries.SPEED_MAX));
        assertFalse(summary.has(ActivitySummaryEntries.SPEED_MAX));
        assertFalse(summary.remove(ActivitySummaryEntries.SPEED_MAX));
    }
}
