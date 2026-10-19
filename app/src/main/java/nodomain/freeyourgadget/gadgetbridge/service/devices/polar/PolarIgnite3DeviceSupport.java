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
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.polar.pftp.PftpConstants;
import nodomain.freeyourgadget.gadgetbridge.service.devices.polar.pftp.PftpFraming;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Support for the Polar Ignite 3 over Polar's PFTP protocol (BLE service 0xFEEE).
 *
 * <p>PFTP is a request/response file system protocol: the watch keeps its data, settings and
 * computed results as protobuf files, which the host reads and writes. Requests and queries
 * go out on {@link PftpConstants#UUID_CHARACTERISTIC_MTU} and are answered there, strictly one
 * at a time; notifications are fire-and-forget on the separate H2D/D2H characteristics.
 *
 * <p>This first version sets the time and reports firmware version and battery.
 */
public class PolarIgnite3DeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(PolarIgnite3DeviceSupport.class);

    private static final int REQUESTED_MTU = 517;
    private static final long RESPONSE_TIMEOUT_MILLIS = 30_000;

    private interface ResponseCallback {
        /** @param message the answer, or null if the watch did not answer in time */
        void onResponse(@Nullable PftpFraming.Message message);
    }

    private static final class Operation {
        final String name;
        final UUID characteristic;
        final byte[] message;
        /** null for notifications, which the watch does not answer */
        @Nullable
        final ResponseCallback callback;

        Operation(final String name, final UUID characteristic, final byte[] message,
                  @Nullable final ResponseCallback callback) {
            this.name = name;
            this.characteristic = characteristic;
            this.message = message;
            this.callback = callback;
        }
    }

    private final Deque<Operation> operations = new ArrayDeque<>();
    @Nullable
    private Operation currentOperation;
    private boolean ready;
    private final PftpFraming.Reassembler mtuReassembler = new PftpFraming.Reassembler();
    private final PftpFraming.Reassembler d2hReassembler = new PftpFraming.Reassembler();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable responseTimeout = this::onResponseTimeout;

    public PolarIgnite3DeviceSupport() {
        super(LOG);
        addSupportedService(PftpConstants.UUID_SERVICE_PFTP);
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        // A support instance may outlive a connection, never carry operations into the next one
        synchronized (this) {
            handler.removeCallbacks(responseTimeout);
            operations.clear();
            currentOperation = null;
            ready = false;
            mtuReassembler.reset();
            d2hReassembler.reset();
        }

        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.requestMtu(REQUESTED_MTU);

        final BluetoothGattCharacteristic mtuCharacteristic = getCharacteristic(PftpConstants.UUID_CHARACTERISTIC_MTU);
        if (mtuCharacteristic != null) {
            // Frames are acknowledged one by one; this keeps large transfers from overrunning the watch
            mtuCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }
        builder.notify(mtuCharacteristic, true);
        builder.notify(PftpConstants.UUID_CHARACTERISTIC_D2H, true);
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        builder.run(this::onReady);
        return builder;
    }

    private void onReady() {
        synchronized (this) {
            ready = true;
        }
        setTime();
        getFile("/DEVICE.BPB", this::handleDeviceInfo);
        query(PftpConstants.QUERY_GET_BATTERY_STATUS, null, this::handleBatteryStatusResult);
    }

    @Override
    public void dispose() {
        synchronized (this) {
            handler.removeCallbacks(responseTimeout);
            operations.clear();
            currentOperation = null;
            ready = false;
        }
        super.dispose();
    }

    @Override
    public void onSetTime() {
        setTime();
    }

    private void setTime() {
        final GregorianCalendar now = new GregorianCalendar();
        final int offsetMinutes = TimeZone.getDefault().getOffset(now.getTimeInMillis()) / 60_000;

        final PolarProto.PbPFtpSetLocalTimeParams local = PolarProto.PbPFtpSetLocalTimeParams.newBuilder()
                .setDate(pbDate(now))
                .setTime(pbTime(now))
                .setTzOffset(offsetMinutes)
                .build();
        query(PftpConstants.QUERY_SET_LOCAL_TIME, local.toByteArray(), statusLogger("set local time"));

        final GregorianCalendar utc = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(now.getTimeInMillis());
        final PolarProto.PbPFtpSetSystemTimeParams system = PolarProto.PbPFtpSetSystemTimeParams.newBuilder()
                .setDate(pbDate(utc))
                .setTime(pbTime(utc))
                .setTrusted(true)
                .build();
        query(PftpConstants.QUERY_SET_SYSTEM_TIME, system.toByteArray(), statusLogger("set system time"));
    }

    private static PolarProto.PbDate pbDate(final Calendar calendar) {
        return PolarProto.PbDate.newBuilder()
                .setYear(calendar.get(Calendar.YEAR))
                .setMonth(calendar.get(Calendar.MONTH) + 1)
                .setDay(calendar.get(Calendar.DAY_OF_MONTH))
                .build();
    }

    private static PolarProto.PbTime pbTime(final Calendar calendar) {
        return PolarProto.PbTime.newBuilder()
                .setHour(calendar.get(Calendar.HOUR_OF_DAY))
                .setMinute(calendar.get(Calendar.MINUTE))
                .setSeconds(calendar.get(Calendar.SECOND))
                .setMillis(calendar.get(Calendar.MILLISECOND))
                .build();
    }

    private void handleDeviceInfo(@Nullable final PftpFraming.Message message) {
        final byte[] payload = payloadOrLog("/DEVICE.BPB", message);
        if (payload == null) {
            return;
        }
        final PolarProto.PbDeviceInfo info;
        try {
            info = PolarProto.PbDeviceInfo.parseFrom(payload);
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse /DEVICE.BPB", e);
            return;
        }
        final GBDeviceEventVersionInfo versionInfo = new GBDeviceEventVersionInfo();
        versionInfo.fwVersion = version(info.getDeviceVersion());
        versionInfo.hwVersion = info.getHardwareCode();
        evaluateGBDeviceEvent(versionInfo);
    }

    private static String version(final PolarProto.PbVersion version) {
        return String.format(Locale.ROOT, "%d.%d.%d", version.getMajor(), version.getMinor(), version.getPatch());
    }

    private void handleBatteryStatusResult(@Nullable final PftpFraming.Message message) {
        final byte[] payload = payloadOrLog("battery status", message);
        if (payload != null) {
            handleBatteryStatus(payload);
        }
    }

    private void handleBatteryStatus(final byte[] payload) {
        final PolarProto.PbPFtpBatteryStatusResult status;
        try {
            status = PolarProto.PbPFtpBatteryStatusResult.parseFrom(payload);
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse battery status", e);
            return;
        }
        final GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
        batteryInfo.level = status.getBatteryStatus();
        batteryInfo.state = status.getCharging() ? BatteryState.BATTERY_CHARGING : BatteryState.BATTERY_NORMAL;
        evaluateGBDeviceEvent(batteryInfo);
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        final UUID uuid = characteristic.getUuid();
        if (PftpConstants.UUID_CHARACTERISTIC_MTU.equals(uuid)) {
            onMtuFrame(value);
            return true;
        } else if (PftpConstants.UUID_CHARACTERISTIC_D2H.equals(uuid)) {
            onD2hFrame(value);
            return true;
        }
        return false;
    }

    private void onMtuFrame(final byte[] frame) {
        final PftpFraming.Message message;
        try {
            message = mtuReassembler.add(frame);
        } catch (final IllegalStateException e) {
            LOG.warn("Dropping broken PFTP response: {}", e.getMessage());
            completeCurrentOperation(null);
            return;
        }
        if (message != null) {
            completeCurrentOperation(message);
        }
    }

    private void onD2hFrame(final byte[] frame) {
        final PftpFraming.Message message;
        try {
            message = d2hReassembler.add(frame);
        } catch (final IllegalStateException e) {
            LOG.warn("Dropping broken PFTP notification: {}", e.getMessage());
            return;
        }
        if (message == null || message.payload == null || message.payload.length == 0) {
            return;
        }
        final int id = message.payload[0] & 0xFF;
        final byte[] params = new byte[message.payload.length - 1];
        System.arraycopy(message.payload, 1, params, 0, params.length);
        handleDeviceNotification(id, params);
    }

    private void handleDeviceNotification(final int id, final byte[] params) {
        switch (id) {
            case PftpConstants.D2H_BATTERY_STATUS:
                handleBatteryStatus(params);
                return;
            case PftpConstants.D2H_SYNC_REQUIRED:
                // Data sync is not implemented yet
                LOG.info("Watch requests a sync: {}", GB.hexdump(params));
                return;
            default:
                LOG.debug("Unhandled PFTP notification {}: {}", id, GB.hexdump(params));
        }
    }

    // ---- operation queue ----

    private void getFile(final String path, final ResponseCallback callback) {
        enqueue(new Operation("GET " + path, PftpConstants.UUID_CHARACTERISTIC_MTU,
                PftpFraming.request(PolarProto.PbPFtpOperation.Command.GET, path, null), callback));
    }

    private void query(final int queryId, @Nullable final byte[] params, final ResponseCallback callback) {
        enqueue(new Operation("query " + queryId, PftpConstants.UUID_CHARACTERISTIC_MTU,
                PftpFraming.query(queryId, params), callback));
    }

    private ResponseCallback statusLogger(final String name) {
        return message -> {
            if (message == null) {
                LOG.warn("{}: no answer", name);
            } else if (message.isStatus() && message.error != PftpConstants.ERROR_OPERATION_SUCCEEDED) {
                LOG.warn("{} failed with PFTP error {}", name, message.error);
            }
        };
    }

    @Nullable
    private static byte[] payloadOrLog(final String name, @Nullable final PftpFraming.Message message) {
        if (message == null) {
            LOG.warn("{}: no answer", name);
            return null;
        }
        if (message.isStatus()) {
            LOG.warn("{}: PFTP error {}", name, message.error);
            return null;
        }
        return message.payload;
    }

    private void enqueue(final Operation operation) {
        synchronized (this) {
            operations.add(operation);
        }
        sendNextOperation();
    }

    private void sendNextOperation() {
        final Operation operation;
        synchronized (this) {
            if (!ready || currentOperation != null || operations.isEmpty()) {
                return;
            }
            operation = operations.poll();
            if (operation.callback != null) {
                currentOperation = operation;
                mtuReassembler.reset();
                handler.postDelayed(responseTimeout, RESPONSE_TIMEOUT_MILLIS);
            }
        }

        final List<byte[]> frames = PftpFraming.frames(operation.message, getMTU() - 3);
        LOG.debug("Sending PFTP {} in {} frame(s)", operation.name, frames.size());
        final BluetoothGattCharacteristic characteristic = getCharacteristic(operation.characteristic);
        final TransactionBuilder builder = createTransactionBuilder("pftp " + operation.name);
        for (final byte[] frame : frames) {
            builder.write(characteristic, frame);
        }
        builder.queue();

        if (operation.callback == null) {
            sendNextOperation();
        }
    }

    private void completeCurrentOperation(@Nullable final PftpFraming.Message message) {
        final Operation operation;
        synchronized (this) {
            operation = currentOperation;
            if (operation == null) {
                LOG.warn("PFTP response without a pending request");
                return;
            }
            currentOperation = null;
            handler.removeCallbacks(responseTimeout);
        }
        try {
            operation.callback.onResponse(message);
        } catch (final Exception e) {
            LOG.error("Failed to handle PFTP response to {}", operation.name, e);
        }
        sendNextOperation();
    }

    private void onResponseTimeout() {
        final String name;
        synchronized (this) {
            name = currentOperation != null ? currentOperation.name : null;
        }
        LOG.warn("PFTP {} timed out", name);
        mtuReassembler.reset();
        completeCurrentOperation(null);
    }
}
