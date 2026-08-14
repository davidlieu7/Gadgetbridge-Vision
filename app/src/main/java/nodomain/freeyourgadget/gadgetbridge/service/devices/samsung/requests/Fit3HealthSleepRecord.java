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
 * {@code SLEEP} - outer marker {@code 0x10}, the band's summary of one sleep
 * session. Framed exactly like {@link Fit3HealthHeartRateRecord}: the marker
 * introduces a series, then back-to-back {@code [hasMore:1][len:4 LE][body]}
 * records that don't repeat it.
 *
 * <p>The body is a {@code [tag][value]} walk carrying, among other things, a
 * session identifier in a fixed 16-byte field ({@code 0x75}; in practice
 * NUL-padded ASCII digits) and the session's start ({@code 0x01}) and end
 * ({@code 0x02}) as seconds since the 1990 epoch (see
 * {@link Fit3HealthPedometerRecord#EPOCH_1990_OFFSET_SECONDS}). Tag
 * {@code 0x03} is the UTC offset the band recorded in, in milliseconds. The
 * rest: {@code 0x53} sleep score (1 byte),
 * {@code 0x56} result type (1 = no stages, 2 = not enough heart-rate data,
 * 3 = continuous heart rate off, 4 = staged), {@code 0x50} efficiency
 * (float), {@code 0x51} has-sleep-data, {@code 0x52} a second 16-byte id,
 * {@code 0x28} total REM time, {@code 0x1c}..{@code 0x25} ten 2-byte factors.
 *
 * <p>This is the session only - no per-stage hypnogram. The band pairs each
 * of these with a much larger per-minute record (outer marker {@code 0x8b})
 * whose {@code 0x53} tag repeats this record's identifier; that one holds the
 * raw per-minute measurements the vendor app feeds to its own on-phone
 * scoring model to produce stages, and is not decoded here. So the sleep
 * window is recoverable from the band, the staging is not.
 *
 * <p>Confidence: decoded from a live Gadgetbridge capture where the band
 * pushed one of these unprompted (94-byte push, 56-byte body), yielding a
 * plausible 6 h 08 m session. The captured sessions carry no score and
 * result type 1, so the score and the staged result type are not yet seen
 * on the wire.
 */
public final class Fit3HealthSleepRecord {

    public static final int MARKER = 0x10;

    private static final int RECORD_HEADER_LEN = 5; // hasMore(1) + len(4)
    private static final int MAX_RECORD_LEN = 4096;

    private static final int TAG_START_TIME = 0x01;
    private static final int TAG_END_TIME = 0x02;
    private static final int TAG_TIME_OFFSET = 0x03;
    private static final int TAG_UPDATE_TIME = 0x05;
    private static final int TAG_FACTOR_FIRST = 0x1c;  // 0x1c..0x25: ten 2-byte factors
    private static final int TAG_FACTOR_LAST = 0x25;
    private static final int TAG_TOTAL_REM = 0x28;
    private static final int TAG_EFFICIENCY = 0x50;   // 4-byte float
    private static final int TAG_HAS_SLEEP_DATA = 0x51;
    private static final int TAG_COMBINED_ID = 0x52;  // 16 bytes
    private static final int TAG_SCORE = 0x53;
    private static final int TAG_RESULT_TYPE = 0x56;
    private static final int TAG_CREATE_VERSION = 0x97;
    private static final int TAG_MODIFY_VERSION = 0x99;
    private static final int TAG_UID = 0x75;          // 16 bytes
    private static final int ID_LEN = 16;

    // Same plausibility window the heart-rate records are checked against:
    // seconds-since-1990 for wall-clock years ~2020..2035.
    private static final long MIN_START_1990 = 946684800L;
    private static final long MAX_START_1990 = 1420070400L;

    /** Longest session accepted, as a guard against a mis-parsed end time. */
    private static final int MAX_DURATION_SECONDS = 24 * 3600;

    /** One sleep session. */
    public static final class Session {
        /** Unix epoch seconds. */
        public final int startTime;
        /** Unix epoch seconds. */
        public final int endTime;
        /** The band's own identifier for the session, or {@code null}. */
        public final String uid;
        /** Sleep score 0..100, or -1 if the record carries none. */
        public final int score;
        /** Result type (see the class docs), or -1 if the record carries none. */
        public final int resultType;

        private Session(int startTime, int endTime, String uid, int score, int resultType) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.uid = uid;
            this.score = score;
            this.resultType = resultType;
        }

        public int durationSeconds() {
            return endTime - startTime;
        }

        @Override
        public String toString() {
            return "Sleep{start=" + startTime + ", end=" + endTime
                    + ", duration=" + durationSeconds() + "s, uid=" + uid
                    + ", score=" + score + ", resultType=" + resultType + "}";
        }
    }

    private final List<Session> sessions;
    /** One past the series' last byte, for scanning past it. */
    public final int endOffset;

    private Fit3HealthSleepRecord(List<Session> sessions, int endOffset) {
        this.sessions = sessions;
        this.endOffset = endOffset;
    }

    /**
     * Parse the series of sleep records introduced by the marker byte at
     * {@code markerOffset}.
     *
     * @return the parsed series, or {@code null} if there isn't a well-formed
     *     one there - a caller scanning a mixed stream should treat that as
     *     "keep looking".
     */
    public static Fit3HealthSleepRecord parse(byte[] data, int markerOffset) {
        if (markerOffset < 0 || markerOffset >= data.length
                || (data[markerOffset] & 0xFF) != MARKER) {
            return null;
        }
        List<Session> sessions = new ArrayList<>();
        int pos = markerOffset + 1;
        while (true) {
            if (pos + RECORD_HEADER_LEN > data.length) {
                return null;
            }
            int hasMore = data[pos] & 0xFF;
            if (hasMore != 0 && hasMore != 1) {
                return null;
            }
            int len = readU32(data, pos + 1);
            if (len < 2 || len > MAX_RECORD_LEN || pos + RECORD_HEADER_LEN + len > data.length) {
                return null;
            }
            int bodyStart = pos + RECORD_HEADER_LEN;
            Session session = parseBody(data, bodyStart, bodyStart + len);
            if (session == null) {
                return null;
            }
            sessions.add(session);
            pos = bodyStart + len;
            if (hasMore == 0) {
                return new Fit3HealthSleepRecord(sessions, pos);
            }
        }
    }

    /** @return the session in this body, or {@code null} if it isn't well-formed. */
    private static Session parseBody(byte[] data, int bodyStart, int bodyEnd) {
        long start1990 = -1;
        long end1990 = -1;
        String uid = null;
        int score = -1;
        int resultType = -1;
        int pos = bodyStart;
        while (pos < bodyEnd) {
            int tag = data[pos] & 0xFF;
            pos++;
            switch (tag) {
                case TAG_START_TIME:
                case TAG_END_TIME:
                case TAG_TIME_OFFSET:
                case TAG_UPDATE_TIME:
                case TAG_TOTAL_REM:
                case TAG_EFFICIENCY:
                case TAG_CREATE_VERSION:
                case TAG_MODIFY_VERSION:
                    if (pos + 4 > bodyEnd) {
                        return null;
                    }
                    if (tag == TAG_START_TIME) {
                        start1990 = readU32(data, pos) & 0xFFFFFFFFL;
                    } else if (tag == TAG_END_TIME) {
                        end1990 = readU32(data, pos) & 0xFFFFFFFFL;
                    }
                    pos += 4;
                    break;
                case TAG_HAS_SLEEP_DATA:
                case TAG_SCORE:
                case TAG_RESULT_TYPE:
                    if (pos + 1 > bodyEnd) {
                        return null;
                    }
                    if (tag == TAG_SCORE) {
                        score = data[pos] & 0xFF;
                    } else if (tag == TAG_RESULT_TYPE) {
                        resultType = data[pos] & 0xFF;
                    }
                    pos += 1;
                    break;
                case TAG_UID:
                case TAG_COMBINED_ID: {
                    if (pos + ID_LEN > bodyEnd) {
                        return null;
                    }
                    if (tag == TAG_UID) {
                        int end = pos;
                        while (end < pos + ID_LEN && data[end] != 0) {
                            end++;
                        }
                        uid = new String(data, pos, end - pos, java.nio.charset.StandardCharsets.US_ASCII);
                    }
                    pos += ID_LEN;
                    break;
                }
                default:
                    if (tag >= TAG_FACTOR_FIRST && tag <= TAG_FACTOR_LAST) {
                        if (pos + 2 > bodyEnd) {
                            return null;
                        }
                        pos += 2;
                        break;
                    }
                    // As in the heart-rate parser: bail on anything unexpected
                    // rather than guess a width, so a stray 0x10 byte in some
                    // other record's payload can't parse as a sleep session.
                    return null;
            }
        }
        if (pos != bodyEnd
                || start1990 < MIN_START_1990 || start1990 > MAX_START_1990
                || end1990 <= start1990 || end1990 - start1990 > MAX_DURATION_SECONDS) {
            return null;
        }
        int epoch = Fit3HealthPedometerRecord.EPOCH_1990_OFFSET_SECONDS;
        return new Session((int) (start1990 + epoch), (int) (end1990 + epoch), uid, score, resultType);
    }

    /** Scan {@code data} for every sleep session from {@code fromOffset} onward. */
    public static List<Session> findAll(byte[] data, int fromOffset) {
        List<Session> all = new ArrayList<>();
        int i = Math.max(0, fromOffset);
        while (i < data.length) {
            if ((data[i] & 0xFF) == MARKER) {
                Fit3HealthSleepRecord record = parse(data, i);
                if (record != null) {
                    all.addAll(record.sessions);
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
