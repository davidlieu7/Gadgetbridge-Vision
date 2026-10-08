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

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Menstrual-cycle data pushed to the band for its Cycle Tracking tile, as a
 * phone-to-band {@code SYNC_DATA} (MSG_ID 2) on {@code CH_HEALTH} (0x0a). The
 * phone computes everything; the band only
 * displays it and never sends any of it back.
 *
 * <pre>
 *   82 [record count] e0 [SEQUENCE_NUMBER:2 LE]
 *      05 [len:1] ...device info...
 *      18 { [hasMore] 75 [uuid:16] 76 [type] 05 [time:4] }*      deletions
 *      7b { [hasMore] [len:4] [body] }*                          factor
 *      71 { ... }*                                               flow, one per day
 *      77 { ... }*                                               predictions
 *      74 { ... }*                                               profile
 * </pre>
 *
 * <p>Each kind of record appears once as a marker followed by a has-more
 * series, and counts once towards the record count. Bodies are tag walks with
 * fixed widths: a 16-byte raw uuid ({@code 0x75}), four-byte times and
 * numbers, one-byte enums.
 *
 * <p>Two kinds of date are in use. Flow days and the profile's last period
 * start are local midnights, as epoch seconds; prediction dates are UTC
 * midnights of the same calendar day. A capture taken at UTC+8 shows both
 * side by side, and this follows it.
 *
 * <p>Every record has its own random uuid. Replacing data means deleting the
 * old uuids and sending new records; the capture does exactly that when the
 * factor changes, even when the predicted dates stay the same.
 *
 * <p>Confidence: the framing and every field are matched byte for byte
 * against the capture ({@code Fit3HealthCycleTest}). The prediction rule in
 * {@link #predict} reproduces that capture's single cycle length only.
 */
public final class Fit3HealthCycle {

    public static final int CHANNEL = SapProtocol.CH_HEALTH;

    /** Device-specific preference keys, registered by the settings customizer. */
    public static final String PREF_ENABLE = "samsung_fit3_cycle_enable";
    public static final String PREF_LAST_START = "samsung_fit3_cycle_last_start";
    public static final String PREF_PERIOD_DAYS = "samsung_fit3_cycle_period_days";
    public static final String PREF_CYCLE_DAYS = "samsung_fit3_cycle_days";
    public static final String PREF_FACTOR = "samsung_fit3_cycle_factor";

    public static final int DEFAULT_PERIOD_DAYS = 5;
    public static final int DEFAULT_CYCLE_DAYS = 28;
    public static final int MSG_ID = Fit3HealthSyncData.MSG_ID;

    private static final int REQ_HEADER = 0x82;
    private static final int PARAM_SEQUENCE_NUMBER = 0xe0;

    private static final int MARKER_DEVICE_INFO = 0x05;
    private static final int MARKER_DELETED = 0x18;
    private static final int MARKER_FLOW = 0x71;
    private static final int MARKER_PROFILE = 0x74;
    private static final int MARKER_PREDICTION = 0x77;
    private static final int MARKER_FACTOR = 0x7b;

    private static final int TAG_START_TIME = 0x01;
    private static final int TAG_END_TIME = 0x02;
    private static final int TAG_TIME_OFFSET = 0x03;
    private static final int TAG_UPDATE_TIME = 0x05;
    private static final int TAG_UUID = 0x75;
    private static final int TAG_DELETED_TYPE = 0x76;

    /** Deletion type codes for the records this sends. */
    public static final int TYPE_FLOW = 7;
    public static final int TYPE_PROFILE = 10;
    public static final int TYPE_PREDICTION = 13;
    public static final int TYPE_FACTOR = 15;

    /** Factor values. There is no 2. */
    public static final int FACTOR_NONE = 0;
    public static final int FACTOR_PREGNANCY = 1;
    public static final int FACTOR_MENOPAUSE = 3;
    public static final int FACTOR_BREASTFEEDING = 4;

    public static final int UUID_LENGTH = 16;

    /** Predictions sent at a time: the current cycle and the next four. */
    public static final int PREDICTION_COUNT = 5;

    /** The fixed trailer values every captured prediction carries. */
    private static final int PREDICTION_STATUS = 4;
    private static final int PREDICTION_SOURCE = 1;
    private static final int PREDICTION_VERSION = 1;

    /** Flow amount and spotting as logged in the capture. */
    private static final int FLOW_AMOUNT = 1;
    private static final int FLOW_SPOTTING = 0;

    private static final int FACTOR_METHOD_UNSET = 0xff;

    private Fit3HealthCycle() {
    }

    /** One record to remove from the band. */
    public static final class Deletion {
        public final byte[] uuid;
        public final int type;

        public Deletion(byte[] uuid, int type) {
            this.uuid = uuid;
            this.type = type;
        }
    }

    /** A logged period day. */
    public static final class Flow {
        public final byte[] uuid;
        public final LocalDate day;

        public Flow(byte[] uuid, LocalDate day) {
            this.uuid = uuid;
            this.day = day;
        }
    }

    /** One predicted cycle. */
    public static final class Prediction {
        public final byte[] uuid;
        public final LocalDate periodStart;
        public final LocalDate periodEnd;
        public final LocalDate fertileStart;
        public final LocalDate fertileEnd;
        public final LocalDate ovulation;
        /** Set on the first future cycle only, as in the capture. */
        @Nullable
        public final LocalDate originalPrediction;

        public Prediction(byte[] uuid, LocalDate periodStart, LocalDate periodEnd,
                          LocalDate fertileStart, LocalDate fertileEnd, LocalDate ovulation,
                          @Nullable LocalDate originalPrediction) {
            this.uuid = uuid;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
            this.fertileStart = fertileStart;
            this.fertileEnd = fertileEnd;
            this.ovulation = ovulation;
            this.originalPrediction = originalPrediction;
        }
    }

    /** Everything one push carries. Lists may be empty, the rest may be null. */
    public static final class Push {
        public final List<Deletion> deletions = new ArrayList<>();
        @Nullable
        public byte[] factorUuid;
        public int factor;
        public final List<Flow> flows = new ArrayList<>();
        public final List<Prediction> predictions = new ArrayList<>();
        @Nullable
        public byte[] profileUuid;
        public LocalDate lastPeriodStart;
        public int periodDays;
        public int cycleDays;

        public boolean isEmpty() {
            return deletions.isEmpty() && factorUuid == null && flows.isEmpty()
                    && predictions.isEmpty() && profileUuid == null;
        }
    }

    /** Whether the band should show predictions under this factor. */
    public static boolean factorAllowsPredictions(int factor) {
        return factor == FACTOR_NONE || factor == FACTOR_BREASTFEEDING;
    }

    /**
     * Predict {@link #PREDICTION_COUNT} cycles, starting with the one that
     * contains {@code today}. A cycle starting on day {@code s} bleeds from
     * {@code s} to {@code s + period - 1}, ovulates 13 days before the next
     * start, and is fertile for the five days before ovulation and the day
     * itself.
     */
    public static List<Prediction> predict(LocalDate lastStart, int periodDays, int cycleDays,
                                           LocalDate today, Supplier<byte[]> uuids) {
        long first = Math.max(0, ChronoUnit.DAYS.between(lastStart, today) / cycleDays);
        List<Prediction> out = new ArrayList<>(PREDICTION_COUNT);
        for (int i = 0; i < PREDICTION_COUNT; i++) {
            LocalDate start = lastStart.plusDays((first + i) * cycleDays);
            LocalDate ovulation = start.plusDays(cycleDays - 13);
            out.add(new Prediction(uuids.get(), start, start.plusDays(periodDays - 1),
                    ovulation.minusDays(5), ovulation, ovulation, i == 1 ? start : null));
        }
        return out;
    }

    /**
     * @param sequenceNumber the phone's own message counter (see
     *     {@link Fit3HealthUserProfile#buildRequest}).
     * @param now seconds since the channel's 1990 epoch, used for the update
     *     time of every record and the device-info record.
     * @param zone the phone's zone: local midnights and the time offset.
     */
    public static byte[] buildRequest(int sequenceNumber, int now, ZoneId zone, Push push) {
        int offsetMs = zone.getRules().getOffset(Instant.ofEpochSecond(
                (long) now + Fit3HealthPedometerRecord.EPOCH_1990_OFFSET_SECONDS)).getTotalSeconds() * 1000;
        int records = 2;  // sequence number and device info

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (!push.deletions.isEmpty()) {
            records++;
            body.write(MARKER_DELETED);
            for (int i = 0; i < push.deletions.size(); i++) {
                Deletion d = push.deletions.get(i);
                ByteBuffer b = le(25);
                b.put((byte) (i < push.deletions.size() - 1 ? 1 : 0));
                b.put((byte) TAG_UUID).put(d.uuid);
                b.put((byte) TAG_DELETED_TYPE).put((byte) d.type);
                b.put((byte) TAG_UPDATE_TIME).putInt(now);
                write(body, b);
            }
        }
        if (push.factorUuid != null) {
            records++;
            body.write(MARKER_FACTOR);
            ByteBuffer b = le(41);
            b.put((byte) TAG_UUID).put(push.factorUuid);
            b.put((byte) TAG_UPDATE_TIME).putInt(now);
            b.put((byte) TAG_START_TIME).putInt(now);
            b.put((byte) TAG_END_TIME).putInt(0);
            b.put((byte) TAG_TIME_OFFSET).putInt(offsetMs);
            b.put((byte) 0x10).put((byte) push.factor);
            b.put((byte) 0x11).put((byte) FACTOR_METHOD_UNSET);
            putSeriesEntry(body, false, b);
        }
        if (!push.flows.isEmpty()) {
            records++;
            body.write(MARKER_FLOW);
            for (int i = 0; i < push.flows.size(); i++) {
                Flow f = push.flows.get(i);
                ByteBuffer b = le(36);
                b.put((byte) TAG_UUID).put(f.uuid);
                b.put((byte) TAG_UPDATE_TIME).putInt(now);
                b.put((byte) TAG_START_TIME).putInt(localMidnight(f.day, zone));
                b.put((byte) TAG_TIME_OFFSET).putInt(offsetMs);
                b.put((byte) 0x10).put((byte) FLOW_AMOUNT);
                b.put((byte) 0x11).put((byte) FLOW_SPOTTING);
                putSeriesEntry(body, i < push.flows.size() - 1, b);
            }
        }
        if (!push.predictions.isEmpty()) {
            records++;
            body.write(MARKER_PREDICTION);
            for (int i = 0; i < push.predictions.size(); i++) {
                Prediction p = push.predictions.get(i);
                ByteBuffer b = le(84);
                b.put((byte) TAG_UUID).put(p.uuid);
                b.put((byte) TAG_UPDATE_TIME).putInt(now);
                b.put((byte) TAG_START_TIME).putInt(0);
                b.put((byte) 0x10).putInt(push.periodDays);
                b.put((byte) 0x11).putInt(push.cycleDays);
                b.put((byte) 0x12).putInt(utcMidnight(p.periodStart));
                b.put((byte) 0x13).putInt(utcMidnight(p.periodEnd));
                b.put((byte) 0x14).putInt(utcMidnight(p.fertileStart));
                b.put((byte) 0x15).putInt(utcMidnight(p.fertileEnd));
                b.put((byte) 0x16).putInt(utcMidnight(p.ovulation));
                b.put((byte) 0x17).putInt(p.originalPrediction == null ? 0 : utcMidnight(p.originalPrediction));
                b.put((byte) 0x18).put((byte) PREDICTION_STATUS);
                b.put((byte) 0x19).putInt(PREDICTION_SOURCE);
                b.put((byte) 0x1a).putInt(PREDICTION_VERSION);
                b.put((byte) 0x1b).putInt(0);  // no unrealistic-period dates
                putSeriesEntry(body, i < push.predictions.size() - 1, b);
            }
        }
        if (push.profileUuid != null) {
            records++;
            body.write(MARKER_PROFILE);
            ByteBuffer b = le(37);
            b.put((byte) TAG_UUID).put(push.profileUuid);
            b.put((byte) TAG_UPDATE_TIME).putInt(now);
            b.put((byte) 0x10).putInt(localMidnight(push.lastPeriodStart, zone));
            b.put((byte) 0x11).putInt(push.periodDays);
            b.put((byte) 0x12).putInt(push.cycleDays);
            putSeriesEntry(body, false, b);
        }

        ByteBuffer head = le(2 + 3 + 27);
        head.put((byte) REQ_HEADER).put((byte) records);
        head.put((byte) PARAM_SEQUENCE_NUMBER).putShort((short) sequenceNumber);
        // Device-info record, the same one Fit3HealthUserProfile leads with.
        head.put((byte) MARKER_DEVICE_INFO).put((byte) 25);
        head.put(new byte[]{0x01, 0x00, 0x02, 0x00, 0x00, 0x03, 0x04, 0x33});
        head.put((byte) 0x04).putInt(now);
        head.put((byte) 0x05).putInt(now);
        head.put((byte) 0x06).putInt(now);
        head.put((byte) 0x07).put((byte) 0x01);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, head);
        byte[] rest = body.toByteArray();
        out.write(rest, 0, rest.length);
        return out.toByteArray();
    }

    private static void putSeriesEntry(ByteArrayOutputStream out, boolean hasMore, ByteBuffer body) {
        ByteBuffer b = le(5);
        b.put((byte) (hasMore ? 1 : 0)).putInt(body.capacity());
        write(out, b);
        write(out, body);
    }

    static int localMidnight(LocalDate day, ZoneId zone) {
        return (int) (day.atStartOfDay(zone).toEpochSecond()
                - Fit3HealthPedometerRecord.EPOCH_1990_OFFSET_SECONDS);
    }

    static int utcMidnight(LocalDate day) {
        return (int) (day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                - Fit3HealthPedometerRecord.EPOCH_1990_OFFSET_SECONDS);
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void write(ByteArrayOutputStream out, ByteBuffer b) {
        out.write(b.array(), 0, b.capacity());
    }
}
