/*  Copyright (C) 2026 Vitaliy Tomin

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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests;

import java.util.ArrayList;
import java.util.List;

/**
 * A workout ("exercise") record the band embeds in a {@code CH_HEALTH} (0x0a)
 * payload, outer marker {@code 0x80}. Framing is {@code [0x80][5 bytes][body]}; the
 * body is a tag/value stream (4-byte values, 16-byte UUID, and length-prefixed
 * strings/lists). Timestamps use
 * the channel's 1990 epoch.
 *
 * <p>This parses the summary fields (which come first in the record) plus the
 * {@code LIVE_DATA} time series (tag 46, 28-byte samples). The summary walk
 * stops at the first tag it doesn't recognise - several optional tags near the
 * end of the record have variable-width nested structures (sensing status,
 * pace-setter, ...) that aren't decoded; the {@code LIVE_DATA} list is located
 * by a separate, validated scan so the walk doesn't need to cross them.
 */
public final class Fit3HealthWorkout {

    public static final int MARKER = 0x80;
    /** Bytes between the marker and the first body tag (marker + a 5-byte framing span). */
    private static final int BODY_OFFSET = 6;
    private static final int EPOCH_1990_OFFSET_SECONDS = 631152000;

    // Summary field tags (all 4-byte values unless noted), from parseExerciseNewByte.
    private static final int TAG_START_TIME = 1;
    private static final int TAG_END_TIME = 2;
    private static final int TAG_TIME_OFFSET = 3;
    private static final int TAG_UPDATE_TIME = 5;
    private static final int TAG_COMMENT = 16;         // string (len-prefixed)
    private static final int TAG_CALORIE = 17;         // float
    private static final int TAG_DURATION = 18;        // int, ms
    private static final int TAG_DISTANCE = 19;        // float, m
    private static final int TAG_EXERCISE_TYPE = 20;
    private static final int TAG_CUSTOM_TYPE = 21;     // string
    private static final int TAG_COUNT = 22;           // steps/reps
    private static final int TAG_COUNT_TYPE = 23;
    private static final int TAG_MAX_SPEED = 26;       // float
    private static final int TAG_MEAN_SPEED = 27;      // float
    private static final int TAG_MAX_ALTITUDE = 28;    // float
    private static final int TAG_MIN_ALTITUDE = 29;    // float
    private static final int TAG_MAX_CADENCE = 34;     // float
    private static final int TAG_MEAN_CADENCE = 35;    // float
    private static final int TAG_MAX_HR = 36;          // float
    private static final int TAG_MIN_HR = 37;          // float
    private static final int TAG_MEAN_HR = 38;         // float
    private static final int TAG_LOCATION_DATA = 45;   // list, 28-byte samples (GPS; band has none)
    private static final int TAG_LIVE_DATA = 46;       // list, 28-byte samples
    private static final int TAG_DATA_UUID = 117;      // 16 raw bytes, no length prefix
    private static final int TAG_CREATE_SH_VER = 151;
    private static final int TAG_MODIFY_SH_VER = 153;
    private static final int LIVE_SAMPLE_STRIDE = 28;

    /** One {@code LIVE_DATA} sample; fields the band left out of range (its own validity gates) are null. */
    public static final class LiveSample {
        public final int timestamp;     // Unix epoch seconds
        public final Float heartRate;
        public final Float cadence;
        public final Integer count;
        public final Float speed;       // m/s
        public final Float distance;    // m

        LiveSample(int timestamp, Float heartRate, Float cadence, Integer count, Float speed, Float distance) {
            this.timestamp = timestamp;
            this.heartRate = heartRate;
            this.cadence = cadence;
            this.count = count;
            this.speed = speed;
            this.distance = distance;
        }
    }

    public final int exerciseType;
    public final int startTime;         // Unix epoch seconds
    public final int endTime;           // Unix epoch seconds
    public final int durationMs;
    public final float distanceMeters;
    public final float calories;
    public final int count;             // steps (or reps, per exerciseType)
    public final Float maxHr;
    public final Float minHr;
    public final Float meanHr;
    public final Float meanCadence;
    public final Float meanSpeed;
    public final List<LiveSample> samples;

    private Fit3HealthWorkout(int exerciseType, int startTime, int endTime, int durationMs,
                              float distanceMeters, float calories, int count,
                              Float maxHr, Float minHr, Float meanHr, Float meanCadence,
                              Float meanSpeed, List<LiveSample> samples) {
        this.exerciseType = exerciseType;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMs = durationMs;
        this.distanceMeters = distanceMeters;
        this.calories = calories;
        this.count = count;
        this.maxHr = maxHr;
        this.minHr = minHr;
        this.meanHr = meanHr;
        this.meanCadence = meanCadence;
        this.meanSpeed = meanSpeed;
        this.samples = samples;
    }

    /**
     * Parse the workout record whose {@code 0x80} marker is at {@code markerOffset}.
     *
     * @return the parsed workout, or {@code null} if this isn't a well-formed one
     *     (start/end/type not all present) - callers should treat null as
     *     "keep scanning", not an error.
     */
    public static Fit3HealthWorkout parse(byte[] data, int markerOffset) {
        if (markerOffset < 0 || (data[markerOffset] & 0xFF) != MARKER) {
            return null;
        }
        int pos = markerOffset + BODY_OFFSET;
        Integer start = null, end = null, tzOffsetMs = null, exerciseType = null, durationMs = null, count = null;
        Float distance = null, calories = null, maxHr = null, minHr = null, meanHr = null,
                meanCadence = null, meanSpeed = null;

        // Walk the summary tags. Stop at the first tag we can't size - the
        // fields we want all come before the record's variable-width tail.
        while (pos + 1 <= data.length) {
            int tag = data[pos] & 0xFF;
            if (tag == TAG_DATA_UUID) {
                pos += 1 + 16;
                continue;
            }
            if (tag == TAG_COMMENT || tag == TAG_CUSTOM_TYPE
                    || tag == TAG_LOCATION_DATA || tag == TAG_LIVE_DATA) {
                if (pos + 5 > data.length) break;
                int len = le32(data, pos + 1);
                // A stray 0x80 byte (in float data etc.) can look like a
                // workout marker and lead here with a garbage length. Reject
                // anything that wouldn't strictly advance pos within bounds,
                // so the walk can't loop or run off the end.
                if (len < 0 || (long) pos + 5 + len > data.length) break;
                pos += 5 + len;
                continue;
            }
            if (!isKnownValueTag(tag) || pos + 5 > data.length) {
                break; // unknown or variable-width tag: end of the part we understand
            }
            int raw = le32(data, pos + 1);
            switch (tag) {
                case TAG_START_TIME:    start = raw + EPOCH_1990_OFFSET_SECONDS; break;
                case TAG_END_TIME:      end = raw + EPOCH_1990_OFFSET_SECONDS; break;
                case TAG_TIME_OFFSET:   tzOffsetMs = raw; break;
                case TAG_CALORIE:       calories = Float.intBitsToFloat(raw); break;
                case TAG_DURATION:      durationMs = raw; break;
                case TAG_DISTANCE:      distance = Float.intBitsToFloat(raw); break;
                case TAG_EXERCISE_TYPE: exerciseType = raw; break;
                case TAG_COUNT:         count = raw; break;
                case TAG_MAX_HR:        maxHr = Float.intBitsToFloat(raw); break;
                case TAG_MIN_HR:        minHr = Float.intBitsToFloat(raw); break;
                case TAG_MEAN_HR:       meanHr = Float.intBitsToFloat(raw); break;
                case TAG_MEAN_CADENCE:  meanCadence = Float.intBitsToFloat(raw); break;
                case TAG_MEAN_SPEED:    meanSpeed = Float.intBitsToFloat(raw); break;
                default: break; // recognised width, value not surfaced
            }
            pos += 5;
        }

        if (start == null || end == null || exerciseType == null) {
            return null;
        }

        List<LiveSample> samples = findLiveData(data, markerOffset, start, end);
        return new Fit3HealthWorkout(exerciseType, start, end,
                durationMs != null ? durationMs : 0,
                distance != null ? distance : 0f,
                calories != null ? calories : 0f,
                count != null ? count : 0,
                maxHr, minHr, meanHr, meanCadence, meanSpeed, samples);
    }

    /** Scan {@code data} for the first well-formed workout at or after {@code fromOffset}. */
    public static Fit3HealthWorkout findFirst(byte[] data, int fromOffset) {
        for (int i = fromOffset; i < data.length; i++) {
            if ((data[i] & 0xFF) == MARKER) {
                Fit3HealthWorkout workout = parse(data, i);
                if (workout != null) {
                    return workout;
                }
            }
        }
        return null;
    }

    /**
     * Locate the {@code LIVE_DATA} list (tag 46) by scanning the record region,
     * rather than walking there through the summary's variable-width tail. The
     * match must be a 4-byte length that's a positive multiple of the sample
     * stride, fits in the buffer, and whose first sample timestamp lands inside
     * the workout window - which makes a false positive from a stray {@code
     * 0x2e} byte effectively impossible.
     */
    private static List<LiveSample> findLiveData(byte[] data, int fromOffset, int start, int end) {
        for (int i = fromOffset; i + 5 <= data.length; i++) {
            if ((data[i] & 0xFF) != TAG_LIVE_DATA) {
                continue;
            }
            int len = le32(data, i + 1);
            if (len <= 0 || len % LIVE_SAMPLE_STRIDE != 0 || i + 5 + len > data.length) {
                continue;
            }
            int base = i + 5;
            int firstTs = le32(data, base) + EPOCH_1990_OFFSET_SECONDS;
            if (firstTs < start - 60 || firstTs > end + 60) {
                continue;
            }
            List<LiveSample> samples = new ArrayList<>(len / LIVE_SAMPLE_STRIDE);
            for (int s = base; s + LIVE_SAMPLE_STRIDE <= base + len; s += LIVE_SAMPLE_STRIDE) {
                int ts = le32(data, s) + EPOCH_1990_OFFSET_SECONDS;
                if (ts < start - 60 || ts > end + 60) {
                    continue; // firmware left this slot uninitialised
                }
                samples.add(new LiveSample(ts,
                        rangedFloat(data, s + 4, 0f, false, 300f),      // heartRate: 0 < x <= 300
                        rangedFloat(data, s + 8, 0f, true, 500000f),    // cadence: 0 <= x <= 500000
                        rangedInt(data, s + 12, 0, 1000000),            // count: 0 <= x <= 1000000
                        rangedFloat(data, s + 20, 0f, false, 140f),     // speed: 0 < x <= 140
                        rangedFloat(data, s + 24, 0f, true, 2000000f))); // distance: 0 <= x <= 2000000
            }
            return samples;
        }
        return new ArrayList<>();
    }

    private static boolean isKnownValueTag(int tag) {
        if (tag == TAG_UPDATE_TIME || tag == TAG_CREATE_SH_VER || tag == TAG_MODIFY_SH_VER) {
            return true;
        }
        // Every summary tag in parseExerciseNewByte's 1..60 range is a plain
        // 4-byte value except the strings/lists/uuid handled above.
        return tag >= TAG_START_TIME && tag <= 60
                && tag != TAG_COMMENT && tag != TAG_CUSTOM_TYPE
                && tag != TAG_LOCATION_DATA && tag != TAG_LIVE_DATA
                && tag != 48 && tag != 58;
    }

    /** @return the float if it passes the band's own validity gate, else null. */
    private static Float rangedFloat(byte[] data, int off, float lo, boolean loInclusive, float hi) {
        float v = Float.intBitsToFloat(le32(data, off));
        boolean okLo = loInclusive ? v >= lo : v > lo;
        return (okLo && v <= hi) ? v : null;
    }

    private static Integer rangedInt(byte[] data, int off, int lo, int hi) {
        int v = le32(data, off);
        return (v >= lo && v <= hi) ? v : null;
    }

    private static int le32(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16) | ((data[off + 3] & 0xFF) << 24);
    }

    @Override
    public String toString() {
        return "Workout{type=" + exerciseType + ", start=" + startTime + ", end=" + endTime
                + ", durationMs=" + durationMs + ", distanceM=" + distanceMeters
                + ", calories=" + calories + ", count=" + count
                + ", meanHr=" + meanHr + ", samples=" + samples.size() + "}";
    }
}
