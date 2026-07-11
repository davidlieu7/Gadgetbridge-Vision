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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code PEDO_STEP_COUNT} - one of the "feature data type" records the band
 * embeds in a {@code CH_HEALTH} (0x0a) payload, marker {@code 0x07}. The field
 * schema is a fixed 12-field walk, each a {@code [tag][value]} pair - all 4
 * bytes except tag 23, which is 1 - confirmed byte-for-byte against a live
 * band: a captured record's {@code COUNT} field matched the step count shown
 * on the band's own screen exactly.
 *
 * <p>Live captures show the marker byte is followed by one extra byte before
 * the 12-field walk starts (always {@code 0x00} so far); its purpose isn't
 * confirmed, so it's skipped rather than interpreted.
 */
public final class Fit3HealthPedometerRecord {

    public static final int MARKER = 0x07;
    private static final int TAG_START_TIME = 1;
    private static final int TAG_END_TIME = 2;
    private static final int TAG_TIME_OFFSET = 3;
    private static final int TAG_UPDATE_TIME = 5;
    private static final int TAG_COUNT = 16;
    private static final int TAG_DISTANCE = 17;
    private static final int TAG_SPEED = 18;
    private static final int TAG_CALORIE = 19;
    private static final int TAG_RUN_STEP = 20;
    private static final int TAG_WALK_STEP = 21;
    private static final int TAG_DURATION = 22;
    private static final int TAG_VERSION_CODE = 23;
    // A *set*, not a fixed sequence - the source dispatches whichever tag comes
    // next via a switch (ModenByteToJsonDataParser#parsePedometerStepCountByteData),
    // it doesn't require a specific field order, and live captures confirm the
    // wire order isn't the numeric tag order (UPDATE_TIME(5) has been observed
    // before START_TIME(1)/END_TIME(2)/TIME_OFFSET(3), not after).
    private static final Set<Integer> EXPECTED_TAGS = new HashSet<>(Arrays.asList(
            TAG_START_TIME, TAG_END_TIME, TAG_TIME_OFFSET, TAG_UPDATE_TIME,
            TAG_COUNT, TAG_DISTANCE, TAG_SPEED, TAG_CALORIE,
            TAG_RUN_STEP, TAG_WALK_STEP, TAG_DURATION, TAG_VERSION_CODE));

    public final int count;
    public final int runStep;
    public final int walkStep;
    public final float distance;
    public final float speed;
    public final float calorie;
    public final int durationMs;
    public final int timeOffsetMs;
    /** End of the record's own tag walk, one past the last byte consumed - for scanning past it. */
    public final int endOffset;

    private Fit3HealthPedometerRecord(int count, int runStep, int walkStep, float distance,
                                       float speed, float calorie, int durationMs, int timeOffsetMs,
                                       int endOffset) {
        this.count = count;
        this.runStep = runStep;
        this.walkStep = walkStep;
        this.distance = distance;
        this.speed = speed;
        this.calorie = calorie;
        this.durationMs = durationMs;
        this.timeOffsetMs = timeOffsetMs;
        this.endOffset = endOffset;
    }

    /**
     * @param markerOffset offset of the {@code 0x07} marker byte itself.
     * @return the parsed record, or {@code null} if this isn't a well-formed
     *     PEDO_STEP_COUNT record at that offset (not every {@code 0x07} byte
     *     in a payload is this marker - callers should treat a {@code null}
     *     as "keep scanning", not as an error).
     */
    public static Fit3HealthPedometerRecord parse(byte[] data, int markerOffset) {
        if (markerOffset < 0 || markerOffset + 2 > data.length || (data[markerOffset] & 0xFF) != MARKER) {
            return null;
        }
        int pos = markerOffset + 2; // skip the marker and the one unidentified byte after it
        Map<Integer, Integer> intFields = new HashMap<>();
        for (int i = 0; i < EXPECTED_TAGS.size(); i++) {
            if (pos >= data.length) {
                return null;
            }
            int tag = data[pos] & 0xFF;
            if (!EXPECTED_TAGS.contains(tag) || intFields.containsKey(tag)) {
                return null;
            }
            pos++;
            if (tag == TAG_VERSION_CODE) {
                if (pos + 1 > data.length) {
                    return null;
                }
                intFields.put(tag, data[pos] & 0xFF);
                pos += 1;
            } else {
                if (pos + 4 > data.length) {
                    return null;
                }
                int raw = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8)
                        | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24);
                intFields.put(tag, raw);
                pos += 4;
            }
        }
        return new Fit3HealthPedometerRecord(
                intFields.get(TAG_COUNT),
                intFields.get(TAG_RUN_STEP),
                intFields.get(TAG_WALK_STEP),
                Float.intBitsToFloat(intFields.get(TAG_DISTANCE)),
                Float.intBitsToFloat(intFields.get(TAG_SPEED)),
                Float.intBitsToFloat(intFields.get(TAG_CALORIE)),
                intFields.get(TAG_DURATION),
                intFields.get(TAG_TIME_OFFSET),
                pos);
    }

    /** Scan {@code data} for the first well-formed record at or after {@code fromOffset}. */
    public static Fit3HealthPedometerRecord findFirst(byte[] data, int fromOffset) {
        for (int i = fromOffset; i < data.length; i++) {
            if ((data[i] & 0xFF) == MARKER) {
                Fit3HealthPedometerRecord record = parse(data, i);
                if (record != null) {
                    return record;
                }
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "PedometerRecord{count=" + count + ", walkStep=" + walkStep + ", runStep=" + runStep
                + ", distance=" + distance + ", speed=" + speed + ", calorie=" + calorie
                + ", durationMs=" + durationMs + ", timeOffsetMs=" + timeOffsetMs + "}";
    }
}
