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
package nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryTableBuilder;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryValue;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBodyCompositionSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodLipidSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBodyCompositionSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodLipidSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodLipidSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GlucoseSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GlucoseSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooEcgSample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooSleepSummarySample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooSleepSummarySampleDao;
import nodomain.freeyourgadget.gadgetbridge.export.GPXExporter;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Device support for the VeePoo G Band (KR96 Pro, {@code com.vpgband.app}).
 *
 * <p>All traffic uses the F008 GATT service: one fixed 20-byte write per command on
 * {@code F0080003}, responses arrive as 20-byte notifications on {@code F0080002} carrying the
 * same opcode in byte 0.
 */
public class VeepooDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooDeviceSupport.class);

    private final VeepooAlarmModule alarmModule = new VeepooAlarmModule(this);
    private final VeepooAutoMeasureModule autoMeasureModule = new VeepooAutoMeasureModule(this);
    private final VeepooLiveWorkoutModule liveWorkoutModule = new VeepooLiveWorkoutModule(this);
    private final VeepooWomenHealthModule womenHealthModule = new VeepooWomenHealthModule(this);

    private static final int DATA_SOURCE_ACTIVITY_SUMMARY = 2; // daily summary records (D1)
    private static final int DATA_SOURCE_SLEEP_SUMMARY = 4;    // sleep function records (E0)
    private static final long MILLIS_PER_DAY = 86400_000L;

    /** Phases of the data-fetch state machine. Sleep is fetched first, then activity, then workouts. */
    private enum FetchPhase {IDLE, SLEEP, ORIGIN, SPORT}

    private final Object syncLock = new Object();
    private FetchPhase fetchPhase = FetchPhase.IDLE;
    private int sleepDayIndex = 0;
    private int originDayIndex = 0;
    private int sportDayIndex = 0;
    private final ByteArrayOutputStream sleepDayPayload = new ByteArrayOutputStream();
    private long sleepLastChunkAt = 0;
    private final List<byte[]> dfDayFrames = new ArrayList<>();
    private long dfLastFrameAt = 0;
    private final List<byte[]> sportDayFrames = new ArrayList<>();
    private long sportLastFrameAt = 0;
    final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable sleepWatchdog = () -> runSleepWatchdog();
    private final Runnable dfWatchdog = () -> runDfWatchdog();
    private final Runnable sportWatchdog = () -> runSportWatchdog();
    private final List<VeepooActivitySample> pendingOriginSamples = new ArrayList<>();
    private final List<VeepooActivitySample> pendingSleepSamples = new ArrayList<>();
    private final List<VeepooSleepSummarySample> pendingSleepSummaries = new ArrayList<>();
    private final List<GenericHrvValueSample> pendingHrvSamples = new ArrayList<>();
    // Per-record all-day stress ("pressure") from the Origin3 0xC1 tag.
    private final List<GenericStressSample> pendingStressSamples = new ArrayList<>();
    // Per-record all-day vitals from Origin3 (0xDF) tags, persisted when the day completes.
    private final List<GenericSpo2Sample> pendingOriginSpo2Samples = new ArrayList<>();
    private final List<GenericBloodPressureSample> pendingOriginBpSamples = new ArrayList<>();
    private final List<GenericTemperatureSample> pendingOriginTemperatureSamples = new ArrayList<>();
    private final List<GlucoseSample> pendingOriginGlucoseSamples = new ArrayList<>();
    private final List<GenericRespiratoryRateSample> pendingOriginBreathSamples = new ArrayList<>();
    private final List<GenericBloodLipidSample> pendingOriginLipidSamples = new ArrayList<>();

    // Manual heart rate read-back state (0xDE)
    private final Object manualDetectLock = new Object();
    private boolean manualDetectActive = false;
    private int manualDetectTotal = -1;
    private int manualDetectNextIndex = 0;
    private final ByteArrayOutputStream manualDetectPayload = new ByteArrayOutputStream();
    private final Runnable manualDetectWatchdog = () -> {
        LOG.warn("Manual heart rate read timed out");
        GB.toast(getContext(), R.string.veepoo_manual_hr_failed, Toast.LENGTH_SHORT, GB.WARN);
        resetManualDetect();
    };

    // On-demand heart rate detection state (0xD0 single-shot, mirrors the vendor home-screen flow)
    private boolean hrDetectActive = false;
    private boolean hrWearErrorSeen = false;
    private final Runnable hrDetectTimeout = () -> {
        LOG.warn("Heart rate detection timed out");
        finishHrDetect(null);
    };

    // On-demand vital detection state (SpO2 0x80, temperature 0x87, stress 0x89)
    private static final int VITAL_FAIL_NONE = 0;
    private static final int VITAL_FAIL_WEAR = 1;
    private static final int VITAL_FAIL_LOW_BATTERY = 2;
    private static final int VITAL_FAIL_BUSY = 3;
    private static final int VITAL_FAIL_NOT_SUPPORTED = 4;

    private boolean spo2DetectActive = false;
    private boolean spo2WearErrorSeen = false;
    private final Runnable spo2DetectTimeout = () -> {
        LOG.warn("SpO2 detection timed out");
        finishSpo2Detect(null, null, VITAL_FAIL_NONE);
    };
    private final Runnable spo2IdleWatchdog = () -> {
        synchronized (this) {
            if (!spo2DetectActive) {
                return;
            }
        }
        LOG.warn("SpO2 stream went idle without a reading");
        finishSpo2Detect(null, null, VITAL_FAIL_NONE);
    };

    private boolean tempDetectActive = false;
    private final Runnable tempDetectTimeout = () -> {
        LOG.warn("Temperature detection timed out");
        finishTemperatureDetect(null, null, VITAL_FAIL_NONE);
    };
    private final Runnable tempIdleWatchdog = () -> {
        synchronized (this) {
            if (!tempDetectActive) {
                return;
            }
        }
        LOG.warn("Temperature stream went idle without a reading");
        finishTemperatureDetect(null, null, VITAL_FAIL_NONE);
    };

    private boolean stressDetectActive = false;
    private final Runnable stressDetectTimeout = () -> {
        LOG.warn("Stress detection timed out");
        finishStressDetect(null, VITAL_FAIL_NONE);
    };
    private final Runnable stressIdleWatchdog = () -> {
        synchronized (this) {
            if (!stressDetectActive) {
                return;
            }
        }
        LOG.warn("Stress stream went idle without a reading");
        finishStressDetect(null, VITAL_FAIL_NONE);
    };

    private boolean bpDetectActive = false;
    private final Runnable bpDetectTimeout = () -> {
        LOG.warn("Blood pressure detection timed out");
        finishBpDetect(null, null, VITAL_FAIL_NONE);
    };
    private final Runnable bpIdleWatchdog = () -> {
        synchronized (this) {
            if (!bpDetectActive) {
                return;
            }
        }
        LOG.warn("Blood pressure stream went idle without a reading");
        finishBpDetect(null, null, VITAL_FAIL_NONE);
    };

    private boolean bcDetectActive = false;
    private final ArrayList<byte[]> bcChunks = new ArrayList<>();
    private final Runnable bcDetectTimeout = () -> {
        LOG.warn("Body composition detection timed out");
        finishBcDetect(null, VITAL_FAIL_NONE);
    };
    private final Runnable bcIdleWatchdog = () -> {
        synchronized (this) {
            if (!bcDetectActive) {
                return;
            }
        }
        LOG.warn("Body composition stream went idle without a result");
        finishBcDetect(null, VITAL_FAIL_NONE);
    };

    private boolean lipidDetectActive = false;
    private final Runnable lipidDetectTimeout = () -> {
        LOG.warn("Blood lipid detection timed out");
        finishLipidDetect(null, VITAL_FAIL_NONE);
    };
    private final Runnable lipidIdleWatchdog = () -> {
        synchronized (this) {
            if (!lipidDetectActive) {
                return;
            }
        }
        LOG.warn("Blood lipid stream went idle without a result");
        finishLipidDetect(null, VITAL_FAIL_NONE);
    };

    private boolean ecgDetectActive = false;
    private int ecgFrameCount = 0;
    private int ecgLatestHr = 0;
    private int ecgFinalHr = 0; // heart rate from the result frame, when the device sends one
    private int ecgProgress = 0; // acquisition progress 0-100 % from the per-second status frames
    private int ecgDrawFrequency = 0; // sample rate from the info frame (Hz)
    private int ecgNoContactStreak = 0; // consecutive status frames with the wear flag set
    private long ecgStartedAtMs = 0L;
    private long ecgLastNotifyMs = 0L;
    private final ByteArrayOutputStream ecgWaveformBuffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream ecgResultBlobBuffer = new ByteArrayOutputStream();
    private int ecgSampleCount = 0;
    private final Runnable ecgDetectTimeout = () -> {
        LOG.warn("ECG measurement window elapsed without a device completion event");
        finishEcgDetect(false, R.string.veepoo_ecg_failed_timeout);
    };

    // Capabilities from the device-pushed 0xA7 report (package 2)
    private int watchDataDay = VeepooConstants.WATCH_DAY_DEFAULT;
    private int originProtocolVersion = 1; // 3/5 => Origin3 (0xDF), anything else => v1 (0xD1)
    private boolean preciseSleepCapable = false;
    private int sportModelDay = 0;      // days of workout history kept (0 = unsupported)
    private int multSportModeType = 0;  // 4/5/6 => GPS variant of the 0xD4 layout

    // Capabilities from the device-pushed 0xA7 report (packages 1 and 3)
    private int spo2hType = -1;     // pkg1: supported when in {1..9, 0xFD, 0xFE, 0xE0}
    /** pkg1: max 0xC2 packets per pushed notification; vendor default when the report says 0. */
    private int socialMsgMaxPackets = 4;
    private int tempType = -1;      // pkg3: 0 => no app-triggered temperature measurement
    private int metType = -1;       // pkg3: 2/3 => MET supported
    private int stressType = -1;    // pkg3: 2/3 => app-triggered stress detection supported

    // All-day vitals capability bytes from the A7 report. Captured for logging only: Origin3 tag
    // parsing is unconditional, so an unknown device build must not hide data the band kept.
    private int bpTag = -1;             // pkg1: non-zero => continuous blood pressure (tag 0xB8)
    private int breathFunction = -1;    // pkg2: non-zero => breath rate (tag 0xB6); vendor: beathFunction
    private int hrvType = -1;           // pkg2: 3/4/6/7/8 => all-day HRV (tag 0xB7)
    private int bloodGlucoseTag = -1;   // pkg3: non-zero => glucose (tag 0xBE); 5 => packed risk+value encoding
    private int bloodComponentTag = -1; // pkg4: non-zero => lipids (tag 0xC2)

    // Capability from the device-pushed 0xA7 report (package 1): text alarms (0xB9). The alarm
    // state itself is tracked by VeepooAlarmModule, which re-reads the list whenever needed.
    volatile boolean textAlarmsSupported = false;

    // Capability from the device-pushed 0xA7 report (package 4): configurable periodic vitals.
    boolean autoMeasureSupported = false;

    // Women's health (cycle tracking), opcode 0x85 (vendor u2 / WomenOperater). The A7 pkg1 [12]
    // tag selects the read-back layout (3 = extended); -1 until the first capability report.
    int womenTag = -1;

    // 0xB8 CustomSetting: last known basic ([19]==0) and extended ([19]==1) frames, pushed by
    // the band after every connect and answered after an explicit read request. Toggles are
    // applied by re-sending the stored frame with the target byte flipped.
    private final Object customSettingLock = new Object();
    private byte[] customSettingFrame1;
    private byte[] customSettingFrame2;
    private String customSettingPendingKey = null;
    private boolean customSettingPendingOn = false;
    private boolean customSettingEchoDone = false;

    // 0xB3 AllSet (legacy dialect): night SpO2 auto-detect with a time window. Used on firmware
    // that does not report the newer 0xB3 TLV auto-measure capability (see A7 package 4).
    private final Object allSetLock = new Object();
    private boolean allSetReadOutstanding = false;
    private boolean allSetPending = false;
    private boolean allSetPendingOn = false;
    private boolean allSetEchoDone = false;
    private int allSetOpen = -1;   // last known open state (-1 unknown), 0 closed, 1 open
    private int allSetStartHour;
    private int allSetStartMinute;
    private int allSetEndHour;
    private int allSetEndMinute;
    private final Runnable allSetReadTimeout = () -> {
        synchronized (allSetLock) {
            if (!allSetReadOutstanding) {
                return;
            }
            allSetReadOutstanding = false;
            allSetPending = false;
        }
        LOG.warn("SpO2 auto-detect settings read timed out");
    };

    // 0xD8 status poll: today's cumulative steps/distance/calories. Runs only while the live
    // activity screen requests realtime step tracking (onEnableRealtimeSteps), so no
    // background BLE traffic is generated; the vendor app polls at the same ~5 s cadence.
    private boolean realtimeStepsRequested = false;
    private long lastStatusSteps = -1;
    private int lastStatusDay = -1;
    private final Runnable statusPollTask = () -> {
        if (!realtimeStepsRequested) {
            return;
        }
        try {
            TransactionBuilder builder = performInitialized("status poll");
            // The vendor app sends exactly {D8, 00} (2 bytes); zero-padded 20-byte frames get no reply.
            builder.write(getCharacteristic(VeepooConstants.UUID_CHARACTERISTIC_F008_CMD),
                    new byte[]{VeepooConstants.OP_STATUS_POLL, 0x00});
            builder.queue();
            LOG.info("Status poll sent (0xD8)");
        } catch (Exception e) {
            LOG.debug("Status poll skipped: {}", e.getMessage());
        }
        scheduleStatusPoll(5_000L);
    };

    private void scheduleStatusPoll(long delayMillis) {
        mainHandler.removeCallbacks(statusPollTask);
        mainHandler.postDelayed(statusPollTask, delayMillis);
    }

    public VeepooDeviceSupport() {
        super(LOG);
        addSupportedService(VeepooConstants.UUID_SERVICE_F008);
        addSupportedService(VeepooConstants.UUID_SERVICE_F002);
        addSupportedService(VeepooConstants.UUID_SERVICE_F003);
        addSupportedService(VeepooConstants.UUID_SERVICE_AE00);
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.requestMtu(VeepooConstants.MTU);
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(VeepooConstants.UUID_CHARACTERISTIC_F008_DATA, true);
        // Light-origin channel: carries live PPG/ECG waveform frames; only active during
        // measurements, but must be subscribed before an ECG detection can start.
        builder.notify(VeepooConstants.UUID_CHARACTERISTIC_F002_DATA, true);
        // Auxiliary channel: unknown purpose, suspected carrier of auto-measure pushes.
        builder.notify(VeepooConstants.UUID_CHARACTERISTIC_F003_DATA, true);
        // "Sinilink-APP" handshake channel: vendor app does a C0BADCFE challenge/response here;
        // subscribe to observe whether autonomous pushes travel on it.
        builder.notify(VeepooConstants.UUID_CHARACTERISTIC_AE02_DATA, true);
        // Password confirmation carries the phone time; followed by an explicit time sync.
        writeFrame(builder, buildPwdConfirmTimeFrame());
        writeFrame(builder, buildSyncTimeFrame());
        writeFrame(builder, new byte[]{VeepooConstants.OP_READ_BATTERY, 0x00});
        writeFrame(builder, new byte[]{VeepooConstants.OP_PRODUCTION_INFO, 0x00});
        // Defer the auto-measure settings read to steady state: issued inside this connect
        // burst the band refuses it with a set-command NAK (see VeepooAutoMeasureModule). Skip
        // it entirely when a previous connection already learned the firmware lacks the feature.
        SharedPreferences capabilities = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if (!capabilities.contains(VeepooConstants.CONFIG_AUTO_MEASURE_SUPPORTED)
                || capabilities.getBoolean(VeepooConstants.CONFIG_AUTO_MEASURE_SUPPORTED, false)) {
            autoMeasureModule.scheduleSteadyStateRead();
        }
        // Same steady-state deferral for the legacy SpO2 auto-detect read; the A7 package 1
        // report (which sets the capability) has arrived by then on supported firmware.
        mainHandler.postDelayed(() -> {
            SharedPreferences spo2Capabilities = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
            if (!spo2Capabilities.getBoolean(VeepooConstants.CONFIG_AUTO_MEASURE_SPO2_SUPPORTED, true)) {
                return;
            }
            synchronized (allSetLock) {
                if (allSetReadOutstanding || allSetOpen >= 0) {
                    return;
                }
            }
            sendAllSetRead();
        }, 5000L);
        // Read the watch's alarm list once the A7 capability report has had time to arrive; the
        // band verifies a whole-list CRC on every 0xB9 op, so we must know its current state.
        alarmModule.scheduleConnectReadBack();
        // Vendor parity: Jieli "Sinilink-APP" challenge/response on AE01. The vendor's BLE stack
        // runs this exchange on every connect and the band answers with a device info
        // notification on AE02 (fe dc ba 00 ...).
        sendSinilinkHandshake();
        // Re-push the women's health settings so the band keeps fresh LMP-relative state across
        // reconnects (vendor parity: its device-init re-sends the last saved tuple). Only when
        // a previous connection learned the firmware supports it and tracking is enabled.
        womenHealthModule.scheduleConnectPush();
        // The device pushes its 0xA7 capability report after pairing; it is handled in
        // onCharacteristicChanged() and may arrive before the INITIALIZED state is reached.
        // If the live activity screen is still open across a reconnect, resume the status poll.
        if (realtimeStepsRequested) {
            scheduleStatusPoll(5_000L);
        }
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    /** Writes a command frame to the F008 write characteristic, zero-padded to 20 bytes. */
    void writeFrame(TransactionBuilder builder, byte[] frame) {
        if (frame.length > VeepooConstants.PACKET_SIZE) {
            throw new IllegalArgumentException("VeePoo frames must be at most " + VeepooConstants.PACKET_SIZE + " bytes");
        }
        if (frame.length == VeepooConstants.PACKET_SIZE) {
            builder.write(getCharacteristic(VeepooConstants.UUID_CHARACTERISTIC_F008_CMD), frame);
            return;
        }
        byte[] padded = new byte[VeepooConstants.PACKET_SIZE];
        System.arraycopy(frame, 0, padded, 0, frame.length);
        builder.write(getCharacteristic(VeepooConstants.UUID_CHARACTERISTIC_F008_CMD), padded);
    }

    /**
     * Jieli "Sinilink-APP" handshake on the AE01 write characteristic (AE00 service), replayed
     * from vendor app captures: challenge, nonce, password token ("pass"), second nonce (twice),
     * final frame. The vendor's BLE stack runs it automatically on every connect; the band
     * answers the nonce steps with notifications on AE02.
     */
    private void sendSinilinkHandshake() {
        BluetoothGattCharacteristic c = getCharacteristic(VeepooConstants.UUID_CHARACTERISTIC_AE01_WRITE);
        if (c == null) {
            LOG.warn("AE01 characteristic missing; skipping Sinilink handshake");
            return;
        }
        java.util.Random rnd = new java.util.Random();
        byte[] nonce1 = new byte[16];
        byte[] nonce2 = new byte[16];
        rnd.nextBytes(nonce1);
        rnd.nextBytes(nonce2);
        byte xx = (byte) rnd.nextInt(256);

        byte[] challenge = {(byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0xC0, 0x06, 0x00, 0x02, 0x00, 0x01, (byte) 0xEF};
        byte[] token = {0x02, 'p', 'a', 's', 's'};
        byte[] n1 = new byte[1 + nonce1.length];
        n1[0] = 0x00;
        System.arraycopy(nonce1, 0, n1, 1, nonce1.length);
        byte[] n2 = new byte[1 + nonce2.length];
        n2[0] = 0x01;
        System.arraycopy(nonce2, 0, n2, 1, nonce2.length);
        byte[] finalFrame = {(byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0xC0, 0x03, 0x00, 0x06, xx,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, (byte) 0xEF};

        ae01WriteAt(3_000L, challenge, "challenge");
        ae01WriteAt(3_500L, n1, "nonce1");
        ae01WriteAt(4_200L, token, "token");
        ae01WriteAt(4_300L, n2, "nonce2a");
        ae01WriteAt(4_400L, n2, "nonce2b");
        ae01WriteAt(4_600L, finalFrame, "final");
    }

    private void ae01WriteAt(long delay, byte[] frame, String name) {
        mainHandler.postDelayed(() -> {
            try {
                TransactionBuilder b = performInitialized("ae01 " + name);
                b.write(getCharacteristic(VeepooConstants.UUID_CHARACTERISTIC_AE01_WRITE), frame);
                b.queue();
                LOG.info("Sinilink {} sent: {}", name, GB.hexdump(frame));
            } catch (Exception e) {
                LOG.warn("Sinilink {} failed: {}", name, e.getMessage());
            }
        }, delay);
    }

    /**
     * Opcode 0xA1: device password confirmation plus phone time.
     * Layout: [0]=A1, [1..2]=password LE16, [3]=mode 0 (confirm+time), [4..5]=year BE16 hex,
     * [6]=month, [7]=day, [8]=hour, [9]=minute, [10]=second, [11]=12/24h flag, [12]=const 1,
     * [13]=timezone in 15-minute steps, [14]=confirm flag (vendor default true).
     */
    byte[] buildPwdConfirmTimeFrame() {
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        byte[] frame = new byte[VeepooConstants.PACKET_SIZE];
        frame[0] = VeepooConstants.OP_PWD_CONFIRM;
        frame[1] = (byte) (VeepooConstants.DEFAULT_PASSWORD & 0xFF);
        frame[2] = (byte) ((VeepooConstants.DEFAULT_PASSWORD >> 8) & 0xFF);
        frame[3] = 0x00;
        frame[4] = (byte) ((year >> 8) & 0xFF);
        frame[5] = (byte) (year & 0xFF);
        frame[6] = (byte) (now.get(Calendar.MONTH) + 1);
        frame[7] = (byte) now.get(Calendar.DAY_OF_MONTH);
        frame[8] = (byte) now.get(Calendar.HOUR_OF_DAY);
        frame[9] = (byte) now.get(Calendar.MINUTE);
        frame[10] = (byte) now.get(Calendar.SECOND);
        frame[11] = 0x01; // 24-hour mode
        frame[12] = 0x01;
        int tzMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000 / 60;
        frame[13] = (byte) (tzMinutes / 15);
        frame[14] = 0x01;
        return frame;
    }

    /** Opcode 0xA5: time sync (vendor settingTime): [1..2]=year BE16, [3..7]=month/day/hour/minute/second, [8]=time mode (2=24h). */
    byte[] buildSyncTimeFrame() {
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        byte[] frame = new byte[VeepooConstants.PACKET_SIZE];
        frame[0] = VeepooConstants.OP_SYNC_TIME;
        frame[1] = (byte) ((year >> 8) & 0xFF);
        frame[2] = (byte) (year & 0xFF);
        frame[3] = (byte) (now.get(Calendar.MONTH) + 1);
        frame[4] = (byte) now.get(Calendar.DAY_OF_MONTH);
        frame[5] = (byte) now.get(Calendar.HOUR_OF_DAY);
        frame[6] = (byte) now.get(Calendar.MINUTE);
        frame[7] = (byte) now.get(Calendar.SECOND);
        frame[8] = 0x02; // MODE_24
        return frame;
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value != null && characteristic.getUuid().equals(VeepooConstants.UUID_CHARACTERISTIC_F008_DATA)) {
            handlePacket(value);
            return true;
        }
        if (value != null && characteristic.getUuid().equals(VeepooConstants.UUID_CHARACTERISTIC_F002_DATA)) {
            handleEcgLightFrame(value);
            return true;
        }
        if (value != null && characteristic.getUuid().equals(VeepooConstants.UUID_CHARACTERISTIC_F003_DATA)) {
            // Diagnostic: newly subscribed channel of unknown purpose; log raw frames first.
            LOG.debug("F003 notification ({} bytes): {}", value.length, GB.hexdump(value));
            return true;
        }
        if (value != null && characteristic.getUuid().equals(VeepooConstants.UUID_CHARACTERISTIC_AE02_DATA)) {
            // Diagnostic: "Sinilink-APP" channel of unknown purpose; log raw frames first.
            LOG.debug("AE02 notification ({} bytes): {}", value.length, GB.hexdump(value));
            return true;
        }
        return super.onCharacteristicChanged(gatt, characteristic, value);
    }

    private void handlePacket(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        int opcode = data[0] & 0xFF;
        switch (opcode) {
            case 0xA1:
                handlePwdConfirmResponse(data);
                break;
            case 0xA5:
                if (data.length >= 2) {
                    LOG.info("Time sync status: {}", data[1] & 0xFF);
                }
                break;
            case 0xA0:
                handleBatteryResponse(data);
                break;
            case 0xFC:
                handleProductionInfoResponse(data);
                break;
            case 0xA7:
                handleDeviceFunctionReport(data);
                break;
            case 0xE0:
                handleSleepChunk(data);
                break;
            case 0xD1:
                handleOriginRecord(data);
                break;
            case 0xDF:
                handleOrigin3Frame(data);
                break;
            case 0xD4:
                handleSportFrame(data);
                break;
            case 0xDA:
                liveWorkoutModule.handleFrame(data);
                break;
            case 0xD0:
                handleHeartRateMeasurement(data);
                break;
            case 0x80:
                handleSpo2Frame(data);
                break;
            case 0x87:
                handleTemperatureFrame(data);
                break;
            case 0x89:
                handleStressFrame(data);
                break;
            case 0x90:
                handleBpFrame(data);
                break;
            case 0x93:
                // Shared opcode: [1]==4 body composition, [1]==1 ECG; each handler self-guards.
                handleBodyCompositionFrame(data);
                handleEcgDetectFrame(data);
                break;
            case 0x8A:
                handleLipidFrame(data);
                break;
            case 0x85:
                // Women's health (cycle tracking). Note: 0x85 is also a PPG light-channel marker,
                // but that one arrives on F002 and is consumed by handleEcgLightFrame before the
                // main-channel dispatch; only F008 frames reach this case.
                womenHealthModule.handleFrame(data);
                break;
            case 0xB3:
                // Two dialects share this opcode; the A7 package 4 capability selects which one
                // the firmware speaks (newer TLV auto-measure vs legacy AllSet SpO2).
                if (autoMeasureSupported) {
                    autoMeasureModule.handleFrame(data);
                } else {
                    handleAllSetFrame(data);
                }
                break;
            case 0xB8:
                handleCustomSettingFrame(data);
                break;
            case 0xB9:
                alarmModule.handleFrame(data);
                break;
            case 0xB5:
                handleFindDeviceAck(data);
                break;
            case 0xDE:
                handleManualDetectFrame(data);
                break;
            case 0xD8:
                handleStatusFrame(data);
                break;
            default:
                LOG.debug("Unhandled VeePoo opcode: {}", String.format("%02X", (int) opcode));
        }
    }

    /**
     * Opcode 0xD8 status reply: [2..5]=today's steps u32 LE, [6..9]=distance meters u32 LE,
     * [10..13]=calories x10 u32 LE. The band keeps the daily total itself and resets it at
     * midnight; we turn consecutive polls into per-poll deltas for the live activity view.
     */
     private void handleStatusFrame(byte[] data) {
        LOG.info("D8 status frame received: len={} hex={}", data.length, GB.hexdump(data));
        if (data.length < 14) {
            LOG.warn("D8 status frame too short ({}), ignoring", data.length);
            return;
        }
        long steps = ((long) (data[2] & 0xFF)) | ((long) (data[3] & 0xFF) << 8)
                | ((long) (data[4] & 0xFF) << 16) | ((long) (data[5] & 0xFF) << 24);
        int day = (int) (System.currentTimeMillis() / 86_400_000L);
        if (day != lastStatusDay || lastStatusSteps < 0) {
            // First poll after screen open, reconnect, or midnight rollover: baseline only.
            LOG.info("D8 status: steps={} (baseline)", steps);
            lastStatusDay = day;
            lastStatusSteps = steps;
            return;
        }
        long delta = steps - lastStatusSteps;
        lastStatusSteps = steps;
        if (delta <= 0 || delta > 1000) {
            // Counter moved backwards (band reset, manual clear) or an implausible jump.
            LOG.info("D8 status: steps={} delta={} (dropped)", steps, delta);
            return;
        }
        LOG.info("D8 status: steps={} delta={}", steps, delta);
        VeepooActivitySample sample = new VeepooActivitySample();
        sample.setTimestamp((int) (System.currentTimeMillis() / 1000L));
        sample.setSteps((int) delta);
        notifyVitalSample(sample);
    }

    @Override
    public void onEnableRealtimeSteps(boolean enable) {
        LOG.info("Live step tracking {}", enable ? "enabled" : "disabled");
        realtimeStepsRequested = enable;
        if (enable) {
            mainHandler.removeCallbacks(statusPollTask);
            mainHandler.post(statusPollTask);
        } else {
            mainHandler.removeCallbacks(statusPollTask);
        }
    }

    /**
     * Opcode 0xA1 response: [3]=status (6 = check + time success), [4..5]=deviceNumber BE,
     * [6..9]=firmware/test version digits, [18]=find-device capability (0 none, 1/2 supported),
     * [19]=wear-check capability (0 none, 1/2 supported).
     */
    private void handlePwdConfirmResponse(byte[] data) {
        if (data.length < 20) {
            LOG.warn("Short 0xA1 response: {} bytes", data.length);
            return;
        }
        int status = data[3] & 0xFF;
        int deviceNumber = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        String firmwareVersion = hexByte(data[6]) + "." + hexByte(data[7]) + "." + hexByte(data[8]) + "." + hexByte(data[9]);
        int findPhoneFunction = data[18] & 0xFF;
        int wearDetectFunction = data[19] & 0xFF;
        LOG.info("Password confirm status: {} (device number {}, firmware {}, findPhone: {}, wearDetect: {})",
                status, deviceNumber, firmwareVersion, findPhoneFunction, wearDetectFunction);
        if (status != 1 && status != 3 && status != 5 && status != 6) {
            LOG.warn("Password confirm did not report success (status {}); the band may be paired to another phone", status);
        }
        GBDeviceEventVersionInfo versionInfo = new GBDeviceEventVersionInfo();
        versionInfo.fwVersion = firmwareVersion;
        versionInfo.fwVersion2 = String.valueOf(deviceNumber);
        evaluateGBDeviceEvent(versionInfo);
    }

    /**
     * Opcode 0xA0 response: [1]=power model (1 = charging), [4]=discrete level 0-4,
     * [5]==1 makes [6] a battery percent, [7]==2 flags low battery.
     */
    private void handleBatteryResponse(byte[] data) {
        if (data.length < 7) {
            LOG.warn("Short 0xA0 battery response: {} bytes", data.length);
            return;
        }
        boolean charging = (data[1] & 0xFF) == 1;
        int level;
        if ((data[5] & 0xFF) == 1) {
            level = data[6] & 0xFF;
        } else {
            // Discrete 0-4 level report: map to a representative percentage.
            int discrete = data[4] & 0xFF;
            if (discrete > 4) {
                discrete = 4;
            }
            level = new int[]{5, 30, 55, 80, 100}[discrete];
        }
        boolean low = data.length >= 8 && (data[7] & 0xFF) == 2;
        LOG.info("Battery: {}% (charging: {}, low: {})", level, charging, low);
        GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
        batteryInfo.level = level;
        batteryInfo.state = charging ? BatteryState.BATTERY_CHARGING : BatteryState.BATTERY_NORMAL;
        evaluateGBDeviceEvent(batteryInfo);
    }

    /** Opcode 0xFC response: accepted only if length == 20 && [18] == 2; TP version = bytes [9..14]. */
    private void handleProductionInfoResponse(byte[] data) {
        if (data.length != 20 || (data[18] & 0xFF) != 2) {
            LOG.debug("Ignoring production info response (len {}, [18]={})", data.length, data.length > 18 ? (int) (data[18] & 0xFF) : -1);
            return;
        }
        StringBuilder tpVersion = new StringBuilder();
        for (int i = 9; i <= 14; i++) {
            if (i > 9) {
                tpVersion.append('.');
            }
            tpVersion.append(hexByte(data[i]));
        }
        LOG.info("Production (TP) version: {}", tpVersion);
    }

    /**
     * Opcode 0xA7 capability report (device push, package id in byte [19]). Package 2 carries the
     * fields we need: watch data days [2], workout history days [5], origin protocol version [10],
     * precision sleep tag [13], ECG capability/type [15], multi-sport mode type [16].
     */
    private void handleDeviceFunctionReport(byte[] data) {
        if (data.length < 20) {
            return;
        }
        int packageId = data[19] & 0xFF;
        LOG.info("Device capability report, package {}: {}", packageId, hexBytes(data));
        if (packageId == 1) {
            bpTag = data[VeepooConstants.CAP_PKG1_BP_TAG] & 0xFF;
            spo2hType = data[VeepooConstants.CAP_PKG1_SPO2H_TYPE] & 0xFF;
            boolean spo2Supported = isSpo2hTypeSupported(spo2hType) && spo2hType != 8;
            int allMsgLength = data[VeepooConstants.CAP_PKG1_ALL_MSG_LENGTH] & 0xFF;
            if (allMsgLength >= 1 && allMsgLength <= 64) {
                socialMsgMaxPackets = allMsgLength;
            }
            int alarmTag = data[VeepooConstants.CAP_PKG1_ALARM_TAG] & 0xFF;
            textAlarmsSupported = alarmTag == 5 || alarmTag == 6 || alarmTag == 7;
            int women = data[VeepooConstants.CAP_PKG1_WOMEN_TAG] & 0xFF;
            boolean womenSupported = women == 1 || women == 2 || women == 3;
            this.womenTag = women;
            LOG.info("Capabilities: spo2hType={} ({}) maxMessagePackets={} alarmTag={} ({}) womenTag={} ({})",
                    spo2hType, spo2Supported ? "supported" : "not supported", socialMsgMaxPackets,
                    alarmTag, textAlarmsSupported ? "text alarms supported" : "no text alarms",
                    women, womenSupported ? "women's health supported" : "no women's health");
            LOG.info("All-day vitals capability (pkg1): bpTag={} spo2hType={}", bpTag, spo2hType);
            GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                    .edit()
                    .putBoolean(VeepooConstants.CONFIG_AUTO_MEASURE_SPO2_SUPPORTED, spo2Supported)
                    .putBoolean(VeepooConstants.CONFIG_ALARMS_SUPPORTED, textAlarmsSupported)
                    .putBoolean(VeepooConstants.CONFIG_WOMEN_SUPPORTED, womenSupported)
                    .putInt(VeepooConstants.CONFIG_WOMEN_TAG, women)
                    .apply();
        } else if (packageId == 2) {
            int watchDay = data[2] & 0xFF;
            if (watchDay >= 1 && watchDay <= 7) {
                watchDataDay = watchDay;
            }
            originProtocolVersion = data[10] & 0xFF;
            preciseSleepCapable = (data[13] & 0xFF) != 0;
            sportModelDay = data[VeepooConstants.CAP_PKG2_SPORT_MODEL_DAY] & 0xFF;
            multSportModeType = data[VeepooConstants.CAP_PKG2_MULTISPORT_MODE_TYPE] & 0xFF;
            int ecgType = data[VeepooConstants.CAP_PKG2_ECG_TYPE] & 0xFF;
            boolean ecgSupported = ecgType != 0;
            breathFunction = data[VeepooConstants.CAP_PKG2_BREATH_FUNCTION] & 0xFF;
            hrvType = data[VeepooConstants.CAP_PKG2_HRV_TYPE] & 0xFF;
            LOG.info("Capabilities: watchDataDay={}, originProtocolVersion={} ({}), preciseSleep={}, workoutDays={}, multisport={}, ecg={} (ecgType={})",
                    watchDataDay, originProtocolVersion,
                    (originProtocolVersion == 3 || originProtocolVersion == 5) ? "Origin3/DF" : "v1",
                    preciseSleepCapable, sportModelDay, multSportModeType,
                    ecgSupported ? "supported" : "not supported", ecgType);
            LOG.info("All-day vitals capability (pkg2): breathFunction={} hrvType={}", breathFunction, hrvType);
            GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                    .edit()
                    .putBoolean(VeepooConstants.CONFIG_ECG_SUPPORTED, ecgSupported)
                    .putInt(VeepooConstants.CONFIG_ECG_TYPE, ecgType)
                    .apply();
        } else if (packageId == 3) {
            tempType = data[VeepooConstants.CAP_PKG3_TEMP_TYPE] & 0xFF;
            metType = data[VeepooConstants.CAP_PKG3_MET_TYPE] & 0xFF;
            stressType = data[VeepooConstants.CAP_PKG3_STRESS_TYPE] & 0xFF;
            int findDeviceByPhone = data[VeepooConstants.CAP_PKG3_FIND_DEVICE_BY_PHONE] & 0xFF;
            bloodGlucoseTag = data[VeepooConstants.CAP_PKG3_BLOOD_GLUCOSE_TAG] & 0xFF;
            LOG.info("Capabilities: tempType={} ({}), metType={}, stressType={} ({}), findDeviceByPhone={}",
                    tempType, tempType != 0 ? "supported" : "not supported", metType, stressType,
                    (stressType == 2 || stressType == 3) ? "supported" : "not supported", findDeviceByPhone);
            LOG.info("All-day vitals capability (pkg3): bloodGlucoseTag={} metType={} stressType={}",
                    bloodGlucoseTag, metType, stressType);
        } else if (packageId == 4) {
            autoMeasureSupported = (data[12] & 0xFF) == 1;
            bloodComponentTag = data[VeepooConstants.CAP_PKG4_BLOOD_COMPONENT_TAG] & 0xFF;
            LOG.info("Capabilities: autoMeasure={} ({})", data[12] & 0xFF,
                    autoMeasureSupported ? "supported" : "not supported");
            LOG.info("All-day vitals capability (pkg4): bloodComponentTag={}", bloodComponentTag);
            GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                    .edit()
                    .putBoolean(VeepooConstants.CONFIG_AUTO_MEASURE_SUPPORTED, autoMeasureSupported)
                    .apply();
        }
    }

    /**
     * Opcode 0xB8 CustomSetting frame: two 20-byte blocks ([19] selects basic/extended) that the
     * band pushes after connect and answers a read request with. Each carries the live state of
     * many feature toggles; we persist the monitor ones and use the stored frames as the write
     * baseline so a toggle flips exactly one byte.
     */
    private void handleCustomSettingFrame(byte[] data) {
        if (data == null || data.length < 20) {
            LOG.warn("Short 0xB8 custom setting frame: {} bytes", data == null ? 0 : data.length);
            return;
        }
        int index = data[19] & 0xFF;
        if (index != 0 && index != 1) {
            LOG.debug("Unknown 0xB8 custom setting index: {}", index);
            return;
        }
        LOG.debug("Custom setting frame {}: {}", index, hexBytes(data));
        boolean baselineComplete;
        synchronized (customSettingLock) {
            if (index == 0) {
                customSettingFrame1 = data.clone();
            } else {
                customSettingFrame2 = data.clone();
            }
            baselineComplete = customSettingFrame1 != null && customSettingFrame2 != null;
        }
        if (baselineComplete && !customSettingEchoDone) {
            customSettingEchoDone = true;
            // Vendor parity: the reference app re-sends these frames on every connect; treat
            // that as a possible "app armed" handshake for autonomous measurement pushes.
            mainHandler.postDelayed(this::echoCustomSettings, 2000L);
        }
        int hr = stateOf(0, VeepooConstants.CUSTOM_SETTING_F1_AUTO_HEART);
        int bp = stateOf(0, VeepooConstants.CUSTOM_SETTING_F1_AUTO_BP);
        int hrv = stateOf(0, VeepooConstants.CUSTOM_SETTING_F1_AUTO_HRV);
        int temp = stateOf(1, VeepooConstants.CUSTOM_SETTING_F2_AUTO_TEMP);
        int stress = stateOf(1, VeepooConstants.CUSTOM_SETTING_F2_STRESS);
        int bloodComponents = stateOf(1, VeepooConstants.CUSTOM_SETTING_F2_BLOOD_COMPONENTS);
        LOG.info("Custom setting monitors: heart={} bp={} hrv={} temp={} stress={} bloodComponents={}",
                hr, bp, hrv, temp, stress, bloodComponents);
        SharedPreferences.Editor editor = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()).edit();
        persistMonitor(editor, VeepooConstants.CONFIG_AUTO_MEASURE_HR, VeepooConstants.CONFIG_AUTO_MEASURE_HR_SUPPORTED, hr);
        persistMonitor(editor, VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_PRESSURE, VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_PRESSURE_SUPPORTED, bp);
        persistMonitor(editor, VeepooConstants.CONFIG_AUTO_MEASURE_HRV, VeepooConstants.CONFIG_AUTO_MEASURE_HRV_SUPPORTED, hrv);
        persistMonitor(editor, VeepooConstants.CONFIG_AUTO_MEASURE_TEMPERATURE, VeepooConstants.CONFIG_AUTO_MEASURE_TEMPERATURE_SUPPORTED, temp);
        persistMonitor(editor, VeepooConstants.CONFIG_AUTO_MEASURE_STRESS, VeepooConstants.CONFIG_AUTO_MEASURE_STRESS_SUPPORTED, stress);
        persistMonitor(editor, VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_COMPONENTS, VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_COMPONENTS_SUPPORTED, bloodComponents);
        editor.apply();
        if (baselineComplete && customSettingPendingKey != null) {
            applyCustomSettingToggle(customSettingPendingKey, customSettingPendingOn);
        }
    }

    /** Stores the live state of one monitor; -1 means the frame carrying it has not arrived. */
    private void persistMonitor(SharedPreferences.Editor editor, String stateKey, String supportKey, int value) {
        if (value < 0) {
            return;
        }
        editor.putBoolean(stateKey, value == VeepooConstants.CUSTOM_SETTING_VALUE_OPEN);
        editor.putBoolean(supportKey, value != 0);
    }

    private int stateOf(int frameIndex, int offset) {
        synchronized (customSettingLock) {
            byte[] frame = frameIndex == 0 ? customSettingFrame1 : customSettingFrame2;
            return frame == null ? -1 : frame[offset] & 0xFF;
        }
    }

    /** Maps a monitor setting key to its {frame index, byte offset}; null for non-0xB8 keys. */
    private static int[] customSettingField(final String config) {
        if (VeepooConstants.CONFIG_AUTO_MEASURE_HR.equals(config)) {
            return new int[]{0, VeepooConstants.CUSTOM_SETTING_F1_AUTO_HEART};
        } else if (VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_PRESSURE.equals(config)) {
            return new int[]{0, VeepooConstants.CUSTOM_SETTING_F1_AUTO_BP};
        } else if (VeepooConstants.CONFIG_AUTO_MEASURE_HRV.equals(config)) {
            return new int[]{0, VeepooConstants.CUSTOM_SETTING_F1_AUTO_HRV};
        } else if (VeepooConstants.CONFIG_AUTO_MEASURE_TEMPERATURE.equals(config)) {
            return new int[]{1, VeepooConstants.CUSTOM_SETTING_F2_AUTO_TEMP};
        } else if (VeepooConstants.CONFIG_AUTO_MEASURE_STRESS.equals(config)) {
            return new int[]{1, VeepooConstants.CUSTOM_SETTING_F2_STRESS};
        } else if (VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_COMPONENTS.equals(config)) {
            return new int[]{1, VeepooConstants.CUSTOM_SETTING_F2_BLOOD_COMPONENTS};
        }
        return null;
    }

    /**
     * Monitor toggle from the device settings screen. If the band already pushed (or answered)
     * both custom-setting frames, write immediately with only the target byte changed; otherwise
     * queue the toggle and request a read so the baseline arrives first.
     */
    private void handleCustomSettingToggle(final String config) {
        int[] field = customSettingField(config);
        if (field == null) {
            return;
        }
        boolean on = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getBoolean(config, false);
        boolean baselineComplete;
        synchronized (customSettingLock) {
            customSettingPendingKey = config;
            customSettingPendingOn = on;
            baselineComplete = customSettingFrame1 != null && customSettingFrame2 != null;
        }
        if (baselineComplete) {
            applyCustomSettingToggle(config, on);
        } else {
            LOG.info("Custom setting baseline not received yet; requesting a read for toggle {}", config);
            sendCustomSettingRead();
        }
    }

    private void sendCustomSettingRead() {
        try {
            TransactionBuilder builder = performInitialized("read custom settings");
            writeFrame(builder, new byte[]{VeepooConstants.OP_CUSTOM_SETTING,
                    (byte) VeepooConstants.CUSTOM_SETTING_CMD_READ});
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error requesting custom settings", e);
        }
    }

    private void applyCustomSettingToggle(final String config, final boolean on) {
        int[] field = customSettingField(config);
        if (field == null) {
            return;
        }
        int frameIndex = field[0];
        int offset = field[1];
        byte[] frame1;
        byte[] frame2;
        synchronized (customSettingLock) {
            customSettingPendingKey = null;
            customSettingPendingOn = false;
            // An extended block that never arrived is all-unsupported; synthesize it so the
            // toggles living in it can still be written.
            frame1 = customSettingFrame1 != null ? customSettingFrame1.clone() : new byte[20];
            frame2 = customSettingFrame2 != null ? customSettingFrame2.clone() : new byte[20];
        }
        frame1[0] = VeepooConstants.OP_CUSTOM_SETTING;
        frame1[1] = (byte) VeepooConstants.CUSTOM_SETTING_CMD_SET;
        frame2[0] = VeepooConstants.OP_CUSTOM_SETTING;
        frame2[1] = (byte) VeepooConstants.CUSTOM_SETTING_CMD_SET;
        if (frameIndex == 0) {
            frame1[offset] = (byte) (on ? VeepooConstants.CUSTOM_SETTING_VALUE_OPEN : VeepooConstants.CUSTOM_SETTING_VALUE_CLOSE);
        } else {
            frame2[offset] = (byte) (on ? VeepooConstants.CUSTOM_SETTING_VALUE_OPEN : VeepooConstants.CUSTOM_SETTING_VALUE_CLOSE);
        }
        // The vendor re-sends the extended block whenever any of its fields is set; follow suit.
        boolean sendExtended = frame2[16] != 0;
        if (!sendExtended) {
            for (int i = 2; i <= 14; i++) {
                if (frame2[i] != 0) {
                    sendExtended = true;
                    break;
                }
            }
        }
        LOG.info("Writing custom setting toggle {} -> {}", config, on ? "on" : "off");
        try {
            TransactionBuilder builder = performInitialized("write custom settings");
            writeFrame(builder, frame1);
            if (sendExtended) {
                writeFrame(builder, frame2);
            }
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error writing custom setting toggle {}", config, e);
        }
    }

    /**
     * Vendor parity: the reference app re-sends the custom-setting frames on every connect, and
     * the band may treat that write as an "app armed" handshake for autonomous measurement
     * pushes. Echo back exactly what was just read at connect, so no setting changes state.
     */
    private void echoCustomSettings() {
        byte[] frame1;
        byte[] frame2;
        synchronized (customSettingLock) {
            if (customSettingFrame1 == null || customSettingFrame2 == null) {
                return;
            }
            frame1 = customSettingFrame1.clone();
            frame2 = customSettingFrame2.clone();
        }
        LOG.info("Echoing custom settings at connect (no state change): {}", hexBytes(frame1));
        try {
            TransactionBuilder builder = performInitialized("echo custom settings at connect");
            writeFrame(builder, frame1);
            writeFrame(builder, frame2);
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error echoing custom settings at connect", e);
        }
    }

    /**
     * Opcode 0xB3 AllSet frame (legacy dialect, only SpO2 night auto-detect on this generation):
     * response [1]==1, [3]=oprate (0 set ack / 1 read data), [4..7]=window start h/m + end h/m,
     * [8]=open state.
     */
    private void handleAllSetFrame(byte[] data) {
        if (data == null || data.length < 9 || (data[1] & 0xFF) != VeepooConstants.AUTO_MEASURE_CMD_SET) {
            LOG.debug("Ignoring unexpected AllSet frame: {}", data == null ? "null" : hexBytes(data));
            return;
        }
        int oprate = data[VeepooConstants.ALLSET_RESP_OPRATE] & 0xFF;
        if (oprate != VeepooConstants.ALLSET_OPRATE_SET && oprate != VeepooConstants.ALLSET_OPRATE_READ) {
            LOG.debug("Unknown AllSet oprate: {}", oprate);
            return;
        }
        boolean applyPending = false;
        int open;
        synchronized (allSetLock) {
            allSetReadOutstanding = false;
            if (oprate == VeepooConstants.ALLSET_OPRATE_READ) {
                allSetOpen = data[VeepooConstants.ALLSET_RESP_OPEN] & 0xFF;
                allSetStartHour = data[VeepooConstants.ALLSET_RESP_START_HOUR] & 0xFF;
                allSetStartMinute = data[VeepooConstants.ALLSET_RESP_START_MINUTE] & 0xFF;
                allSetEndHour = data[VeepooConstants.ALLSET_RESP_END_HOUR] & 0xFF;
                allSetEndMinute = data[VeepooConstants.ALLSET_RESP_END_MINUTE] & 0xFF;
                applyPending = allSetPending;
            }
            open = allSetOpen;
        }
        if (oprate == VeepooConstants.ALLSET_OPRATE_READ) {
            LOG.info("SpO2 auto-detect: open={} window {}:{}-{}:{}", open,
                    allSetStartHour, allSetStartMinute, allSetEndHour, allSetEndMinute);
            GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                    .edit()
                    .putBoolean(VeepooConstants.CONFIG_AUTO_MEASURE_SPO2, open == 1)
                    .apply();
            // Vendor parity: the reference app re-writes the SpO2 auto-detect settings on every
            // connect; echo back exactly what was just read so no setting changes state.
            synchronized (allSetLock) {
                if (!allSetEchoDone && !allSetPending) {
                    allSetEchoDone = true;
                    LOG.info("Echoing SpO2 auto-detect at connect (no state change): open={}", open);
                    writeAllSet(open == 1);
                }
            }
        } else {
            LOG.info("SpO2 auto-detect set acknowledged (open={})", open);
        }
        if (applyPending) {
            boolean pendingOn;
            synchronized (allSetLock) {
                pendingOn = allSetPendingOn;
            }
            writeAllSet(pendingOn);
        }
    }

    private void sendAllSetRead() {
        synchronized (allSetLock) {
            allSetReadOutstanding = true;
        }
        try {
            TransactionBuilder builder = performInitialized("read SpO2 auto-detect settings");
            writeFrame(builder, new byte[]{VeepooConstants.OP_AUTO_MEASURE, 0x00,
                    (byte) VeepooConstants.ALLSET_OPRATE_READ});
            builder.queue();
            mainHandler.removeCallbacks(allSetReadTimeout);
            mainHandler.postDelayed(allSetReadTimeout, 15000L);
        } catch (IOException e) {
            synchronized (allSetLock) {
                allSetReadOutstanding = false;
            }
            LOG.error("Error requesting SpO2 auto-detect settings", e);
        }
    }

    /** SpO2 auto-detect toggle from the device settings screen. */
    private void handleSpo2Configuration(final String config) {
        boolean on = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getBoolean(VeepooConstants.CONFIG_AUTO_MEASURE_SPO2, false);
        boolean known;
        synchronized (allSetLock) {
            allSetPending = true;
            allSetPendingOn = on;
            known = allSetOpen >= 0;
        }
        if (known) {
            writeAllSet(on);
        } else {
            LOG.info("SpO2 auto-detect state unknown; requesting a read before writing");
            sendAllSetRead();
        }
    }

    private void writeAllSet(final boolean on) {
        int open, startHour, startMinute, endHour, endMinute;
        synchronized (allSetLock) {
            allSetPending = false;
            open = on ? 1 : 0;
            startHour = allSetStartHour;
            startMinute = allSetStartMinute;
            endHour = allSetEndHour;
            endMinute = allSetEndMinute;
        }
        LOG.info("Writing SpO2 auto-detect: open={} window {}:{}-{}:{}", open,
                startHour, startMinute, endHour, endMinute);
        try {
            TransactionBuilder builder = performInitialized("write SpO2 auto-detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_AUTO_MEASURE, 0x00,
                    (byte) VeepooConstants.ALLSET_OPRATE_SET,
                    (byte) startHour, (byte) startMinute, (byte) endHour, (byte) endMinute, (byte) open});
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error writing SpO2 auto-detect settings", e);
        }
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        if ((dataTypes & RecordedDataTypes.TYPE_ACTIVITY) == 0) {
            return;
        }
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.IDLE) {
                LOG.warn("Data fetch already in progress (phase {}); ignoring new request", fetchPhase);
                return;
            }
            fetchPhase = FetchPhase.SLEEP;
            sleepDayIndex = 0;
            originDayIndex = 0;
            sportDayIndex = 0;
            sportDayFrames.clear();
        }
        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        LOG.info("Starting data fetch: {} days (sleep then activity)", watchDataDay);
        requestSleepDay(0);
    }

    private void requestSleepDay(int dayIndex) {
        scheduleSleepWatchdog();
        continueFetch("fetch sleep day " + dayIndex, builder ->
                writeFrame(builder, new byte[]{VeepooConstants.OP_SLEEP_READ, (byte) dayIndex}));
    }

    private void requestOriginDay(int dayIndex) {
        continueFetch("fetch activity day " + dayIndex, builder ->
                writeFrame(builder, new byte[]{VeepooConstants.OP_ORIGIN_V1, 0x01, 0x00, (byte) dayIndex}));
    }

    /** Queues a follow-up write after the current transaction completed. */
    private void continueFetch(String taskName, java.util.function.Consumer<TransactionBuilder> action) {
        try {
            TransactionBuilder builder = performInitialized(taskName);
            action.accept(builder);
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error continuing VeePoo data fetch ({})", taskName, e);
            abortFetch();
        }
    }

    /**
     * Opcode 0xE0 chunk: [1]=remaining chunks of the day (0 = last), [3]=day index echo,
     * payload in bytes [4..19]. Payloads are concatenated and split into 96-byte day records.
     * A zero-payload chunk with [2]==0x00 is the device's "this day has no data" answer
     * (seen for days beyond the stored window); it never completes via remaining==0.
     */
    private void handleSleepChunk(byte[] data) {
        if (data.length < VeepooConstants.PACKET_SIZE) {
            LOG.debug("Dropping short 0xE0 chunk: {} bytes", data.length);
            return;
        }
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.SLEEP) {
                LOG.warn("Unexpected 0xE0 chunk outside the sleep phase ({})", fetchPhase);
                return;
            }
        }
        int remaining = data[1] & 0xFF;
        int dayIndex = data[3] & 0xFF;
        if (dayIndex != sleepDayIndex) {
            LOG.warn("Sleep chunk day index {} does not match requested day {}", dayIndex, sleepDayIndex);
        }
        if ((data[2] & 0xFF) == 0x00 && isZeroPayload(data)) {
            LOG.info("Sleep day {} reported empty by the device", dayIndex);
            advanceSleepDay(dayIndex, false);
            return;
        }
        if (dayIndex == sleepDayIndex) {
            sleepLastChunkAt = System.currentTimeMillis();
            sleepDayPayload.write(data, 4, VeepooConstants.PACKET_SIZE - 4);
        }
        if (remaining != 0) {
            return;
        }

        byte[] stream = sleepDayPayload.toByteArray();
        sleepDayPayload.reset();
        LOG.info("Sleep day {} complete: {} payload bytes", dayIndex, stream.length);
        if (preciseSleepCapable) {
            parsePrecisionSleepStream(stream, dayIndex);
        } else {
            for (int offset = 0; offset + VeepooConstants.SLEEP_RECORD_SIZE <= stream.length; offset += VeepooConstants.SLEEP_RECORD_SIZE) {
                parseNormalSleepRecord(stream, offset, dayIndex);
            }
        }
        advanceSleepDay(dayIndex, stream.length > 0);
    }

    private boolean isZeroPayload(byte[] data) {
        for (int i = 4; i < VeepooConstants.PACKET_SIZE; i++) {
            if (data[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Advances the fetch after one sleep day. Inside the advertised watchDataDay window every
     * day is requested; beyond it, days are probed until an empty day or SLEEP_FETCH_MAX_DAYS.
     */
    private void advanceSleepDay(int dayIndex, boolean hadData) {
        int nextDay = dayIndex + 1;
        int limit = sleepFetchLimit();
        if (nextDay >= watchDataDay && !hadData) {
            if (dayIndex >= watchDataDay) {
                LOG.info("Sleep probe: day {} empty, history ends at day {}", dayIndex, dayIndex);
            }
            enterOriginPhase();
            return;
        }
        if (nextDay < limit) {
            if (nextDay == watchDataDay) {
                LOG.info("Sleep window full at {} day(s); probing up to day {}", watchDataDay, limit - 1);
            }
            synchronized (syncLock) {
                sleepDayIndex = nextDay;
            }
            requestSleepDay(nextDay);
        } else {
            enterOriginPhase();
        }
    }

    private int sleepFetchLimit() {
        return watchDataDay > 0 ? Math.max(watchDataDay, VeepooConstants.SLEEP_FETCH_MAX_DAYS) : 0;
    }

    private void enterOriginPhase() {
        synchronized (syncLock) {
            fetchPhase = FetchPhase.ORIGIN;
        }
        if (originProtocolVersion == 3 || originProtocolVersion == 5) {
            requestOrigin3Day(0);
        } else {
            requestOriginDay(0);
        }
    }

    /** 90 s for advertised days (mirrors the vendor app), 10 s for days beyond the window. */
    private long sleepWatchdogTimeout() {
        return sleepDayIndex >= watchDataDay ? 10_000L : 90_000L;
    }

    private void scheduleSleepWatchdog() {
        mainHandler.removeCallbacks(sleepWatchdog);
        sleepLastChunkAt = System.currentTimeMillis();
        mainHandler.postDelayed(sleepWatchdog, sleepWatchdogTimeout());
    }

    private void runSleepWatchdog() {
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.SLEEP) {
                return;
            }
        }
        long idle = System.currentTimeMillis() - sleepLastChunkAt;
        if (idle < sleepWatchdogTimeout()) {
            scheduleSleepWatchdog();
            return;
        }
        mainHandler.removeCallbacks(sleepWatchdog);
        int dayIndex = sleepDayIndex;
        byte[] stream = sleepDayPayload.toByteArray();
        sleepDayPayload.reset();
        if (dayIndex >= watchDataDay) {
            LOG.info("Sleep probe: no response for day {}, history ends at day {}", dayIndex, dayIndex - 1);
            enterOriginPhase();
            return;
        }
        LOG.warn("Sleep read stalled for {} ms; finishing the day with partial data", idle);
        if (preciseSleepCapable) {
            parsePrecisionSleepStream(stream, dayIndex);
        } else {
            for (int offset = 0; offset + VeepooConstants.SLEEP_RECORD_SIZE <= stream.length; offset += VeepooConstants.SLEEP_RECORD_SIZE) {
                parseNormalSleepRecord(stream, offset, dayIndex);
            }
        }
        advanceSleepDay(dayIndex, stream.length > 0);
    }

    /**
     * Opcode 0xD1 response: one activity record per notification.
     * [1..2]=packageNumber BE, [3..4]=allPackage BE, [5]=day offset, [7]=date day, [8]=hour,
     * [9]=minute, [10..11]=steps BE, [14]=heart rate (valid 30..200).
     */
    private void handleOriginRecord(byte[] data) {
        if (data.length < VeepooConstants.PACKET_SIZE) {
            return;
        }
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.ORIGIN) {
                LOG.warn("Unexpected 0xD1 record outside the activity phase ({})", fetchPhase);
                return;
            }
        }
        int packageNumber = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        int allPackage = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);

        int dayOffset = data[5] & 0xFF;
        if (dayOffset > 7) {
            dayOffset = originDayIndex; // implausible echo; fall back to the requested day
        }
        int hour = data[8] & 0xFF;
        int minute = data[9] & 0xFF;
        int steps = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
        int heartRate = data[14] & 0xFF;
        if (heartRate < 30 || heartRate > 200) {
            heartRate = ActivitySample.NOT_MEASURED;
        }

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -dayOffset);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        VeepooActivitySample sample = new VeepooActivitySample();
        sample.setTimestamp((int) (calendar.getTimeInMillis() / 1000L));
        sample.setRawKind(VeepooConstants.RAW_KIND_ACTIVITY);
        sample.setSteps(steps);
        sample.setHeartRate(heartRate);
        sample.setCaloriesBurnt(0);
        sample.setDistanceMeters(0);
        sample.setDataSource(DATA_SOURCE_ACTIVITY_SUMMARY);
        pendingOriginSamples.add(sample);

        boolean dayComplete = (data[1] & 0xFF) == (data[3] & 0xFF) && (data[2] & 0xFF) == (data[4] & 0xFF);
        if (!dayComplete) {
            return;
        }
        LOG.info("Activity day {} complete ({} packages, {} records total)", originDayIndex, allPackage, packageNumber);
        boolean hadData = !pendingOriginSamples.isEmpty();
        persistPendingOriginDay();
        afterOriginDayComplete(originDayIndex, hadData);
    }

    /** Requests the Origin3 (0xDF) 5-minute activity stream for dayIndex. */
    private void requestOrigin3Day(int dayIndex) {
        dfDayFrames.clear();
        scheduleDfWatchdog();
        continueFetch("fetch Origin3 activity day " + dayIndex, builder ->
                writeFrame(builder, new byte[]{VeepooConstants.OP_ORIGIN_DF, 0x01, 0x00, (byte) dayIndex}));
    }

    /**
     * Opcode 0xDF frame (Origin3, protocol versions 3/5). Each day read is a series of blocks:
     * a header frame ([3]=0, no payload) followed by payload frames carrying bytes [4..4+(j-4))
     * where j = header[13] on v5 and 20 on v3. The day ends when [1]==[2]==0xFF. The joined block
     * payloads are TLV streams: [tag][len][payload], tag 0xB1 = record time (5-minute boundary),
     * 0xB2 = steps/distance/kcal, 0xB4/0xB5 = heart rate, 0xC0 = end of stream.
     */
    private void handleOrigin3Frame(byte[] data) {
        if (data.length < VeepooConstants.PACKET_SIZE) {
            LOG.debug("Dropping short 0xDF frame: {} bytes", data.length);
            return;
        }
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.ORIGIN) {
                LOG.warn("Unexpected 0xDF frame outside the activity phase ({})", fetchPhase);
                return;
            }
        }
        dfLastFrameAt = System.currentTimeMillis();

        if ((data[1] & 0xFF) == 0xFF && (data[2] & 0xFF) == 0xFF) {
            mainHandler.removeCallbacks(dfWatchdog);
            List<byte[]> frames = new ArrayList<>(dfDayFrames);
            dfDayFrames.clear();
            int dayIndex = originDayIndex;
            LOG.info("Origin3 day {} complete ({} frames)", dayIndex, frames.size());
            boolean hadData = parseOrigin3Day(frames, dayIndex);
            mergeOriginHeartRateIntoSleep();
            persistPendingOriginDay();
            afterOriginDayComplete(dayIndex, hadData);
            return;
        }
        dfDayFrames.add(data);
    }

    /** 90 s for advertised days (mirrors the vendor app), 10 s for days beyond the window. */
    private long dfWatchdogTimeout() {
        return originDayIndex >= watchDataDay ? 10_000L : 90_000L;
    }

    private void scheduleDfWatchdog() {
        mainHandler.removeCallbacks(dfWatchdog);
        dfLastFrameAt = System.currentTimeMillis();
        mainHandler.postDelayed(dfWatchdog, dfWatchdogTimeout());
    }

    private void runDfWatchdog() {
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.ORIGIN) {
                return;
            }
        }
        long idle = System.currentTimeMillis() - dfLastFrameAt;
        if (idle < dfWatchdogTimeout()) {
            scheduleDfWatchdog();
            return;
        }
        mainHandler.removeCallbacks(dfWatchdog);
        List<byte[]> frames = new ArrayList<>(dfDayFrames);
        dfDayFrames.clear();
        int dayIndex = originDayIndex;
        boolean hadData;
        if (dayIndex >= watchDataDay) {
            LOG.info("Activity probe: no response for day {}, history ends at day {}", dayIndex, dayIndex - 1);
            hadData = false;
        } else {
            LOG.warn("Origin3 read stalled for {} ms; finishing the day with partial data", idle);
            hadData = parseOrigin3Day(frames, dayIndex);
        }
        mergeOriginHeartRateIntoSleep();
        persistPendingOriginDay();
        afterOriginDayComplete(dayIndex, hadData);
    }

    /**
     * Advances the fetch after an activity day. Inside the advertised watchDataDay window every
     * day is requested; beyond it (Origin3 only), days are probed until an empty day or
     * ORIGIN_FETCH_MAX_DAYS. Then the workout history days follow if the device keeps them.
     */
    private void afterOriginDayComplete(int dayIndex, boolean hadData) {
        int nextDay = dayIndex + 1;
        if (nextDay < watchDataDay) {
            synchronized (syncLock) {
                originDayIndex = nextDay;
            }
            if (originProtocolVersion == 3 || originProtocolVersion == 5) {
                requestOrigin3Day(nextDay);
            } else {
                requestOriginDay(nextDay);
            }
            return;
        }
        boolean origin3 = originProtocolVersion == 3 || originProtocolVersion == 5;
        int limit = origin3 ? Math.max(watchDataDay, VeepooConstants.ORIGIN_FETCH_MAX_DAYS) : watchDataDay;
        if (origin3 && hadData && nextDay < limit) {
            if (nextDay == watchDataDay) {
                LOG.info("Activity window full at {} day(s); probing up to day {}", watchDataDay, limit - 1);
            }
            synchronized (syncLock) {
                originDayIndex = nextDay;
            }
            requestOrigin3Day(nextDay);
            return;
        }
        if (origin3 && dayIndex >= watchDataDay && !hadData) {
            LOG.info("Activity probe: day {} empty, history ends at day {}", dayIndex, dayIndex);
        }
        if (sportModelDay > 0) {
            synchronized (syncLock) {
                fetchPhase = FetchPhase.SPORT;
                sportDayIndex = 0;
            }
            LOG.info("Activity days complete; reading up to {} day(s) of workout history", sportFetchLimit());
            requestSportDay(0);
        } else {
            synchronized (syncLock) {
                fetchPhase = FetchPhase.IDLE;
            }
            finishFetch();
        }
    }

    /** Requests the workout history (0xD4) stream for dayIndex. */
    private void requestSportDay(int dayIndex) {
        sportDayFrames.clear();
        scheduleSportWatchdog();
        continueFetch("fetch workout history day " + dayIndex, builder ->
                writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_ORIGIN, (byte) dayIndex}));
    }

    /**
     * Opcode 0xD4 frame (workout history): one workout session per requested day. All frames
     * accumulate; the day ends when [1]==[3] && [2]==[4]. Multi-sport types 4/5/6 use the GPS
     * layout, everything else the plain layout.
     */
    private void handleSportFrame(byte[] data) {
        if (data.length < VeepooConstants.PACKET_SIZE) {
            LOG.debug("Dropping short 0xD4 frame: {} bytes", data.length);
            return;
        }
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.SPORT) {
                LOG.warn("Unexpected 0xD4 frame outside the workout phase ({})", fetchPhase);
                return;
            }
        }
        sportLastFrameAt = System.currentTimeMillis();
        sportDayFrames.add(data);

        boolean dayComplete = (data[1] & 0xFF) == (data[3] & 0xFF) && (data[2] & 0xFF) == (data[4] & 0xFF);
        if (!dayComplete) {
            return;
        }
        mainHandler.removeCallbacks(sportWatchdog);
        List<byte[]> frames = new ArrayList<>(sportDayFrames);
        sportDayFrames.clear();
        int dayIndex = sportDayIndex;
        LOG.info("Workout day {} complete ({} frames)", dayIndex, frames.size());
        boolean hadSession = parseSportDay(frames, dayIndex);
        advanceSportDay(dayIndex, hadSession);
    }

    /**
     * Advances the fetch after one workout day. Inside the advertised sportModelDay window every
     * day is requested; beyond it, days are probed (the firmware keeps a compacted ring of
     * sessions that can extend past the window) until an empty day or SPORT_FETCH_MAX_DAYS.
     */
    private void advanceSportDay(int dayIndex, boolean hadSession) {
        int nextDay = dayIndex + 1;
        int limit = sportFetchLimit();
        if (nextDay >= sportModelDay && !hadSession) {
            if (dayIndex >= sportModelDay) {
                LOG.info("Workout probe: day {} empty, history ends at day {}", dayIndex, dayIndex);
            }
            synchronized (syncLock) {
                fetchPhase = FetchPhase.IDLE;
            }
            finishFetch();
            return;
        }
        if (nextDay < limit) {
            if (nextDay == sportModelDay) {
                LOG.info("Workout window full at {} day(s); probing up to day {}", sportModelDay, limit - 1);
            }
            synchronized (syncLock) {
                sportDayIndex = nextDay;
            }
            requestSportDay(nextDay);
        } else {
            synchronized (syncLock) {
                fetchPhase = FetchPhase.IDLE;
            }
            finishFetch();
        }
    }

    /** Total 0xD4 days to request: the advertised window, extended by the probe. */
    private int sportFetchLimit() {
        return sportModelDay > 0 ? Math.max(sportModelDay, VeepooConstants.SPORT_FETCH_MAX_DAYS) : 0;
    }

    /** Mirrors the app's 90-second per-day timeout; probe days time out much faster. */
    private void scheduleSportWatchdog() {
        mainHandler.removeCallbacks(sportWatchdog);
        sportLastFrameAt = System.currentTimeMillis();
        mainHandler.postDelayed(sportWatchdog, sportWatchdogTimeout());
    }

    /** 90 s for advertised days (mirrors the vendor app), 10 s for days beyond the window. */
    private long sportWatchdogTimeout() {
        return sportDayIndex >= sportModelDay ? 10_000L : 90_000L;
    }

    private void runSportWatchdog() {
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.SPORT) {
                return;
            }
        }
        long idle = System.currentTimeMillis() - sportLastFrameAt;
        if (idle < sportWatchdogTimeout()) {
            scheduleSportWatchdog();
            return;
        }
        mainHandler.removeCallbacks(sportWatchdog);
        if (sportDayIndex >= sportModelDay) {
            LOG.info("Workout probe: no response for day {}, stopping the history fetch", sportDayIndex);
            sportDayFrames.clear();
            synchronized (syncLock) {
                fetchPhase = FetchPhase.IDLE;
            }
            finishFetch();
            return;
        }
        LOG.warn("Workout read stalled for {} ms; finishing the day with partial data", idle);
        List<byte[]> frames = new ArrayList<>(sportDayFrames);
        sportDayFrames.clear();
        int dayIndex = sportDayIndex;
        boolean hadSession = parseSportDay(frames, dayIndex);
        advanceSportDay(dayIndex, hadSession);
    }

    /** Parses one 0xD4 day: GPS layout for multi-sport types 4/5/6, plain layout otherwise. */
    private boolean parseSportDay(List<byte[]> frames, int dayIndex) {
        try {
            if (multSportModeType == 4 || multSportModeType == 5 || multSportModeType == 6) {
                return parseSportGpsDay(frames, dayIndex);
            } else {
                return parseSportPlainDay(frames, dayIndex);
            }
        } catch (Exception e) {
            LOG.error("Error parsing workout day {}", dayIndex, e);
            return false;
        }
    }

    /**
     * Plain (non-GPS) workout layout. The head is the [6..19] slice of the first three frames
     * concatenated (42 bytes): start year BE16 [1..2], start month/day/hour/min/sec [3..7], end
     * year BE16 [8..9], end month/day/hour/min/sec [10..14], LE u32 steps [15..18], LE u32
     * distance (meters) [19..22], LE u32 kcal x 1000 [23..26], LE u32 sport seconds [27..30],
     * BE u16 record count [31..32], pause count [33], BE u16 pause seconds [34..35], BE u16 CRC
     * [36..37], sport type [38]. Item frames (index >= 3) carry one per-minute record: rate [6],
     * BE u16 minute index [1..2] (3-based), BE u16 total minutes [3..4] (3-based), BE u16 steps
     * [9..10], BE u16 kcal x 1000 [11..12], BE u16 distance (meters) [13..14].
     */
    private boolean parseSportPlainDay(List<byte[]> frames, int dayIndex) {
        if (frames.size() < 3) {
            LOG.info("Workout day {}: no workout data ({} frame(s))", dayIndex, frames.size());
            return false;
        }
        byte[] head = new byte[42];
        for (int k = 0; k < 3; k++) {
            System.arraycopy(frames.get(k), 6, head, k * 14, 14);
        }

        Date startTime = dateFromParts(be16(head, 1), head[3] & 0xFF, head[4] & 0xFF,
                head[5] & 0xFF, head[6] & 0xFF, head[7] & 0xFF);
        Date stopTime = dateFromParts(be16(head, 8), head[10] & 0xFF, head[11] & 0xFF,
                head[12] & 0xFF, head[13] & 0xFF, head[14] & 0xFF);
        if (startTime == null || stopTime == null || stopTime.before(startTime)) {
            if (startTime == null && stopTime == null) {
                LOG.info("Workout day {}: empty slot", dayIndex);
            } else {
                LOG.warn("Workout day {}: implausible workout window in the head; skipping", dayIndex);
            }
            return false;
        }

        int steps = (int) le32(head, 15);
        int distanceMeters = (int) le32(head, 19);
        float kcal = (int) le32(head, 23) / 1000f;
        long sportSeconds = le32(head, 27);
        int pauseSeconds = be16(head, 34);
        int sportType = head[38] & 0xFF;

        long totalSeconds = Math.abs(stopTime.getTime() - startTime.getTime()) / 1000L;
        long activeSeconds = sportSeconds > 0 ? sportSeconds : Math.max(0L, totalSeconds - pauseSeconds);

        ActivitySummaryData summary = new ActivitySummaryData();
        addSportEntries(summary, activeSeconds, distanceMeters, kcal, steps);

        int hrCount = 0;
        long hrSum = 0;
        int hrMin = Integer.MAX_VALUE;
        int hrMax = 0;
        ActivitySummaryTableBuilder perMinute = newPerMinuteTable();
        for (int i = 3; i < frames.size(); i++) {
            byte[] frame = frames.get(i);
            int minuteIndex = be16(frame, 1) - 3;
            int allMinutes = be16(frame, 3) - 3;
            int rate = frame[6] & 0xFF;
            int itemSteps = be16(frame, 9);
            int itemKcalRaw = be16(frame, 11);
            int itemDistance = be16(frame, 13);
            if (minuteIndex < 0 || allMinutes < 0) {
                continue; // zero-padded frame, no record data
            }
            boolean validHr = rate >= 30 && rate <= 200;
            if (!validHr && itemSteps == 0 && itemDistance == 0 && itemKcalRaw == 0) {
                continue;
            }
            if (validHr) {
                hrCount++;
                hrSum += rate;
                hrMin = Math.min(hrMin, rate);
                hrMax = Math.max(hrMax, rate);
            }
            perMinute.addRow("minute_" + (minuteIndex + 1), Arrays.asList(
                    new ActivitySummaryValue(minuteIndex + 1, ActivitySummaryEntries.UNIT_NONE),
                    new ActivitySummaryValue(validHr ? rate : null, ActivitySummaryEntries.UNIT_BPM),
                    new ActivitySummaryValue(itemSteps > 0 ? itemSteps : null, ActivitySummaryEntries.UNIT_STEPS),
                    new ActivitySummaryValue(itemDistance > 0 ? itemDistance : null, ActivitySummaryEntries.UNIT_METERS),
                    new ActivitySummaryValue(itemKcalRaw > 0 ? itemKcalRaw / 1000f : null, ActivitySummaryEntries.UNIT_KCAL)));
        }
        if (hrCount > 0) {
            summary.add(ActivitySummaryEntries.HR_AVG, hrSum / hrCount, ActivitySummaryEntries.UNIT_BPM);
            summary.add(ActivitySummaryEntries.HR_MIN, hrMin, ActivitySummaryEntries.UNIT_BPM);
            summary.add(ActivitySummaryEntries.HR_MAX, hrMax, ActivitySummaryEntries.UNIT_BPM);
        }
        perMinute.addToSummaryData(summary);
        saveSportSession(startTime, stopTime, sportType, summary, new ArrayList<>(), dayIndex);
        return true;
    }

    /**
     * GPS workout layout: strip the 5-byte frame prefix from every frame and join the payloads
     * into one blob. Head: [0]=0xA1, [1]=headLen-2, start/stop/sport-duration LE u32 [4..7]/
     * [8..11]/[12..15], block base offset [17], steps LE u32 [18..21], distance (meters) LE u32
      * [22..25], kcal x 1000 LE u32 [26..29], max/avg/min HR [34..36], avg pace LE16 [39..40].
      * Optional altitude block (base + 2 > 39): max/avg/min altitude LE u32 at [55..66], climb
      * LE u32 [67..70], decline LE u32 [71..74]. Sport block at base: pause seconds LE16
      * [base+23..24], sport type LE u16 [base+31..32], data type [base+33] (1/2/5/6 => per-point
       * altitude). Items follow as TLV: tag 0xA2, LE u16 length (the KR96 Pro firmware sends the
       * length little-endian; the vendor app's big-endian read rejects them), then one per-minute
       * record (HR [2], steps LE16 [5..6], distance LE16 [7..8], kcal x 1000 LE16 [9..10]) plus
       * any trailing GPS points. On the KR96 Pro every item is exactly 16 bytes, i.e. no GPS
       * points are embedded in the workout history stream.
     */
    private boolean parseSportGpsDay(List<byte[]> frames, int dayIndex) {
        if (frames.isEmpty()) {
            LOG.info("Workout day {}: no workout data", dayIndex);
            return false;
        }
        int blobLength = 0;
        for (byte[] frame : frames) {
            blobLength += frame.length - 5;
        }
        byte[] blob = new byte[blobLength];
        int position = 0;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 5, blob, position, frame.length - 5);
            position += frame.length - 5;
        }

        if (blob.length < 40 || (blob[0] & 0xFF) != 0xA1) {
            LOG.info("Workout day {}: no GPS workout data in {}-byte blob", dayIndex, blob.length);
            return false;
        }
        int headLength = Math.min((blob[1] & 0xFF) + 2, blob.length);
        long fieldA = le32(blob, 4);
        long fieldB = le32(blob, 8);
        if (fieldA == 0 && fieldB == 0) {
            LOG.info("Workout day {}: empty slot", dayIndex);
            return false;
        }
        // Older firmware versions stamped the start epoch with a wrong timezone (future date),
        // leaving the two epochs out of order. Normalize with min/max so those sessions are
        // still saved.
        long startUnix = Math.min(fieldA, fieldB);
        long stopUnix = Math.max(fieldA, fieldB);
        if (startUnix < 946684800L || startUnix > 4102444800L || headLength < 40) {
            LOG.warn("Workout day {}: implausible GPS head (epochs {} and {}); skipping",
                    dayIndex, fieldA, fieldB);
            return false;
        }
        if (fieldB < fieldA) {
            LOG.warn("Workout day {}: start/stop epochs out of order ({}, {}); using min/max",
                    dayIndex, fieldA, fieldB);
        }

        int sportSeconds = (int) le32(blob, 12);
        int base = blob[17] & 0xFF;
        int steps = (int) le32(blob, 18);
        int distanceMeters = (int) le32(blob, 22);
        float kcal = (int) le32(blob, 26) / 1000f;
        int maxHr = blob[34] & 0xFF;
        int avgHr = blob[35] & 0xFF;
        int minHr = blob[36] & 0xFF;
        int avgPace = le16(blob, 39);
        int maxSpeed = le16(blob, 43);
        int avgSpeed = le16(blob, 45);
        int minSpeed = le16(blob, 47);
        int maxCadence = le16(blob, 49);
        int avgCadence = le16(blob, 51);
        int minCadence = le16(blob, 53);

        long maxAltitude = 0;
        long avgAltitude = 0;
        long minAltitude = 0;
        long climb = 0;
        long decline = 0;
        boolean hasAltitudeBlock = base + 2 > 39 && blob.length >= 75;
        if (hasAltitudeBlock) {
            maxAltitude = le32(blob, 55);
            avgAltitude = le32(blob, 59);
            minAltitude = le32(blob, 63);
            climb = le32(blob, 67);
            decline = le32(blob, 71);
        }

        if (blob.length < base + 34) {
            LOG.warn("Workout day {}: GPS blob truncated before the sport block; skipping", dayIndex);
            return false;
        }
        int pauseSeconds = le16(blob, base + 23);
        int sportType = le16(blob, base + 31);
        int dataType = blob[base + 33] & 0xFF;

        Date startTime = new Date(startUnix * 1000L);
        Date stopTime = new Date(stopUnix * 1000L);
        long totalSeconds = Math.max(0L, stopUnix - startUnix);
        long activeSeconds = sportSeconds > 0 ? sportSeconds : Math.max(0L, totalSeconds - pauseSeconds);

        ActivitySummaryData summary = new ActivitySummaryData();
        addSportEntries(summary, activeSeconds, distanceMeters, kcal, steps);
        if (avgHr >= 30 && avgHr <= 200) {
            summary.add(ActivitySummaryEntries.HR_AVG, avgHr, ActivitySummaryEntries.UNIT_BPM);
        }
        if (maxHr >= 30 && maxHr <= 200) {
            summary.add(ActivitySummaryEntries.HR_MAX, maxHr, ActivitySummaryEntries.UNIT_BPM);
        }
        if (minHr >= 30 && minHr <= 200) {
            summary.add(ActivitySummaryEntries.HR_MIN, minHr, ActivitySummaryEntries.UNIT_BPM);
        }
        if (avgPace > 0) {
            summary.add(ActivitySummaryEntries.PACE_AVG_SECONDS_KM, avgPace, ActivitySummaryEntries.UNIT_SECONDS_PER_KM);
        }
        if (avgSpeed > 0) {
            summary.add(ActivitySummaryEntries.SPEED_AVG, avgSpeed / 1000f, ActivitySummaryEntries.UNIT_KMPH);
        }
        if (maxSpeed > 0) {
            summary.add(ActivitySummaryEntries.SPEED_MAX, maxSpeed / 1000f, ActivitySummaryEntries.UNIT_KMPH);
        }
        if (minSpeed > 0) {
            summary.add(ActivitySummaryEntries.SPEED_MIN, minSpeed / 1000f, ActivitySummaryEntries.UNIT_KMPH);
        }
        if (avgCadence >= 30 && avgCadence <= 400) {
            summary.add(ActivitySummaryEntries.CADENCE_AVG, avgCadence, ActivitySummaryEntries.UNIT_SPM);
        }
        if (maxCadence >= 30 && maxCadence <= 400) {
            summary.add(ActivitySummaryEntries.CADENCE_MAX, maxCadence, ActivitySummaryEntries.UNIT_SPM);
        }
        if (minCadence >= 30 && minCadence <= 400) {
            summary.add(ActivitySummaryEntries.CADENCE_MIN, minCadence, ActivitySummaryEntries.UNIT_SPM);
        }
        if (hasAltitudeBlock) {
            if (maxAltitude > 0) {
                summary.add(ActivitySummaryEntries.ALTITUDE_MAX, maxAltitude, ActivitySummaryEntries.UNIT_METERS);
            }
            if (avgAltitude > 0) {
                summary.add(ActivitySummaryEntries.ALTITUDE_AVG, avgAltitude, ActivitySummaryEntries.UNIT_METERS);
            }
            if (minAltitude > 0) {
                summary.add(ActivitySummaryEntries.ALTITUDE_MIN, minAltitude, ActivitySummaryEntries.UNIT_METERS);
            }
            if (climb > 0) {
                summary.add(ActivitySummaryEntries.TOTAL_ASCENT, climb, ActivitySummaryEntries.UNIT_METERS);
            }
            if (decline > 0) {
                summary.add(ActivitySummaryEntries.TOTAL_DESCENT, decline, ActivitySummaryEntries.UNIT_METERS);
            }
        }

        ActivitySummaryTableBuilder perMinute = newPerMinuteTable();
        List<GpxPoint> gpsPoints = new ArrayList<>();
        int offset = headLength;
        int itemIndex = 0;
        int rowsAdded = 0;
        while (offset + 3 <= blob.length) {
            if ((blob[offset] & 0xFF) != 0xA2) {
                break;
            }
            int recordLength = le16(blob, offset + 1);
            int dataStart = offset + 3;
            if (recordLength < 12 || dataStart + recordLength > blob.length) {
                break;
            }
            int rate = blob[dataStart + 2] & 0xFF;
            int itemSteps = le16(blob, dataStart + 5);
            int itemDistance = le16(blob, dataStart + 7);
            int itemKcalRaw = le16(blob, dataStart + 9);
            boolean validHr = rate >= 30 && rate <= 200;
            if (validHr || itemSteps > 0 || itemDistance > 0 || itemKcalRaw > 0) {
                perMinute.addRow("minute_" + (itemIndex + 1), Arrays.asList(
                        new ActivitySummaryValue(itemIndex + 1, ActivitySummaryEntries.UNIT_NONE),
                        new ActivitySummaryValue(validHr ? rate : null, ActivitySummaryEntries.UNIT_BPM),
                        new ActivitySummaryValue(itemSteps > 0 ? itemSteps : null, ActivitySummaryEntries.UNIT_STEPS),
                        new ActivitySummaryValue(itemDistance > 0 ? itemDistance : null, ActivitySummaryEntries.UNIT_METERS),
                        new ActivitySummaryValue(itemKcalRaw > 0 ? itemKcalRaw / 1000f : null, ActivitySummaryEntries.UNIT_KCAL)));
                rowsAdded++;
            }
            int pointStart = hasAltitudeBlock ? 22 : 18;
            long itemAltitude = hasAltitudeBlock && recordLength > 16 ? le32(blob, dataStart + 13) : 0;
            int pointCount = Math.max(0, (recordLength - 16) / 11);
            for (int p = 0; p < pointCount; p++) {
                int pos = dataStart + pointStart + p * 11;
                if (pos + 11 > dataStart + recordLength) {
                    break;
                }
                long lon = le32(blob, pos + 1);
                long lat = le32(blob, pos + 5);
                if (lon != 0 && lat != 0) {
                    long timeMs = startUnix * 1000L + itemIndex * 60000L
                            + (pointCount > 1 ? p * 60000L / pointCount : 30000L);
                    gpsPoints.add(new GpxPoint(lon, lat, itemAltitude, timeMs));
                }
            }
            itemIndex++;
            offset = dataStart + recordLength;
        }
        perMinute.addToSummaryData(summary);
        LOG.info("Workout day {}: parsed {} item records, saved {} per-minute rows ({} GPS points)",
                dayIndex, itemIndex, rowsAdded, gpsPoints.size());
        if (rowsAdded == 0 && blob.length > headLength + 3) {
            StringBuilder hex = new StringBuilder();
            int dumpEnd = Math.min(blob.length, headLength + 48);
            for (int i = headLength; i < dumpEnd; i++) {
                hex.append(String.format("%02x ", blob[i] & 0xFF));
            }
            StringBuilder a2Offsets = new StringBuilder();
            int a2Count = 0;
            for (int i = 0; i < blob.length && a2Count < 40; i++) {
                if ((blob[i] & 0xFF) == 0xA2) {
                    a2Offsets.append(i).append(' ');
                    a2Count++;
                }
            }
            LOG.info("Workout day {}: no 0xA2 item records at offset {}; bytes there: [{}]; 0xA2 byte offsets in blob: [{}]",
                    dayIndex, headLength, hex.toString().trim(), a2Offsets.toString().trim());
        }
        saveSportSession(startTime, stopTime, sportType, summary, gpsPoints, dayIndex);
        return true;
    }

    private static void addSportEntries(ActivitySummaryData summary, long activeSeconds, int distanceMeters, float kcal, int steps) {
        if (activeSeconds > 0) {
            summary.add(ActivitySummaryEntries.ACTIVE_SECONDS, activeSeconds, ActivitySummaryEntries.UNIT_SECONDS);
        }
        if (distanceMeters > 0) {
            summary.add(ActivitySummaryEntries.DISTANCE_METERS, distanceMeters, ActivitySummaryEntries.UNIT_METERS);
        }
        if (kcal > 0f) {
            summary.add(ActivitySummaryEntries.CALORIES_BURNT, kcal, ActivitySummaryEntries.UNIT_KCAL);
        }
        if (steps > 0) {
            summary.add(ActivitySummaryEntries.STEPS, steps, ActivitySummaryEntries.UNIT_STEPS);
        }
    }

    private static ActivitySummaryTableBuilder newPerMinuteTable() {
        return new ActivitySummaryTableBuilder(ActivitySummaryEntries.GROUP_LAPS, "per_minute_header",
                Arrays.asList("minute", "heartRate", "steps", "distanceMeters", "caloriesBurnt"));
    }

    /** Persists one parsed workout as a BaseActivitySummary; identical sessions are skipped. */
    private void saveSportSession(Date startTime, Date stopTime, int sportType, ActivitySummaryData summary,
                                  List<GpxPoint> gpsPoints, int dayIndex) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            User user = DBHelper.getUser(session);
            Device device = DBHelper.getDevice(getDevice(), session);

            BaseActivitySummaryDao dao = session.getBaseActivitySummaryDao();
            List<BaseActivitySummary> existing = dao.queryBuilder()
                    .where(BaseActivitySummaryDao.Properties.DeviceId.eq(device.getId()),
                            BaseActivitySummaryDao.Properties.StartTime.eq(startTime))
                    .list();
            if (!existing.isEmpty()) {
                LOG.debug("Workout day {}: session starting at {} already stored; skipping", dayIndex, startTime);
                return;
            }

            ActivityKind kind = sportTypeToKind(sportType);
            BaseActivitySummary workout = new BaseActivitySummary();
            workout.setName(kind.getLabel(getContext()));
            workout.setActivityKind(kind.getCode());
            workout.setStartTime(startTime);
            workout.setEndTime(stopTime);
            workout.setDevice(device);
            workout.setUser(user);
            if (gpsPoints.size() >= 2) {
                try {
                    summary.setHasGps(true);
                    workout.setGpxTrack(writeSportGpx(startTime, workout.getName(), device, user, gpsPoints));
                } catch (Exception e) {
                    LOG.warn("Workout day {}: GPX export failed, saving without track", dayIndex, e);
                }
            }
            workout.setSummaryData(summary.toString());
            dao.insertOrReplace(workout);
            LOG.info("Workout day {}: saved {} session {} to {}", dayIndex, kind.name(), startTime, stopTime);
        } catch (Exception e) {
            LOG.error("Error saving VeePoo workout session (day {})", dayIndex, e);
        }
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    /** Writes the collected GPS track points as a .gpx file in the export directory. */
    private String writeSportGpx(Date startTime, String name, Device device, User user, List<GpxPoint> gpsPoints) throws Exception {
        File dir = getDevice().getDeviceCoordinator().getWritableExportDirectory(getDevice(), true);
        File gpxFile = new File(dir, "veepoo_sport_" + (startTime.getTime() / 1000L) + ".gpx");
        ActivityTrack track = new ActivityTrack();
        track.setBaseTime(startTime);
        track.setName(name);
        track.setDevice(device);
        track.setUser(user);
        for (GpxPoint point : gpsPoints) {
            ActivityPoint activityPoint = new ActivityPoint(new Date(point.timeMs));
            if (point.altitude > 0) {
                activityPoint.setLocation(new GPSCoordinate(point.longitude / 1e6, point.latitude / 1e6, point.altitude));
            } else {
                activityPoint.setLocation(new GPSCoordinate(point.longitude / 1e6, point.latitude / 1e6));
            }
            track.addTrackPoint(activityPoint);
        }
        new GPXExporter().performExport(track, gpxFile, null);
        return gpxFile.getAbsolutePath();
    }

    /** One decoded GPS track point from a per-minute sport item (coordinates in micro-degrees). */
    private static final class GpxPoint {
        private final long longitude;
        private final long latitude;
        private final long altitude;
        private final long timeMs;

        private GpxPoint(long longitude, long latitude, long altitude, long timeMs) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.altitude = altitude;
            this.timeMs = timeMs;
        }
    }

    /** Maps the VeePoo ESportType value to the closest Gadgetbridge activity kind. */
    private static ActivityKind sportTypeToKind(int type) {
        switch (type) {
            case 1: return ActivityKind.RUNNING;
            case 2: return ActivityKind.WALKING;
            case 3: return ActivityKind.INDOOR_TRACK_RUNNING;
            case 4: return ActivityKind.INDOOR_WALKING;
            case 5: return ActivityKind.HIKING;
            case 6: return ActivityKind.TREADMILL;
            case 7: return ActivityKind.CYCLING;
            case 8: return ActivityKind.INDOOR_CYCLING;
            case 9: return ActivityKind.ELLIPTICAL_TRAINER;
            case 10: return ActivityKind.ROWING_MACHINE;
            case 11: return ActivityKind.MOUNTAINEERING;
            case 12: return ActivityKind.POOL_SWIM;
            case 13: return ActivityKind.SIT_UPS;
            case 14: return ActivityKind.SKIING;
            case 15: return ActivityKind.JUMP_ROPING;
            case 16: return ActivityKind.YOGA;
            case 17: return ActivityKind.TABLE_TENNIS;
            case 18: return ActivityKind.BASKETBALL;
            case 19: return ActivityKind.VOLLEYBALL;
            case 20: return ActivityKind.SOCCER;
            case 21: return ActivityKind.BADMINTON;
            case 22: return ActivityKind.TENNIS;
            case 23: return ActivityKind.STAIR_STEPPER; // climb stairs
            case 24: return ActivityKind.CARDIO;
            case 25: return ActivityKind.STRENGTH_TRAINING;
            case 26: return ActivityKind.DIVING;
            case 27: return ActivityKind.BOXING;
            case 30: return ActivityKind.MULTISPORT;
            case 31: return ActivityKind.DANCE;
            case 32: return ActivityKind.HIIT;
            case 33: return ActivityKind.ROCK_CLIMBING;
            case 45: return ActivityKind.RUNNING; // marathon
            case 53: return ActivityKind.GOLF;
            case 56: return ActivityKind.ROWING;
            case 57: return ActivityKind.BOATING;
            case 58: return ActivityKind.INLINE_SKATING;
            case 62: return ActivityKind.WALKING; // strong walk
            case 63: return ActivityKind.TREADMILL;
            case 64: return ActivityKind.TRAIL_RUN;
            case 65: return ActivityKind.WALKING; // race walking
            case 66: return ActivityKind.MOUNTAIN_BIKE;
            case 68: return ActivityKind.HIKING; // orienteering
            case 69: return ActivityKind.FISHING;
            case 70: return ActivityKind.HUNTING;
            case 84: return ActivityKind.STRENGTH_TRAINING;
            case 89: return ActivityKind.PILATES;
            case 108: return ActivityKind.MARTIAL_ARTS;
            case 109: return ActivityKind.TAI_CHI;
            case 142: return ActivityKind.SNOWBOARDING;
            case 143: return ActivityKind.SNOWBOARDING;
            case 144: return ActivityKind.SKIING; // alpine skiing
            case 177: return ActivityKind.RUNNING; // long distance running
            case 178: return ActivityKind.RUNNING; // full speed run
            case 179: return ActivityKind.RUNNING; // variable speed run
            default: return ActivityKind.EXERCISE;
        }
    }

    private static Date dateFromParts(int year, int month, int day, int hour, int minute, int second) {
        if (year < 2000 || year > 2100 || !isValidDatePart(month, day, hour, minute) || second > 60) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        return calendar.getTime();
    }

    /**
     * Reassembles the per-block payload streams of one Origin3 day and parses each TLV stream.
     * Header fields: [4..5]=LE total blocks, [6]=packets per block, [10]=valid payload bytes,
     * [11..12]=LE CRC-16 of the block payload, [13]=single-packet length j (read once from the
     * first block header on v5; v3 uses 20).
     */
    private boolean parseOrigin3Day(List<byte[]> frames, int dayIndex) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        boolean inBlock = false;
        int packetLen = -1; // single-packet length j: read from the first block header, reused for the rest of the day
        int validBytes = -1;
        int checksum = -1;
        int records = 0;

        for (byte[] frame : frames) {
            if ((frame[3] & 0xFF) == 0) {
                records += flushOrigin3Block(stream, validBytes, checksum, dayIndex);
                inBlock = true;
                if (packetLen < 0) {
                    packetLen = originProtocolVersion == VeepooConstants.ORIGIN_PROTOCOL_VERSION_DF
                            ? frame[13] & 0xFF : 20;
                    if (packetLen < 5) {
                        packetLen = 20;
                    }
                }
                validBytes = frame[10] & 0xFF;
                checksum = (frame[11] & 0xFF) | ((frame[12] & 0xFF) << 8);
                continue;
            }
            if (!inBlock) {
                LOG.debug("Dropping Origin3 payload frame before any block header (day {})", dayIndex);
                continue;
            }
            int copy = Math.min(packetLen - 4, frame.length - 4);
            stream.write(frame, 4, copy);
        }
        records += flushOrigin3Block(stream, validBytes, checksum, dayIndex);
        return records > 0;
    }

    private int flushOrigin3Block(ByteArrayOutputStream stream, int validBytes, int checksum, int dayIndex) {
        byte[] payload = stream.toByteArray();
        stream.reset();
        if (payload.length == 0) {
            return 0;
        }
        if (validBytes >= 0 && validBytes < payload.length) {
            payload = Arrays.copyOf(payload, validBytes);
        }
        if (checksum >= 0 && vpCrc16(payload) != checksum) {
            LOG.warn("Origin3 block for day {} failed its CRC-16 check; dropping", dayIndex);
            return 0;
        }
        return parseOrigin3TlvStream(payload, dayIndex);
    }

    /**
     * Parses one Origin3 block stream. Each 0xB1 tag starts a new 5-minute record (hour [2],
     * minute [3]); fields accumulate until the next 0xB1 or the 0xC0 terminator. B2 (SportFiveMinuteData):
     * [0..1]=steps BE16, [2..3]=sport BE16, [4..5]=distance meters BE16, [6..7]=kcal tenths BE16,
     * [8]=pose, [9]=wear. B4 carries the PPG heart-rate array, B5 the ECG one (B4 wins for the
     * record when both are present). The all-day vitals tags are handled per tag and queued for
     * batch persistence: 0xB6 breath rate (1 byte/minute), 0xB8 blood pressure (systolic,
     * diastolic), 0xB9 SpO2 (up to five 1-byte percentages, one per minute) plus the trailing
     * apnea count / hypoxia time / cardiac load groups (aggregated onto the activity record),
     * 0xBE glucose (LE16,
     * 0.01 mmol/L steps), 0xBF MET (1 byte, /10; log-only), 0xC1 pressure/wear (log-only) and
     * 0xC2 lipids (TCHO/TAG/HDL/LDL as LE16 in 0.01 mmol/L steps + uric acid in mg/dL).
     */
    private int parseOrigin3TlvStream(byte[] payload, int dayIndex) {
        Calendar base = Calendar.getInstance();
        base.add(Calendar.DAY_OF_MONTH, -dayIndex);

        boolean haveRecord = false;
        int hour = -1;
        int minute = -1;
        int steps = 0;
        int distanceMeters = 0;
        int kcalTenths = 0;
        int ppgHeartRate = ActivitySample.NOT_MEASURED;
        int ecgHeartRate = ActivitySample.NOT_MEASURED;
        int ppgHrRecords = 0;
        int ecgHrRecords = 0;
        int hrvRecords = 0;
        int hrvSamplesAdded = 0;
        // Aggregates of the 0xB9 apnea/hypoxia/cardiac-load groups (null until the tag is seen).
        Integer apneaCount = null;
        Integer hypoxiaMinutes = null;
        Integer hypoxiaTimeSum = null;
        Integer cardiacLoadMinutes = null;
        int recordsAdded = 0;

        int pos = 0;
        while (pos + 2 <= payload.length) {
            int tag = payload[pos] & 0xFF;
            if (tag == 0xC0) {
                break;
            }
            int length = payload[pos + 1] & 0xFF;
            if (pos + 2 + length > payload.length) {
                LOG.debug("Origin3 stream for day {} truncated at tag 0x{}", dayIndex, Integer.toHexString(tag));
                break;
            }
            byte[] body = Arrays.copyOfRange(payload, pos + 2, pos + 2 + length);

            if (tag == 0xB1) {
                if (flushOrigin3Record(base, dayIndex, haveRecord, hour, minute, steps, distanceMeters, kcalTenths,
                        ppgHeartRate, ecgHeartRate, apneaCount, hypoxiaMinutes, hypoxiaTimeSum, cardiacLoadMinutes)) {
                    recordsAdded++;
                }
                if (body.length == 4 && ((body[0] & 0xFF) | (body[1] & 0xFF)) != 0) {
                    haveRecord = true;
                    hour = body[2] & 0xFF;
                    minute = body[3] & 0xFF;
                } else {
                    haveRecord = false;
                }
                steps = 0;
                distanceMeters = 0;
                kcalTenths = 0;
                ppgHeartRate = ActivitySample.NOT_MEASURED;
                ecgHeartRate = ActivitySample.NOT_MEASURED;
                apneaCount = null;
                hypoxiaMinutes = null;
                hypoxiaTimeSum = null;
                cardiacLoadMinutes = null;
            } else if (haveRecord && tag == 0xB2 && body.length >= 10) {
                steps = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                distanceMeters = ((body[4] & 0xFF) << 8) | (body[5] & 0xFF);
                kcalTenths = ((body[6] & 0xFF) << 8) | (body[7] & 0xFF);
            } else if (haveRecord && tag == 0xB4) {
                int average = averageValidHeartRate(body);
                if (average > 0) {
                    ppgHeartRate = average;
                    ppgHrRecords++;
                }
            } else if (haveRecord && tag == 0xB5) {
                int average = averageValidHeartRate(body);
                if (average > 0) {
                    ecgHeartRate = average;
                    ecgHrRecords++;
                }
            } else if (haveRecord && tag == 0xB7) {
                LOG.debug("Origin3 day {} HRV tag at {}:{} len={} body={}",
                        dayIndex, hour, minute, body.length, hexBytes(body));
                if (hour <= 23 && minute <= 59 && body.length >= 51) {
                    Calendar stamp = (Calendar) base.clone();
                    stamp.set(Calendar.HOUR_OF_DAY, hour);
                    stamp.set(Calendar.MINUTE, minute);
                    stamp.set(Calendar.SECOND, 0);
                    stamp.set(Calendar.MILLISECOND, 0);
                    if (stamp.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) {
                        hrvRecords++;
                        hrvSamplesAdded += parseHrvFiveMinuteRecord(stamp, body, dayIndex);
                    }
                }
            } else if (haveRecord && tag == 0xB6 && hour <= 23 && minute <= 59) {
                // Breath rate: one byte per minute (breaths/min).
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                for (int m = 0; stamp != null && m < body.length && m < 5; m++) {
                    int breaths = body[m] & 0xFF;
                    if (breaths >= 5 && breaths <= 100) {
                        GenericRespiratoryRateSample sample = new GenericRespiratoryRateSample();
                        sample.setTimestamp(stamp.getTimeInMillis() + m * 60_000L);
                        sample.setRespiratoryRate(breaths);
                        pendingOriginBreathSamples.add(sample);
                    }
                }
            } else if (haveRecord && tag == 0xB8 && hour <= 23 && minute <= 59 && body.length >= 2) {
                // Blood pressure: systolic then diastolic, one reading per record.
                int systolic = body[0] & 0xFF;
                int diastolic = body[1] & 0xFF;
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && systolic >= 50 && systolic <= 250
                        && diastolic >= 30 && diastolic <= 150) {
                    GenericBloodPressureSample sample = new GenericBloodPressureSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setBpSystolic(systolic);
                    sample.setBpDiastolic(diastolic);
                    sample.setMeanArterialPressure((systolic + 2 * diastolic) / 3);
                    pendingOriginBpSamples.add(sample);
                } else if (stamp != null) {
                    LOG.debug("Ignoring implausible Origin3 BP at {}:{}: {}/{}", hour, minute, systolic, diastolic);
                }
            } else if (haveRecord && tag == 0xB9 && hour <= 23 && minute <= 59) {
                // SpO2: up to five 1-byte percentages, one per minute.
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                for (int m = 0; stamp != null && m < body.length && m < 5; m++) {
                    int spo2 = body[m] & 0xFF;
                    if (spo2 > 0 && spo2 <= 100) {
                        GenericSpo2Sample sample = new GenericSpo2Sample();
                        sample.setTimestamp(stamp.getTimeInMillis() + m * 60_000L);
                        sample.setSpo2(spo2);
                        pendingOriginSpo2Samples.add(sample);
                    }
                }
                // Apnea count [5..9], hypoxia time [15..19] and cardiac load [20..24]: five 1-byte
                // values per group, one per minute. Aggregated like the vendor app: apnea events are
                // summed; a hypoxia-time or cardiac-load byte is counted only when plausible.
                if (body.length > 5) {
                    int recordApnea = 0;
                    int recordHypoxiaMinutes = 0;
                    int recordHypoxiaTimeSum = 0;
                    int recordCardiacLoadMinutes = 0;
                    for (int m = 0; m < 5; m++) {
                        if (body.length > 5 + m) {
                            int apnea = body[5 + m] & 0xFF;
                            if (apnea > 0) {
                                recordApnea += apnea;
                            }
                        }
                        if (body.length > 15 + m) {
                            int hypoxia = body[15 + m] & 0xFF;
                            if (hypoxia >= 1 && hypoxia <= 60) {
                                recordHypoxiaMinutes++;
                                recordHypoxiaTimeSum += hypoxia;
                            }
                        }
                        if (body.length > 20 + m) {
                            int cardiacLoad = body[20 + m] & 0xFF;
                            if (cardiacLoad >= 1 && cardiacLoad <= 100) {
                                recordCardiacLoadMinutes++;
                            }
                        }
                    }
                    apneaCount = recordApnea;
                    hypoxiaMinutes = recordHypoxiaMinutes;
                    hypoxiaTimeSum = recordHypoxiaTimeSum;
                    cardiacLoadMinutes = recordCardiacLoadMinutes;
                    if (recordApnea == 0 && recordHypoxiaMinutes == 0 && recordCardiacLoadMinutes == 0) {
                        LOG.debug("Origin3 day {} 0xB9 at {}:{}: apnea/hypoxia/cardiac groups present ({} bytes) but all zero",
                                dayIndex, hour, minute, body.length);
                    }
                }
            } else if (haveRecord && tag == 0xBE && hour <= 23 && minute <= 59 && body.length >= 2) {
                // Blood glucose: LE16 in 0.01 mmol/L steps (the vendor app stores value/100 as mmol/L).
                int raw = ((body[1] & 0xFF) << 8) | (body[0] & 0xFF);
                if (bloodGlucoseTag == 5) {
                    // Tag-5 encoding packs the risk level into the top three bits.
                    raw &= 0x1FFF;
                }
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && raw > 0) {
                    GlucoseSample sample = new GlucoseSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setValueMgDl(raw * 0.18); // GB stores mg/dL; the wire value is 0.01 mmol/L
                    pendingOriginGlucoseSamples.add(sample);
                }
            } else if (haveRecord && tag == 0xBF && hour <= 23 && minute <= 59 && body.length >= 1) {
                // MET (metabolic equivalent): one byte, value = byte/10. VeepooActivitySample has no
                // MET field, so the value is logged only.
                LOG.debug("Origin3 day {} MET at {}:{}: {}", dayIndex, hour, minute, (body[0] & 0xFF) / 10.0);
            } else if (haveRecord && tag == 0xC1 && hour <= 23 && minute <= 59 && body.length >= 1) {
                // Per-record stress ("pressure") index, one byte per five-minute record; the vendor
                // stores this byte and charts it as the all-day stress series.
                int stress = body[0] & 0xFF;
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && stress <= 100) {
                    GenericStressSample sample = new GenericStressSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setStress(stress);
                    pendingStressSamples.add(sample);
                } else if (stamp != null) {
                    LOG.debug("Ignoring implausible Origin3 stress at {}:{}: {}", hour, minute, stress);
                }
            } else if (haveRecord && tag == 0xC2 && hour <= 23 && minute <= 59 && body.length >= 10) {
                // Lipids: TCHO/TAG/HDL/LDL as LE16 in 0.01 mmol/L steps plus uric acid as LE16 mg/dL.
                int tcho = ((body[1] & 0xFF) << 8) | (body[0] & 0xFF);
                int trig = ((body[3] & 0xFF) << 8) | (body[2] & 0xFF);
                int hdl = ((body[5] & 0xFF) << 8) | (body[4] & 0xFF);
                int ldl = ((body[7] & 0xFF) << 8) | (body[6] & 0xFF);
                int uricAcid = ((body[9] & 0xFF) << 8) | (body[8] & 0xFF);
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && tcho > 0 && trig > 0 && hdl > 0 && ldl > 0 && uricAcid > 0) {
                    GenericBloodLipidSample sample = new GenericBloodLipidSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setTotalCholesterol(tcho / 100.0f);
                    sample.setTriglyceride(trig / 100.0f);
                    sample.setHdl(hdl / 100.0f);
                    sample.setLdl(ldl / 100.0f);
                    sample.setUricAcid(uricAcid / 10.0f);
                    pendingOriginLipidSamples.add(sample);
                    LOG.debug("Origin3 day {} lipids at {}:{}: tcho={} trig={} hdl={} ldl={} uricAcid={}",
                            dayIndex, hour, minute, tcho / 100.0f, trig / 100.0f, hdl / 100.0f,
                            ldl / 100.0f, uricAcid / 10.0f);
                } else if (stamp != null) {
                    LOG.debug("Ignoring empty Origin3 lipid record at {}:{}: tcho={} trig={} hdl={} ldl={} uricAcid={}",
                            dayIndex, hour, minute, tcho, trig, hdl, ldl, uricAcid);
                }
            } else if (haveRecord && tag == 0xC3 && hour <= 23 && minute <= 59 && body.length >= 4) {
                // Wrist skin temperature: LE16 base and measured values, each in tenths of a
                // degree; the vendor drops the record when either value is outside 120..480
                // (12.0..48.0 C). The on-demand 0x87 measurement stays TYPE_BODY.
                int baseTenths = ((body[1] & 0xFF) << 8) | (body[0] & 0xFF);
                int measuredTenths = ((body[3] & 0xFF) << 8) | (body[2] & 0xFF);
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && baseTenths >= 120 && baseTenths <= 480
                        && measuredTenths >= 120 && measuredTenths <= 480) {
                    GenericTemperatureSample sample = new GenericTemperatureSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setTemperature(measuredTenths / 10.0f);
                    sample.setTemperatureType(TemperatureSample.TYPE_SKIN);
                    sample.setTemperatureLocation(TemperatureSample.LOCATION_WRIST);
                    pendingOriginTemperatureSamples.add(sample);
                }
            }
            pos += 2 + length;
        }
        if (flushOrigin3Record(base, dayIndex, haveRecord, hour, minute, steps, distanceMeters, kcalTenths,
                ppgHeartRate, ecgHeartRate, apneaCount, hypoxiaMinutes, hypoxiaTimeSum, cardiacLoadMinutes)) {
            recordsAdded++;
        }
        LOG.debug("Origin3 stream for day {}: {} five-minute records", dayIndex, recordsAdded);
        LOG.info("Origin3 day {}: heart rate in {} PPG / {} ECG of {} five-minute records; HRV tags {} ({} minute samples)",
                dayIndex, ppgHrRecords, ecgHrRecords, recordsAdded, hrvRecords, hrvSamplesAdded);
        return recordsAdded;
    }

    /**
     * Record start time for hour/minute within {@code base}, or null when the record lies in the
     * future (the band clock can run ahead of the phone).
     */
    private static Calendar origin3RecordStamp(Calendar base, int hour, int minute) {
        Calendar stamp = (Calendar) base.clone();
        stamp.set(Calendar.HOUR_OF_DAY, hour);
        stamp.set(Calendar.MINUTE, minute);
        stamp.set(Calendar.SECOND, 0);
        stamp.set(Calendar.MILLISECOND, 0);
        if (stamp.getTimeInMillis() > System.currentTimeMillis() + 60_000L) {
            return null;
        }
        return stamp;
    }

    private boolean flushOrigin3Record(Calendar base, int dayIndex, boolean valid, int hour, int minute,
                                       int steps, int distanceMeters, int kcalTenths,
                                       int ppgHeartRate, int ecgHeartRate,
                                       Integer apneaCount, Integer hypoxiaMinutes,
                                       Integer hypoxiaTimeSum, Integer cardiacLoadMinutes) {
        if (!valid || hour > 23 || minute > 59) {
            return false;
        }
        Calendar stamp = (Calendar) base.clone();
        stamp.set(Calendar.HOUR_OF_DAY, hour);
        stamp.set(Calendar.MINUTE, minute);
        stamp.set(Calendar.SECOND, 0);
        stamp.set(Calendar.MILLISECOND, 0);
        if (stamp.getTimeInMillis() > System.currentTimeMillis() + 60_000L) {
            LOG.debug("Skipping Origin3 record for day {} with a future timestamp ({})", dayIndex, stamp);
            return false;
        }
        // Prefer the PPG (0xB4) average when both it and the ECG (0xB5) array were present.
        int heartRate = ppgHeartRate != ActivitySample.NOT_MEASURED ? ppgHeartRate : ecgHeartRate;
        if (ppgHeartRate != ActivitySample.NOT_MEASURED && ecgHeartRate != ActivitySample.NOT_MEASURED) {
            LOG.debug("Origin3 day {} record at {}:{}: PPG HR {} preferred over ECG HR {}",
                    dayIndex, hour, minute, ppgHeartRate, ecgHeartRate);
        }
        VeepooActivitySample sample = new VeepooActivitySample();
        sample.setTimestamp((int) (stamp.getTimeInMillis() / 1000L));
        sample.setRawKind(VeepooConstants.RAW_KIND_ACTIVITY);
        sample.setSteps(steps);
        sample.setHeartRate(heartRate);
        sample.setCaloriesBurnt(kcalTenths * 100);
        sample.setDistanceMeters(distanceMeters);
        sample.setDataSource(DATA_SOURCE_ACTIVITY_SUMMARY);
        sample.setApneaCount(apneaCount);
        sample.setHypoxiaMinutes(hypoxiaMinutes);
        sample.setHypoxiaTimeSum(hypoxiaTimeSum);
        sample.setCardiacLoadMinutes(cardiacLoadMinutes);
        pendingOriginSamples.add(sample);
        return true;
    }

    /**
     * Back-fills per-minute heart rate for the pending sleep rows from the Origin3 five-minute
     * records covering them: the 0xE0 sleep stream carries no heart rate of its own, but the band
     * stores PPG/ECG averages in the activity stream. Runs after each Origin3 day parse, while
     * both lists are still in memory (sleep rows persist last, so without this merge they would
     * replace the overlapping activity rows and drop their heart rate).
     */
    private void mergeOriginHeartRateIntoSleep() {
        if (pendingOriginSamples.isEmpty() || pendingSleepSamples.isEmpty()) {
            return;
        }
        Map<Integer, Integer> heartRateBySlot = new HashMap<>();
        for (VeepooActivitySample sample : pendingOriginSamples) {
            int heartRate = sample.getHeartRate();
            if (heartRate >= 30 && heartRate <= 200) {
                heartRateBySlot.put((sample.getTimestamp() / 300) * 300, heartRate);
            }
        }
        if (heartRateBySlot.isEmpty()) {
            return;
        }
        int filled = 0;
        for (VeepooActivitySample sleep : pendingSleepSamples) {
            if (sleep.getHeartRate() != ActivitySample.NOT_MEASURED) {
                continue;
            }
            Integer heartRate = heartRateBySlot.get((sleep.getTimestamp() / 300) * 300);
            if (heartRate != null) {
                sleep.setHeartRate(heartRate);
                filled++;
            }
        }
        if (filled > 0) {
            LOG.info("Back-filled heart rate into {} sleep minutes from Origin3 records", filled);
        }
    }

    /**
     * Parses one Origin3 0xB7 five-minute HRV record: body[0] is unused on this firmware (always
     * 0) and bytes [1..50] hold 50 RR-interval samples in centiseconds, ten per minute. The
     * per-minute HRV value in milliseconds is the average absolute difference of consecutive valid
     * RR samples (centiseconds × 10). A minute with fewer than two valid samples is skipped.
     */
    private int parseHrvFiveMinuteRecord(Calendar stamp, byte[] body, int dayIndex) {
        int added = 0;
        for (int m = 0; m < 5; m++) {
            int sumAbsDiff = 0;
            int diffs = 0;
            int prevRr = -1;
            for (int i = m * 10; i < m * 10 + 10; i++) {
                int rr = body[i + 1] & 0xFF;
                if (rr >= 30 && rr <= 210) {
                    if (prevRr != -1) {
                        sumAbsDiff += Math.abs(prevRr - rr);
                        diffs++;
                    }
                    prevRr = rr;
                }
            }
            int value = diffs > 0 ? sumAbsDiff * 10 / diffs : 0;
            if (value <= 0 || value > 600) {
                continue;
            }
            GenericHrvValueSample sample = new GenericHrvValueSample();
            sample.setTimestamp(stamp.getTimeInMillis() + m * 60_000L);
            sample.setValue(value);
            pendingHrvSamples.add(sample);
            added++;
        }
        return added;
    }

    /** Averages the heart-rate bytes inside the plausible 30..200 range; 0 when none qualify. */
    private static int averageValidHeartRate(byte[] body) {
        int sum = 0;
        int count = 0;
        for (byte value : body) {
            int rate = value & 0xFF;
            if (rate >= 30 && rate <= 200) {
                sum += rate;
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    /** VeePoo's proprietary CRC-16 (AlarmCrcUtil): init 0xFFFF, table-driven; verifies Origin3 block payloads. */
    private static final int[] VP_CRC16_TABLE = {
            0, 49345, 49537, 320, 49921, 960, 640, 49729,
            50689, 1728, 1920, 51009, 1280, 50625, 50305, 1088,
            52225, 3264, 3456, 52545, 3840, 53185, 52865, 3648,
            2560, 51905, 52097, 2880, 51457, 2496, 2176, 51265,
            55297, 6336, 6528, 55617, 6912, 56257, 55937, 6720,
            7680, 57025, 57217, 8000, 56577, 7616, 7296, 56385,
            5120, 54465, 54657, 5440, 55041, 6080, 5760, 54849,
            53761, 4800, 4992, 54081, 4352, 53697, 53377, 4160,
            61441, 12480, 12672, 61761, 13056, 62401, 62081, 12864,
            13824, 63169, 63361, 14144, 62721, 13760, 13440, 62529,
            15360, 64705, 64897, 15680, 65281, 16320, 16000, 65089,
            64001, 15040, 15232, 64321, 14592, 63937, 63617, 14400,
            10240, 59585, 59777, 10560, 60161, 11200, 10880, 59969,
            60929, 11968, 12160, 61249, 11520, 60865, 60545, 11328,
            58369, 9408, 9600, 58689, 9984, 59329, 59009, 9792,
            8704, 58049, 58241, 9024, 57601, 8640, 8320, 57409,
            40961, 24768, 24960, 41281, 25344, 41921, 41601, 25152,
            26112, 42689, 42881, 26432, 42241, 26048, 25728, 42049,
            27648, 44225, 44417, 27968, 44801, 28608, 28288, 44609,
            43521, 27328, 27520, 43841, 26880, 43457, 43137, 26688,
            30720, 47297, 47489, 31040, 47873, 31680, 31360, 47681,
            48641, 32448, 32640, 48961, 32000, 48577, 48257, 31808,
            46081, 29888, 30080, 46401, 30464, 47041, 46721, 30272,
            29184, 45761, 45953, 29504, 45313, 29120, 28800, 45121,
            20480, 37057, 37249, 20800, 37633, 21440, 21120, 37441,
            38401, 22208, 22400, 38721, 21760, 38337, 38017, 21568,
            39937, 23744, 23936, 40257, 24320, 40897, 40577, 24128,
            23040, 39617, 39809, 23360, 39169, 22976, 22656, 38977,
            34817, 18624, 18816, 35137, 19200, 35777, 35457, 19008,
            19968, 36545, 36737, 20288, 36097, 19904, 19584, 35905,
            17408, 33985, 34177, 17728, 34561, 18368, 18048, 34369,
            33281, 17088, 17280, 33601, 16640, 33217, 32897, 16448
    };

    private static int vpCrc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte value : data) {
            crc = (((crc >> 8) & 0xFF) ^ VP_CRC16_TABLE[(crc & 0xFF) ^ (value & 0xFF)]) & 0xFFFF;
        }
        return crc;
    }

    /**
     * Parses one 96-byte normal sleep record and emits per-minute activity samples.
     * Stages: bitmap bit 0 = deep, 1 = light; window spans are marked '2' (REM).
     */
    private void parseNormalSleepRecord(byte[] stream, int offset, int dayIndex) {
        byte[] rec = new byte[VeepooConstants.SLEEP_RECORD_SIZE];
        System.arraycopy(stream, offset, rec, 0, VeepooConstants.SLEEP_RECORD_SIZE);

        int startMonth = rec[1] & 0xFF;
        int startDay = rec[2] & 0xFF;
        int startHour = rec[3] & 0xFF;
        int startMinute = rec[4] & 0xFF;
        int endMonth = rec[5] & 0xFF;
        int endDay = rec[6] & 0xFF;
        int endHour = rec[7] & 0xFF;
        int endMinute = rec[8] & 0xFF;
        int quality = rec[11] & 0xFF;
        int totalSlots = rec[43] & 0xFF;

        if (quality == 255 || quality < 1 || quality > 5) {
            LOG.debug("Discarding sleep record for day {} (quality {})", dayIndex, quality);
            return;
        }
        if (totalSlots > 248) {
            LOG.warn("Discarding sleep record for day {} (invalid total slots {})", dayIndex, totalSlots);
            return;
        }
        if (!isValidDatePart(startMonth, startDay, startHour, startMinute) || !isValidDatePart(endMonth, endDay, endHour, endMinute)) {
            LOG.warn("Discarding sleep record for day {} (invalid date parts)", dayIndex);
            return;
        }

        // Stage bitmap: slot k uses bit (7 - k%8) of byte 12 + k/8.
        char[] stages = new char[totalSlots];
        for (int slot = 0; slot < totalSlots; slot++) {
            int bit = (rec[12 + slot / 8] >>> (7 - slot % 8)) & 1;
            stages[slot] = bit == 0 ? '0' : '1';
        }

        // Window override: if deep time is non-zero, mark REM spans where light time covers them.
        int windowCount = rec[55] & 0xFF;
        int deepSlots = rec[9] & 0xFF;
        int lightSlots = rec[10] & 0xFF;
        if (deepSlots != 0 && windowCount > 0) {
            if (windowCount > 10) {
                windowCount = 10;
            }
            for (int w = 0; w < windowCount; w++) {
                int windowStart = rec[45 + w] & 0xFF;
                int windowEnd = rec[57 + w] & 0xFF;
                int span = windowEnd - windowStart;
                if (span > 0 && lightSlots >= span) {
                    for (int slot = windowStart; slot < Math.min(windowStart + span, totalSlots); slot++) {
                        stages[slot] = '2';
                    }
                }
            }
        }

        Calendar start = anchorSleepDate(dayIndex, startMonth, startDay, startHour, startMinute);
        Calendar end = anchorSleepDate(dayIndex, endMonth, endDay, endHour, endMinute);
        if (end.getTimeInMillis() <= start.getTimeInMillis()) {
            // Crossed a year boundary that anchoring could not resolve; use the tracked duration.
            end.setTimeInMillis(start.getTimeInMillis() + totalSlots * 5L * 60_000L);
        }
        long endCap = start.getTimeInMillis() + 24L * MILLIS_PER_DAY;
        if (end.getTimeInMillis() > endCap) {
            LOG.warn("Sleep record for day {} spans more than a day; clamping", dayIndex);
            end.setTimeInMillis(endCap);
        }

        int totalMinutes = (int) ((end.getTimeInMillis() - start.getTimeInMillis()) / 60_000L);
        long startEpochSeconds = start.getTimeInMillis() / 1000L;

        for (int minuteOffset = 0; minuteOffset < totalMinutes; minuteOffset++) {
            int slot = minuteOffset / 5;
            int rawKind;
            if (slot >= totalSlots) {
                rawKind = VeepooConstants.RAW_KIND_AWAKE_SLEEP;
            } else {
                switch (stages[slot]) {
                    case '0':
                        rawKind = VeepooConstants.RAW_KIND_DEEP_SLEEP;
                        break;
                    case '1':
                        rawKind = VeepooConstants.RAW_KIND_LIGHT_SLEEP;
                        break;
                    case '2':
                        rawKind = VeepooConstants.RAW_KIND_REM_SLEEP;
                        break;
                    default:
                        rawKind = VeepooConstants.RAW_KIND_AWAKE_SLEEP;
                }
            }
            VeepooActivitySample sample = new VeepooActivitySample();
            sample.setTimestamp((int) (startEpochSeconds + minuteOffset * 60L));
            sample.setRawKind(rawKind);
            sample.setSteps(0);
            sample.setHeartRate(ActivitySample.NOT_MEASURED);
            sample.setCaloriesBurnt(0);
            sample.setDistanceMeters(0);
            sample.setDataSource(DATA_SOURCE_SLEEP_SUMMARY);
            pendingSleepSamples.add(sample);
        }
        VeepooSleepSummarySample summary = new VeepooSleepSummarySample();
        summary.setTimestamp(start.getTimeInMillis());
        summary.setDurationMin(totalMinutes);
        summary.setQuality(quality);
        summary.setWakeCount(rec[55] & 0xFF);
        pendingSleepSummaries.add(summary);
        LOG.info("Parsed sleep record for day {}: {} minutes ({} deep slots, {} light slots, quality {})",
                dayIndex, totalMinutes, deepSlots, lightSlots, quality);
    }

    /**
     * Parses the precise sleep stream used by preciseSleep-capable bands: an A1 marker followed by
     * a LE16 record length, then tagged blocks. A3 is the main block (dates and durations), A5 is
     * the per-minute stage curve (BE16 words with the stage in the top three bits).
     */
    private void parsePrecisionSleepStream(byte[] stream, int dayIndex) {
        int pos = 0;
        while (pos + 3 <= stream.length) {
            int marker = -1;
            for (int i = pos; i + 3 <= stream.length; i++) {
                if ((stream[i] & 0xFF) == 0xA1) {
                    marker = i;
                    break;
                }
            }
            if (marker < 0) {
                break;
            }
            int recordLength = le16(stream, marker + 1);
            int recordStart = marker + 3;
            int recordEnd = Math.min(recordStart + Math.max(0, recordLength - 3), stream.length);
            parsePrecisionSleepRecord(stream, recordStart, recordEnd, dayIndex);
            pos = recordEnd;
        }
    }

    private void parsePrecisionSleepRecord(byte[] stream, int recordStart, int recordEnd, int dayIndex) {
        if (recordEnd - recordStart < 40) {
            LOG.warn("Precise sleep record for day {} is too short ({} bytes)", dayIndex, recordEnd - recordStart);
            return;
        }

        byte[] a3 = null;
        int curveStart = -1;
        int curveLen = 0;
        int pos = recordStart;
        while (pos + 2 <= recordEnd) {
            int tag = stream[pos] & 0xFF;
            int payloadStart;
            int payloadLen;
            if (tag == 0xA5) {
                payloadStart = pos + 3;
                payloadLen = le16(stream, pos + 1);
            } else {
                payloadStart = pos + 2;
                payloadLen = stream[pos + 1] & 0xFF;
            }
            if (payloadStart + payloadLen > recordEnd) {
                LOG.debug("Precise sleep tag 0x{} at record offset {} declares more bytes than remain; stopping the walk",
                        hexByte(tag), pos - recordStart);
                break;
            }
            if (tag == 0xA3 && a3 == null) {
                a3 = Arrays.copyOfRange(stream, payloadStart, payloadStart + payloadLen);
            } else if (tag == 0xA5 && curveStart < 0) {
                curveStart = payloadStart;
                curveLen = payloadLen;
            }
            pos = payloadStart + payloadLen;
        }
        if (a3 == null || curveStart < 0) {
            LOG.warn("Precise sleep record for day {} is missing the A3 block or the stage curve", dayIndex);
            return;
        }

        if (a3.length < 27) {
            LOG.warn("Discarding precise sleep record for day {} (A3 block too short: {} bytes)", dayIndex, a3.length);
            return;
        }
        int startMonth = a3[0] & 0xFF;
        int startDay = a3[1] & 0xFF;
        int startHour = a3[2] & 0xFF;
        int startMinute = a3[3] & 0xFF;
        if (!isValidDatePart(startMonth, startDay, startHour, startMinute)) {
            LOG.warn("Discarding precise sleep record for day {} (invalid start date parts)", dayIndex);
            return;
        }
        int deepSleepTime = le16(a3, 19);
        int lightSleepTime = le16(a3, 21);
        int allSleepTime = le16(a3, 25);
        int accurateType = a3.length >= 36 ? a3[35] & 0xFF : 1;

        // Type 0 (scientific sleep off) and type 1 both carry plain BE16 stage words.
        if (accurateType != 0 && accurateType != 1) {
            LOG.warn("Precise sleep record for day {} uses unsupported accuracy type {}; skipping", dayIndex, accurateType);
            return;
        }
        int wordCount = curveLen / 2;
        if (wordCount < 30 || allSleepTime < 30 || allSleepTime > 24 * 60) {
            LOG.warn("Discarding precise sleep record for day {} (curve words {}, total minutes {})", dayIndex, wordCount, allSleepTime);
            return;
        }

        Calendar start = anchorSleepDate(dayIndex, startMonth, startDay, startHour, startMinute);
        long startEpochSeconds = start.getTimeInMillis() / 1000L;
        int minutes = Math.min(wordCount, allSleepTime);
        for (int minuteOffset = 0; minuteOffset < minutes; minuteOffset++) {
            int word = ((stream[curveStart + minuteOffset * 2] & 0xFF) << 8) | (stream[curveStart + minuteOffset * 2 + 1] & 0xFF);
            int stage = (word & 0xE000) >> 13;
            int rawKind;
            switch (stage) {
                case 0:
                    rawKind = VeepooConstants.RAW_KIND_DEEP_SLEEP;
                    break;
                case 1:
                    rawKind = VeepooConstants.RAW_KIND_LIGHT_SLEEP;
                    break;
                case 2:
                    rawKind = VeepooConstants.RAW_KIND_REM_SLEEP;
                    break;
                default:
                    rawKind = VeepooConstants.RAW_KIND_AWAKE_SLEEP;
            }
            VeepooActivitySample sample = new VeepooActivitySample();
            sample.setTimestamp((int) (startEpochSeconds + minuteOffset * 60L));
            sample.setRawKind(rawKind);
            sample.setSteps(0);
            sample.setHeartRate(ActivitySample.NOT_MEASURED);
            sample.setCaloriesBurnt(0);
            sample.setDistanceMeters(0);
            sample.setDataSource(DATA_SOURCE_SLEEP_SUMMARY);
            pendingSleepSamples.add(sample);
        }
        int rawQuality = a3[15] & 0xFF;
        VeepooSleepSummarySample summary = new VeepooSleepSummarySample();
        summary.setTimestamp(start.getTimeInMillis());
        summary.setDurationMin(allSleepTime);
        summary.setQuality(rawQuality >= 1 && rawQuality <= 5 ? rawQuality : null);
        summary.setWakeCount(a3[16] & 0xFF);
        summary.setScoreGetUp(a3[9] & 0xFF);
        summary.setScoreDeep(a3[10] & 0xFF);
        summary.setScoreEfficiency(a3[11] & 0xFF);
        summary.setScoreFallAsleep(a3[12] & 0xFF);
        summary.setScoreTime(a3[13] & 0xFF);
        pendingSleepSummaries.add(summary);
        LOG.info("Parsed precise sleep record for day {}: {} minutes ({} deep, {} light)", dayIndex, minutes, deepSleepTime, lightSleepTime);
    }

    private static int le16(byte[] buf, int offset) {
        return (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
    }

    private static int be16(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
    }

    private static long le32(byte[] buf, int offset) {
        return (buf[offset] & 0xFFL)
                | ((buf[offset + 1] & 0xFFL) << 8)
                | ((buf[offset + 2] & 0xFFL) << 16)
                | ((buf[offset + 3] & 0xFFL) << 24);
    }

    /**
     * Anchors a month/day/hour/minute tuple to an actual date, assuming the current year and
     * rolling back one year when the candidate ends up in the future (December/January boundary).
     */
    private Calendar anchorSleepDate(int dayIndex, int month, int day, int hour, int minute) {
        Calendar reference = Calendar.getInstance();
        reference.add(Calendar.DAY_OF_MONTH, -dayIndex);
        Calendar candidate = Calendar.getInstance();
        candidate.clear();
        candidate.set(reference.get(Calendar.YEAR), month - 1, day, hour, minute);
        if (candidate.getTimeInMillis() > reference.getTimeInMillis() + MILLIS_PER_DAY) {
            candidate.add(Calendar.YEAR, -1);
        }
        return candidate;
    }

    private static boolean isValidDatePart(int month, int day, int hour, int minute) {
        return month >= 1 && month <= 12
                && day >= 1 && day <= 31
                && hour >= 0 && hour <= 23
                && minute >= 0 && minute <= 59;
    }

    private static String hexByte(int value) {
        return String.format("%02x", value & 0xFF);
    }

    @Override
    public void onSendConfiguration(final String config) {
        if (config == null) {
            return;
        }
        if (config.startsWith(VeepooConstants.CONFIG_WORKOUT_START_PREFIX)) {
            int sportType;
            try {
                sportType = Integer.parseInt(config.substring(VeepooConstants.CONFIG_WORKOUT_START_PREFIX.length()));
            } catch (NumberFormatException e) {
                LOG.warn("Invalid workout config key: {}", config);
                return;
            }
            liveWorkoutModule.startWorkout(sportType);
        } else if (VeepooConstants.CONFIG_WORKOUT_STOP.equals(config)) {
            liveWorkoutModule.stopWorkout();
        } else if (VeepooConstants.CONFIG_MEASURE_SPO2.equals(config)) {
            startSpo2Detect();
        } else if (VeepooConstants.CONFIG_MEASURE_TEMPERATURE.equals(config)) {
            startTemperatureDetect();
        } else if (VeepooConstants.CONFIG_MEASURE_STRESS.equals(config)) {
            startStressDetect();
        } else if (VeepooConstants.CONFIG_MEASURE_BLOOD_PRESSURE.equals(config)) {
            startBpDetect();
        } else if (VeepooConstants.CONFIG_MEASURE_BODY_COMPOSITION.equals(config)) {
            startBcDetect();
        } else if (VeepooConstants.CONFIG_MEASURE_BLOOD_LIPIDS.equals(config)) {
            startLipidDetect();
        } else if (VeepooConstants.CONFIG_MEASURE_ECG.equals(config)) {
            startEcgDetect();
        } else if (VeepooConstants.CONFIG_WOMEN_APPLY.equals(config)) {
            womenHealthModule.applyWomenSettingFromPrefs(GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()));
        } else {
            if (autoMeasureSupported) {
                autoMeasureModule.handleConfiguration(config);
            } else if (VeepooConstants.CONFIG_AUTO_MEASURE_SPO2.equals(config)) {
                handleSpo2Configuration(config);
            } else {
                handleCustomSettingToggle(config);
            }
            super.onSendConfiguration(config);
        }
    }

    /**
     * Pushes a notification to the watch (vendor "device_msg_oprate"): fixed 20-byte 0xC2 frames,
     * [1]=message type, [2]=payload length, [3]=total packet count, [4]=1-based index,
     * [5]=section (1=contact name, 2=message text, 0=no contact), [6..19]=UTF-8 payload
     * (max 14 bytes). The watch performs its own vibration and light on receipt.
     */
    @Override
    public void onNotification(NotificationSpec spec) {
        if (spec == null) {
            return;
        }
        NotificationType type = spec.getType();
        String title = firstNonEmpty(spec.getTitle(), spec.getSubject());
        String sender = spec.getSender();
        boolean hasSenderName = sender != null && !sender.trim().isEmpty();
        // Google Messages (and other SMS apps) report the caller as a phone number rather than a
        // GENERIC_SMS type; route anything carrying a phone number through the SMS layout so the
        // watch plays its SMS pattern and shows a proper contact entry.
        boolean smsLayout = type == NotificationType.GENERIC_SMS
                || firstNonEmpty(spec.getPhoneNumber()).length() > 0;
        List<byte[]> frames = new ArrayList<>();
        if (type == NotificationType.GENERIC_PHONE) {
            byte[] nameBytes = VeepooAlarmModule.truncateUtf8(firstNonEmpty(sender, spec.getPhoneNumber()), 12)
                    .getBytes(StandardCharsets.UTF_8);
            frames.add(socialMsgFrame(VeepooConstants.SOCIAL_MSG_TYPE_PHONE, 1, 1,
                    hasSenderName ? 1 : 0, nameBytes));
        } else if (smsLayout) {
            String body = firstNonEmpty(spec.getBody(), spec.getTitle());
            byte[] nameBytes = VeepooAlarmModule.truncateUtf8(firstNonEmpty(sender, spec.getPhoneNumber(), " "), 12)
                    .getBytes(StandardCharsets.UTF_8);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            int total = Math.min(1 + (bodyBytes.length + 13) / 14, socialMsgMaxPackets);
            frames.add(socialMsgFrame(VeepooConstants.SOCIAL_MSG_TYPE_SMS, total, 1,
                    hasSenderName ? 1 : 0, nameBytes));
            int offset = 0;
            for (int index = 2; index <= total; index++) {
                byte[] chunk = new byte[Math.min(14, bodyBytes.length - offset)];
                System.arraycopy(bodyBytes, offset, chunk, 0, chunk.length);
                frames.add(socialMsgFrame(VeepooConstants.SOCIAL_MSG_TYPE_SMS, total, index, 2, chunk));
                offset += chunk.length;
            }
        } else {
            String text;
            if (title.isEmpty()) {
                text = firstNonEmpty(spec.getBody());
            } else if (firstNonEmpty(spec.getBody()).isEmpty()) {
                text = title;
            } else {
                text = VeepooAlarmModule.truncateUtf8(title, 12) + ":" + spec.getBody();
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0) {
                int msgType = mapSocialMsgType(type);
                int total = Math.min((bytes.length + 13) / 14, socialMsgMaxPackets);
                int offset = 0;
                for (int index = 1; index <= total; index++) {
                    byte[] chunk = new byte[Math.min(14, bytes.length - offset)];
                    System.arraycopy(bytes, offset, chunk, 0, chunk.length);
                    frames.add(socialMsgFrame(msgType, total, index, 2, chunk));
                    offset += chunk.length;
                }
            }
        }
        if (frames.isEmpty()) {
            return;
        }
        LOG.info("Pushing notification to watch: type={} frames={}", spec.getType(), frames.size());
        try {
            TransactionBuilder builder = performInitialized("send notification");
            for (byte[] frame : frames) {
                writeFrame(builder, frame);
            }
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error pushing notification to watch", e);
        }
    }

    /** Maps a Gadgetbridge notification type onto the vendor ESocailMsg value at frame [1]. */
    private static int mapSocialMsgType(NotificationType type) {
        switch (type) {
            case WECHAT:
                return VeepooConstants.SOCIAL_MSG_TYPE_WECHAT;
            case QQ:
                return VeepooConstants.SOCIAL_MSG_TYPE_QQ;
            case TWITTER:
                return VeepooConstants.SOCIAL_MSG_TYPE_TWITTER;
            case LINKEDIN:
                return VeepooConstants.SOCIAL_MSG_TYPE_LINKEDIN;
            case WHATSAPP:
                return VeepooConstants.SOCIAL_MSG_TYPE_WHATSAPP;
            case LINE:
                return VeepooConstants.SOCIAL_MSG_TYPE_LINE;
            case INSTAGRAM:
                return VeepooConstants.SOCIAL_MSG_TYPE_INSTAGRAM;
            case SNAPCHAT:
                return VeepooConstants.SOCIAL_MSG_TYPE_SNAPCHAT;
            case SKYPE:
                return VeepooConstants.SOCIAL_MSG_TYPE_SKYPE;
            case GMAIL:
                return VeepooConstants.SOCIAL_MSG_TYPE_GMAIL;
            case TELEGRAM:
                return VeepooConstants.SOCIAL_MSG_TYPE_TELEGRAM;
            case KAKAO_TALK:
                return VeepooConstants.SOCIAL_MSG_TYPE_KAKAOTALK;
            case VIBER:
                return VeepooConstants.SOCIAL_MSG_TYPE_VIBER;
            default:
                return VeepooConstants.SOCIAL_MSG_TYPE_OTHER;
        }
    }

    /** Builds one 20-byte 0xC2 frame; the payload must be at most 14 UTF-8 bytes. */
    private static byte[] socialMsgFrame(int msgType, int total, int index, int section, byte[] payload) {
        if (payload.length > 14) {
            throw new IllegalArgumentException("0xC2 payloads must be at most 14 bytes");
        }
        byte[] frame = new byte[VeepooConstants.PACKET_SIZE];
        frame[0] = VeepooConstants.OP_SOCIAL_MESSAGE;
        frame[1] = (byte) msgType;
        frame[2] = (byte) payload.length;
        frame[3] = (byte) total;
        frame[4] = (byte) index;
        frame[5] = (byte) section;
        System.arraycopy(payload, 0, frame, 6, payload.length);
        return frame;
    }

    @Override
    public void onFindDevice(boolean start) {
        try {
            byte[] frame = new byte[VeepooConstants.PACKET_SIZE];
            frame[0] = VeepooConstants.OP_FIND_DEVICE;
            frame[1] = (byte) (start ? VeepooConstants.FIND_DEVICE_START : VeepooConstants.FIND_DEVICE_STOP);
            TransactionBuilder builder = performInitialized("find device");
            writeFrame(builder, frame);
            builder.queue();
            LOG.info("Find device: {}", start ? "start" : "stop");
        } catch (IOException e) {
            LOG.error("Error sending find-device command", e);
        }
    }

    /** Status frame for 0xB5 find-device: [1] echoes the op (0x0A start / 0x0B stop) and [2] is
     * the state (0 unsupported, 1 finding, 2 found, 3 stopped). [1]=0/2 are band-initiated
     * find-phone events. */
    private void handleFindDeviceAck(byte[] data) {
        if (data.length < 2) {
            return;
        }
        int operation = data[1] & 0xFF;
        if (operation == VeepooConstants.FIND_DEVICE_START || operation == VeepooConstants.FIND_DEVICE_STOP) {
            if (data.length >= 3) {
                int state = data[2] & 0xFF;
                if (state == 1) {
                    LOG.info("Find device: finding");
                } else if (state == 2) {
                    LOG.info("Find device: found");
                } else if (state == 3) {
                    LOG.info("Find device: stopped");
                } else if (state == 0) {
                    LOG.warn("Find device: band reports not supported");
                } else {
                    LOG.debug("Find device status: op={} state={}", operation, state);
                }
            }
        } else if (operation == 0 || operation == 2) {
            LOG.info("Band-initiated find event from watch (op={})", operation);
        } else {
            LOG.debug("Find-device frame: op={}", operation);
        }
    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        alarmModule.onSetAlarms(alarms);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /** Fetches day 0 of the workout history (called by VeepooLiveWorkoutModule's post-stop task). */
    void startPostWorkoutFetch() {
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.IDLE) {
                LOG.info("A data fetch is already running; it will pick up the finished workout");
                return;
            }
            fetchPhase = FetchPhase.SPORT;
            sportDayIndex = 0;
        }
        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
        // The firmware only writes a stopped session into the day-0 history slot when a new
        // phone session is established (an A1 password-confirm frame), so re-send A1+A5 in
        // the same transaction as the day-0 read.
        sportDayFrames.clear();
        scheduleSportWatchdog();
        continueFetch("fetch workout history day 0", builder -> {
            writeFrame(builder, buildPwdConfirmTimeFrame());
            writeFrame(builder, buildSyncTimeFrame());
            writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_ORIGIN, (byte) 0});
        });
    }

    static String hexBytes(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Opcode 0xD0 stream: [1] = bpm (1/2 = not worn), [5] = sensor status (busy unless 0/2/3).
     * While a single-shot read is active the first plausible reading completes it; frames
     * outside a read are autonomous auto-measure pushes and are persisted silently. The band
     * also sends a compact push form ({0xD0, bpm}, no status byte) from its monitor feature.
     */
    private void handleHeartRateMeasurement(byte[] data) {
        if (data.length < 2) {
            LOG.debug("Dropping short 0xD0 frame: {} bytes", data.length);
            return;
        }
        final boolean longForm = data.length >= 6;
        int bpm = data[1] & 0xFF;
        int status = longForm ? data[5] & 0xFF : 0;
        synchronized (this) {
            if (!hrDetectActive) {
                persistAutonomousHeartRate(bpm, status);
                return;
            }
        }
        if (!longForm) {
            LOG.debug("Ignoring compact 0xD0 push during a manual heart-rate detect");
            return;
        }
        if (status != 0 && status != 2 && status != 3) {
            LOG.debug("Heart rate detect busy: bpm={} status={}", bpm, status);
            return;
        }
        if (bpm == 1 || bpm == 2) {
            synchronized (this) {
                hrWearErrorSeen = true;
            }
            LOG.debug("Heart rate detect: band not worn");
            return;
        }
        if (bpm < 30 || bpm > 210) {
            LOG.debug("Ignoring implausible heart rate measurement: {}", bpm);
            return;
        }
        LOG.info("Heart rate measurement: {} bpm", bpm);
        finishHrDetect(bpm);
    }

    /** Persists an autonomous auto-measure heart-rate push (no detect session in progress). */
    private void persistAutonomousHeartRate(int bpm, int status) {
        if (status != 0 && status != 2 && status != 3) {
            LOG.debug("Ignoring autonomous heart rate push: sensor status {} (expected 0/2/3)", status);
            return;
        }
        if (bpm == 1 || bpm == 2 || bpm < 30 || bpm > 210) {
            LOG.debug("Ignoring autonomous heart rate push: bpm {} not plausible", bpm);
            return;
        }
        LOG.info("Autonomous heart rate push: {} bpm", bpm);
        saveHeartRateSample(bpm);
    }

    /** Stores a single heart rate sample in the DB; returns it so callers can announce it. */
    private GenericHeartRateSample saveHeartRateSample(int bpm) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();

            GenericHeartRateSample sample = new GenericHeartRateSample();
            sample.setTimestamp(System.currentTimeMillis());
            sample.setHeartRate(bpm);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider(getDevice(), session).addSample(sample);
            return sample;
        } catch (Exception e) {
            LOG.error("Error saving heart rate sample", e);
            return null;
        }
    }

    /**
     * Announces the measured sample to listeners such as HeartRateDialog, which hides its
     * spinner and shows the value; the user dismisses the dialog from there.
     */
    private void notifyVitalSample(Serializable sample) {
        Intent intent = new Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                .putExtra(GBDevice.EXTRA_DEVICE, getDevice())
                .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, sample);
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
    }

    @Override
    public void onHeartRateTest() {
        startHeartRateDetect();
    }

    /**
     * On-demand heart rate measurement, mirroring the vendor app's home-screen flow: start the
     * 0xD0 stream ({0xD0, 1}), keep the first plausible reading, then stop the stream
     * ({0xD0, 0}). The band keeps streaming until stopped or the timeout fires.
     */
    private void startHeartRateDetect() {
        synchronized (this) {
            if (hrDetectActive) {
                LOG.warn("A heart rate read is already in progress");
                GB.toast(getContext(), R.string.veepoo_manual_hr_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            synchronized (syncLock) {
                if (fetchPhase != FetchPhase.IDLE) {
                    LOG.warn("Heart rate read ignored: a data fetch is in progress");
                    GB.toast(getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                    return;
                }
            }
            hrDetectActive = true;
            hrWearErrorSeen = false;
        }
        try {
            TransactionBuilder builder = performInitialized("heart rate detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_HR_REALTIME, 0x01});
            builder.queue();
            mainHandler.postDelayed(hrDetectTimeout, 45_000L);
            LOG.info("Heart rate detection started");
        } catch (IOException e) {
            synchronized (this) {
                hrDetectActive = false;
            }
            LOG.error("Error starting heart rate detection", e);
        }
    }

    /** Sends the 0xD0 stop frame; errors are only logged. */
    private void sendStopHeartRateDetect() {
        try {
            TransactionBuilder builder = performInitialized("stop heart rate detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_HR_REALTIME, 0x00});
            builder.queue();
            LOG.info("Heart rate detection stopped");
        } catch (IOException e) {
            LOG.warn("Error stopping heart rate detection", e);
        }
    }

    /** Completes the single-shot read: stops the stream, then stores or reports the result. */
    private void finishHrDetect(Integer bpm) {
        boolean wearError;
        synchronized (this) {
            if (!hrDetectActive) {
                return;
            }
            hrDetectActive = false;
            wearError = hrWearErrorSeen;
        }
        mainHandler.removeCallbacks(hrDetectTimeout);
        sendStopHeartRateDetect();
        if (bpm != null) {
            GenericHeartRateSample sample = saveHeartRateSample(bpm);
            if (sample != null) {
                notifyVitalSample(sample);
            }
            GB.toast(getContext(), String.format(getContext().getString(R.string.veepoo_hr_ok), bpm), Toast.LENGTH_SHORT, GB.INFO);
        } else if (wearError) {
            GB.toast(getContext(), R.string.veepoo_hr_not_worn, Toast.LENGTH_SHORT, GB.WARN);
        } else {
            GB.toast(getContext(), R.string.veepoo_hr_no_reading, Toast.LENGTH_SHORT, GB.WARN);
        }
    }

    /** A7 package 1 SpO2H type values the vendor app treats as supported. */
    private static boolean isSpo2hTypeSupported(int type) {
        return (type >= 1 && type <= 9) || type == 0xFD || type == 0xFE || type == 0xE0;
    }

    // ---------------------------------------------------------------------
    // SpO2 (blood oxygen) single-shot detection, opcode 0x80
    // ---------------------------------------------------------------------

    /**
     * On-demand SpO2 measurement: start the 0x80 stream ({0x80,1,2}); frames report progress and
     * finally a percentage; stop the stream ({0x80,2,2}) on completion or timeout.
     */
    private void startSpo2Detect() {
        synchronized (this) {
            if (spo2DetectActive) {
                LOG.warn("A SpO2 measurement is already in progress");
                GB.toast(getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            synchronized (syncLock) {
                if (fetchPhase != FetchPhase.IDLE) {
                    LOG.warn("SpO2 measurement ignored: a data fetch is in progress");
                    GB.toast(getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                    return;
                }
            }
            spo2DetectActive = true;
            spo2WearErrorSeen = false;
        }
        try {
            TransactionBuilder builder = performInitialized("spo2 detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_SPO2_REALTIME,
                    (byte) VeepooConstants.SPO2_DETECT_START, (byte) VeepooConstants.SPO2_DETECT_STOP});
            builder.queue();
            mainHandler.postDelayed(spo2DetectTimeout, 90_000L);
            mainHandler.postDelayed(spo2IdleWatchdog, 45_000L);
            LOG.info("SpO2 detection started");
        } catch (IOException e) {
            synchronized (this) {
                spo2DetectActive = false;
            }
            LOG.error("Error starting SpO2 detection", e);
        }
    }

    /**
     * Opcode 0x80 frame: [1]=state (0 not supported, 1 open, 2 close), [2]=what the device is
     * doing (4 = measuring SpO2, 10 = charging low, 11 = finished, 12 = not worn,
     * 13 = keep still), [3]=SpO2 percent (75-99 valid, 1 = not worn), [4]!=0 => measuring with
     * [5] progress; [6]==6 => [7]=pulse rate.
     */
    private void handleSpo2Frame(byte[] data) {
        if (data.length < 6) {
            LOG.debug("Dropping short 0x80 frame: {}", hexBytes(data));
            return;
        }
        synchronized (this) {
            if (!spo2DetectActive) {
                persistAutonomousSpo2(data);
                return;
            }
        }
        LOG.debug("SpO2 frame: {}", hexBytes(data));
        mainHandler.removeCallbacks(spo2IdleWatchdog);
        mainHandler.postDelayed(spo2IdleWatchdog, 45_000L);
        int state = data[1] & 0xFF;
        if (state == 0) {
            LOG.warn("Band reports SpO2 as not supported");
            finishSpo2Detect(null, null, VITAL_FAIL_NOT_SUPPORTED);
            return;
        }
        int deviceState = data[2] & 0xFF;
        int value = data[3] & 0xFF;
        Integer hr = null;
        if (data.length >= 8 && data[6] == 6) {
            int rate = data[7] & 0xFF;
            if (rate >= 30 && rate <= 210) {
                hr = rate;
            }
        }
        if (value == 1 || deviceState == 12) {
            synchronized (this) {
                spo2WearErrorSeen = true;
            }
            LOG.debug("SpO2 detect: band not worn");
            finishSpo2Detect(null, hr, VITAL_FAIL_WEAR);
            return;
        }
        if (deviceState == 10) {
            finishSpo2Detect(null, hr, VITAL_FAIL_LOW_BATTERY);
            return;
        }
        int checking = data[4] & 0xFF;
        if (checking == 1 || checking == 2) {
            LOG.debug("SpO2 in progress: {}% (deviceState={})", data[5] & 0xFF, deviceState);
            return;
        }
        if (value >= 75 && value <= 99) {
            finishSpo2Detect(value, hr, VITAL_FAIL_NONE);
        } else if (state == 2 || deviceState == 11) {
            // The band closed the measurement or finished without a valid value.
            LOG.debug("SpO2 measurement ended without a reading (state={}, deviceState={})", state, deviceState);
            finishSpo2Detect(null, hr, VITAL_FAIL_NONE);
        } else {
            LOG.debug("Ignoring implausible SpO2 frame: value={} state={} deviceState={}", value, state, deviceState);
        }
    }

    /** Persists an autonomous auto-measure SpO2 push (no detect session in progress). */
    private void persistAutonomousSpo2(byte[] data) {
        int state = data[1] & 0xFF;
        if (state == 0) {
            LOG.debug("Ignoring autonomous SpO2 push: state byte 0");
            return;
        }
        int deviceState = data[2] & 0xFF;
        int value = data[3] & 0xFF;
        Integer hr = null;
        if (data.length >= 8 && data[6] == 6) {
            int rate = data[7] & 0xFF;
            if (rate >= 30 && rate <= 210) {
                hr = rate;
            }
        }
        if (value == 1 || deviceState == 12) { // not worn
            LOG.debug("Ignoring autonomous SpO2 push: not worn (value={} deviceState={})", value, deviceState);
            return;
        }
        int checking = data[4] & 0xFF;
        if (checking == 1 || checking == 2) { // still measuring
            LOG.debug("Ignoring autonomous SpO2 push: still measuring (checking={}) value={}", checking, value);
            return;
        }
        if (value >= 75 && value <= 99) {
            LOG.info("Autonomous SpO2 push: {}% (hr={})", value, hr);
            saveSpo2Sample(value);
            if (hr != null) {
                saveHeartRateSample(hr);
            }
        }
    }

    /** Completes the SpO2 read: stops the stream, then stores or reports the result. */
    private void finishSpo2Detect(Integer spo2, Integer hr, int failure) {
        synchronized (this) {
            if (!spo2DetectActive) {
                return;
            }
            spo2DetectActive = false;
        }
        mainHandler.removeCallbacks(spo2DetectTimeout);
        mainHandler.removeCallbacks(spo2IdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_SPO2_REALTIME,
                VeepooConstants.SPO2_DETECT_STOP, VeepooConstants.SPO2_DETECT_STOP);
        if (spo2 != null) {
            GenericSpo2Sample sample = saveSpo2Sample(spo2);
            if (sample != null) {
                notifyVitalSample(sample);
                GB.toast(getContext(), getContext().getString(R.string.veepoo_spo2_ok, spo2), Toast.LENGTH_SHORT, GB.INFO);
            }
            if (hr != null) {
                saveHeartRateSample(hr);
            }
        } else {
            toastVitalFailure(R.string.veepoo_spo2_no_reading, failure);
        }
    }

    /** Stores a SpO2 sample; returns the saved sample, or null on failure. */
    private GenericSpo2Sample saveSpo2Sample(int spo2) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();

            GenericSpo2Sample sample = new GenericSpo2Sample();
            sample.setTimestamp(System.currentTimeMillis());
            sample.setSpo2(spo2);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new GenericSpo2SampleProvider(getDevice(), session).addSample(sample);
            return sample;
        } catch (Exception e) {
            LOG.error("Error saving SpO2 sample", e);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Body temperature single-shot detection, opcode 0x87
    // ---------------------------------------------------------------------

    /** On-demand body-temperature measurement: start {0x87,1,1}, stop {0x87,1,2}. */
    private void startTemperatureDetect() {
        synchronized (this) {
            if (tempDetectActive) {
                LOG.warn("A temperature measurement is already in progress");
                GB.toast(getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            synchronized (syncLock) {
                if (fetchPhase != FetchPhase.IDLE) {
                    LOG.warn("Temperature measurement ignored: a data fetch is in progress");
                    GB.toast(getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                    return;
                }
            }
            tempDetectActive = true;
        }
        try {
            TransactionBuilder builder = performInitialized("temperature detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_TEMP_REALTIME,
                    (byte) VeepooConstants.TEMP_DETECT_START, (byte) VeepooConstants.TEMP_DETECT_START});
            builder.queue();
            mainHandler.postDelayed(tempDetectTimeout, 60_000L);
            mainHandler.postDelayed(tempIdleWatchdog, 45_000L);
            LOG.info("Temperature detection started");
        } catch (IOException e) {
            synchronized (this) {
                tempDetectActive = false;
            }
            LOG.error("Error starting temperature detection", e);
        }
    }

    /**
     * Opcode 0x87 frame (per vendor TemptureDetectOperater): [2]=oprate (0=invalid, 2=stop ack,
     * else running), [3]=deviceState (0/7=running, 1..6=busy, 8=low battery), [4]=progress %,
     * [5..6] uint16 BE / 10.0 = body temperature, [7..8] uint16 BE / 10.0 = base (skin)
     * temperature. The band streams one frame per second while state=7 and progress climbs;
     * the measurement completes with the frame reaching progress >= 100 carrying a reading.
     */
    private void handleTemperatureFrame(byte[] data) {
        if (data.length < 9) {
            LOG.debug("Dropping short 0x87 frame: {}", hexBytes(data));
            return;
        }
        synchronized (this) {
            if (!tempDetectActive) {
                persistAutonomousTemperature(data);
                return;
            }
        }
        LOG.debug("Temperature frame: {}", hexBytes(data));
        mainHandler.removeCallbacks(tempIdleWatchdog);
        mainHandler.postDelayed(tempIdleWatchdog, 45_000L);
        int oprate = data[2] & 0xFF;
        if (oprate == 0) {
            LOG.warn("Temperature measurement rejected as invalid by the band");
            finishTemperatureDetect(null, null, VITAL_FAIL_NONE);
            return;
        }
        if (oprate != 2) {
            int deviceState = data[3] & 0xFF;
            if (deviceState >= 1 && deviceState <= 6) {
                LOG.warn("Temperature measurement: band is busy (deviceState={})", deviceState);
                finishTemperatureDetect(null, null, VITAL_FAIL_BUSY);
                return;
            }
            if (deviceState == 8) {
                LOG.warn("Temperature measurement: low battery");
                finishTemperatureDetect(null, null, VITAL_FAIL_LOW_BATTERY);
                return;
            }
        }
        int progress = data[4] & 0xFF;
        Float body = plausibleTemperature(data[5], data[6]);
        Float base = plausibleTemperature(data[7], data[8]);
        if (progress >= 100 && body != null) {
            LOG.info("Temperature measurement complete: body={} C, base={} C", body, base);
            finishTemperatureDetect(body, base, VITAL_FAIL_NONE);
            return;
        }
        LOG.debug("Temperature in progress: {}% (raw [5..8]={})", progress,
                String.format("%02X %02X %02X %02X", data[5] & 0xFF, data[6] & 0xFF, data[7] & 0xFF, data[8] & 0xFF));
    }

    /** Persists an autonomous auto-measure temperature push (no detect session in progress). */
    private void persistAutonomousTemperature(byte[] data) {
        if ((data[2] & 0xFF) == 0) {
            LOG.debug("Ignoring autonomous temperature push: data[2]=0");
            return;
        }
        int progress = data[4] & 0xFF;
        Float body = plausibleTemperature(data[5], data[6]);
        if (progress >= 100 && body != null) {
            LOG.info("Autonomous temperature push: {} C", body);
            saveTemperatureSample(body, TemperatureSample.TYPE_BODY);
        } else {
            LOG.debug("Ignoring autonomous temperature push: progress={} body={}", progress, body);
        }
    }

    /**
     * Interprets two raw bytes as a temperature in tenths of a degree, accepting whichever byte
     * order (little- or big-endian) yields a plausible human body/skin temperature. Returns null
     * when neither order is plausible (i.e. no valid reading in this frame yet).
     */
    private Float plausibleTemperature(byte lowIndex, byte highIndex) {
        int le = (lowIndex & 0xFF) | ((highIndex & 0xFF) << 8);
        float tLe = le / 10.0f;
        if (tLe >= 20.0f && tLe <= 45.0f) {
            return tLe;
        }
        int be = (highIndex & 0xFF) | ((lowIndex & 0xFF) << 8);
        float tBe = be / 10.0f;
        if (tBe >= 20.0f && tBe <= 45.0f) {
            return tBe;
        }
        return null;
    }

    /** Result of an on-demand temperature measurement: the estimated body and the raw skin (base) readings. */
    public static final class VeepooTemperatureMeasurementResult implements Serializable {
        private static final long serialVersionUID = 1L;
        public final float bodyCelsius;
        public final Float skinCelsius;

        public VeepooTemperatureMeasurementResult(float bodyCelsius, Float skinCelsius) {
            this.bodyCelsius = bodyCelsius;
            this.skinCelsius = skinCelsius;
        }
    }

    /** Completes the temperature read: stops the stream, then stores or reports the result. */
    private void finishTemperatureDetect(Float body, Float base, int failure) {
        synchronized (this) {
            if (!tempDetectActive) {
                return;
            }
            tempDetectActive = false;
        }
        mainHandler.removeCallbacks(tempDetectTimeout);
        mainHandler.removeCallbacks(tempIdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_TEMP_REALTIME,
                VeepooConstants.TEMP_DETECT_START, VeepooConstants.TEMP_DETECT_STOP);
        if (body != null) {
            final GenericTemperatureSample bodySample = saveTemperatureSample(body, TemperatureSample.TYPE_BODY);
            final GenericTemperatureSample skinSample = base == null ? null : saveTemperatureSample(base, TemperatureSample.TYPE_SKIN);
            if (bodySample == null || (base != null && skinSample == null)) {
                LOG.warn("Temperature measurement completed but sample persistence failed");
            }
            notifyVitalSample(new VeepooTemperatureMeasurementResult(body, base));
            GB.toast(getContext(), getContext().getString(R.string.veepoo_temp_ok, body), Toast.LENGTH_SHORT, GB.INFO);
        } else {
            toastVitalFailure(R.string.veepoo_temp_no_reading, failure);
        }
    }

    /** Stores a temperature sample of the given type (TYPE_BODY or TYPE_SKIN); returns it, or null on failure. */
    private GenericTemperatureSample saveTemperatureSample(float celsius, int temperatureType) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();

            GenericTemperatureSample sample = new GenericTemperatureSample();
            sample.setTimestamp(System.currentTimeMillis());
            sample.setTemperature(celsius);
            sample.setTemperatureType(temperatureType);
            sample.setTemperatureLocation(TemperatureSample.LOCATION_WRIST);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new GenericTemperatureSampleProvider(getDevice(), session).addSample(sample);
            return sample;
        } catch (Exception e) {
            LOG.error("Error saving temperature sample", e);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Stress (pressure) single-shot detection, opcode 0x89 sub-parameter 6
    // ---------------------------------------------------------------------

    /** On-demand stress measurement: start {0x89,6,1}, stop {0x89,6,2}. */
    private void startStressDetect() {
        synchronized (this) {
            if (stressDetectActive) {
                LOG.warn("A stress measurement is already in progress");
                GB.toast(getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            synchronized (syncLock) {
                if (fetchPhase != FetchPhase.IDLE) {
                    LOG.warn("Stress measurement ignored: a data fetch is in progress");
                    GB.toast(getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                    return;
                }
            }
            stressDetectActive = true;
        }
        try {
            TransactionBuilder builder = performInitialized("stress detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_STRESS_REALTIME,
                    (byte) VeepooConstants.STRESS_DETECT_METRIC, (byte) VeepooConstants.STRESS_DETECT_START});
            builder.queue();
            mainHandler.postDelayed(stressDetectTimeout, 120_000L);
            mainHandler.postDelayed(stressIdleWatchdog, 60_000L);
            LOG.info("Stress detection started");
        } catch (IOException e) {
            synchronized (this) {
                stressDetectActive = false;
            }
            LOG.error("Error starting stress detection", e);
        }
    }

    /**
     * Opcode 0x89 frame: [1] must be 6 (stress metric), [2]=1 progress / 2 stop. Progress frames
     * carry [3] status (0 ok, 1/3 busy, 2 low battery, 4 not worn), [4] progress percent and
     * [5] the stress value once progress reaches 100.
     */
    private void handleStressFrame(byte[] data) {
        if (data.length < 6 || data[1] != 6) {
            LOG.debug("Dropping unexpected 0x89 frame: {}", hexBytes(data));
            return;
        }
        synchronized (this) {
            if (!stressDetectActive) {
                persistAutonomousStress(data);
                return;
            }
        }
        LOG.debug("Stress frame: {}", hexBytes(data));
        int cmd = data[2] & 0xFF;
        if (cmd == VeepooConstants.STRESS_DETECT_STOP) {
            LOG.info("Band stopped the stress measurement");
            finishStressDetect(null, VITAL_FAIL_NONE);
            return;
        }
        int status = data[3] & 0xFF;
        if (status == 0) {
            int progress = data[4] & 0xFF;
            int value = data[5] & 0xFF;
            if (progress == 100 && value > 0) {
                finishStressDetect(value, VITAL_FAIL_NONE);
            } else {
                LOG.debug("Stress in progress: {}% (value={})", progress, value);
            }
        } else if (status == 2) {
            finishStressDetect(null, VITAL_FAIL_LOW_BATTERY);
        } else if (status == 4) {
            finishStressDetect(null, VITAL_FAIL_WEAR);
        } else {
            LOG.warn("Stress detect: device busy (status={})", status);
            finishStressDetect(null, VITAL_FAIL_BUSY);
        }
    }

    /** Persists an autonomous auto-measure stress push (no detect session in progress). */
    private void persistAutonomousStress(byte[] data) {
        int cmd = data[2] & 0xFF;
        if (cmd != VeepooConstants.STRESS_DETECT_START) {
            LOG.debug("Ignoring autonomous 0x89 frame: cmd={} (expected stress detect)", cmd);
            return;
        }
        int status = data[3] & 0xFF;
        if (status != 0) {
            LOG.debug("Ignoring autonomous stress push: status={}", status);
            return;
        }
        int progress = data[4] & 0xFF;
        int value = data[5] & 0xFF;
        if (progress == 100 && value > 0) {
            LOG.info("Autonomous stress push: {}", value);
            saveStressSample(value);
        } else {
            LOG.debug("Ignoring autonomous stress push: progress={} value={}", progress, value);
        }
    }

    /** Completes the stress read: stops the stream, then stores or reports the result. */
    private void finishStressDetect(Integer value, int failure) {
        synchronized (this) {
            if (!stressDetectActive) {
                return;
            }
            stressDetectActive = false;
        }
        mainHandler.removeCallbacks(stressDetectTimeout);
        mainHandler.removeCallbacks(stressIdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_STRESS_REALTIME,
                VeepooConstants.STRESS_DETECT_METRIC, VeepooConstants.STRESS_DETECT_STOP);
        if (value != null) {
            GenericStressSample sample = saveStressSample(value);
            if (sample != null) {
                notifyVitalSample(sample);
                GB.toast(getContext(), getContext().getString(R.string.veepoo_stress_ok, value), Toast.LENGTH_SHORT, GB.INFO);
            }
        } else {
            toastVitalFailure(R.string.veepoo_stress_no_reading, failure);
        }
    }

    /** Stores a stress sample; returns the saved sample, or null on failure. */
    private GenericStressSample saveStressSample(int value) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();

            GenericStressSample sample = new GenericStressSample();
            sample.setTimestamp(System.currentTimeMillis());
            sample.setStress(value);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new GenericStressSampleProvider(getDevice(), session).addSample(sample);
            return sample;
        } catch (Exception e) {
            LOG.error("Error saving stress sample", e);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Blood pressure single-shot detection, opcode 0x90
    // ---------------------------------------------------------------------

    /** On-demand blood pressure measurement in public (uncalibrated) mode: start {0x90,1,0}, stop {0x90,0,0}. */
    private void startBpDetect() {
        synchronized (this) {
            if (bpDetectActive) {
                LOG.warn("A blood pressure measurement is already in progress");
                GB.toast(getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            synchronized (syncLock) {
                if (fetchPhase != FetchPhase.IDLE) {
                    LOG.warn("Blood pressure measurement ignored: a data fetch is in progress");
                    GB.toast(getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                    return;
                }
            }
            bpDetectActive = true;
        }
        try {
            TransactionBuilder builder = performInitialized("bp detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_BP_REALTIME,
                    (byte) VeepooConstants.BP_DETECT_START, (byte) VeepooConstants.BP_DETECT_MODEL_PUBLIC});
            builder.queue();
            mainHandler.postDelayed(bpDetectTimeout, 120_000L);
            mainHandler.postDelayed(bpIdleWatchdog, 60_000L);
            LOG.info("Blood pressure detection started");
        } catch (IOException e) {
            synchronized (this) {
                bpDetectActive = false;
            }
            LOG.error("Error starting blood pressure detection", e);
        }
    }

    /**
     * Opcode 0x90 frame: [1]=systolic, [2]=diastolic, [3]=progress, [4]=status, optional
     * [5]=hasProgress (1 = the device streams progress frames). Status codes: 1/2/4/5/0xFF
     * busy, 0xFE low battery, 0xFD charging, 0xFC not worn; anything else is a data frame.
     * Systolic/diastolic are 0 while the measurement is running and carry the result at the end.
     * The monitor feature also sends a compact push ({0x90, sys, dia, pulse}) without a status
     * byte; outside a manual detect it is persisted directly.
     */
    private void handleBpFrame(byte[] data) {
        if (data.length < 4) {
            LOG.debug("Dropping short 0x90 frame: {} bytes", data.length);
            return;
        }
        final boolean longForm = data.length >= 6;
        synchronized (this) {
            if (!bpDetectActive) {
                if (longForm) {
                    persistAutonomousBp(data);
                } else {
                    persistCompactAutonomousBp(data);
                }
                return;
            }
        }
        if (!longForm) {
            LOG.debug("Ignoring compact 0x90 push during a manual blood-pressure detect");
            return;
        }
        LOG.debug("Blood pressure frame: {}", hexBytes(data));
        int status = data[4] & 0xFF;
        if (status == 0xFE) {
            finishBpDetect(null, null, VITAL_FAIL_LOW_BATTERY);
            return;
        }
        if (status == 0xFC) {
            finishBpDetect(null, null, VITAL_FAIL_WEAR);
            return;
        }
        if (status == 1 || status == 2 || status == 4 || status == 5 || status == 0xFF) {
            LOG.warn("Blood pressure detect: device busy (status={})", status);
            finishBpDetect(null, null, VITAL_FAIL_BUSY);
            return;
        }
        int systolic = data[1] & 0xFF;
        int diastolic = data[2] & 0xFF;
        if (systolic >= 60 && systolic <= 300 && diastolic >= 20 && diastolic <= 200) {
            finishBpDetect(systolic, diastolic, VITAL_FAIL_NONE);
        } else if (systolic > 0 || diastolic > 0) {
            LOG.warn("Blood pressure result out of range: {}/{}", systolic, diastolic);
            finishBpDetect(null, null, VITAL_FAIL_NONE);
        } else {
            LOG.debug("Blood pressure in progress: {}%", data[3] & 0xFF);
        }
    }

    /** Persists an autonomous auto-measure blood-pressure push (no detect session in progress). */
    private void persistAutonomousBp(byte[] data) {
        int status = data[4] & 0xFF;
        if (status != 0) {
            LOG.debug("Ignoring autonomous blood pressure push: status={}", status);
            return;
        }
        int systolic = data[1] & 0xFF;
        int diastolic = data[2] & 0xFF;
        if (systolic >= 60 && systolic <= 300 && diastolic >= 20 && diastolic <= 200) {
            LOG.info("Autonomous blood pressure push: {}/{}", systolic, diastolic);
            saveBpSample(systolic, diastolic);
        } else if (systolic > 0 || diastolic > 0) {
            LOG.debug("Ignoring autonomous blood pressure push out of range: {}/{}", systolic, diastolic);
        }
    }

    /** Persists the compact monitor BP push ({0x90, sys, dia, pulse}). */
    private void persistCompactAutonomousBp(byte[] data) {
        int systolic = data[1] & 0xFF;
        int diastolic = data[2] & 0xFF;
        if (systolic >= 60 && systolic <= 300 && diastolic >= 20 && diastolic <= 200) {
            int pulse = data.length >= 4 ? data[3] & 0xFF : -1;
            LOG.info("Autonomous blood pressure push (compact): {}/{} (pulse {})", systolic, diastolic, pulse);
            saveBpSample(systolic, diastolic);
        } else {
            LOG.debug("Ignoring compact 0x90 push out of range: {}/{}", systolic, diastolic);
        }
    }

    /** Completes the blood pressure read: stops the stream, then stores or reports the result. */
    private void finishBpDetect(Integer systolic, Integer diastolic, int failure) {
        synchronized (this) {
            if (!bpDetectActive) {
                return;
            }
            bpDetectActive = false;
        }
        mainHandler.removeCallbacks(bpDetectTimeout);
        mainHandler.removeCallbacks(bpIdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_BP_REALTIME,
                VeepooConstants.BP_DETECT_STOP, VeepooConstants.BP_DETECT_MODEL_PUBLIC);
        if (systolic != null) {
            GenericBloodPressureSample sample = saveBpSample(systolic, diastolic);
            if (sample != null) {
                notifyVitalSample(sample);
                GB.toast(getContext(), getContext().getString(R.string.veepoo_bp_ok, systolic, diastolic), Toast.LENGTH_SHORT, GB.INFO);
            }
        } else {
            toastVitalFailure(R.string.veepoo_bp_no_reading, failure);
        }
    }

    /** Stores a blood pressure sample; returns the saved sample, or null on failure. */
    private GenericBloodPressureSample saveBpSample(int systolic, int diastolic) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();

            GenericBloodPressureSample sample = new GenericBloodPressureSample();
            sample.setTimestamp(System.currentTimeMillis());
            sample.setBpSystolic(systolic);
            sample.setBpDiastolic(diastolic);
            sample.setMeanArterialPressure((systolic + 2 * diastolic) / 3);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new GenericBloodPressureSampleProvider(getDevice(), session).addSample(sample);
            return sample;
        } catch (Exception e) {
            LOG.error("Error saving blood pressure sample", e);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Body composition single-shot detection, opcode 0x93
    // ---------------------------------------------------------------------

    /** On-demand body composition measurement: start {0x93,4,1}, stop {0x93,4,2}. */
    private void startBcDetect() {
        synchronized (this) {
            if (bcDetectActive) {
                LOG.warn("A body composition measurement is already in progress");
                GB.toast(getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            synchronized (syncLock) {
                if (fetchPhase != FetchPhase.IDLE) {
                    LOG.warn("Body composition measurement ignored: a data fetch is in progress");
                    GB.toast(getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                    return;
                }
            }
            bcDetectActive = true;
        }
        try {
            TransactionBuilder builder = performInitialized("body composition detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_BODY_COMPOSITION_REALTIME,
                    (byte) VeepooConstants.BC_DETECT_FUNCTION, (byte) VeepooConstants.BC_DETECT_START});
            builder.queue();
            mainHandler.postDelayed(bcDetectTimeout, 300_000L);
            mainHandler.postDelayed(bcIdleWatchdog, 90_000L);
            LOG.info("Body composition detection started");
        } catch (IOException e) {
            synchronized (this) {
                bcDetectActive = false;
            }
            LOG.error("Error starting body composition detection", e);
        }
    }

    /**
     * Opcode 0x93 frame: [1]=4, [2]=event (1 detect / 2 stop), [3]=state. State 0 streams
     * progress at [4]; state 1 carries data frames with [4]=seq (1-based), [5]=total and a
     * 14-byte chunk at [6..19]. The concatenated chunks must start with 0x9C followed by 14
     * big-endian u16 values (divided by 10): BMI, body fat %, fat weight, FFM, muscle %,
     * muscle mass, subcutaneous fat, body water %, water content, skeletal muscle %,
     * bone mass, protein %, protein mass, BMR. States 2/3/4 are failed/busy/low battery.
     */
    private void handleBodyCompositionFrame(byte[] data) {
        if (data.length < 20 || data[1] != VeepooConstants.BC_DETECT_FUNCTION) {
            return;
        }
        synchronized (this) {
            if (!bcDetectActive) {
                return;
            }
        }
        LOG.debug("Body composition frame: {}", hexBytes(data));
        if (data[2] == 2) {
            return; // stop acknowledgement
        }
        int state = data[3] & 0xFF;
        if (state == 0) {
            LOG.debug("Body composition in progress: {}%", data[4] & 0xFF);
            return;
        }
        if (state != 1) {
            finishBcDetect(null, state == 4 ? VITAL_FAIL_LOW_BATTERY : state == 3 ? VITAL_FAIL_BUSY : VITAL_FAIL_NONE);
            return;
        }
        int seq = data[4] & 0xFF;
        int total = data[5] & 0xFF;
        float[] values;
        synchronized (this) {
            if (!bcDetectActive) {
                return;
            }
            if (seq == 1) {
                bcChunks.clear();
            }
            bcChunks.add(Arrays.copyOfRange(data, 6, 20));
            if (seq != total) {
                return;
            }
            byte[] blob = new byte[bcChunks.size() * 14];
            for (int i = 0; i < bcChunks.size(); i++) {
                System.arraycopy(bcChunks.get(i), 0, blob, i * 14, 14);
            }
            bcChunks.clear();
            values = parseBodyCompositionBlob(blob);
        }
        if (values == null) {
            LOG.warn("Body composition result rejected: bad payload");
            finishBcDetect(null, VITAL_FAIL_NONE);
            return;
        }
        finishBcDetect(values, VITAL_FAIL_NONE);
    }

    /**
     * Decodes the concatenated body composition payload into 14 values, or null when invalid.
     * The first two bytes are a header (0xA0 on this firmware), then 14 little-endian u16
     * values divided by 10.
     */
    private static float[] parseBodyCompositionBlob(byte[] blob) {
        if (blob == null || blob.length < 30) {
            return null;
        }
        float[] values = new float[14];
        for (int i = 0; i < 14; i++) {
            int u16 = ((blob[3 + i * 2] & 0xFF) << 8) | (blob[2 + i * 2] & 0xFF);
            values[i] = u16 / 10f;
        }
        return values;
    }

    /** Completes the body composition read: stops the stream, then stores or reports the result. */
    private void finishBcDetect(float[] values, int failure) {
        synchronized (this) {
            if (!bcDetectActive) {
                return;
            }
            bcDetectActive = false;
        }
        mainHandler.removeCallbacks(bcDetectTimeout);
        mainHandler.removeCallbacks(bcIdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_BODY_COMPOSITION_REALTIME,
                VeepooConstants.BC_DETECT_FUNCTION, VeepooConstants.BC_DETECT_STOP);
        if (values != null) {
            GenericBodyCompositionSample sample = saveBcSample(values);
            if (sample != null) {
                notifyVitalSample(sample);
                GB.toast(getContext(), getContext().getString(R.string.veepoo_bc_ok), Toast.LENGTH_SHORT, GB.INFO);
            }
        } else {
            toastVitalFailure(R.string.veepoo_bc_no_reading, failure);
        }
    }

    /** Stores a body composition sample; returns the saved sample, or null on failure. */
    private GenericBodyCompositionSample saveBcSample(float[] v) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericBodyCompositionSample sample = new GenericBodyCompositionSample();
            sample.setTimestamp(System.currentTimeMillis());
            sample.setBmi(v[0]);
            sample.setBodyFatRate(v[1]);
            sample.setFatWeight(v[2]);
            sample.setFfm(v[3]);
            sample.setMuscleRate(v[4]);
            sample.setMuscleMass(v[5]);
            sample.setSubcutaneousFat(v[6]);
            sample.setBodyWaterRate(v[7]);
            sample.setWaterContent(v[8]);
            sample.setSkeletalMuscleRate(v[9]);
            sample.setBoneMass(v[10]);
            sample.setProteinProportion(v[11]);
            sample.setProteinMass(v[12]);
            sample.setBasalMetabolicRate(v[13]);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new GenericBodyCompositionSampleProvider(getDevice(), session).addSample(sample);
            return sample;
        } catch (Exception e) {
            LOG.error("Error saving body composition sample", e);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // ECG single-lead detection, opcode 0x93 function 1 (capture phase)
    // ---------------------------------------------------------------------

    /**
     * Result announced to HeartRateDialog during and after an ECG measurement. {@code sampleCount}
     * is the number of decoded waveform samples captured so far; {@code savedId} is the id of the
     * persisted {@link nodomain.freeyourgadget.gadgetbridge.entities.VeepooEcgSample} (0 while the
     * measurement is still running).
     */
    public static final class VeepooEcgMeasurementResult implements Serializable {
        private static final long serialVersionUID = 1L;
        public int frameCount;
        public int sampleCount;
        public int latestHr;
        public int progress;
        public int drawFrequency;
        public long savedId;
        /** 0 = success, otherwise a string resource id describing the failure. */
        public int failCode;

        public VeepooEcgMeasurementResult(int frameCount, int sampleCount, int latestHr,
                                          int progress, int drawFrequency, long savedId,
                                          int failCode) {
            this.frameCount = frameCount;
            this.sampleCount = sampleCount;
            this.latestHr = latestHr;
            this.progress = progress;
            this.drawFrequency = drawFrequency;
            this.savedId = savedId;
            this.failCode = failCode;
        }
    }

    private static final long ECG_NOTIFY_INTERVAL_MS = 800L;
    /** Safety net only: normally the device signals completion itself (subcommand 4). */
    private static final long ECG_DETECTION_WINDOW_MS = 90_000L;

    /**
     * On-demand single-lead ECG measurement: start {0x93,1,1,1}; the device streams the waveform
     * for a fixed ~40 s acquisition and then signals completion (subcommand 4) or failure
     * (subcommand 3). The {@link #ECG_DETECTION_WINDOW_MS} timer is only a safety net, and a
     * no-contact watchdog aborts early when the wear flag stays set.
     */
    private void startEcgDetect() {
        synchronized (this) {
            if (ecgDetectActive) {
                LOG.warn("An ECG measurement is already in progress");
                GB.toast(getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            synchronized (syncLock) {
                if (fetchPhase != FetchPhase.IDLE) {
                    LOG.warn("ECG measurement ignored: a data fetch is in progress");
                    GB.toast(getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                    return;
                }
            }
            ecgDetectActive = true;
            ecgFrameCount = 0;
            ecgSampleCount = 0;
            ecgLatestHr = 0;
            ecgFinalHr = 0;
            ecgProgress = 0;
            ecgDrawFrequency = 0;
            ecgNoContactStreak = 0;
            ecgStartedAtMs = System.currentTimeMillis();
            ecgLastNotifyMs = 0L;
            ecgWaveformBuffer.reset();
            ecgResultBlobBuffer.reset();
        }
        try {
            TransactionBuilder builder = performInitialized("ECG detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_ECG_REALTIME,
                    (byte) VeepooConstants.ECG_DETECT_FUNCTION, (byte) VeepooConstants.ECG_DETECT_START, 0x01});
            builder.queue();
            mainHandler.postDelayed(ecgDetectTimeout, ECG_DETECTION_WINDOW_MS);
            LOG.info("ECG detection started");
        } catch (IOException e) {
            synchronized (this) {
                ecgDetectActive = false;
            }
            LOG.error("Error starting ECG detection", e);
        }
    }

    /**
     * Ends the measurement: stops the stream (unless the device already signalled completion),
     * stores what was captured and announces it. {@code failCode} 0 means success; otherwise a
     * string resource id describing why it failed (no storage, toast shown). Runs the
     * persistence on the main handler.
     */
    private void finishEcgDetect(boolean deviceSignalled, int failCode) {
        synchronized (this) {
            if (!ecgDetectActive) {
                return;
            }
            ecgDetectActive = false;
        }
        mainHandler.removeCallbacks(ecgDetectTimeout);
        if (!deviceSignalled) {
            try {
                TransactionBuilder builder = performInitialized("stop ECG detect");
                writeFrame(builder, new byte[]{VeepooConstants.OP_ECG_REALTIME,
                        (byte) VeepooConstants.ECG_DETECT_FUNCTION,
                        (byte) VeepooConstants.ECG_DETECT_STOP, 0x01});
                builder.queue();
            } catch (IOException e) {
                LOG.warn("Error stopping ECG detection", e);
            }
        }
        long durationMs = System.currentTimeMillis() - ecgStartedAtMs;
        int frameCount = ecgFrameCount;
        int sampleCount = ecgSampleCount;
        int heartRate = ecgFinalHr > 0 ? ecgFinalHr : ecgLatestHr;
        LOG.info("ECG measurement finished ({}): {} samples in {} frames over {} ms (HR {}, progress {} %, sample rate {} Hz, failCode {})",
                deviceSignalled ? "device event" : "timeout", sampleCount, frameCount, durationMs,
                heartRate, ecgProgress, ecgDrawFrequency, failCode);
        mainHandler.post(() -> {
            if (failCode != 0) {
                GB.toast(getContext(), failCode, Toast.LENGTH_LONG, GB.ERROR);
            }
            long savedId = failCode == 0 ? saveEcgSample(durationMs, frameCount, sampleCount) : 0L;
            notifyVitalSample(new VeepooEcgMeasurementResult(frameCount, sampleCount, heartRate,
                    ecgProgress, ecgDrawFrequency, savedId, failCode));
        });
    }

    /** Persists the measurement (summary + raw waveform) and returns the new row id (0 on failure). */
    private long saveEcgSample(long durationMs, int frameCount, int sampleCount) {
        if (sampleCount == 0) {
            LOG.warn("ECG measurement produced no samples; not storing");
            return 0L;
        }
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            VeepooEcgSample sample = new VeepooEcgSample();
            sample.setTimestamp(ecgStartedAtMs);
            sample.setHeartRate(ecgLatestHr);
            sample.setSampleRate(ecgDrawFrequency);
            sample.setDurationMs((int) durationMs);
            sample.setSampleCount(sampleCount);
            sample.setResultBlob(ecgResultBlobBuffer.toByteArray());
            sample.setWaveform(ecgWaveformBuffer.toByteArray());
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            return session.getVeepooEcgSampleDao().insert(sample);
        } catch (Exception e) {
            LOG.error("Error saving ECG sample", e);
            return 0L;
        }
    }

    /**
     * Opcode 0x93 frame with [1]==1: ECG control/status on the main channel. The subcommand is
     * at [3]: 0 = info (little-endian u16 sample rate at [4..5]), 1 = per-second status (see
     * {@link VeepooConstants} for the layout; live HR is a single byte at [5], progress at [19],
     * wear flag at [12]), 2 = result frame (average heart rate at [13]; not sent by all
     * firmwares), 3 = failed on the device, 4 = finished successfully (the device-side
     * completion event; result chunks arrive just before it), 5 = diagnosis chunk (seq/total at
     * [4]/[5]) whose 14-byte payload at [6..19] is stored raw.
     */
    private void handleEcgDetectFrame(byte[] data) {
        if (data.length < 4 || data[1] != VeepooConstants.ECG_DETECT_FUNCTION) {
            return;
        }
        synchronized (this) {
            if (!ecgDetectActive) {
                return;
            }
        }
        LOG.debug("ECG control frame: {}", hexBytes(data));
        int sub = data[3];
        if (sub == 4) {
            finishEcgDetect(true, 0);
            return;
        }
        if (sub == 3) {
            LOG.warn("ECG device reported measurement failure");
            finishEcgDetect(true, R.string.veepoo_ecg_failed_device);
            return;
        }
        if (sub == 2 && data.length >= 18) {
            int hr = data[13] & 0xFF;
            if (hr >= 30 && hr <= 250) {
                ecgFinalHr = hr;
            }
            return;
        }
        if (data.length < 20) {
            return;
        }
        if (sub == 0) {
            ecgDrawFrequency = (data[VeepooConstants.ECG_INFO_SAMPLE_RATE_OFFSET] & 0xFF)
                    | ((data[VeepooConstants.ECG_INFO_SAMPLE_RATE_OFFSET + 1] & 0xFF) << 8);
            LOG.info("ECG info frame: sample rate {} Hz", ecgDrawFrequency);
        } else if (sub == 1) {
            int deviceState = data[VeepooConstants.ECG_STATUS_DEVICE_STATE_OFFSET] & 0xFF;
            int hr = data[VeepooConstants.ECG_STATUS_HR_OFFSET] & 0xFF;
            if (hr >= 30 && hr <= 250) {
                ecgLatestHr = hr;
            }
            int wear = data[VeepooConstants.ECG_STATUS_WEAR_OFFSET];
            int progress = data[VeepooConstants.ECG_STATUS_PROGRESS_OFFSET] & 0xFF;
            if (progress <= 100) {
                ecgProgress = progress;
            }
            // No-contact watchdog: the vendor app aborts once the wear flag stays set for about
            // five consecutive status frames (after a couple of seconds of data).
            boolean noContact = (wear == 1) || deviceState == VeepooConstants.ECG_DEVICE_STATE_UNPASS_WEAR;
            synchronized (this) {
                if (!ecgDetectActive) {
                    return;
                }
                long runningMs = System.currentTimeMillis() - ecgStartedAtMs;
                if (noContact && runningMs > 3000) {
                    ecgNoContactStreak++;
                    LOG.warn("ECG status reports no contact/wear (streak {})", ecgNoContactStreak);
                    if (ecgNoContactStreak >= 5) {
                        finishEcgDetect(false, R.string.veepoo_ecg_failed_no_contact);
                        return;
                    }
                } else {
                    ecgNoContactStreak = 0;
                }
            }
            notifyEcgProgress();
        } else if (sub == 5) {
            synchronized (this) {
                int seq = data[4] & 0xFF;
                if (seq == 1) {
                    ecgResultBlobBuffer.reset();
                }
                if (data.length >= 20) {
                    ecgResultBlobBuffer.write(data, 6, 14);
                }
            }
        }
    }

    /** Throttled progress announcement while the measurement is running. */
    private void notifyEcgProgress() {
        synchronized (this) {
            if (!ecgDetectActive) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - ecgLastNotifyMs < ECG_NOTIFY_INTERVAL_MS) {
                return;
            }
            ecgLastNotifyMs = now;
            notifyVitalSample(new VeepooEcgMeasurementResult(ecgFrameCount, ecgSampleCount,
                    ecgLatestHr, ecgProgress, ecgDrawFrequency, 0L, 0));
        }
    }

    /**
     * Light-origin channel frame: {@code 0x88} + waveform samples (big-endian signed int24,
     * 3 bytes each) + a 4-byte constant tail ending in a per-frame sequence counter. Decoded
     * samples are appended to the measurement buffer; invalid markers (0xFFFFFF / 0x020202) are
     * skipped. Full frames stay in the debug log for protocol verification.
     */
    private void handleEcgLightFrame(byte[] value) {
        synchronized (this) {
            if (!ecgDetectActive) {
                LOG.debug("Dropping non-ECG F002 frame: {} bytes, first byte 0x{:02X}",
                        value.length, value[0] & 0xFF);
                return; // non-ECG light traffic (e.g. live PPG) outside a measurement
            }
        }
        int payloadStart = 1;
        int payloadLen = value.length - VeepooConstants.ECG_LIGHT_TAIL_LEN - payloadStart;
        if (value.length >= VeepooConstants.ECG_LIGHT_MIN_FRAME_LEN && payloadLen >= 3 && payloadLen % 3 == 0) {
            synchronized (this) {
                ecgFrameCount++;
                for (int i = 0; i < payloadLen / 3; i++) {
                    int b0 = value[payloadStart + 3 * i] & 0xFF;
                    int b1 = value[payloadStart + 3 * i + 1] & 0xFF;
                    int b2 = value[payloadStart + 3 * i + 2] & 0xFF;
                    int raw = (b0 << 16) | (b1 << 8) | b2;
                    if (raw == 0xFFFFFF || raw == 0x020202) {
                        continue; // invalid-sample marker
                    }
                    ecgWaveformBuffer.write(b0);
                    ecgWaveformBuffer.write(b1);
                    ecgWaveformBuffer.write(b2);
                    ecgSampleCount++;
                }
            }
            notifyEcgProgress();
        }
        LOG.debug("ECG light frame: len={}: {}", value.length, hexBytes(value));
    }

    // ---------------------------------------------------------------------
    // Blood component (lipid) single-shot detection, opcode 0x8A
    // ---------------------------------------------------------------------

    /** On-demand blood lipid measurement in public (uncalibrated) mode: start {0x8A,1,1,0}, stop {0x8A,1,2,0}. */
    private void startLipidDetect() {
        synchronized (this) {
            if (lipidDetectActive) {
                LOG.warn("A blood lipid measurement is already in progress");
                GB.toast(getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            synchronized (syncLock) {
                if (fetchPhase != FetchPhase.IDLE) {
                    LOG.warn("Blood lipid measurement ignored: a data fetch is in progress");
                    GB.toast(getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                    return;
                }
            }
            lipidDetectActive = true;
        }
        try {
            TransactionBuilder builder = performInitialized("lipid detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_BLOOD_LIPID_REALTIME,
                    (byte) VeepooConstants.LIPID_DETECT_FUNCTION, (byte) VeepooConstants.LIPID_DETECT_START, 0});
            builder.queue();
            mainHandler.postDelayed(lipidDetectTimeout, 180_000L);
            mainHandler.postDelayed(lipidIdleWatchdog, 60_000L);
            LOG.info("Blood lipid detection started");
        } catch (IOException e) {
            synchronized (this) {
                lipidDetectActive = false;
            }
            LOG.error("Error starting blood lipid detection", e);
        }
    }

    /**
     * Opcode 0x8A frame: [2]=stop ack, [3]=state (0 ok, 2 low battery, 3 busy, 4 not worn),
     * [4]=progress percent; at progress 100 [5..14] carry uric acid (/10) then total
     * cholesterol, triglyceride, HDL and LDL (/100). Frames outside a manual read are
     * autonomous auto-measure pushes and are persisted.
     */
    private void handleLipidFrame(byte[] data) {
        if (data.length < 15 || data[1] != VeepooConstants.LIPID_DETECT_FUNCTION) {
            LOG.debug("Dropping unexpected 0x8A frame: {}", hexBytes(data));
            return;
        }
        synchronized (this) {
            if (!lipidDetectActive) {
                persistAutonomousLipid(data);
                return;
            }
        }
        LOG.debug("Blood lipid frame: {}", hexBytes(data));
        if (data[2] == 2) {
            return; // stop acknowledgement
        }
        int state = data[3] & 0xFF;
        if (state != 0) {
            int failure = state == 2 ? VITAL_FAIL_LOW_BATTERY
                    : state == 3 ? VITAL_FAIL_BUSY
                    : state == 4 ? VITAL_FAIL_WEAR
                    : VITAL_FAIL_NONE;
            finishLipidDetect(null, failure);
            return;
        }
        int progress = data[4] & 0xFF;
        if (progress == 100) {
            finishLipidDetect(parseLipidValues(data), VITAL_FAIL_NONE);
        } else {
            LOG.debug("Blood lipid in progress: {}%", progress);
        }
    }

    /** Parses the five lipid values (uric acid, TCHO, TAG, HDL, LDL) from a data frame. */
    private static float[] parseLipidValues(byte[] data) {
        return new float[]{
                (((data[6] & 0xFF) << 8) | (data[5] & 0xFF)) / 10f,
                (((data[8] & 0xFF) << 8) | (data[7] & 0xFF)) / 100f,
                (((data[10] & 0xFF) << 8) | (data[9] & 0xFF)) / 100f,
                (((data[12] & 0xFF) << 8) | (data[11] & 0xFF)) / 100f,
                (((data[14] & 0xFF) << 8) | (data[13] & 0xFF)) / 100f};
    }

    private void persistAutonomousLipid(byte[] data) {
        if (data[2] == 2 || (data[3] & 0xFF) != 0 || (data[4] & 0xFF) != 100) {
            LOG.debug("Ignoring autonomous lipid push: data[2]={} data[3]={} data[4]={}",
                    data[2] & 0xFF, data[3] & 0xFF, data[4] & 0xFF);
            return; // stop ack, failure frame or still in progress
        }
        float[] values = parseLipidValues(data);
        LOG.info("Autonomous blood lipid push: TCHO={} TAG={}", values[1], values[2]);
        saveLipidSample(values);
    }

    /** Completes the blood lipid read: stops the stream, then stores or reports the result. */
    private void finishLipidDetect(float[] values, int failure) {
        synchronized (this) {
            if (!lipidDetectActive) {
                return;
            }
            lipidDetectActive = false;
        }
        mainHandler.removeCallbacks(lipidDetectTimeout);
        mainHandler.removeCallbacks(lipidIdleWatchdog);
        try {
            TransactionBuilder builder = performInitialized("stop lipid detect");
            writeFrame(builder, new byte[]{VeepooConstants.OP_BLOOD_LIPID_REALTIME,
                    (byte) VeepooConstants.LIPID_DETECT_FUNCTION, (byte) VeepooConstants.LIPID_DETECT_STOP, 0});
            builder.queue();
        } catch (IOException e) {
            LOG.warn("Error stopping blood lipid detection", e);
        }
        if (values != null) {
            GenericBloodLipidSample sample = saveLipidSample(values);
            if (sample != null) {
                notifyVitalSample(sample);
                GB.toast(getContext(), getContext().getString(R.string.veepoo_lipids_ok, values[1], values[2]),
                        Toast.LENGTH_SHORT, GB.INFO);
            }
        } else {
            toastVitalFailure(R.string.veepoo_lipids_no_reading, failure);
        }
    }

    /** Stores a blood lipid sample; returns the saved sample, or null on failure. */
    private GenericBloodLipidSample saveLipidSample(float[] v) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericBloodLipidSample sample = new GenericBloodLipidSample();
            sample.setTimestamp(System.currentTimeMillis());
            sample.setUricAcid(v[0]);
            sample.setTotalCholesterol(v[1]);
            sample.setTriglyceride(v[2]);
            sample.setHdl(v[3]);
            sample.setLdl(v[4]);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new GenericBloodLipidSampleProvider(getDevice(), session).addSample(sample);
            return sample;
        } catch (Exception e) {
            LOG.error("Error saving blood lipid sample", e);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers for the vital detections above
    // ---------------------------------------------------------------------

    /** Sends a vital stop frame {opcode, b1, b2}; errors are only logged. */
    private void sendStopVitalFrame(byte opcode, int b1, int b2) {
        try {
            TransactionBuilder builder = performInitialized("stop vital detect");
            writeFrame(builder, new byte[]{opcode, (byte) b1, (byte) b2});
            builder.queue();
        } catch (IOException e) {
            LOG.warn("Error stopping vital detection", e);
        }
    }

    /** Reports a failed vital measurement; {@code noReadingRes} is used when no specific cause applies. */
    private void toastVitalFailure(int noReadingRes, int failure) {
        switch (failure) {
            case VITAL_FAIL_WEAR:
                GB.toast(getContext(), R.string.veepoo_measure_not_worn, Toast.LENGTH_SHORT, GB.WARN);
                break;
            case VITAL_FAIL_LOW_BATTERY:
                GB.toast(getContext(), R.string.veepoo_measure_low_battery, Toast.LENGTH_SHORT, GB.WARN);
                break;
            case VITAL_FAIL_BUSY:
                GB.toast(getContext(), R.string.veepoo_measure_busy, Toast.LENGTH_SHORT, GB.WARN);
                break;
            case VITAL_FAIL_NOT_SUPPORTED:
                GB.toast(getContext(), R.string.veepoo_measure_not_supported, Toast.LENGTH_SHORT, GB.WARN);
                break;
            default:
                GB.toast(getContext(), noReadingRes, Toast.LENGTH_SHORT, GB.WARN);
        }
    }

    /**
     * Triggers the manual heart rate read-back (0xDE). The band stores its on-demand measurement
     * results on-device, so the vendor app reads them back instead of streaming; pos=0 means
     * "all stored records" and only the heart-rate type bit is requested.
     */
    private void readManualHeartRate() {
        synchronized (manualDetectLock) {
            if (manualDetectActive) {
                LOG.warn("A manual heart rate read is already in progress");
                GB.toast(getContext(), R.string.veepoo_manual_hr_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            synchronized (syncLock) {
                if (fetchPhase != FetchPhase.IDLE) {
                    LOG.warn("Manual heart rate read ignored: a data fetch is in progress");
                    GB.toast(getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                    return;
                }
            }
            manualDetectActive = true;
            manualDetectTotal = -1;
            manualDetectNextIndex = 0;
            manualDetectPayload.reset();
        }
        try {
            TransactionBuilder builder = performInitialized("manual heart rate read");
            byte[] command = new byte[VeepooConstants.PACKET_SIZE];
            command[0] = VeepooConstants.OP_MANUAL_DETECT;
            command[1] = 0x02;
            // command[2..5] pos = 0 (no cursor: read all stored records)
            command[6] = (byte) VeepooConstants.MANUAL_MASK_HEART_RATE; // readMask: heart rate only
            writeFrame(builder, command);
            builder.queue();
            mainHandler.postDelayed(manualDetectWatchdog, 120_000L);
            LOG.info("Manual heart rate read requested (all stored HR records)");
        } catch (IOException e) {
            resetManualDetect();
            LOG.error("Error triggering manual heart rate read", e);
        }
    }

    /**
     * Opcode 0xDE response: [2..5] record index (LE32, 0-based), first frame only carries the
     * total count at [8..11], payload bytes start at [6]. Frames arrive in strict sequence; the
     * concatenated stream is parsed once all {@code total} frames have been received.
     */
    private void handleManualDetectFrame(byte[] data) {
        if (data.length < 12) {
            return;
        }
        int index = (int) le32(data, 2);
        boolean complete = false;
        synchronized (manualDetectLock) {
            if (!manualDetectActive) {
                LOG.debug("Ignoring unexpected manual detect frame (index {})", index);
                return;
            }
            if (index == 0) {
                manualDetectTotal = (int) le32(data, 8);
                manualDetectNextIndex = 1;
                manualDetectPayload.reset();
                LOG.info("Manual heart rate read: {} payload frame(s) expected", manualDetectTotal);
                if (manualDetectTotal == 0) {
                    complete = true;
                }
            } else if (index != manualDetectNextIndex) {
                LOG.warn("Out-of-sequence manual detect frame: got index {}, expected {}", index, manualDetectNextIndex);
                GB.toast(getContext(), R.string.veepoo_manual_hr_failed, Toast.LENGTH_SHORT, GB.WARN);
                resetManualDetect();
                return;
            }
            if (!complete) {
                manualDetectPayload.write(data, 6, data.length - 6);
                manualDetectNextIndex = index + 1;
                if (manualDetectTotal > 0 && manualDetectNextIndex >= manualDetectTotal) {
                    complete = true;
                }
            }
        }
        if (!complete) {
            return;
        }
        mainHandler.removeCallbacks(manualDetectWatchdog);
        byte[] stream = manualDetectPayload.toByteArray();
        int saved = parseAndSaveManualHeartRate(stream);
        synchronized (manualDetectLock) {
            manualDetectActive = false;
            manualDetectTotal = -1;
            manualDetectNextIndex = 0;
            manualDetectPayload.reset();
        }
        LOG.info("Manual heart rate read complete: {} byte(s) of stream, {} reading(s) saved", stream.length, saved);
        if (saved > 0) {
            GB.toast(getContext(), getContext().getString(R.string.veepoo_manual_hr_saved, saved), Toast.LENGTH_SHORT, GB.INFO);
        } else {
            GB.toast(getContext(), R.string.veepoo_manual_hr_empty, Toast.LENGTH_SHORT, GB.WARN);
        }
    }

    /**
     * Parses the concatenated 0xDE stream and saves the heart rate records (type 1), mirroring
     * the vendor parser (p000if/y.java): 0xA0 blocks are skipped; a 0xA1 block is
     * [0xA1][unused byte][type][len LE32] followed by len bytes of [0xB0][recLen LE32][record].
     * Each HR record carries sub-TLVs 0xC0 (timestamp), 0xC1 (version) and 0xC2 (n × bpm).
     */
    private int parseAndSaveManualHeartRate(byte[] stream) {
        int saved = 0;
        ByteBuffer bb = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (bb.hasRemaining()) {
                int tag = bb.get() & 0xFF;
                if (tag == 0xA0) {
                    int skip = bb.get() & 0xFF;
                    bb.position(bb.position() + skip);
                } else if (tag == 0xA1) {
                    bb.get(); // unused length/count byte (discarded by the vendor parser)
                    int type = bb.get() & 0xFF;
                    byte[] group = new byte[bb.getInt()];
                    bb.get(group);
                    if (type != 1) {
                        continue;
                    }
                    ByteBuffer gb = ByteBuffer.wrap(group).order(ByteOrder.LITTLE_ENDIAN);
                    while (gb.hasRemaining()) {
                        if ((gb.get() & 0xFF) != 0xB0) {
                            continue;
                        }
                        byte[] record = new byte[gb.getInt()];
                        gb.get(record);
                        saved += saveManualHeartRateRecord(record);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse the manual heart rate stream ({} bytes)", stream.length, e);
        }
        return saved;
    }

    /** Parses one HR record and stores it as a heart rate sample if it carries valid samples. */
    private int saveManualHeartRateRecord(byte[] record) {
        long timestampSeconds = 0;
        List<Integer> samples = null;
        ByteBuffer rb = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (rb.hasRemaining()) {
                switch (rb.get() & 0xFF) {
                    case 0xC0:
                        rb.get(); // len byte (discarded)
                        timestampSeconds = Integer.toUnsignedLong(rb.getInt());
                        break;
                    case 0xC1:
                        rb.get(); // len byte (discarded)
                        rb.get(); // version
                        break;
                    case 0xC2:
                        int count = rb.get() & 0xFF;
                        samples = new ArrayList<>(count);
                        for (int i = 0; i < count && rb.hasRemaining(); i++) {
                            samples.add(rb.get() & 0xFF);
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse a manual heart rate record", e);
            return 0;
        }
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (int bpm : samples) {
            sum += bpm;
        }
        int average = sum / samples.size();
        if (average < 30 || average > 220) {
            LOG.debug("Ignoring implausible manual heart rate record: {} bpm over {} sample(s)", average, samples.size());
            return 0;
        }
        long timestampMs = timestampSeconds > 0 ? timestampSeconds * 1000L : System.currentTimeMillis();
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericHeartRateSampleDao dao = session.getGenericHeartRateSampleDao();
            boolean alreadySaved = !dao.queryBuilder()
                    .where(GenericHeartRateSampleDao.Properties.Timestamp.eq(timestampMs),
                            GenericHeartRateSampleDao.Properties.DeviceId.eq(deviceId))
                    .list().isEmpty();
            if (alreadySaved) {
                LOG.debug("Manual heart rate reading already saved ({})", new Date(timestampMs));
                return 0;
            }
            GenericHeartRateSample sample = new GenericHeartRateSample();
            sample.setTimestamp(timestampMs);
            sample.setHeartRate(average);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider(getDevice(), session).addSample(sample);
            return 1;
        } catch (Exception e) {
            LOG.error("Error saving manual heart rate record", e);
            return 0;
        }
    }

    private void resetManualDetect() {
        mainHandler.removeCallbacks(manualDetectWatchdog);
        synchronized (manualDetectLock) {
            manualDetectActive = false;
            manualDetectTotal = -1;
            manualDetectNextIndex = 0;
            manualDetectPayload.reset();
        }
    }

    /**
     * Persist the collected origin (activity) samples. Sleep samples are persisted last so they
     * win over overlapping activity rows within the sleep window.
     */
    private void persistPendingOriginDay() {
        if (pendingOriginSamples.isEmpty() && pendingHrvSamples.isEmpty()
                && pendingStressSamples.isEmpty()
                && pendingOriginSpo2Samples.isEmpty() && pendingOriginBpSamples.isEmpty()
                && pendingOriginTemperatureSamples.isEmpty() && pendingOriginGlucoseSamples.isEmpty()
                && pendingOriginBreathSamples.isEmpty() && pendingOriginLipidSamples.isEmpty()) {
            return;
        }
        List<VeepooActivitySample> batch = new ArrayList<>(pendingOriginSamples);
        pendingOriginSamples.clear();
        if (!batch.isEmpty()) {
            persistSamples(batch, false);
        }
        persistPendingHrvSamples();
        persistPendingStressSamples();
        persistPendingOriginVitalsSamples();
    }

    private void persistPendingStressSamples() {
        if (pendingStressSamples.isEmpty()) {
            return;
        }
        List<GenericStressSample> batch = new ArrayList<>(pendingStressSamples);
        pendingStressSamples.clear();
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericStressSampleDao dao = session.getGenericStressSampleDao();
            long from = Long.MAX_VALUE;
            long to = Long.MIN_VALUE;
            for (GenericStressSample sample : batch) {
                sample.setUserId(userId);
                sample.setDeviceId(deviceId);
                from = Math.min(from, sample.getTimestamp());
                to = Math.max(to, sample.getTimestamp());
            }
            dao.queryBuilder()
                    .where(GenericStressSampleDao.Properties.DeviceId.eq(deviceId),
                            GenericStressSampleDao.Properties.Timestamp.ge(from - 60_000L),
                            GenericStressSampleDao.Properties.Timestamp.le(to + 60_000L))
                    .buildDelete()
                    .executeDeleteWithoutDetachingEntities();
            dao.insertInTx(batch);
        } catch (Exception e) {
            LOG.error("Error saving stress samples", e);
        }
    }

    private void persistPendingHrvSamples() {
        if (pendingHrvSamples.isEmpty()) {
            return;
        }
        List<GenericHrvValueSample> batch = new ArrayList<>(pendingHrvSamples);
        pendingHrvSamples.clear();
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericHrvValueSampleDao dao = session.getGenericHrvValueSampleDao();
            long from = Long.MAX_VALUE;
            long to = Long.MIN_VALUE;
            for (GenericHrvValueSample sample : batch) {
                sample.setUserId(userId);
                sample.setDeviceId(deviceId);
                from = Math.min(from, sample.getTimestamp());
                to = Math.max(to, sample.getTimestamp());
            }
            dao.queryBuilder()
                    .where(GenericHrvValueSampleDao.Properties.DeviceId.eq(deviceId),
                            GenericHrvValueSampleDao.Properties.Timestamp.ge(from - 60_000L),
                            GenericHrvValueSampleDao.Properties.Timestamp.le(to + 60_000L))
                    .buildDelete()
                    .executeDeleteWithoutDetachingEntities();
            dao.insertInTx(batch);
        } catch (Exception e) {
            LOG.error("Error saving HRV samples", e);
        }
    }

    /**
     * Persist the per-record all-day vitals (SpO2, blood pressure, temperature, glucose, breath
     * rate, lipids) collected while parsing Origin3 days. Like the HRV batch, rows inside each
     * batch's time window are deleted first so re-syncing a day replaces instead of duplicates.
     */
    private void persistPendingOriginVitalsSamples() {
        if (pendingOriginSpo2Samples.isEmpty() && pendingOriginBpSamples.isEmpty()
                && pendingOriginTemperatureSamples.isEmpty() && pendingOriginGlucoseSamples.isEmpty()
                && pendingOriginBreathSamples.isEmpty() && pendingOriginLipidSamples.isEmpty()) {
            return;
        }
        List<GenericSpo2Sample> spo2Batch = new ArrayList<>(pendingOriginSpo2Samples);
        List<GenericBloodPressureSample> bpBatch = new ArrayList<>(pendingOriginBpSamples);
        List<GenericTemperatureSample> temperatureBatch = new ArrayList<>(pendingOriginTemperatureSamples);
        List<GlucoseSample> glucoseBatch = new ArrayList<>(pendingOriginGlucoseSamples);
        List<GenericRespiratoryRateSample> breathBatch = new ArrayList<>(pendingOriginBreathSamples);
        List<GenericBloodLipidSample> lipidBatch = new ArrayList<>(pendingOriginLipidSamples);
        pendingOriginSpo2Samples.clear();
        pendingOriginBpSamples.clear();
        pendingOriginTemperatureSamples.clear();
        pendingOriginGlucoseSamples.clear();
        pendingOriginBreathSamples.clear();
        pendingOriginLipidSamples.clear();
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();

            if (!spo2Batch.isEmpty()) {
                GenericSpo2SampleDao dao = session.getGenericSpo2SampleDao();
                long from = Long.MAX_VALUE;
                long to = Long.MIN_VALUE;
                for (GenericSpo2Sample sample : spo2Batch) {
                    sample.setUserId(userId);
                    sample.setDeviceId(deviceId);
                    from = Math.min(from, sample.getTimestamp());
                    to = Math.max(to, sample.getTimestamp());
                }
                dao.queryBuilder()
                        .where(GenericSpo2SampleDao.Properties.DeviceId.eq(deviceId),
                                GenericSpo2SampleDao.Properties.Timestamp.ge(from - 60_000L),
                                GenericSpo2SampleDao.Properties.Timestamp.le(to + 60_000L))
                        .buildDelete()
                        .executeDeleteWithoutDetachingEntities();
                dao.insertInTx(spo2Batch);
            }

            if (!bpBatch.isEmpty()) {
                GenericBloodPressureSampleDao dao = session.getGenericBloodPressureSampleDao();
                long from = Long.MAX_VALUE;
                long to = Long.MIN_VALUE;
                for (GenericBloodPressureSample sample : bpBatch) {
                    sample.setUserId(userId);
                    sample.setDeviceId(deviceId);
                    from = Math.min(from, sample.getTimestamp());
                    to = Math.max(to, sample.getTimestamp());
                }
                dao.queryBuilder()
                        .where(GenericBloodPressureSampleDao.Properties.DeviceId.eq(deviceId),
                                GenericBloodPressureSampleDao.Properties.Timestamp.ge(from - 60_000L),
                                GenericBloodPressureSampleDao.Properties.Timestamp.le(to + 60_000L))
                        .buildDelete()
                        .executeDeleteWithoutDetachingEntities();
                dao.insertInTx(bpBatch);
            }

            if (!temperatureBatch.isEmpty()) {
                GenericTemperatureSampleDao dao = session.getGenericTemperatureSampleDao();
                long from = Long.MAX_VALUE;
                long to = Long.MIN_VALUE;
                for (GenericTemperatureSample sample : temperatureBatch) {
                    sample.setUserId(userId);
                    sample.setDeviceId(deviceId);
                    from = Math.min(from, sample.getTimestamp());
                    to = Math.max(to, sample.getTimestamp());
                }
                dao.queryBuilder()
                        .where(GenericTemperatureSampleDao.Properties.DeviceId.eq(deviceId),
                                GenericTemperatureSampleDao.Properties.Timestamp.ge(from - 60_000L),
                                GenericTemperatureSampleDao.Properties.Timestamp.le(to + 60_000L))
                        .buildDelete()
                        .executeDeleteWithoutDetachingEntities();
                dao.insertInTx(temperatureBatch);
            }

            if (!glucoseBatch.isEmpty()) {
                GlucoseSampleDao dao = session.getGlucoseSampleDao();
                long from = Long.MAX_VALUE;
                long to = Long.MIN_VALUE;
                for (GlucoseSample sample : glucoseBatch) {
                    sample.setUserId(userId);
                    sample.setDeviceId(deviceId);
                    from = Math.min(from, sample.getTimestamp());
                    to = Math.max(to, sample.getTimestamp());
                }
                dao.queryBuilder()
                        .where(GlucoseSampleDao.Properties.DeviceId.eq(deviceId),
                                GlucoseSampleDao.Properties.Timestamp.ge(from - 60_000L),
                                GlucoseSampleDao.Properties.Timestamp.le(to + 60_000L))
                        .buildDelete()
                        .executeDeleteWithoutDetachingEntities();
                dao.insertInTx(glucoseBatch);
            }

            if (!breathBatch.isEmpty()) {
                GenericRespiratoryRateSampleDao dao = session.getGenericRespiratoryRateSampleDao();
                long from = Long.MAX_VALUE;
                long to = Long.MIN_VALUE;
                for (GenericRespiratoryRateSample sample : breathBatch) {
                    sample.setUserId(userId);
                    sample.setDeviceId(deviceId);
                    from = Math.min(from, sample.getTimestamp());
                    to = Math.max(to, sample.getTimestamp());
                }
                dao.queryBuilder()
                        .where(GenericRespiratoryRateSampleDao.Properties.DeviceId.eq(deviceId),
                                GenericRespiratoryRateSampleDao.Properties.Timestamp.ge(from - 60_000L),
                                GenericRespiratoryRateSampleDao.Properties.Timestamp.le(to + 60_000L))
                        .buildDelete()
                        .executeDeleteWithoutDetachingEntities();
                dao.insertInTx(breathBatch);
            }

            if (!lipidBatch.isEmpty()) {
                GenericBloodLipidSampleDao dao = session.getGenericBloodLipidSampleDao();
                long from = Long.MAX_VALUE;
                long to = Long.MIN_VALUE;
                for (GenericBloodLipidSample sample : lipidBatch) {
                    sample.setUserId(userId);
                    sample.setDeviceId(deviceId);
                    from = Math.min(from, sample.getTimestamp());
                    to = Math.max(to, sample.getTimestamp());
                }
                dao.queryBuilder()
                        .where(GenericBloodLipidSampleDao.Properties.DeviceId.eq(deviceId),
                                GenericBloodLipidSampleDao.Properties.Timestamp.ge(from - 60_000L),
                                GenericBloodLipidSampleDao.Properties.Timestamp.le(to + 60_000L))
                        .buildDelete()
                        .executeDeleteWithoutDetachingEntities();
                dao.insertInTx(lipidBatch);
            }
        } catch (Exception e) {
            LOG.error("Error saving Origin3 vital samples", e);
        }
    }

    private void finishFetch() {
        persistPendingOriginDay();
        if (!pendingSleepSamples.isEmpty()) {
            List<VeepooActivitySample> sleepBatch = new ArrayList<>(pendingSleepSamples);
            pendingSleepSamples.clear();
            persistSamples(sleepBatch, true);
        }
        persistPendingSleepSummaries();
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
        GB.signalActivityDataFinish(getDevice());
        LOG.info("VeePoo data fetch complete");
    }

    private void abortFetch() {
        mainHandler.removeCallbacks(sleepWatchdog);
        mainHandler.removeCallbacks(dfWatchdog);
        mainHandler.removeCallbacks(sportWatchdog);
        synchronized (syncLock) {
            fetchPhase = FetchPhase.IDLE;
        }
        pendingOriginSamples.clear();
        pendingSleepSamples.clear();
        pendingSleepSummaries.clear();
        pendingStressSamples.clear();
        pendingOriginSpo2Samples.clear();
        pendingOriginBpSamples.clear();
        pendingOriginTemperatureSamples.clear();
        pendingOriginGlucoseSamples.clear();
        pendingOriginBreathSamples.clear();
        pendingOriginLipidSamples.clear();
        sleepDayPayload.reset();
        dfDayFrames.clear();
        sportDayFrames.clear();
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    private void persistSamples(List<VeepooActivitySample> samples, boolean isSleep) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            User user = DBHelper.getUser(session);
            Device device = DBHelper.getDevice(getDevice(), session);
            nodomain.freeyourgadget.gadgetbridge.devices.veepoo.samples.VeepooActivitySampleProvider provider =
                    new nodomain.freeyourgadget.gadgetbridge.devices.veepoo.samples.VeepooActivitySampleProvider(getDevice(), session);
            for (VeepooActivitySample sample : samples) {
                sample.setDevice(device);
                sample.setUser(user);
            }
            provider.addGBActivitySamples(samples);
        } catch (Exception e) {
            LOG.error("Error saving VeePoo {} samples", isSleep ? "sleep" : "activity", e);
        }
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    /**
     * Persist the per-night sleep summaries collected while parsing sleep records. Rows inside
     * each batch's time window are deleted first so re-syncing a night replaces instead of
     * duplicates; the window is padded by two days to absorb clock/anchoring drift.
     */
    private void persistPendingSleepSummaries() {
        if (pendingSleepSummaries.isEmpty()) {
            return;
        }
        List<VeepooSleepSummarySample> batch = new ArrayList<>(pendingSleepSummaries);
        pendingSleepSummaries.clear();
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            VeepooSleepSummarySampleDao dao = session.getVeepooSleepSummarySampleDao();
            long from = Long.MAX_VALUE;
            long to = Long.MIN_VALUE;
            for (VeepooSleepSummarySample summary : batch) {
                summary.setUserId(userId);
                summary.setDeviceId(deviceId);
                from = Math.min(from, summary.getTimestamp());
                to = Math.max(to, summary.getTimestamp());
            }
            dao.queryBuilder()
                    .where(VeepooSleepSummarySampleDao.Properties.DeviceId.eq(deviceId),
                            VeepooSleepSummarySampleDao.Properties.Timestamp.ge(from - 2 * MILLIS_PER_DAY),
                            VeepooSleepSummarySampleDao.Properties.Timestamp.le(to + 2 * MILLIS_PER_DAY))
                    .buildDelete()
                    .executeDeleteWithoutDetachingEntities();
            dao.insertInTx(batch);
        } catch (Exception e) {
            LOG.error("Error saving VeePoo sleep summaries", e);
        }
    }

    @Override
    public void onSetTime() {
        try {
            TransactionBuilder builder = performInitialized("onSetTime");
            writeFrame(builder, buildSyncTimeFrame());
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error setting time", e);
        }
    }

    @Override
    public void dispose() {
        liveWorkoutModule.dispose();
        mainHandler.removeCallbacks(manualDetectWatchdog);
        mainHandler.removeCallbacks(hrDetectTimeout);
        mainHandler.removeCallbacks(spo2DetectTimeout);
        mainHandler.removeCallbacks(spo2IdleWatchdog);
        mainHandler.removeCallbacks(tempDetectTimeout);
        mainHandler.removeCallbacks(tempIdleWatchdog);
        mainHandler.removeCallbacks(stressDetectTimeout);
        mainHandler.removeCallbacks(stressIdleWatchdog);
        mainHandler.removeCallbacks(bpDetectTimeout);
        mainHandler.removeCallbacks(bpIdleWatchdog);
        mainHandler.removeCallbacks(statusPollTask);
        alarmModule.dispose();
        autoMeasureModule.dispose();
        womenHealthModule.dispose();
        realtimeStepsRequested = false;
        synchronized (syncLock) {
            fetchPhase = FetchPhase.IDLE;
        }
        super.dispose();
    }
}
