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
 * {@code HEART_RATE} - one of the "feature data type" records the band embeds
 * in a {@code CH_HEALTH} (0x0a) payload, outer marker {@code 0x0a}/10.
 *
 * <p>Unlike a {@code PEDO_STEP_COUNT} record (a bare tag walk introduced by a
 * has-more flag), a heart-rate record is length-prefixed:
 *
 * <pre>
 *   [0x0a][hasMore:1][len:4 LE][ body (len bytes) ]
 * </pre>
 *
 * <p>The body is an order-independent {@code [tag][value]} walk. Value widths
 * are fixed per tag: the time/version tags (1/2/3/5/0x97/0x99) carry 4 bytes,
 * the summary tags (0x20-0x23) carry 1, {@code tag_id} (0x25) carries 2, and
 * {@code BINNING} (0x24) carries a {@code [count:4 LE]} byte length followed by
 * {@code count/4} four-byte entries:
 *
 * <pre>
 *   [minuteOffset:1][heartRate:1][min:1][max:1]
 * </pre>
 *
 * <p>Each binning entry is one minute of data; its wall-clock time is {@code
 * START_TIME (tag 1) + minuteOffset*60}. Like every 4-byte timestamp on this
 * channel, {@code START_TIME} is seconds since 1990-01-01T00:00:00Z (see
 * {@link Fit3HealthPedometerRecord#EPOCH_1990_OFFSET_SECONDS}), which decodes
 * captured records to exact top-of-the-hour instants.
 *
 * <p>Confidence: the wire format is matched byte-for-byte against captures
 * (heart-rate values land in a
 * physiological range and timestamps align to the minute). Confirmed
 * end-to-end against a live band: with continuous heart-rate measurement
 * enabled, real records synced through and showed up as heart-rate samples in
 * Gadgetbridge. (When the band's continuous measurement is off it sends an
 * empty heart-rate record, i.e. no binning entries - which parses to zero
 * samples, not an error.)
 */
public final class Fit3HealthHeartRateRecord {

    public static final int MARKER = 0x0a;

    private static final int RECORD_HEADER_LEN = 6; // marker(1) + hasMore(1) + len(4)

    private static final int TAG_START_TIME = 1;
    private static final int TAG_END_TIME = 2;
    private static final int TAG_TIME_OFFSET = 3;
    private static final int TAG_UPDATE_TIME = 5;
    private static final int TAG_HEART_RATE = 0x20;
    private static final int TAG_MIN = 0x21;
    private static final int TAG_MAX = 0x22;
    private static final int TAG_BEAT_COUNT = 0x23;
    private static final int TAG_BINNING = 0x24;
    private static final int TAG_TAG_ID = 0x25;
    private static final int TAG_CREATE_VERSION = 0x97;
    private static final int TAG_MODIFY_VERSION = 0x99;

    private static final int BINNING_ENTRY_LEN = 4;
    // Source guards the binning byte-count at <= 300 (parseHeartRateTrackerByteData).
    private static final int MAX_BINNING_BYTES = 300;
    private static final int MAX_RECORD_LEN = 4096;

    // Reject a stray 0x0a byte whose "start time" isn't a plausible instant:
    // seconds-since-1990 window for wall-clock years ~2020..2035.
    private static final long MIN_START_1990 = 946684800L;  // 2020-01-01Z
    private static final long MAX_START_1990 = 1420070400L; // 2035-01-01Z

    // Physiological bounds - entries outside this (e.g. a 0 "no reading") are dropped.
    private static final int MIN_HR = 25;
    private static final int MAX_HR = 250;

    /** One minute of heart-rate data. */
    public static final class Sample {
        /** Unix epoch seconds. */
        public final int timestamp;
        public final int heartRate;
        public final int minHeartRate;
        public final int maxHeartRate;

        private Sample(int timestamp, int heartRate, int minHeartRate, int maxHeartRate) {
            this.timestamp = timestamp;
            this.heartRate = heartRate;
            this.minHeartRate = minHeartRate;
            this.maxHeartRate = maxHeartRate;
        }

        @Override
        public String toString() {
            return "HeartRate{ts=" + timestamp + ", hr=" + heartRate
                    + ", min=" + minHeartRate + ", max=" + maxHeartRate + "}";
        }
    }

    private final List<Sample> samples;
    /** One past the record's last byte, for scanning past it. */
    public final int endOffset;

    private Fit3HealthHeartRateRecord(List<Sample> samples, int endOffset) {
        this.samples = samples;
        this.endOffset = endOffset;
    }

    /**
     * Parse the length-prefixed heart-rate record whose marker byte is at
     * {@code markerOffset}.
     *
     * @return the parsed record, or {@code null} if there isn't a well-formed
     *     one there (not every {@code 0x0a} byte is this marker - a caller
     *     scanning a mixed stream should treat {@code null} as "keep looking").
     */
    public static Fit3HealthHeartRateRecord parse(byte[] data, int markerOffset) {
        if (markerOffset < 0 || markerOffset + RECORD_HEADER_LEN > data.length
                || (data[markerOffset] & 0xFF) != MARKER) {
            return null;
        }
        int hasMore = data[markerOffset + 1] & 0xFF;
        if (hasMore != 0 && hasMore != 1) {
            return null;
        }
        int len = readU32(data, markerOffset + 2);
        if (len < 2 || len > MAX_RECORD_LEN) {
            return null;
        }
        int bodyStart = markerOffset + RECORD_HEADER_LEN;
        int bodyEnd = bodyStart + len;
        if (bodyEnd > data.length) {
            return null;
        }

        long startTime1990 = -1;
        List<int[]> binning = new ArrayList<>();
        int pos = bodyStart;
        while (pos < bodyEnd) {
            int tag = data[pos] & 0xFF;
            pos++;
            switch (tag) {
                case TAG_START_TIME:
                case TAG_END_TIME:
                case TAG_TIME_OFFSET:
                case TAG_UPDATE_TIME:
                case TAG_CREATE_VERSION:
                case TAG_MODIFY_VERSION:
                    if (pos + 4 > bodyEnd) {
                        return null;
                    }
                    if (tag == TAG_START_TIME) {
                        startTime1990 = readU32(data, pos) & 0xFFFFFFFFL;
                    }
                    pos += 4;
                    break;
                case TAG_HEART_RATE:
                case TAG_MIN:
                case TAG_MAX:
                case TAG_BEAT_COUNT:
                    if (pos + 1 > bodyEnd) {
                        return null;
                    }
                    pos += 1;
                    break;
                case TAG_TAG_ID:
                    if (pos + 2 > bodyEnd) {
                        return null;
                    }
                    pos += 2;
                    break;
                case TAG_BINNING: {
                    if (pos + 4 > bodyEnd) {
                        return null;
                    }
                    int count = readU32(data, pos);
                    if (count < 0 || count > MAX_BINNING_BYTES
                            || count % BINNING_ENTRY_LEN != 0 || pos + 4 + count > bodyEnd) {
                        return null;
                    }
                    int entriesStart = pos + 4;
                    for (int e = 0; e < count / BINNING_ENTRY_LEN; e++) {
                        int o = entriesStart + e * BINNING_ENTRY_LEN;
                        binning.add(new int[]{
                                data[o] & 0xFF,      // minute offset
                                data[o + 1] & 0xFF,  // heart rate
                                data[o + 2] & 0xFF,  // min
                                data[o + 3] & 0xFF,  // max
                        });
                    }
                    pos = entriesStart + count;
                    break;
                }
                default:
                    // The vendor parser abandons the whole record on an unknown
                    // tag; matching that keeps a stray 0x0a from parsing.
                    return null;
            }
        }
        // The tag walk must consume the body exactly, and a real record must
        // carry a sane start time - both make a false positive vanishingly
        // unlikely without needing to know the surrounding record framing.
        if (pos != bodyEnd || startTime1990 < MIN_START_1990 || startTime1990 > MAX_START_1990) {
            return null;
        }

        List<Sample> samples = new ArrayList<>(binning.size());
        for (int[] b : binning) {
            int hr = b[1];
            if (hr < MIN_HR || hr > MAX_HR) {
                continue; // e.g. a 0 "no reading" minute
            }
            int ts = (int) (startTime1990 + Fit3HealthPedometerRecord.EPOCH_1990_OFFSET_SECONDS + b[0] * 60L);
            samples.add(new Sample(ts, hr, b[2], b[3]));
        }
        return new Fit3HealthHeartRateRecord(samples, bodyEnd);
    }

    /**
     * Scan {@code data} for every heart-rate record's samples from {@code
     * fromOffset} onward. Scans (rather than walking the surrounding record
     * structure) because the other record types sharing the stream are mostly
     * undecoded, so the next marker's position can't be computed; {@link
     * #parse}'s strict validation makes a false positive from a stray {@code
     * 0x0a} byte effectively impossible.
     */
    public static List<Sample> findAll(byte[] data, int fromOffset) {
        List<Sample> all = new ArrayList<>();
        int i = Math.max(0, fromOffset);
        while (i < data.length) {
            if ((data[i] & 0xFF) == MARKER) {
                Fit3HealthHeartRateRecord record = parse(data, i);
                if (record != null) {
                    all.addAll(record.samples);
                    i = record.endOffset;
                    continue;
                }
            }
            i++;
        }
        return all;
    }

    private static int readU32(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16) | ((data[off + 3] & 0xFF) << 24);
    }
}
