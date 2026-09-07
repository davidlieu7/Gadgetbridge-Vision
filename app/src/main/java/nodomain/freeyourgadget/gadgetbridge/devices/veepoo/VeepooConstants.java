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
package nodomain.freeyourgadget.gadgetbridge.devices.veepoo;

import java.util.UUID;

/**
 * VeePoo "G Band" (KR96 Pro, com.vpgband.app) protocol constants.
 * NORDIC platform, deviceNumber 5931.
 *
 * Transport: one GATT write = one command, fixed {@link #PACKET_SIZE} bytes
 * (zero-padded); {@code byte[0]} = opcode. Responses are same-size
 * notifications on the notify characteristic carrying the same opcode at [0].
 */
public final class VeepooConstants {

    private VeepooConstants() {
    }

    // GATT channels (VPProfile, lf/a.java)
    public static final UUID UUID_SERVICE_F008 =
            UUID.fromString("F0080001-0451-4000-B000-000000000000");
    /** Main service — notify characteristic (device -> app). */
    public static final UUID UUID_CHARACTERISTIC_F008_DATA =
            UUID.fromString("F0080002-0451-4000-B000-000000000000");
    /** Main service — write characteristic (app -> device). */
    public static final UUID UUID_CHARACTERISTIC_F008_CMD =
            UUID.fromString("F0080003-0451-4000-B000-000000000000");

    // Raw sensor / light-origin channel (live PPG/ECG streaming; not used for history)
    public static final UUID UUID_SERVICE_F002 =
            UUID.fromString("F0020001-0451-4000-B000-000000000000");
    public static final UUID UUID_CHARACTERISTIC_F002_DATA =
            UUID.fromString("F0020002-0451-4000-B000-000000000000");

    public static final int PACKET_SIZE = 20;
    /** Pairing password used in the 0xA1 confirm command. Factory default is "no password" (0). */
    public static final int DEFAULT_PASSWORD = 0;
    /** Negotiated MTU requested right after connect. */
    public static final int MTU = 248;

    // ---- Opcodes (unsigned byte[0]) --------------------------------------
    public static final byte OP_READ_BATTERY = (byte) 0xA0;
    /** Password confirm + set phone time (first post-connect command). */
    public static final byte OP_PWD_CONFIRM = (byte) 0xA1;
    /** Set device time. */
    public static final byte OP_SYNC_TIME = (byte) 0xA5;
    /** Device capability report — pushed by the device only, no command built. */
    public static final byte OP_DEVICE_FUNCTION = (byte) 0xA7;
    /** Live step counter of the current activity. */
    public static final byte OP_CURRENT_STEPS = (byte) 0xA8;
    /** Personal info + goals (height/weight/age/sex, step & sleep aim). */
    public static final byte OP_PERSON_INFO = (byte) 0xA3;
    /** Daily 5-minute activity samples, protocol v1. */
    public static final byte OP_ORIGIN_V1 = (byte) 0xD1;
    /** Daily 5-minute data, Origin3/DF framing (protocol v3/v5). */
    public static final byte OP_ORIGIN_DF = (byte) 0xDF;
    /** Workout (sport) history read: [0xD4][dayIndex]; one workout session per day. */
    public static final byte OP_SPORT_ORIGIN = (byte) 0xD4;
    /** Real-time heart-rate stream start/stop. */
    public static final byte OP_HR_REALTIME = (byte) 0xD0;
    /** Real-time SpO2 stream start/stop. */
    public static final byte OP_SPO2_REALTIME = (byte) 0x80;
    /** Real-time stress stream start/stop. */
    public static final byte OP_STRESS_REALTIME = (byte) 0x82;
    /** Real-time body-temperature stream start/stop. */
    public static final byte OP_TEMP_REALTIME = (byte) 0x87;
    /** Night sleep read: [0xE0][dayIndex]. */
    public static final byte OP_SLEEP_READ = (byte) 0xE0;
    /** Production / firmware info. */
    public static final byte OP_PRODUCTION_INFO = (byte) 0xFC;
    /**
     * Sport (workout) control: [0xDA][action][sportType LE16][control]. Action 0 opens the live
     * control report, 1 sets control (start/pause/resume/stop), 2 reads info. Responses are an
     * ack ([1]==1, [2]==1 success) or a chunked little-endian TLV live report.
     */
    public static final byte OP_SPORT_CONTROL = (byte) 0xDA;

    // ---- 0xDA sport control values ----------------------------------------
    /** [4] for the set-control action: start the workout. */
    public static final int SPORT_CTRL_START = 1;
    /** [4] for the set-control action: stop the workout (sport type may be 0/NONE). */
    public static final int SPORT_CTRL_STOP = 4;

    // ---- Phone-initiated workout config keys (onSendConfiguration) --------
    /** Prefix of the "start workout" key; the numeric suffix is the ESportType value. */
    public static final String CONFIG_WORKOUT_START_PREFIX = "veepoo_workout_start_";
    /** Stop the running workout. */
    public static final String CONFIG_WORKOUT_STOP = "veepoo_workout_stop";

    // ---- Activity rawKind (stored in VeepooActivitySample.rawKind) -------
    // Mapped to ActivityKind by VeepooActivitySampleProvider.normalizeType().
    public static final int RAW_KIND_NOT_MEASURED = -1;
    public static final int RAW_KIND_ACTIVITY = 1;
    public static final int RAW_KIND_DEEP_SLEEP = 2;
    public static final int RAW_KIND_LIGHT_SLEEP = 3;
    public static final int RAW_KIND_REM_SLEEP = 4;
    public static final int RAW_KIND_AWAKE_SLEEP = 5;

    // ---- A7 device_function capability package offsets --------------------
    // Package id is at byte[19] of each pushed 20-byte packet.
    public static final int CAP_PACKAGE_ID_OFFSET = 19;
    /** Package 2: days of data kept on the watch; 0 -> default WATCH_DAY_DEFAULT. */
    public static final int CAP_PKG2_WATCH_DAY = 2;
    /** Package 2: origin protocol version; 3 or 5 -> Origin DF (0xDF), else v1 (0xD1). */
    public static final int CAP_PKG2_ORIGIN_PROTOCOL_VERSION = 10;
    /** Package 2: precision-sleep tag / sleep tag; non-zero -> precise sleep supported. */
    public static final int CAP_PKG2_PRECISE_SLEEP_TAG = 13;
    /** Package 2: days of workout history kept; 0 -> no workout history support. */
    public static final int CAP_PKG2_SPORT_MODEL_DAY = 5;
    /** Package 2: multi-sport mode type; 4/5/6 -> GPS variant of the 0xD4 layout. */
    public static final int CAP_PKG2_MULTISPORT_MODE_TYPE = 16;

    public static final int WATCH_DAY_DEFAULT = 3;
    public static final int ORIGIN_PROTOCOL_VERSION_DF = 5;
    public static final int ORIGIN_PROTOCOL_VERSION_DF_ALT = 3;

    // ---- Sleep record (normal mode) --------------------------------------
    /** Length of one normal-mode sleep day-record (in bytes). */
    public static final int SLEEP_RECORD_SIZE = 96;
    /** Number of 5-minute slots in a full day record. */
    public static final int SLEEP_SLOTS_PER_DAY = 248;

    // ---- D1 origin v1 response fields (one 20-byte record per notify) -----
    public static final int ORIGIN_RESP_PKG_NUMBER = 1;
    public static final int ORIGIN_RESP_ALL_PACKAGE = 3;
    public static final int ORIGIN_RESP_DAY_OFFSET = 5;
    public static final int ORIGIN_RESP_DATE_DAY = 7;
    public static final int ORIGIN_RESP_HOUR = 8;
    public static final int ORIGIN_RESP_MINUTE = 9;
    public static final int ORIGIN_RESP_STEPS = 10;
    public static final int ORIGIN_RESP_SPORT_VALUE = 12;
    public static final int ORIGIN_RESP_HEART_RATE = 14;
}
