/*  Copyright (C) 2025 Gadgetbridge contributors

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryTableBuilder;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryValue;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;

/**
 * Pure parser for fully assembled 0xD4 workout-history day payloads: the raw frames of one
 * requested day in, a plain {@link SportDay} data object out (or {@code null} when the day
 * holds no plausible session). No database access and no Android dependencies; the frame-chunk
 * assembly, GPX export and persistence stay in {@link VeepooDeviceSupport}.
 */
final class VeepooSportDayParser {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooSportDayParser.class);

    private VeepooSportDayParser() {
    }

    /** One parsed workout session of a 0xD4 day. */
    public static final class SportDay {
        public final Date startTime;
        public final Date stopTime;
        public final int sportType;
        public final ActivitySummaryData summary;
        public final List<GpxPoint> gpsPoints;

        SportDay(Date startTime, Date stopTime, int sportType, ActivitySummaryData summary, List<GpxPoint> gpsPoints) {
            this.startTime = startTime;
            this.stopTime = stopTime;
            this.sportType = sportType;
            this.summary = summary;
            this.gpsPoints = gpsPoints;
        }
    }

    /** One decoded GPS track point from a per-minute sport item (coordinates in micro-degrees). */
    public static final class GpxPoint {
        public final long longitude;
        public final long latitude;
        public final long altitude;
        public final long timeMs;

        public GpxPoint(long longitude, long latitude, long altitude, long timeMs) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.altitude = altitude;
            this.timeMs = timeMs;
        }
    }

    /**
     * Plain (non-GPS) workout layout. The head is the [6..19] slice of the first three frames
     * concatenated (42 bytes): start year BE16 [1..2], start month/day/hour/min/sec [3..7], end
     * year BE16 [8..9], end month/day/hour/min/sec [10..14], LE u32 steps [15..18], LE u32
     * distance (meters) [19..22], LE u32 kcal x 1000 [23..26], LE u32 sport seconds [27..30],
     * BE u16 record count [31..32], pause count [33], BE u16 pause seconds [34..35], BE u16 CRC
     * [36..37], sport type [38]. Item frames (index >= 3) carry one per-minute record: rate [6],
     * BE u16 minute index [1..2] (3-based), BE u16 total minutes [3..4] (3-based), BE u16 steps
     * [9..10], BE u16 kcal x 1000 [11..12], BE u16 distance (meters) [13..14].
     */
    public static SportDay parsePlainDay(List<byte[]> frames, int dayIndex) {
        if (frames.size() < 3) {
            LOG.info("Workout day {}: no workout data ({} frame(s))", dayIndex, frames.size());
            return null;
        }
        byte[] head = new byte[42];
        for (int k = 0; k < 3; k++) {
            System.arraycopy(frames.get(k), 6, head, k * 14, 14);
        }

        Date startTime = VeepooDeviceSupport.dateFromParts(VeepooDeviceSupport.be16(head, 1), head[3] & 0xFF, head[4] & 0xFF,
                head[5] & 0xFF, head[6] & 0xFF, head[7] & 0xFF);
        Date stopTime = VeepooDeviceSupport.dateFromParts(VeepooDeviceSupport.be16(head, 8), head[10] & 0xFF, head[11] & 0xFF,
                head[12] & 0xFF, head[13] & 0xFF, head[14] & 0xFF);
        if (startTime == null || stopTime == null || stopTime.before(startTime)) {
            if (startTime == null && stopTime == null) {
                LOG.info("Workout day {}: empty slot", dayIndex);
            } else {
                LOG.warn("Workout day {}: implausible workout window in the head; skipping", dayIndex);
            }
            return null;
        }

        int steps = (int) VeepooDeviceSupport.le32(head, 15);
        int distanceMeters = (int) VeepooDeviceSupport.le32(head, 19);
        float kcal = (int) VeepooDeviceSupport.le32(head, 23) / 1000f;
        long sportSeconds = VeepooDeviceSupport.le32(head, 27);
        int pauseSeconds = VeepooDeviceSupport.be16(head, 34);
        int sportType = head[38] & 0xFF;

        long totalSeconds = Math.abs(stopTime.getTime() - startTime.getTime()) / 1000L;
        long activeSeconds = sportSeconds > 0 ? sportSeconds : Math.max(0L, totalSeconds - pauseSeconds);

        ActivitySummaryData summary = new ActivitySummaryData();
        addSportEntries(summary, activeSeconds, distanceMeters, kcal, steps);

        int hrCount = 0;
        long hrSum = 0;
        int hrMin = Integer.MAX_VALUE;
        int hrMax = 0;
        ActivitySummaryTableBuilder perMinute = newPerMinuteTable();
        for (int i = 3; i < frames.size(); i++) {
            byte[] frame = frames.get(i);
            int minuteIndex = VeepooDeviceSupport.be16(frame, 1) - 3;
            int allMinutes = VeepooDeviceSupport.be16(frame, 3) - 3;
            int rate = frame[6] & 0xFF;
            int itemSteps = VeepooDeviceSupport.be16(frame, 9);
            int itemKcalRaw = VeepooDeviceSupport.be16(frame, 11);
            int itemDistance = VeepooDeviceSupport.be16(frame, 13);
            if (minuteIndex < 0 || allMinutes < 0) {
                continue; // zero-padded frame, no record data
            }
            boolean validHr = rate >= 30 && rate <= 200;
            if (!validHr && itemSteps == 0 && itemDistance == 0 && itemKcalRaw == 0) {
                continue;
            }
            if (validHr) {
                hrCount++;
                hrSum += rate;
                hrMin = Math.min(hrMin, rate);
                hrMax = Math.max(hrMax, rate);
            }
            perMinute.addRow("minute_" + (minuteIndex + 1), Arrays.asList(
                    new ActivitySummaryValue(minuteIndex + 1, ActivitySummaryEntries.UNIT_NONE),
                    new ActivitySummaryValue(validHr ? rate : null, ActivitySummaryEntries.UNIT_BPM),
                    new ActivitySummaryValue(itemSteps > 0 ? itemSteps : null, ActivitySummaryEntries.UNIT_STEPS),
                    new ActivitySummaryValue(itemDistance > 0 ? itemDistance : null, ActivitySummaryEntries.UNIT_METERS),
                    new ActivitySummaryValue(itemKcalRaw > 0 ? itemKcalRaw / 1000f : null, ActivitySummaryEntries.UNIT_KCAL)));
        }
        if (hrCount > 0) {
            summary.add(ActivitySummaryEntries.HR_AVG, hrSum / hrCount, ActivitySummaryEntries.UNIT_BPM);
            summary.add(ActivitySummaryEntries.HR_MIN, hrMin, ActivitySummaryEntries.UNIT_BPM);
            summary.add(ActivitySummaryEntries.HR_MAX, hrMax, ActivitySummaryEntries.UNIT_BPM);
        }
        perMinute.addToSummaryData(summary);
        return new SportDay(startTime, stopTime, sportType, summary, new ArrayList<>());
    }

    /**
     * GPS workout layout: strip the 5-byte frame prefix from every frame and join the payloads
     * into one blob. Head: [0]=0xA1, [1]=headLen-2, start/stop/sport-duration LE u32 [4..7]/
     * [8..11]/[12..15], block base offset [17], steps LE u32 [18..21], distance (meters) LE u32
      * [22..25], kcal x 1000 LE u32 [26..29], max/avg/min HR [34..36], avg pace LE16 [39..40].
      * Optional altitude block (base + 2 > 39): max/avg/min altitude LE u32 at [55..66], climb
      * LE u32 [67..70], decline LE u32 [71..74]. Sport block at base: pause seconds LE16
      * [base+23..24], sport type LE u16 [base+31..32], data type [base+33] (1/2/5/6 => per-point
       * altitude). Items follow as TLV: tag 0xA2, LE u16 length (the KR96 Pro firmware sends the
       * length little-endian; the vendor app's big-endian read rejects them), then one per-minute
       * record (HR [2], steps LE16 [5..6], distance LE16 [7..8], kcal x 1000 LE16 [9..10]) plus
       * any trailing GPS points. On the KR96 Pro every item is exactly 16 bytes, i.e. no GPS
       * points are embedded in the workout history stream.
     */
    public static SportDay parseGpsDay(List<byte[]> frames, int dayIndex) {
        if (frames.isEmpty()) {
            LOG.info("Workout day {}: no workout data", dayIndex);
            return null;
        }
        int blobLength = 0;
        for (byte[] frame : frames) {
            blobLength += frame.length - 5;
        }
        byte[] blob = new byte[blobLength];
        int position = 0;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 5, blob, position, frame.length - 5);
            position += frame.length - 5;
        }

        if (blob.length < 40 || (blob[0] & 0xFF) != 0xA1) {
            LOG.info("Workout day {}: no GPS workout data in {}-byte blob", dayIndex, blob.length);
            return null;
        }
        int headLength = Math.min((blob[1] & 0xFF) + 2, blob.length);
        long fieldA = VeepooDeviceSupport.le32(blob, 4);
        long fieldB = VeepooDeviceSupport.le32(blob, 8);
        if (fieldA == 0 && fieldB == 0) {
            LOG.info("Workout day {}: empty slot", dayIndex);
            return null;
        }
        // Older firmware versions stamped the start epoch with a wrong timezone (future date),
        // leaving the two epochs out of order. Normalize with min/max so those sessions are
        // still saved.
        long startUnix = Math.min(fieldA, fieldB);
        long stopUnix = Math.max(fieldA, fieldB);
        if (startUnix < 946684800L || startUnix > 4102444800L || headLength < 40) {
            LOG.warn("Workout day {}: implausible GPS head (epochs {} and {}); skipping",
                    dayIndex, fieldA, fieldB);
            return null;
        }
        if (fieldB < fieldA) {
            LOG.warn("Workout day {}: start/stop epochs out of order ({}, {}); using min/max",
                    dayIndex, fieldA, fieldB);
        }

        int sportSeconds = (int) VeepooDeviceSupport.le32(blob, 12);
        int base = blob[17] & 0xFF;
        int steps = (int) VeepooDeviceSupport.le32(blob, 18);
        int distanceMeters = (int) VeepooDeviceSupport.le32(blob, 22);
        float kcal = (int) VeepooDeviceSupport.le32(blob, 26) / 1000f;
        int maxHr = blob[34] & 0xFF;
        int avgHr = blob[35] & 0xFF;
        int minHr = blob[36] & 0xFF;
        int avgPace = VeepooDeviceSupport.le16(blob, 39);
        int maxSpeed = VeepooDeviceSupport.le16(blob, 43);
        int avgSpeed = VeepooDeviceSupport.le16(blob, 45);
        int minSpeed = VeepooDeviceSupport.le16(blob, 47);
        int maxCadence = VeepooDeviceSupport.le16(blob, 49);
        int avgCadence = VeepooDeviceSupport.le16(blob, 51);
        int minCadence = VeepooDeviceSupport.le16(blob, 53);

        long maxAltitude = 0;
        long avgAltitude = 0;
        long minAltitude = 0;
        long climb = 0;
        long decline = 0;
        boolean hasAltitudeBlock = base + 2 > 39 && blob.length >= 75;
        if (hasAltitudeBlock) {
            maxAltitude = VeepooDeviceSupport.le32(blob, 55);
            avgAltitude = VeepooDeviceSupport.le32(blob, 59);
            minAltitude = VeepooDeviceSupport.le32(blob, 63);
            climb = VeepooDeviceSupport.le32(blob, 67);
            decline = VeepooDeviceSupport.le32(blob, 71);
        }

        if (blob.length < base + 34) {
            LOG.warn("Workout day {}: GPS blob truncated before the sport block; skipping", dayIndex);
            return null;
        }
        int pauseSeconds = VeepooDeviceSupport.le16(blob, base + 23);
        int sportType = VeepooDeviceSupport.le16(blob, base + 31);
        int dataType = blob[base + 33] & 0xFF;

        Date startTime = new Date(startUnix * 1000L);
        Date stopTime = new Date(stopUnix * 1000L);
        long totalSeconds = Math.max(0L, stopUnix - startUnix);
        long activeSeconds = sportSeconds > 0 ? sportSeconds : Math.max(0L, totalSeconds - pauseSeconds);

        ActivitySummaryData summary = new ActivitySummaryData();
        addSportEntries(summary, activeSeconds, distanceMeters, kcal, steps);
        if (avgHr >= 30 && avgHr <= 200) {
            summary.add(ActivitySummaryEntries.HR_AVG, avgHr, ActivitySummaryEntries.UNIT_BPM);
        }
        if (maxHr >= 30 && maxHr <= 200) {
            summary.add(ActivitySummaryEntries.HR_MAX, maxHr, ActivitySummaryEntries.UNIT_BPM);
        }
        if (minHr >= 30 && minHr <= 200) {
            summary.add(ActivitySummaryEntries.HR_MIN, minHr, ActivitySummaryEntries.UNIT_BPM);
        }
        if (avgPace > 0) {
            summary.add(ActivitySummaryEntries.PACE_AVG_SECONDS_KM, avgPace, ActivitySummaryEntries.UNIT_SECONDS_PER_KM);
        }
        if (avgSpeed > 0) {
            summary.add(ActivitySummaryEntries.SPEED_AVG, avgSpeed / 1000f, ActivitySummaryEntries.UNIT_KMPH);
        }
        if (maxSpeed > 0) {
            summary.add(ActivitySummaryEntries.SPEED_MAX, maxSpeed / 1000f, ActivitySummaryEntries.UNIT_KMPH);
        }
        if (minSpeed > 0) {
            summary.add(ActivitySummaryEntries.SPEED_MIN, minSpeed / 1000f, ActivitySummaryEntries.UNIT_KMPH);
        }
        if (avgCadence >= 30 && avgCadence <= 400) {
            summary.add(ActivitySummaryEntries.CADENCE_AVG, avgCadence, ActivitySummaryEntries.UNIT_SPM);
        }
        if (maxCadence >= 30 && maxCadence <= 400) {
            summary.add(ActivitySummaryEntries.CADENCE_MAX, maxCadence, ActivitySummaryEntries.UNIT_SPM);
        }
        if (minCadence >= 30 && minCadence <= 400) {
            summary.add(ActivitySummaryEntries.CADENCE_MIN, minCadence, ActivitySummaryEntries.UNIT_SPM);
        }
        if (hasAltitudeBlock) {
            if (maxAltitude > 0) {
                summary.add(ActivitySummaryEntries.ALTITUDE_MAX, maxAltitude, ActivitySummaryEntries.UNIT_METERS);
            }
            if (avgAltitude > 0) {
                summary.add(ActivitySummaryEntries.ALTITUDE_AVG, avgAltitude, ActivitySummaryEntries.UNIT_METERS);
            }
            if (minAltitude > 0) {
                summary.add(ActivitySummaryEntries.ALTITUDE_MIN, minAltitude, ActivitySummaryEntries.UNIT_METERS);
            }
            if (climb > 0) {
                summary.add(ActivitySummaryEntries.TOTAL_ASCENT, climb, ActivitySummaryEntries.UNIT_METERS);
            }
            if (decline > 0) {
                summary.add(ActivitySummaryEntries.TOTAL_DESCENT, decline, ActivitySummaryEntries.UNIT_METERS);
            }
        }

        ActivitySummaryTableBuilder perMinute = newPerMinuteTable();
        List<GpxPoint> gpsPoints = new ArrayList<>();
        int offset = headLength;
        int itemIndex = 0;
        int rowsAdded = 0;
        while (offset + 3 <= blob.length) {
            if ((blob[offset] & 0xFF) != 0xA2) {
                break;
            }
            int recordLength = VeepooDeviceSupport.le16(blob, offset + 1);
            int dataStart = offset + 3;
            if (recordLength < 12 || dataStart + recordLength > blob.length) {
                break;
            }
            int rate = blob[dataStart + 2] & 0xFF;
            int itemSteps = VeepooDeviceSupport.le16(blob, dataStart + 5);
            int itemDistance = VeepooDeviceSupport.le16(blob, dataStart + 7);
            int itemKcalRaw = VeepooDeviceSupport.le16(blob, dataStart + 9);
            boolean validHr = rate >= 30 && rate <= 200;
            if (validHr || itemSteps > 0 || itemDistance > 0 || itemKcalRaw > 0) {
                perMinute.addRow("minute_" + (itemIndex + 1), Arrays.asList(
                        new ActivitySummaryValue(itemIndex + 1, ActivitySummaryEntries.UNIT_NONE),
                        new ActivitySummaryValue(validHr ? rate : null, ActivitySummaryEntries.UNIT_BPM),
                        new ActivitySummaryValue(itemSteps > 0 ? itemSteps : null, ActivitySummaryEntries.UNIT_STEPS),
                        new ActivitySummaryValue(itemDistance > 0 ? itemDistance : null, ActivitySummaryEntries.UNIT_METERS),
                        new ActivitySummaryValue(itemKcalRaw > 0 ? itemKcalRaw / 1000f : null, ActivitySummaryEntries.UNIT_KCAL)));
                rowsAdded++;
            }
            int pointStart = hasAltitudeBlock ? 22 : 18;
            long itemAltitude = hasAltitudeBlock && recordLength > 16 ? VeepooDeviceSupport.le32(blob, dataStart + 13) : 0;
            int pointCount = Math.max(0, (recordLength - 16) / 11);
            for (int p = 0; p < pointCount; p++) {
                int pos = dataStart + pointStart + p * 11;
                if (pos + 11 > dataStart + recordLength) {
                    break;
                }
                long lon = VeepooDeviceSupport.le32(blob, pos + 1);
                long lat = VeepooDeviceSupport.le32(blob, pos + 5);
                if (lon != 0 && lat != 0) {
                    long timeMs = startUnix * 1000L + itemIndex * 60000L
                            + (pointCount > 1 ? p * 60000L / pointCount : 30000L);
                    gpsPoints.add(new GpxPoint(lon, lat, itemAltitude, timeMs));
                }
            }
            itemIndex++;
            offset = dataStart + recordLength;
        }
        perMinute.addToSummaryData(summary);
        LOG.info("Workout day {}: parsed {} item records, saved {} per-minute rows ({} GPS points)",
                dayIndex, itemIndex, rowsAdded, gpsPoints.size());
        if (rowsAdded == 0 && blob.length > headLength + 3) {
            StringBuilder hex = new StringBuilder();
            int dumpEnd = Math.min(blob.length, headLength + 48);
            for (int i = headLength; i < dumpEnd; i++) {
                hex.append(String.format("%02x ", blob[i] & 0xFF));
            }
            StringBuilder a2Offsets = new StringBuilder();
            int a2Count = 0;
            for (int i = 0; i < blob.length && a2Count < 40; i++) {
                if ((blob[i] & 0xFF) == 0xA2) {
                    a2Offsets.append(i).append(' ');
                    a2Count++;
                }
            }
            LOG.info("Workout day {}: no 0xA2 item records at offset {}; bytes there: [{}]; 0xA2 byte offsets in blob: [{}]",
                    dayIndex, headLength, hex.toString().trim(), a2Offsets.toString().trim());
        }
        return new SportDay(startTime, stopTime, sportType, summary, gpsPoints);
    }

    private static void addSportEntries(ActivitySummaryData summary, long activeSeconds, int distanceMeters, float kcal, int steps) {
        if (activeSeconds > 0) {
            summary.add(ActivitySummaryEntries.ACTIVE_SECONDS, activeSeconds, ActivitySummaryEntries.UNIT_SECONDS);
        }
        if (distanceMeters > 0) {
            summary.add(ActivitySummaryEntries.DISTANCE_METERS, distanceMeters, ActivitySummaryEntries.UNIT_METERS);
        }
        if (kcal > 0f) {
            summary.add(ActivitySummaryEntries.CALORIES_BURNT, kcal, ActivitySummaryEntries.UNIT_KCAL);
        }
        if (steps > 0) {
            summary.add(ActivitySummaryEntries.STEPS, steps, ActivitySummaryEntries.UNIT_STEPS);
        }
    }

    private static ActivitySummaryTableBuilder newPerMinuteTable() {
        return new ActivitySummaryTableBuilder(ActivitySummaryEntries.GROUP_LAPS, "per_minute_header",
                Arrays.asList("minute", "heartRate", "steps", "distanceMeters", "caloriesBurnt"));
    }
}
