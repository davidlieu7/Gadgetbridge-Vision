/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Cycle tracking for the Mi Band 5 and similar Huami bands. Uses the menstruation module (0x0005)
 * on the chunked 2021 characteristics, as decoded from Zepp Life.
 */
public class HuamiCycleTracking {
    private static final Logger LOG = LoggerFactory.getLogger(HuamiCycleTracking.class);

    public static final short ENDPOINT = 0x0005;

    public static final byte CMD_TOTAL_RECORD_GET = 0x01;
    public static final byte CMD_TOTAL_RECORD_RET = 0x02;
    public static final byte CMD_TOTAL_RECORD_SET = 0x03;
    public static final byte CMD_SWITCH_SET = 0x07;

    public static final short SWITCH_SMART_PREDICT = 0x01;
    public static final short SWITCH_DEVICE_NOTIFY = 0x02;
    public static final short SWITCH_PERIOD_REMINDER = 0x04;
    public static final short SWITCH_OVULATION_REMINDER = 0x08;

    private static final byte STATE_IN_PERIOD = 1;
    private static final byte STATE_NOT_IN_PERIOD = 2;

    private static final int MAX_REMINDER_DAYS = 5;

    public static byte[] encodeTotalRecordRequest() {
        return new byte[]{CMD_TOTAL_RECORD_GET};
    }

    /**
     * @return the data format version reported by the band, or null if the response is invalid
     */
    @Nullable
    public static Byte decodeVersion(final byte[] payload) {
        if (payload.length < 3 || payload[0] != CMD_TOTAL_RECORD_RET || payload[1] != 0x01) {
            LOG.warn("Unexpected total record response");
            return null;
        }
        return payload[2];
    }

    /**
     * Encodes the last period and the cycle settings, from which the band predicts the next periods.
     * Each record is one day starting at the beginning of the last period.
     */
    public static byte[] encodeTotalRecord(final byte version,
                                           final int cycleLength,
                                           final int periodLength,
                                           final LocalDate lastPeriodStart) {
        final int lastPeriodBegin = toDeviceDay(lastPeriodStart);
        final int lastPeriodEnd = toDeviceDay(lastPeriodStart.plusDays(periodLength - 1));
        final int recordTime = (int) (System.currentTimeMillis() / 1000);
        final int recordCount = periodLength + 1;

        final ByteBuffer buf = ByteBuffer.allocate(18 + recordCount * 5).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_TOTAL_RECORD_SET);
        buf.put(version);
        buf.put((byte) cycleLength);
        buf.put((byte) periodLength);
        buf.putInt(lastPeriodBegin);
        buf.putInt(lastPeriodEnd);
        buf.putInt(lastPeriodBegin); // first record day
        buf.putShort((short) recordCount);
        for (int i = 0; i < periodLength; i++) {
            buf.put(STATE_IN_PERIOD);
            buf.putInt(recordTime);
        }
        // the day after the period, marks the period as finished
        buf.put(STATE_NOT_IN_PERIOD);
        buf.putInt(recordTime);

        return buf.array();
    }

    public static byte[] encodeSwitch(final short flag, final boolean enable) {
        final ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SWITCH_SET);
        buf.putShort(flag);
        buf.put((byte) (enable ? 0x01 : 0x00));
        return buf.array();
    }

    /**
     * @param days 0 to disable the reminder, up to 5 days before
     */
    public static byte[] encodeReminder(final short flag, final int days) {
        final int clampedDays = Math.max(0, Math.min(days, MAX_REMINDER_DAYS));
        final ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SWITCH_SET);
        buf.putShort(flag);
        buf.put((byte) ((clampedDays > 0 ? 0x80 : 0x00) | clampedDays));
        return buf.array();
    }

    /**
     * The band expects the local day as seconds since epoch, as if it was UTC.
     */
    private static int toDeviceDay(final LocalDate date) {
        return (int) date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }
}
