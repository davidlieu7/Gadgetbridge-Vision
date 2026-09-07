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
import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryTableBuilder;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryValue;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooActivitySample;
import nodomain.freeyourgadget.gadgetbridge.export.GPXExporter;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;

/**
 * Device support for the VeePoo G Band (KR96 Pro, {@code com.vpgband.app}).
 *
 * <p>All traffic uses the F008 GATT service: one fixed 20-byte write per command on
 * {@code F0080003}, responses arrive as 20-byte notifications on {@code F0080002} carrying the
 * same opcode in byte 0.
 */
public class VeepooDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooDeviceSupport.class);

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
    private final List<byte[]> dfDayFrames = new ArrayList<>();
    private long dfLastFrameAt = 0;
    private final List<byte[]> sportDayFrames = new ArrayList<>();
    private long sportLastFrameAt = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable dfWatchdog = () -> runDfWatchdog();
    private final Runnable sportWatchdog = () -> runSportWatchdog();
    private final List<VeepooActivitySample> pendingOriginSamples = new ArrayList<>();
    private final List<VeepooActivitySample> pendingSleepSamples = new ArrayList<>();

    // Capabilities from the device-pushed 0xA7 report (package 2)
    private int watchDataDay = VeepooConstants.WATCH_DAY_DEFAULT;
    private int originProtocolVersion = 1; // 3/5 => Origin3 (0xDF), anything else => v1 (0xD1)
    private boolean preciseSleepCapable = false;
    private int sportModelDay = 0;      // days of workout history kept (0 = unsupported)
    private int multSportModeType = 0;  // 4/5/6 => GPS variant of the 0xD4 layout

    public VeepooDeviceSupport() {
        super(LOG);
        addSupportedService(VeepooConstants.UUID_SERVICE_F008);
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
        // Password confirmation carries the phone time; followed by an explicit time sync.
        writeFrame(builder, buildPwdConfirmTimeFrame());
        writeFrame(builder, buildSyncTimeFrame());
        writeFrame(builder, new byte[]{VeepooConstants.OP_READ_BATTERY, 0x00});
        writeFrame(builder, new byte[]{VeepooConstants.OP_PRODUCTION_INFO, 0x00});
        // The device pushes its 0xA7 capability report after pairing; it is handled in
        // onCharacteristicChanged() and may arrive before the INITIALIZED state is reached.
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    /** Writes a command frame to the F008 write characteristic, zero-padded to 20 bytes. */
    private void writeFrame(TransactionBuilder builder, byte[] frame) {
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
     * Opcode 0xA1: device password confirmation plus phone time.
     * Layout: [0]=A1, [1..2]=password LE16, [3]=mode 0 (confirm+time), [4..5]=year BE16 hex,
     * [6]=month, [7]=day, [8]=hour, [9]=minute, [10]=second, [11]=12/24h flag, [12]=const 1,
     * [13]=timezone in 15-minute steps.
     */
    private byte[] buildPwdConfirmTimeFrame() {
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
        int tzMinutes = TimeZone.getDefault().getRawOffset() / 60;
        frame[13] = (byte) (tzMinutes / 15);
        return frame;
    }

    /** Opcode 0xA5: time sync. [4..7]=hour/minute/second after year BE16/month/day, [8]=time mode (2=24h). */
    private byte[] buildSyncTimeFrame() {
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
            case 0xD0:
                handleHeartRateMeasurement(data);
                break;
            default:
                LOG.debug("Unhandled VeePoo opcode: {}", String.format("%02X", (int) opcode));
        }
    }

    /**
     * Opcode 0xA1 response: [3]=status (6 = check + time success), [4..5]=deviceNumber BE,
     * [6..9]=firmware/test version digits.
     */
    private void handlePwdConfirmResponse(byte[] data) {
        if (data.length < 20) {
            LOG.warn("Short 0xA1 response: {} bytes", data.length);
            return;
        }
        int status = data[3] & 0xFF;
        int deviceNumber = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        String firmwareVersion = hexByte(data[6]) + "." + hexByte(data[7]) + "." + hexByte(data[8]) + "." + hexByte(data[9]);
        LOG.info("Password confirm status: {} (device number {}, firmware {})", status, deviceNumber, firmwareVersion);
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
     * precision sleep tag [13], multi-sport mode type [16].
     */
    private void handleDeviceFunctionReport(byte[] data) {
        if (data.length < 20) {
            return;
        }
        int packageId = data[19] & 0xFF;
        LOG.info("Device capability report, package {}", packageId);
        if (packageId == 2) {
            int watchDay = data[2] & 0xFF;
            if (watchDay >= 1 && watchDay <= 7) {
                watchDataDay = watchDay;
            }
            originProtocolVersion = data[10] & 0xFF;
            preciseSleepCapable = (data[13] & 0xFF) != 0;
            sportModelDay = data[VeepooConstants.CAP_PKG2_SPORT_MODEL_DAY] & 0xFF;
            multSportModeType = data[VeepooConstants.CAP_PKG2_MULTISPORT_MODE_TYPE] & 0xFF;
            LOG.info("Capabilities: watchDataDay={}, originProtocolVersion={} ({}), preciseSleep={}, workoutDays={}, multisport={}",
                    watchDataDay, originProtocolVersion,
                    (originProtocolVersion == 3 || originProtocolVersion == 5) ? "Origin3/DF" : "v1",
                    preciseSleepCapable, sportModelDay, multSportModeType);
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
        LOG.info("Starting data fetch: {} days (sleep then activity)", watchDataDay);
        requestSleepDay(0);
    }

    private void requestSleepDay(int dayIndex) {
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
        sleepDayPayload.write(data, 4, VeepooConstants.PACKET_SIZE - 4);
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

        int nextDay = dayIndex + 1;
        if (nextDay < watchDataDay) {
            synchronized (syncLock) {
                sleepDayIndex = nextDay;
            }
            requestSleepDay(nextDay);
            return;
        }
        synchronized (syncLock) {
            fetchPhase = FetchPhase.ORIGIN;
        }
        if (originProtocolVersion == 3 || originProtocolVersion == 5) {
            requestOrigin3Day(0);
        } else {
            requestOriginDay(0);
        }
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
        persistPendingOriginDay();
        afterOriginDayComplete(originDayIndex);
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
            parseOrigin3Day(frames, dayIndex);
            persistPendingOriginDay();
            afterOriginDayComplete(dayIndex);
            return;
        }
        dfDayFrames.add(data);
    }

    /** Mirrors the app's 90-second per-day timeout: advances with whatever data arrived so far. */
    private void scheduleDfWatchdog() {
        mainHandler.removeCallbacks(dfWatchdog);
        dfLastFrameAt = System.currentTimeMillis();
        mainHandler.postDelayed(dfWatchdog, 90_000L);
    }

    private void runDfWatchdog() {
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.ORIGIN) {
                return;
            }
        }
        long idle = System.currentTimeMillis() - dfLastFrameAt;
        if (idle < 90_000L) {
            scheduleDfWatchdog();
            return;
        }
        LOG.warn("Origin3 read stalled for {} ms; finishing the day with partial data", idle);
        mainHandler.removeCallbacks(dfWatchdog);
        List<byte[]> frames = new ArrayList<>(dfDayFrames);
        dfDayFrames.clear();
        int dayIndex = originDayIndex;
        parseOrigin3Day(frames, dayIndex);
        persistPendingOriginDay();
        afterOriginDayComplete(dayIndex);
    }

    /**
     * Advances the fetch after an activity day: next origin day, then the workout history days if
     * the device keeps them (A7 [5] > 0), otherwise done.
     */
    private void afterOriginDayComplete(int dayIndex) {
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
        if (sportModelDay > 0) {
            synchronized (syncLock) {
                fetchPhase = FetchPhase.SPORT;
                sportDayIndex = 0;
            }
            LOG.info("Activity days complete; reading {} day(s) of workout history", sportModelDay);
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
        parseSportDay(frames, dayIndex);

        int nextDay = dayIndex + 1;
        synchronized (syncLock) {
            if (nextDay < sportModelDay) {
                sportDayIndex = nextDay;
            } else {
                fetchPhase = FetchPhase.IDLE;
            }
        }
        if (nextDay < sportModelDay) {
            requestSportDay(nextDay);
        } else {
            finishFetch();
        }
    }

    /** Mirrors the app's 90-second per-day timeout: advances with whatever data arrived so far. */
    private void scheduleSportWatchdog() {
        mainHandler.removeCallbacks(sportWatchdog);
        sportLastFrameAt = System.currentTimeMillis();
        mainHandler.postDelayed(sportWatchdog, 90_000L);
    }

    private void runSportWatchdog() {
        synchronized (syncLock) {
            if (fetchPhase != FetchPhase.SPORT) {
                return;
            }
        }
        long idle = System.currentTimeMillis() - sportLastFrameAt;
        if (idle < 90_000L) {
            scheduleSportWatchdog();
            return;
        }
        LOG.warn("Workout read stalled for {} ms; finishing the day with partial data", idle);
        mainHandler.removeCallbacks(sportWatchdog);
        List<byte[]> frames = new ArrayList<>(sportDayFrames);
        sportDayFrames.clear();
        int dayIndex = sportDayIndex;
        parseSportDay(frames, dayIndex);

        int nextDay = dayIndex + 1;
        synchronized (syncLock) {
            if (nextDay < sportModelDay) {
                sportDayIndex = nextDay;
            } else {
                fetchPhase = FetchPhase.IDLE;
            }
        }
        if (nextDay < sportModelDay) {
            requestSportDay(nextDay);
        } else {
            finishFetch();
        }
    }

    /** Parses one 0xD4 day: GPS layout for multi-sport types 4/5/6, plain layout otherwise. */
    private void parseSportDay(List<byte[]> frames, int dayIndex) {
        try {
            if (multSportModeType == 4 || multSportModeType == 5 || multSportModeType == 6) {
                parseSportGpsDay(frames, dayIndex);
            } else {
                parseSportPlainDay(frames, dayIndex);
            }
        } catch (Exception e) {
            LOG.error("Error parsing workout day {}", dayIndex, e);
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
    private void parseSportPlainDay(List<byte[]> frames, int dayIndex) {
        if (frames.size() < 3) {
            LOG.info("Workout day {}: no workout data ({} frame(s))", dayIndex, frames.size());
            return;
        }
        byte[] head = new byte[42];
        for (int k = 0; k < 3; k++) {
            System.arraycopy(frames.get(k), 6, head, k * 14, 14);
        }

        Date startTime = dateFromParts(be16(head, 1), head[3] & 0xFF, head[4] & 0xFF,
                head[5] & 0xFF, head[6] & 0xFF, head[7] & 0xFF);
        Date stopTime = dateFromParts(be16(head, 8), head[10] & 0xFF, head[11] & 0xFF,
                head[12] & 0xFF, head[13] & 0xFF, head[14] & 0xFF);
        if (startTime == null || stopTime == null) {
            LOG.warn("Workout day {}: implausible workout window in the head; skipping", dayIndex);
            return;
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
    private void parseSportGpsDay(List<byte[]> frames, int dayIndex) {
        if (frames.isEmpty()) {
            LOG.info("Workout day {}: no workout data", dayIndex);
            return;
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
            return;
        }
        int headLength = Math.min((blob[1] & 0xFF) + 2, blob.length);
        long startUnix = le32(blob, 4);
        if (startUnix < 946684800L || startUnix > 4102444800L || headLength < 40) {
            LOG.warn("Workout day {}: implausible GPS head (start epoch {}); skipping", dayIndex, startUnix);
            return;
        }

        long stopUnix = le32(blob, 8);
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
            return;
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
    private void parseOrigin3Day(List<byte[]> frames, int dayIndex) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        boolean inBlock = false;
        int packetLen = -1; // single-packet length j: read from the first block header, reused for the rest of the day
        int validBytes = -1;
        int checksum = -1;

        for (byte[] frame : frames) {
            if ((frame[3] & 0xFF) == 0) {
                flushOrigin3Block(stream, validBytes, checksum, dayIndex);
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
        flushOrigin3Block(stream, validBytes, checksum, dayIndex);
    }

    private void flushOrigin3Block(ByteArrayOutputStream stream, int validBytes, int checksum, int dayIndex) {
        byte[] payload = stream.toByteArray();
        stream.reset();
        if (payload.length == 0) {
            return;
        }
        if (validBytes >= 0 && validBytes < payload.length) {
            payload = Arrays.copyOf(payload, validBytes);
        }
        if (checksum >= 0 && vpCrc16(payload) != checksum) {
            LOG.warn("Origin3 block for day {} failed its CRC-16 check; dropping", dayIndex);
            return;
        }
        parseOrigin3TlvStream(payload, dayIndex);
    }

    /**
     * Parses one Origin3 block stream. Each 0xB1 tag starts a new 5-minute record (hour [2],
     * minute [3]); fields accumulate until the next 0xB1 or the 0xC0 terminator. B2 (SportFiveMinuteData):
     * [0..1]=steps BE16, [2..3]=sport BE16, [4..5]=distance meters BE16, [6..7]=kcal tenths BE16,
     * [8]=pose, [9]=wear. B4/B5 carry heart-rate sample arrays.
     */
    private void parseOrigin3TlvStream(byte[] payload, int dayIndex) {
        Calendar base = Calendar.getInstance();
        base.add(Calendar.DAY_OF_MONTH, -dayIndex);

        boolean haveRecord = false;
        int hour = -1;
        int minute = -1;
        int steps = 0;
        int distanceMeters = 0;
        int kcalTenths = 0;
        int heartRate = ActivitySample.NOT_MEASURED;
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
                if (flushOrigin3Record(base, dayIndex, haveRecord, hour, minute, steps, distanceMeters, kcalTenths, heartRate)) {
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
                heartRate = ActivitySample.NOT_MEASURED;
            } else if (haveRecord && tag == 0xB2 && body.length >= 10) {
                steps = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                distanceMeters = ((body[4] & 0xFF) << 8) | (body[5] & 0xFF);
                kcalTenths = ((body[6] & 0xFF) << 8) | (body[7] & 0xFF);
            } else if (haveRecord && (tag == 0xB4 || tag == 0xB5)) {
                int average = averageValidHeartRate(body);
                if (average > 0 && heartRate == ActivitySample.NOT_MEASURED) {
                    heartRate = average;
                }
            }
            pos += 2 + length;
        }
        if (flushOrigin3Record(base, dayIndex, haveRecord, hour, minute, steps, distanceMeters, kcalTenths, heartRate)) {
            recordsAdded++;
        }
        LOG.debug("Origin3 stream for day {}: {} five-minute records", dayIndex, recordsAdded);
    }

    private boolean flushOrigin3Record(Calendar base, int dayIndex, boolean valid, int hour, int minute,
                                       int steps, int distanceMeters, int kcalTenths, int heartRate) {
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
        VeepooActivitySample sample = new VeepooActivitySample();
        sample.setTimestamp((int) (stamp.getTimeInMillis() / 1000L));
        sample.setRawKind(VeepooConstants.RAW_KIND_ACTIVITY);
        sample.setSteps(steps);
        sample.setHeartRate(heartRate);
        sample.setCaloriesBurnt((kcalTenths + 5) / 10);
        sample.setDistanceMeters(distanceMeters);
        sample.setDataSource(DATA_SOURCE_ACTIVITY_SUMMARY);
        pendingOriginSamples.add(sample);
        return true;
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

    /** Opcode 0xD0: single-shot heart rate result, [1] = bpm. */
    private void handleHeartRateMeasurement(byte[] data) {
        if (data.length < 2) {
            return;
        }
        int bpm = data[1] & 0xFF;
        if (bpm < 30 || bpm > 200) {
            LOG.debug("Ignoring implausible heart rate measurement: {}", bpm);
            return;
        }
        LOG.info("Heart rate measurement: {} bpm", bpm);
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
        } catch (Exception e) {
            LOG.error("Error saving heart rate measurement", e);
        }
    }

    @Override
    public void onHeartRateTest() {
        try {
            TransactionBuilder builder = performInitialized("heart rate test");
            writeFrame(builder, new byte[]{VeepooConstants.OP_HR_REALTIME, 0x01});
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error triggering heart rate measurement", e);
        }
    }

    /**
     * Persist the collected origin (activity) samples. Sleep samples are persisted last so they
     * win over overlapping activity rows within the sleep window.
     */
    private void persistPendingOriginDay() {
        if (pendingOriginSamples.isEmpty()) {
            return;
        }
        List<VeepooActivitySample> batch = new ArrayList<>(pendingOriginSamples);
        pendingOriginSamples.clear();
        persistSamples(batch, false);
    }

    private void finishFetch() {
        persistPendingOriginDay();
        if (!pendingSleepSamples.isEmpty()) {
            List<VeepooActivitySample> sleepBatch = new ArrayList<>(pendingSleepSamples);
            pendingSleepSamples.clear();
            persistSamples(sleepBatch, true);
        }
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
        LOG.info("VeePoo data fetch complete");
    }

    private void abortFetch() {
        mainHandler.removeCallbacks(dfWatchdog);
        mainHandler.removeCallbacks(sportWatchdog);
        synchronized (syncLock) {
            fetchPhase = FetchPhase.IDLE;
        }
        pendingOriginSamples.clear();
        pendingSleepSamples.clear();
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
        synchronized (syncLock) {
            fetchPhase = FetchPhase.IDLE;
        }
        super.dispose();
    }
}
