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

    // Auxiliary channel discovered at connect (suspected auto-measure push channel)
    public static final UUID UUID_SERVICE_F003 =
            UUID.fromString("F0030001-0451-4000-B000-000000000000");
    public static final UUID UUID_CHARACTERISTIC_F003_DATA =
            UUID.fromString("F0030002-0451-4000-B000-000000000000");

    // "Sinilink-APP" service (firmware vendor handshake channel): the vendor app performs a
    // C0BADCFE challenge/response here on every connect. Subscribed for diagnostics in case
    // autonomous measurement pushes travel on this channel.
    public static final UUID UUID_SERVICE_AE00 =
            UUID.fromString("0000AE00-0000-1000-8000-00805F9B34FB");
    public static final UUID UUID_CHARACTERISTIC_AE01_WRITE =
            UUID.fromString("0000AE01-0000-1000-8000-00805F9B34FB");
    public static final UUID UUID_CHARACTERISTIC_AE02_DATA =
            UUID.fromString("0000AE02-0000-1000-8000-00805F9B34FB");

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
    /**
     * Status poll {0xD8, 0x00}: today's cumulative activity in one reply — steps u32 LE at [2..5],
     * distance meters u32 LE at [6..9], calories x10 u32 LE at [10..13]. The vendor app polls this
     * every ~5 s to drive its real-time step counter.
     */
    public static final byte OP_STATUS_POLL = (byte) 0xD8;
    /** Real-time SpO2 stream start {0x80,1,2} / stop {0x80,2,2}. */
    public static final byte OP_SPO2_REALTIME = (byte) 0x80;
    /**
     * Stress (pressure) detection — multiplexed "detect" opcode: start {0x89,6,1} / stop {0x89,6,2}.
     * The vendor opcode table labels 0x89 as blood_glucose_detect_operate; sub-parameter 6 selects stress.
     */
    public static final byte OP_STRESS_REALTIME = (byte) 0x89;
    /** Body-temperature detection start {0x87,1,1} / stop {0x87,1,2}. */
    public static final byte OP_TEMP_REALTIME = (byte) 0x87;
    /** Blood pressure detection start {0x90,1,model} / stop {0x90,0,model}. */
    public static final byte OP_BP_REALTIME = (byte) 0x90;
    /**
     * Body composition detection. Start {0x93,4,1}, stop {0x93,4,2}. Response frames are
     * [0x93][4][event 1 detect / 2 stop][state]. State: 0 progress ([4]=%), 1 data, 2 failed,
     * 3 busy, 4 low battery. Data frames carry [4]=seq (1-based), [5]=total and a 14-byte chunk
     * at [6..19]; the concatenated chunks form a payload whose first byte must be 0x9C followed
     * by 14 big-endian u16 values (divided by 10).
     */
    public static final byte OP_BODY_COMPOSITION_REALTIME = (byte) 0x93;
    /**
     * Blood component (lipids) detection. Start {0x8A,1,1,calibrated}, stop {0x8A,1,2,0}.
     * Response frames are [0x8A][1][event 1 detect / 2 stop][state]. State: 0 values, 1
     * detecting, 2 low battery, 3 busy, 4 not worn. Value frames carry [4]=progress % and the
     * results at [5..14]: uric acid (u16/10), cholesterol, triglycerides, HDL, LDL (u16/100);
     * the measurement completes at 100%.
     */
    public static final byte OP_BLOOD_LIPID_REALTIME = (byte) 0x8A;
    /**
     * ECG (single-lead) detection. Same wire opcode as body composition, selected by the
     * function byte: start {0x93,1,1,1}, stop {0x93,1,2,1}. Control/status frames reply on the
     * main channel with [1]==1; the subcommand is at [3]: 0 = info (sample rate as little-endian
     * u16 at [4..5], 500 Hz), 1 = per-second status (live HR big-endian u16 at [4..5],
     * acquisition progress 0-100 % at [19]), 2 = failed, 4 = finished successfully (all-zero
     * payload, the device-side completion event), 5 = result chunk (seq/total at [4]/[5]; the
     * device sends seq 1..total and then the completion frame). The waveform itself streams on
     * the light-origin channel ({@link #UUID_CHARACTERISTIC_F002_DATA}) as frames of a leading
     * 0x88, N big-endian signed int24 samples (3 bytes each; 0xFFFFFF/0x020202 are invalid
     * markers) and a 4-byte tail ending in a per-frame sequence counter.
     */
    public static final byte OP_ECG_REALTIME = (byte) 0x93;
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
    /**
     * Multi-type manual measurement data read-back: [0xDE][2][pos LE32][readMask LE32]
     * [detectMask LE32][0 x6]. Responses are 0xDE notifications with the record index at
     * [2..5] (LE32, 0-based, strictly sequential); only the first frame carries the total count
     * at [8..11]; every frame appends payload bytes starting at [6].
     */
    public static final byte OP_MANUAL_DETECT = (byte) 0xDE;
    /**
     * Find device: make the band vibrate (vendor "find_phone_by_watch_operate", FindPhoneOrDeviceOprate).
     * {0xB5, 0x0A} starts ringing, {0xB5, 0x0B} stops it. Status frames echo [1]=0x0A/0x0B with
     * [2] = 0 unsupported, 1 finding, 2 found, 3 stopped. The vendor UI gates this on A7 pkg3[8].
     */
    public static final byte OP_FIND_DEVICE = (byte) 0xB5;
    /** Find-device operation values for byte [1]. */
    public static final int FIND_DEVICE_START = 0x0A;
    public static final int FIND_DEVICE_STOP = 0x0B;

    // ---- 0xDE manual measurement read-back ----------------------------------
    /** Heart-rate bit of the 4-byte LE read/detect masks (DeviceManualDataType bit 1). */
    public static final int MANUAL_MASK_HEART_RATE = 1 << 1;

    // ---- 0xDA sport control values ----------------------------------------
    /** [4] for the set-control action: start the workout. */
    public static final int SPORT_CTRL_START = 1;
    /** [4] for the set-control action: read the current run state (live report answer). */
    public static final int SPORT_CTRL_READ = 2;
    /** [4] for the set-control action: stop the workout (must carry the active sport type). */
    public static final int SPORT_CTRL_STOP = 4;

    // ---- Phone-initiated workout config keys (onSendConfiguration) --------
    /** Prefix of the "start workout" key; the numeric suffix is the ESportType value. */
    public static final String CONFIG_WORKOUT_START_PREFIX = "veepoo_workout_start_";
    /** Stop the running workout. */
    public static final String CONFIG_WORKOUT_STOP = "veepoo_workout_stop";
    /** Start a single-shot SpO2 (blood oxygen) measurement. */
    public static final String CONFIG_MEASURE_SPO2 = "veepoo_measure_spo2";
    /** Start a single-shot body-temperature measurement. */
    public static final String CONFIG_MEASURE_TEMPERATURE = "veepoo_measure_temperature";
    /** Start a single-shot stress (pressure) measurement. */
    public static final String CONFIG_MEASURE_STRESS = "veepoo_measure_stress";
    /** Start a single-shot blood pressure measurement. */
    public static final String CONFIG_MEASURE_BLOOD_PRESSURE = "veepoo_measure_blood_pressure";
    public static final String CONFIG_MEASURE_BODY_COMPOSITION = "veepoo_measure_body_composition";
    public static final String CONFIG_MEASURE_BLOOD_LIPIDS = "veepoo_measure_blood_lipids";
    /** Start a single-shot ECG measurement (waveform capture). */
    public static final String CONFIG_MEASURE_ECG = "veepoo_measure_ecg";

    // ---- Auto-measure (periodic vitals) settings, opcode 0xB3 -------------
    /** Opcode for reading/writing per-metric auto-measure settings. */
    public static final byte OP_AUTO_MEASURE = (byte) 0xB3;
    /** [1] of a 0xB3 frame: set command / set acknowledgement. */
    public static final int AUTO_MEASURE_CMD_SET = 1;
    /** [1] of a 0xB3 frame: data (chunked TLV report in reply to a read). */
    public static final int AUTO_MEASURE_CMD_DATA = 2;
    // Fun-type values (vendor EAutoMeasureType) addressed by the settings
    public static final int AUTO_MEASURE_PULSE_RATE = 0;
    public static final int AUTO_MEASURE_BLOOD_PRESSURE = 1;
    public static final int AUTO_MEASURE_STRESS = 3;
    public static final int AUTO_MEASURE_BLOOD_OXYGEN = 4;
    public static final int AUTO_MEASURE_BODY_TEMPERATURE = 5;
    // Device settings screen keys (per-device prefs, delivered via onSendConfiguration)
    public static final String CONFIG_AUTO_MEASURE_HR = "veepoo_auto_measure_hr";
    public static final String CONFIG_AUTO_MEASURE_SPO2 = "veepoo_auto_measure_spo2";
    public static final String CONFIG_AUTO_MEASURE_TEMPERATURE = "veepoo_auto_measure_temperature";
    public static final String CONFIG_AUTO_MEASURE_STRESS = "veepoo_auto_measure_stress";
    public static final String CONFIG_AUTO_MEASURE_BLOOD_PRESSURE = "veepoo_auto_measure_blood_pressure";
    public static final String CONFIG_AUTO_MEASURE_HRV = "veepoo_auto_measure_hrv";
    public static final String CONFIG_AUTO_MEASURE_BLOOD_COMPONENTS = "veepoo_auto_measure_blood_components";
    // Per-monitor capability flags persisted from the device reports; the settings UI hides a
    // switch when its monitor is not supported by the firmware.
    public static final String CONFIG_AUTO_MEASURE_HR_SUPPORTED = "veepoo_monitor_hr_supported";
    public static final String CONFIG_AUTO_MEASURE_SPO2_SUPPORTED = "veepoo_monitor_spo2_supported";
    public static final String CONFIG_AUTO_MEASURE_TEMPERATURE_SUPPORTED = "veepoo_monitor_temperature_supported";
    public static final String CONFIG_AUTO_MEASURE_STRESS_SUPPORTED = "veepoo_monitor_stress_supported";
    public static final String CONFIG_AUTO_MEASURE_BLOOD_PRESSURE_SUPPORTED = "veepoo_monitor_bp_supported";
    public static final String CONFIG_AUTO_MEASURE_HRV_SUPPORTED = "veepoo_monitor_hrv_supported";
    public static final String CONFIG_AUTO_MEASURE_BLOOD_COMPONENTS_SUPPORTED = "veepoo_monitor_blood_components_supported";
    // Capability flag from the 0xA7 package 4 report; persisted so the settings UI can hide
    // the auto-measure screen on firmware that does not support it.
    public static final String CONFIG_AUTO_MEASURE_SUPPORTED = "veepoo_auto_measure_supported";

    // ---- Notification push, opcode 0xC2 (vendor SocialMsgOperater) ---------
    /**
     * Opcode for pushing phone notifications to the watch. Fixed 20-byte frames:
     * [1]=message type (see SOCIAL_MSG_TYPE_*), [2]=payload length, [3]=total packet count,
     * [4]=1-based packet index, [5]=section (1=contact name, 2=message text, 0=no contact),
     * [6..19]=UTF-8 payload (max 14 bytes). The watch vibrates and lights up on its side.
     */
    public static final byte OP_SOCIAL_MESSAGE = (byte) 0xC2;
    /** Message-type values for byte [1] (vendor ESocailMsg). */
    public static final int SOCIAL_MSG_TYPE_PHONE = 0;
    public static final int SOCIAL_MSG_TYPE_SMS = 1;
    public static final int SOCIAL_MSG_TYPE_WECHAT = 2;
    public static final int SOCIAL_MSG_TYPE_QQ = 3;
    public static final int SOCIAL_MSG_TYPE_TWITTER = 6;
    public static final int SOCIAL_MSG_TYPE_LINKEDIN = 8;
    public static final int SOCIAL_MSG_TYPE_WHATSAPP = 9;
    public static final int SOCIAL_MSG_TYPE_LINE = 10;
    public static final int SOCIAL_MSG_TYPE_INSTAGRAM = 11;
    public static final int SOCIAL_MSG_TYPE_SNAPCHAT = 12;
    public static final int SOCIAL_MSG_TYPE_SKYPE = 13;
    public static final int SOCIAL_MSG_TYPE_GMAIL = 14;
    public static final int SOCIAL_MSG_TYPE_OTHER = 17;
    public static final int SOCIAL_MSG_TYPE_TELEGRAM = 19;
    public static final int SOCIAL_MSG_TYPE_KAKAOTALK = 21;
    public static final int SOCIAL_MSG_TYPE_VIBER = 25;

    // ---- Text alarms (multi-alarm "alarm2"), opcode 0xB9 (vendor TextAlarmOprate) ---
    /**
     * Opcode for text alarms. Variable-length frames: [1]=sub-op (ALARM_OP_*), [2..3]=packet
     * count/index (always 1/1 here, negotiated MTU is large enough), [4]=0xA0 marker, [5]=2,
     * [6..7]=CRC16 (vendor Alarm2Util) over the full post-op alarm list as concatenated records,
     * [8..]=one alarm record. Acks echo the CRC in [7..8] so state drift is detectable.
     */
    public static final byte OP_MULTI_ALARM = (byte) 0xB9;
    /** [1] sub-ops of a 0xB9 frame (1/2 phone-initiated, 3 read; 4-7 pushed by the band). */
    public static final int ALARM_OP_DELETE = 1;
    public static final int ALARM_OP_SET = 2;
    public static final int ALARM_OP_READ = 3;
    public static final int ALARM_OP_FIRE_REPORT = 4;
    public static final int ALARM_OP_DEVICE_ADD = 5;
    public static final int ALARM_OP_DEVICE_DELETE = 6;
    public static final int ALARM_OP_DEVICE_TOGGLE = 7;
    /** Trailer marker at [4] of a 0xB9 frame and its ack. */
    public static final int ALARM_CRC_MARKER = 0xA0;
    /** Max text alarms this firmware keeps (vendor Alarm2Util.isTextAlarmListFull). */
    public static final int ALARM_MAX_ALARMS = 10;
    public static final String CONFIG_ALARMS_SUPPORTED = "veepoo_alarms_supported";
    /** ECG capability persisted from the A7 pkg2 [15] report; false only when the firmware says so. */
    public static final String CONFIG_ECG_SUPPORTED = "veepoo_ecg_supported";
    /** ECG type byte from A7 pkg2 [15]; selects the warm-up length in VeepooEcgWaveform.dropWarmup(). */
    public static final String CONFIG_ECG_TYPE = "veepoo_ecg_type";

    // ---- Custom setting (per-feature monitor toggles), opcode 0xB8 --------
    /** Opcode for reading/writing the classic per-feature monitor settings. */
    public static final byte OP_CUSTOM_SETTING = (byte) 0xB8;
    /** [1] of a 0xB8 frame: set command / set acknowledgement. */
    public static final int CUSTOM_SETTING_CMD_SET = 1;
    /** [1] of a 0xB8 frame: read request / read acknowledgement. */
    public static final int CUSTOM_SETTING_CMD_READ = 2;
    /** Feature byte values: 0 unsupported, 1 supported + open, 2 supported + closed. */
    public static final int CUSTOM_SETTING_VALUE_OPEN = 1;
    public static final int CUSTOM_SETTING_VALUE_CLOSE = 2;
    // Frame 1 ([19]==0) monitor field offsets.
    public static final int CUSTOM_SETTING_F1_AUTO_HEART = 4;
    public static final int CUSTOM_SETTING_F1_AUTO_BP = 5;
    public static final int CUSTOM_SETTING_F1_AUTO_HRV = 12;
    // Frame 2 ([19]==1) monitor field offsets.
    public static final int CUSTOM_SETTING_F2_AUTO_TEMP = 4;
    public static final int CUSTOM_SETTING_F2_STRESS = 9;
    public static final int CUSTOM_SETTING_F2_BLOOD_COMPONENTS = 11;

    // ---- AllSet night SpO2 auto-detect, opcode 0xB3 (legacy dialect) ------
    /** [2] of an AllSet write frame: set command. */
    public static final int ALLSET_OPRATE_SET = 0;
    /** [2] of an AllSet write frame: read request. */
    public static final int ALLSET_OPRATE_READ = 1;
    // Response layout: [3]=oprate (0=set ack, 1=read data), [4..7]=start h/m + end h/m, [8]=open.
    public static final int ALLSET_RESP_OPRATE = 3;
    public static final int ALLSET_RESP_START_HOUR = 4;
    public static final int ALLSET_RESP_START_MINUTE = 5;
    public static final int ALLSET_RESP_END_HOUR = 6;
    public static final int ALLSET_RESP_END_MINUTE = 7;
    public static final int ALLSET_RESP_OPEN = 8;

    // ---- Single-shot vital detection sub-parameters -----------------------
    // 0x80 SpO2: start {0x80,1,2}, stop {0x80,2,2}
    public static final int SPO2_DETECT_START = 1;
    public static final int SPO2_DETECT_STOP = 2;
    // 0x87 temperature: start {0x87,1,1}, stop {0x87,1,2}
    public static final int TEMP_DETECT_START = 1;
    public static final int TEMP_DETECT_STOP = 2;
    // 0x89 stress: sub-parameter selects the metric (6 = pressure/stress)
    public static final int STRESS_DETECT_METRIC = 6;
    public static final int STRESS_DETECT_START = 1;
    public static final int STRESS_DETECT_STOP = 2;
    // 0x90 blood pressure: {0x90, action, model}; action 1=start / 0=stop, model 0=public (uncalibrated)
    public static final int BP_DETECT_START = 1;
    public static final int BP_DETECT_STOP = 0;
    public static final int BP_DETECT_MODEL_PUBLIC = 0;
    // 0x93 body composition: {0x93, function 4, event}; event 1=start / 2=stop
    public static final int BC_DETECT_FUNCTION = 4;
    public static final int BC_DETECT_START = 1;
    public static final int BC_DETECT_STOP = 2;
    // 0x8A blood component: {0x8A, function 1, event, calibrated}; event 1=start / 2=stop
    public static final int LIPID_DETECT_FUNCTION = 1;
    public static final int LIPID_DETECT_START = 1;
    public static final int LIPID_DETECT_STOP = 2;
    // 0x93 ECG: {0x93, function 1, event, 1}; event 1=start / 2=stop
    public static final int ECG_DETECT_FUNCTION = 1;
    public static final int ECG_DETECT_START = 1;
    public static final int ECG_DETECT_STOP = 2;

    // ---- ECG light-origin (F002) waveform frames --------------------------
    // Frame layout (verified against live capture):
    //   [0]        0x88 opcode
    //   [1..len-5] waveform samples, each a big-endian signed int24 (3 bytes)
    //   [len-4]    0x00, [len-3] 0x14, [len-2] 0xFF (constant), [len-1] per-frame seq counter
    /** Minimum total frame length carrying at least one waveform sample. */
    public static final int ECG_LIGHT_MIN_FRAME_LEN = 7;
    /** Waveform samples before the constant tail. */
    public static final int ECG_LIGHT_TAIL_LEN = 4;
    /** Sample-rate field of the [3]==0 info frame: little-endian u16 at [4..5]. */
    public static final int ECG_INFO_SAMPLE_RATE_OFFSET = 4;
    // [3]==1 per-second status frame layout (verified against vendor + live capture):
    //   [4]  device state byte (0 = FREE, 0xFC = UNPASS_WEAR / no contact, others: charging/busy)
    //   [5]  hr1 live heart rate (single byte), [6] hr2, [7] HRV, [8..9] RR intervals
    //   [10..11] battery, [12] wear flag (1 = not worn / no probe contact), [13] mid
    //   [14..15] QTc little-endian u16, [16] PWV, [19] progress 0-100 %
    public static final int ECG_STATUS_DEVICE_STATE_OFFSET = 4;
    public static final int ECG_STATUS_HR_OFFSET = 5;
    public static final int ECG_STATUS_WEAR_OFFSET = 12;
    public static final int ECG_STATUS_PROGRESS_OFFSET = 19;
    public static final int ECG_DEVICE_STATE_UNPASS_WEAR = 0xFC;

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
    /** Package 1: SpO2H type; supported when in {1..9, -3, -2, -32} (0/other = unsupported). */
    public static final int CAP_PKG1_SPO2H_TYPE = 8;
    /** Package 1: max packets per pushed notification (0xC2); 0 -> vendor default. */
    public static final int CAP_PKG1_ALL_MSG_LENGTH = 9;
    /** Package 1: alarm tag; 5/6/7 -> text alarms + multi-alarm (0xB9) supported. */
    public static final int CAP_PKG1_ALARM_TAG = 17;
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
    /** Package 2: ECG capability/type byte; 0 -> no ECG, non-zero -> supported (value is the ecgType). */
    public static final int CAP_PKG2_ECG_TYPE = 15;
    /** Package 3: temperature type; 0 -> no app-triggered temperature measurement. */
    public static final int CAP_PKG3_TEMP_TYPE = 7;
    /** Package 3: MET (metabolic rate) type; 2/3 -> supported. */
    public static final int CAP_PKG3_MET_TYPE = 17;
    /** Package 3: stress (pressure) type; 2/3 -> app-triggered detection supported. */
    public static final int CAP_PKG3_STRESS_TYPE = 18;
    /** Package 3: find-device-by-phone flag; non-zero -> the vendor UI shows "Find device". */
    public static final int CAP_PKG3_FIND_DEVICE_BY_PHONE = 8;

    // ---- A7 all-day vitals capability bytes (informational) -----------------
    // Captured for logging/diagnostics only; Origin3 tag parsing is unconditional so an
    // unknown or stale capability report never suppresses a dataset the band actually kept.
    /** Package 1: passive blood pressure tag; non-zero -> continuous BP (0xDF tag 0xB8) supported. */
    public static final int CAP_PKG1_BP_TAG = 1;
    /** Package 2: respiration (breath) function byte; non-zero -> breath rate (tag 0xB6) supported. Vendor field name: beathFunction. */
    public static final int CAP_PKG2_BREATH_FUNCTION = 7;
    /** Package 2: HRV type; 3/4/6/7/8 -> all-day HRV (tag 0xB7) supported. */
    public static final int CAP_PKG2_HRV_TYPE = 8;
    /** Package 3: blood glucose tag; non-zero -> glucose (tag 0xBE) supported, value 5 selects the packed risk+value encoding. */
    public static final int CAP_PKG3_BLOOD_GLUCOSE_TAG = 15;
    /** Package 4: blood component (lipid) tag; non-zero -> lipids (tag 0xC2) supported, 2 = single-calibration variant. */
    public static final int CAP_PKG4_BLOOD_COMPONENT_TAG = 1;

    /**
     * Maximum total days to request from the 0xD4 workout history, even when the device
     * advertises fewer via sportModelDay. The firmware keeps a compacted ring of sessions
     * that may extend past the advertised window; day 0 of the 0xD5 CRC report frame can
     * carry at most 8 LE u16 values, so 8 is the practical cap.
     */
    public static final int SPORT_FETCH_MAX_DAYS = 8;

    /** Max total days to request from the 0xE0 sleep history, probing past watchDataDay. */
    public static final int SLEEP_FETCH_MAX_DAYS = 8;

    /** Max total days to request from the Origin (0xDF/0xD1) activity history, probing past watchDataDay. */
    public static final int ORIGIN_FETCH_MAX_DAYS = 8;

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
