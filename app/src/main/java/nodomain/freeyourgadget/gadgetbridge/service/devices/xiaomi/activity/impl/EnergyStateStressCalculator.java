/*  Copyright (C) 2026 Ludwig Ettner

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Daily details files before v3 (eg. Mi Watch Color Sport) do not contain stress values. The
 * official app derives a stress value for every half hour from the per-minute energy state
 * (1 = activity, 2 = stress, 3 = recovery) and the signed energy state value, which is
 * replicated here.
 */
public final class EnergyStateStressCalculator {
    public static final int STATE_ACTIVITY = 1;
    public static final int STATE_STRESS = 2;
    public static final int STATE_RECOVERY = 3;

    private static final int BUCKET_SECONDS = 30 * 60;
    private static final int MIN_SAMPLES = 6;

    private EnergyStateStressCalculator() {
    }

    public static class Entry {
        final int timestamp;
        final int state;
        final int value;

        /**
         * @param timestamp unix timestamp, in seconds
         * @param state     energy state, 0 if not valid
         * @param value     signed energy state value
         */
        public Entry(final int timestamp, final int state, final int value) {
            this.timestamp = timestamp;
            this.state = state;
            this.value = value;
        }
    }

    /**
     * @param dayStart unix timestamp of the start of the day, in seconds
     * @param entries  one entry per minute that has a valid energy value
     * @return stress per half hour, keyed by the start of the half hour (unix seconds)
     */
    public static Map<Integer, Integer> compute(final int dayStart, final List<Entry> entries) {
        final Map<Integer, List<Entry>> buckets = new TreeMap<>();
        for (final Entry entry : entries) {
            if (entry.timestamp < dayStart) {
                continue;
            }
            final int bucket = bucketStart(dayStart, entry.timestamp);
            List<Entry> bucketEntries = buckets.get(bucket);
            if (bucketEntries == null) {
                bucketEntries = new ArrayList<>();
                buckets.put(bucket, bucketEntries);
            }
            bucketEntries.add(entry);
        }

        final Map<Integer, Integer> stressPerBucket = new TreeMap<>();
        for (final Map.Entry<Integer, List<Entry>> bucket : buckets.entrySet()) {
            final int stress = computeBucket(bucket.getValue());
            if (stress > 0) {
                stressPerBucket.put(bucket.getKey(), stress);
            }
        }

        return stressPerBucket;
    }

    public static int bucketStart(final int dayStart, final int timestamp) {
        return dayStart + ((timestamp - dayStart) / BUCKET_SECONDS) * BUCKET_SECONDS;
    }

    private static int computeBucket(final List<Entry> entries) {
        int numActivity = 0;
        int numStressOrRecovery = 0;
        for (final Entry entry : entries) {
            if (entry.state == STATE_ACTIVITY) {
                numActivity++;
            } else if (entry.state == STATE_STRESS || entry.state == STATE_RECOVERY) {
                numStressOrRecovery++;
            }
        }

        final int total = numActivity + numStressOrRecovery;
        if (total < MIN_SAMPLES || (float) numActivity / total > 0.5f) {
            // not enough data, or mostly activity - no stress value
            return 0;
        }

        int sum = 0;
        int count = 0;
        for (final Entry entry : entries) {
            if (entry.state == STATE_STRESS || entry.state == STATE_RECOVERY) {
                sum += entry.value;
                count++;
            }
        }
        final int avg = (int) ((float) sum / count);
        if (avg == 0) {
            return 0;
        }

        return (int) friendlyStress(avg > 0 ? STATE_RECOVERY : STATE_STRESS, avg);
    }

    static float friendlyStress(final int state, final int value) {
        if (state != STATE_STRESS && state != STATE_RECOVERY) {
            return 0;
        }

        final float f = value == 0 ? 25f : 25f - value * (value > 0 ? 0.25f : 0.75f);
        if (f == 25f) {
            return 25f;
        } else if (f > 0 && f < 25f) {
            return f * 0.6f + 10;
        } else if (f > 25f && f < 100f) {
            return (f - 25) * 65 / 75 + 25;
        }

        return 0;
    }
}
