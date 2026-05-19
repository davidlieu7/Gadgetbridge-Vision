/*  Copyright (C) 2026 Ariel Saghiv

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
package nodomain.freeyourgadget.gadgetbridge.devices.vring;

import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

/**
 * Protocol constants for the VRing R26 smart ring (MO YOUNG LTD "Da Ring" app).
 * BLE service 0xFDDA (registered to "MHCS").
 * Packet framing: {@code FD DA 10 <total_len> <category> <command> <payload...>}.
 */
public final class VRingR26Constants {

    private VRingR26Constants() { }

    public static final UUID UUID_SERVICE =
            UUID.fromString("0000fdda-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_NOTIFY =
            UUID.fromString("0000fdd3-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_NOTIFY_ALT =
            UUID.fromString("0000fdd1-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_WRITE_DEFAULT =
            UUID.fromString("0000fdd2-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_WRITE_F1 =
            UUID.fromString("0000fdd5-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_WRITE_F2 =
            UUID.fromString("0000fdd6-0000-1000-8000-00805f9b34fb");

    public static final byte MAGIC_0 = (byte) 0xFD;
    public static final byte MAGIC_1 = (byte) 0xDA;
    public static final byte MAGIC_2 = (byte) 0x10;
    public static final int HEADER_LEN = 6;

    public static final int CAT_SET = 1;
    public static final int CAT_QUERY = 2;
    public static final int CAT_CTRL = 3;
    public static final int CAT_FILE = 4;
    public static final int CAT_SYS = 5;
    public static final int CAT_PWR = 6;
    public static final int CAT_DEVMETA = 7;
    public static final int CAT_FIND = 9;

    public static final int CMD_SET_USER_INFO = 0;
    public static final int CMD_SET_TIME = 1;
    public static final int CMD_SET_DAILY_GOALS = 2;
    public static final int CMD_START_SPO2_MEASURE = 8;
    public static final int CMD_START_HR_MEASURE = 9;
    public static final int CMD_SET_NOTIFY_MESSAGE = 18;

    public static final int CMD_QUERY_DEVICE_INFO = 0;
    public static final int CMD_QUERY_SERIAL = 1;
    public static final int CMD_QUERY_HR_LATEST = 9;
    public static final int CMD_QUERY_HRV_LATEST = 10;
    public static final int CMD_QUERY_SPO2_LATEST = 11;
    public static final int CMD_QUERY_FIRMWARE_TABLE = 12;
    public static final int CMD_QUERY_HISTORY_STEPS = 13;
    public static final int CMD_QUERY_HISTORY_DAY = 14;
    public static final int CMD_QUERY_TIMING_HR = 15;
    public static final int CMD_QUERY_HR_DETAIL = 17;
    public static final int CMD_QUERY_HISTORY_STEPS_DETAIL = 18;
    public static final int CMD_QUERY_STEP_RECORD = 20;
    public static final int CMD_QUERY_SLEEP = 25;
    public static final int CMD_QUERY_STRESS_HISTORY = 32;
    public static final int CMD_QUERY_SPO2_CURRENT = 38;

    public static final int CMD_CTRL_SHUTDOWN = 1;
    public static final int CMD_VIBRATION_LEVEL = 0;
    public static final int CMD_FIND_DEVICE = 2;

    public static final int HEART_RATE_MIN_BPM = 30;
    public static final int HEART_RATE_MAX_BPM = 220;
    public static final int STEP_BUCKET_SECONDS = 30 * 60;
    public static final int STEP_BUCKETS_PER_DAY = 48;
    public static final float FULL_INTENSITY_STEPS_PER_MINUTE = 135.0f;

    public enum RawActivityKind {
        UNKNOWN(0, ActivityKind.UNKNOWN),
        ACTIVITY(1, ActivityKind.ACTIVITY),
        NOT_WORN(100, ActivityKind.NOT_WORN);

        private final int rawValue;
        private final ActivityKind activityKind;

        RawActivityKind(final int rawValue, final ActivityKind activityKind) {
            this.rawValue = rawValue;
            this.activityKind = activityKind;
        }

        public int getRawValue() {
            return rawValue;
        }

        public ActivityKind getActivityKind() {
            return activityKind;
        }

        public static RawActivityKind fromRawValue(final int rawValue) {
            for (final RawActivityKind kind : values()) {
                if (kind.rawValue == rawValue) {
                    return kind;
                }
            }
            return UNKNOWN;
        }

        public static RawActivityKind fromActivityKind(final ActivityKind activityKind) {
            for (final RawActivityKind kind : values()) {
                if (kind.activityKind == activityKind) {
                    return kind;
                }
            }
            return UNKNOWN;
        }
    }
}
