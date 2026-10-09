/*  Copyright (C) 2026

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.jbl;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static nodomain.freeyourgadget.gadgetbridge.service.btle.BluetoothCompanyIdentifiers.ZHONGSHAN_CITY_RICHSOUND_ELECTRONIC_INDUSTRIAL_LTD;

/**
 * Resolves the rotating BLE session address of a paired JBL TWS headset.
 *
 * <p>A JBL Tune Buds 2 advertises under a rotating LE address. Its advertisement
 * includes CRCs derived from the phone's Bluetooth name.</p>
 */
final class JblLeAddressScanner {

    interface Listener {
        @MainThread
        void onResolved(@NonNull String address);

        @MainThread
        default void onFailed() {
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(JblLeAddressScanner.class);

    /** Stock JBL scan cadence: scan for 8 seconds, pause for 1 second, then retry. */
    private static final long SCAN_WINDOW_MS = 8_000L;
    private static final long SCAN_RESTART_DELAY_MS = 1_000L;

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean delivered = new AtomicBoolean(false);
    private BluetoothLeScanner activeScanner;
    private ScanCallback activeCallback;
    private Runnable cycle;

    JblLeAddressScanner(@NonNull final Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Starts a BLE scan for an advertisement matching this phone's Bluetooth name.
     *
     * @param listener callback fired on the main thread with the resolved LE address
     */
    @RequiresPermission(allOf = {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"})
    @SuppressLint("MissingPermission")
    void start(@NonNull final Listener listener) {
        if (!inFlight.compareAndSet(false, true)) {
            LOG.warn("JblLeAddressScanner.start: already in flight");
            return;
        }
        delivered.set(false);
        handler.post(() -> startOnMainThread(listener));
    }

    @RequiresPermission(allOf = {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"})
    @SuppressLint("MissingPermission")
    private void startOnMainThread(@NonNull final Listener listener) {
        if (delivered.get()) {
            return;
        }

        final BluetoothLeScanner scanner;
        final String localBluetoothName;
        try {
            final BluetoothManager manager = ContextCompat.getSystemService(appContext, BluetoothManager.class);
            final BluetoothAdapter adapter = (manager != null) ? manager.getAdapter() : null;
            if (adapter == null || !adapter.isEnabled()) {
                LOG.warn("JblLeAddressScanner.start: Bluetooth adapter not available");
                finish(null, listener);
                return;
            }
            localBluetoothName = adapter.getName();
            scanner = adapter.getBluetoothLeScanner();
        } catch (final SecurityException e) {
            LOG.warn("JblLeAddressScanner.start: Bluetooth permission denied", e);
            finish(null, listener);
            return;
        }
        if (localBluetoothName == null) {
            LOG.warn("JblLeAddressScanner.start: local Bluetooth name not available");
            finish(null, listener);
            return;
        }
        if (scanner == null) {
            LOG.warn("JblLeAddressScanner.start: scanner not available");
            finish(null, listener);
            return;
        }

        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        // Scan everything because firmware revisions use both manufacturer data
        // and FDDF service data for the same vendor payload.
        final ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(final int callbackType, @NonNull final ScanResult result) {
                if (delivered.get()) {
                    // Already resolved; ignore late results.
                    return;
                }
                final BluetoothDevice device = result.getDevice();
                if (device == null) {
                    return;
                }
                if (matchesHeadset(result, localBluetoothName)) {
                    LOG.info("JblLeAddressScanner: Tune Buds 2 phone-name CRC match at {}", device.getAddress());
                    resolve(device.getAddress(), listener);
                }
            }

            @Override
            public void onScanFailed(final int errorCode) {
                LOG.warn("JblLeAddressScanner.onScanFailed: code={}", errorCode);
                finish(null, listener);
            }
        };

        activeScanner = scanner;
        activeCallback = callback;
        handler.post(() -> startScanCycle(scanner, settings, callback, listener));
        LOG.info("JblLeAddressScanner: scanning for matching PID/phone-name CRC in repeating {}ms windows",
                SCAN_WINDOW_MS);
    }

    boolean isInFlight() {
        return inFlight.get();
    }

    void cancel() {
        if (!delivered.compareAndSet(false, true)) {
            return;
        }
        inFlight.set(false);
        handler.post(this::stopActiveScan);
    }

    @SuppressLint("MissingPermission")
    private void startScanCycle(@NonNull final BluetoothLeScanner scanner,
                                @NonNull final ScanSettings settings,
                                @NonNull final ScanCallback callback,
                                @NonNull final Listener listener) {
        if (delivered.get()) {
            return;
        }
        try {
            scanner.startScan(null, settings, callback);
        } catch (final SecurityException | IllegalStateException e) {
            LOG.warn("JblLeAddressScanner.start: scanner not ready", e);
            finish(null, listener);
            return;
        }
        cycle = () -> {
            if (delivered.get()) {
                return;
            }
            stopScan(scanner, callback);
            handler.postDelayed(() -> startScanCycle(scanner, settings, callback, listener),
                    SCAN_RESTART_DELAY_MS);
        };
        handler.postDelayed(cycle, SCAN_WINDOW_MS);
    }

    private void resolve(@NonNull final String address,
                         @NonNull final Listener listener) {
        finish(address, listener);
    }

    private static boolean matchesHeadset(@NonNull final ScanResult result,
                                          @Nullable final String localBluetoothName) {
        final android.bluetooth.le.ScanRecord record = result.getScanRecord();
        if (record == null) {
            return false;
        }
        byte[] data = record.getManufacturerSpecificData(ZHONGSHAN_CITY_RICHSOUND_ELECTRONIC_INDUSTRIAL_LTD);
        if (data == null || data.length < 2
                || BLETypeConversions.toUint16(data) != JblProtocol.TUNE_BUDS_2_PRODUCT_ID) {
            data = record.getServiceData(JblProtocol.HARMAN_SERVICE_DATA_UUID);
        }
        return matchesAdvertisementPayload(data, localBluetoothName);
    }

    static boolean matchesAdvertisementPayload(@Nullable final byte[] data,
                                               @Nullable final String localBluetoothName) {
        if (data == null || data.length < 5 || localBluetoothName == null
                || BLETypeConversions.toUint16(data) != JblProtocol.TUNE_BUDS_2_PRODUCT_ID) {
            return false;
        }

        // The payload carries local Bluetooth-name CRCs at offsets 3 and 5.
        final int nameCrc31 = crc16Prefix(localBluetoothName, 31);
        final int nameCrc50 = crc16Prefix(localBluetoothName, 50);
        if (matchesNameCrc(BLETypeConversions.toUint16(data, 3), nameCrc31, nameCrc50)) {
            return true;
        }
        return data.length >= 7 && matchesNameCrc(BLETypeConversions.toUint16(data, 5), nameCrc31, nameCrc50);
    }

    private static boolean matchesNameCrc(final int advertisedCrc, final int nameCrc31, final int nameCrc50) {
        return advertisedCrc == nameCrc31 || advertisedCrc == nameCrc50;
    }

    private static int crc16Prefix(@NonNull final String value, final int maxBytes) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return crc16(bytes, Math.min(bytes.length, maxBytes));
    }

    private static int crc16(@NonNull final byte[] data, final int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            final byte value = data[i];
            crc ^= value & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
            }
        }
        return crc & 0xffff;
    }

    @SuppressLint("MissingPermission")
    private void stopScan(@NonNull final BluetoothLeScanner scanner,
                          @Nullable final ScanCallback callback) {
        try {
            if (callback != null) {
                scanner.stopScan(callback);
            } else {
                scanner.stopScan((ScanCallback) null);
            }
        } catch (final SecurityException | IllegalStateException ignored) {
        }
    }

    private void finish(@Nullable final String address, @NonNull final Listener listener) {
        if (!delivered.compareAndSet(false, true)) {
            return;
        }
        handler.post(() -> {
            if (cycle != null) {
                handler.removeCallbacks(cycle);
            }
            stopActiveScan();
            try {
                if (address != null) {
                    listener.onResolved(address);
                } else {
                    listener.onFailed();
                }
            } finally {
                inFlight.set(false);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void stopActiveScan() {
        if (cycle != null) {
            handler.removeCallbacks(cycle);
        }
        cycle = null;
        final BluetoothLeScanner scanner = activeScanner;
        final ScanCallback callback = activeCallback;
        if (scanner != null && callback != null) {
            stopScan(scanner, callback);
        }
        activeScanner = null;
        activeCallback = null;
    }
}
