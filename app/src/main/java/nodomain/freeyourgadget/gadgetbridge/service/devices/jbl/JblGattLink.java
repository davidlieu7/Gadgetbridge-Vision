/*  Copyright (C) 2026

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package nodomain.freeyourgadget.gadgetbridge.service.devices.jbl;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Minimal, single-link BLE GATT queue for the JBL TWS support.
 *
 * <p>The stock {@code BtLEQueue} always opens a connection against
 * {@link GBDevice#getAddress()} (the persistent Classic MAC), but JBL TWS
 * headphones rotate their BLE session address.  This class accepts a
 * caller-supplied LE address and manages the full GATT lifecycle:
 * connect, service discovery, MTU negotiation, notification subscription,
 * connection priority request, and a serial write queue.</p>
 *
 * <p>After service discovery succeeds we request
 * {@link BluetoothGatt#CONNECTION_PRIORITY_HIGH}, matching the stock JBL
 * app's behavior, to reduce the BLE connection interval for lower command
 * latency.</p>
 */
final class JblGattLink {

    interface Sink {
        void onConnectionStateChange(int newState, int status);
        void onServicesDiscovered(@NonNull List<BluetoothGattService> services);
        void onCharacteristicChanged(@NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value);
        void onCharacteristicWrite(@NonNull BluetoothGattCharacteristic characteristic, int status);
        void onMtuChanged(int mtu, int status);
    }

    private static final Logger LOG = LoggerFactory.getLogger(JblGattLink.class);

    /** Delay before retrying a failed {@code writeCharacteristic} call. */
    private static final long WRITE_RETRY_DELAY_MS = 50L;
    /** Max consecutive write retries before failing the link. */
    private static final int WRITE_RETRY_LIMIT = 20;
    /** Number of retries after the first failed service-discovery attempt. */
    private static final int SERVICE_DISCOVERY_RETRY_LIMIT = 2;

    private final Context appContext;
    private final Sink sink;

    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final Deque<PendingWrite> pendingWrites = new ArrayDeque<>();
    private final Object gattMonitor = new Object();

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private final HandlerThread dispatchThread;
    private final Handler dispatchHandler;
    private final Runnable dispatchNext = this::dispatchNextWrite;
    private boolean writeInFlight;
    private int consecutiveWriteRetries;
    private int serviceDiscoveryRetries;
    private List<BluetoothGattService> discoveredServices;
    private boolean waitingForNotificationSetup;
    /**
     * Count of in-flight non-write GATT operations (MTU request, notification
     * descriptor writes). Android BLE serialises GATT operations on a single
     * link, so {@code writeCharacteristic} returns false while any of these is
     * pending. We defer write dispatch until this reaches zero, otherwise the
     * headset's initial queries (notably the battery) are dropped at random.
     */
    private int pendingOperations;
    private boolean operationsWatchdogArmed;
    @SuppressLint("MissingPermission")
    private final Runnable operationsWatchdog = () -> {
        operationsWatchdogArmed = false;
        if (pendingOperations > 0) {
            if (waitingForNotificationSetup) {
                LOG.warn("JblGattLink: notification setup was not acknowledged in time");
                final BluetoothGatt gatt;
                synchronized (gattMonitor) {
                    gatt = bluetoothGatt;
                }
                if (gatt != null) {
                    failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
                }
                return;
            }
            LOG.warn("JblGattLink: optional GATT operation was not acknowledged in time; continuing without it");
            pendingOperations = 0;
        }
        dispatchNextWrite();
    };
    /** True once the required JBL service and notification subscription are ready. */
    private volatile boolean protocolReady;

    JblGattLink(@NonNull final Context context,
                @NonNull final GBDevice gbDevice,
                @NonNull final Sink sink) {
        this.appContext = context.getApplicationContext();
        this.sink = sink;
        this.dispatchThread = new HandlerThread("JblGattLink-" + gbDevice.getAddress());
        this.dispatchThread.start();
        this.dispatchHandler = new Handler(this.dispatchThread.getLooper());
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    @SuppressLint("MissingPermission")
    void connect(@NonNull final String remoteAddress) {
        if (disposed.get()) {
            LOG.warn("JblGattLink.connect: link is disposed, refusing connect to {}", remoteAddress);
            return;
        }
        protocolReady = false;
        final BluetoothDevice remoteDevice;
        try {
            final BluetoothManager manager = ContextCompat.getSystemService(appContext, BluetoothManager.class);
            final BluetoothAdapter adapter = (manager != null) ? manager.getAdapter() : null;
            if (adapter == null || !adapter.isEnabled()) {
                LOG.warn("JblGattLink.connect: Bluetooth adapter is not enabled");
                postConnectionFailure(BluetoothGatt.GATT_FAILURE);
                return;
            }
            remoteDevice = adapter.getRemoteDevice(remoteAddress);
        } catch (final IllegalArgumentException ex) {
            LOG.warn("JblGattLink.connect: invalid BLE address {}", remoteAddress);
            postConnectionFailure(BluetoothGatt.GATT_FAILURE);
            return;
        } catch (final SecurityException ex) {
            LOG.warn("JblGattLink.connect: Bluetooth permission denied", ex);
            postConnectionFailure(BluetoothGatt.GATT_FAILURE);
            return;
        }
        dispatchHandler.post(() -> {
            if (disposed.get()) {
                return;
            }
            try {
                final BluetoothGatt gatt;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    gatt = remoteDevice.connectGatt(appContext, /*autoConnect*/ false,
                            internalCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    gatt = remoteDevice.connectGatt(appContext, /*autoConnect*/ false, internalCallback);
                }
                final boolean closeImmediately;
                synchronized (gattMonitor) {
                    closeImmediately = disposed.get();
                    if (!closeImmediately) {
                        bluetoothGatt = gatt;
                    }
                }
                if (closeImmediately && gatt != null) {
                    try {
                        gatt.disconnect();
                    } catch (final SecurityException ignored) {
                    }
                    try {
                        gatt.close();
                    } catch (final SecurityException ignored) {
                    }
                    return;
                }
                if (gatt == null) {
                    LOG.warn("JblGattLink.connect: connectGatt returned null");
                    if (!disposed.get()) {
                        sink.onConnectionStateChange(BluetoothProfile.STATE_DISCONNECTED, BluetoothGatt.GATT_FAILURE);
                    }
                }
            } catch (final SecurityException ex) {
                LOG.error("JblGattLink.connect: SecurityException while connecting", ex);
                if (!disposed.get()) {
                    sink.onConnectionStateChange(BluetoothProfile.STATE_DISCONNECTED, BluetoothGatt.GATT_FAILURE);
                }
            }
        });
    }

    private void postConnectionFailure(final int status) {
        dispatchHandler.post(() -> {
            if (!disposed.get()) {
                sink.onConnectionStateChange(BluetoothProfile.STATE_DISCONNECTED, status);
            }
        });
    }

    private boolean isCurrentGatt(@NonNull final BluetoothGatt gatt) {
        synchronized (gattMonitor) {
            return bluetoothGatt == gatt;
        }
    }

    @SuppressLint("MissingPermission")
    private void failCurrentGatt(@NonNull final BluetoothGatt gatt, final int status) {
        finishCurrentGatt(gatt, status, true);
    }

    @SuppressLint("MissingPermission")
    private void finishCurrentGatt(@NonNull final BluetoothGatt gatt,
                                   final int status,
                                   final boolean disconnect) {
        synchronized (gattMonitor) {
            if (bluetoothGatt != gatt) {
                return;
            }
            bluetoothGatt = null;
        }
        dispatchHandler.removeCallbacks(dispatchNext);
        dispatchHandler.removeCallbacks(operationsWatchdog);
        operationsWatchdogArmed = false;
        pendingWrites.clear();
        writeCharacteristic = null;
        writeInFlight = false;
        consecutiveWriteRetries = 0;
        serviceDiscoveryRetries = 0;
        discoveredServices = null;
        waitingForNotificationSetup = false;
        pendingOperations = 0;
        try {
            if (disconnect) {
                gatt.disconnect();
            }
        } catch (final SecurityException ignored) {
        }
        try {
            gatt.close();
        } catch (final SecurityException ignored) {
        }
        if (!disposed.get()) {
            sink.onConnectionStateChange(BluetoothProfile.STATE_DISCONNECTED, status);
        }
    }

    @SuppressLint("MissingPermission")
    private void requestServiceDiscovery(@NonNull final BluetoothGatt gatt) {
        if (!isCurrentGatt(gatt) || disposed.get()) {
            return;
        }
        try {
            if (gatt.discoverServices()) {
                return;
            }
        } catch (final SecurityException ex) {
            LOG.warn("JblGattLink: SecurityException starting service discovery", ex);
        }
        retryServiceDiscovery(gatt, BluetoothGatt.GATT_FAILURE);
    }

    private void retryServiceDiscovery(@NonNull final BluetoothGatt gatt, final int status) {
        if (++serviceDiscoveryRetries > SERVICE_DISCOVERY_RETRY_LIMIT) {
            LOG.warn("JblGattLink: service discovery failed after {} retries (status={})",
                    SERVICE_DISCOVERY_RETRY_LIMIT, status);
            failCurrentGatt(gatt, status);
            return;
        }
        LOG.warn("JblGattLink: service discovery failed (status={}), retry {}/{}",
                status, serviceDiscoveryRetries, SERVICE_DISCOVERY_RETRY_LIMIT);
        dispatchHandler.postDelayed(() -> requestServiceDiscovery(gatt), 200L);
    }

    /**
     * Returns {@code true} once the expected JBL GATT service and notification
     * subscription have been established. A bare LE connection is not sufficient:
     * a different peripheral can accept the connection but lack the JBL protocol.
     */
    boolean didCompleteProtocolSetup() {
        return protocolReady;
    }

    @SuppressLint("MissingPermission")
    void disconnect() {
        dispatchHandler.removeCallbacksAndMessages(null);
        synchronized (gattMonitor) {
            if (bluetoothGatt != null) {
                try {
                    bluetoothGatt.disconnect();
                    bluetoothGatt.close();
                } catch (final SecurityException ignored) {
                }
                bluetoothGatt = null;
            }
        }
    }

    void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        disconnect();
        dispatchThread.quitSafely();
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    @SuppressLint("MissingPermission")
    void enqueueWrite(@NonNull final UUID serviceUuid,
                      @NonNull final UUID characteristicUuid,
                      @NonNull final byte[] value) {
        if (disposed.get()) {
            LOG.warn("JblGattLink.enqueueWrite: link disposed, dropping {} bytes", value.length);
            return;
        }
        final PendingWrite write = new PendingWrite(serviceUuid, characteristicUuid, value);
        dispatchHandler.post(() -> {
            pendingWrites.addLast(write);
            dispatchNextWrite();
        });
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    @SuppressLint("MissingPermission")
    private void armOperationsWatchdog() {
        if (operationsWatchdogArmed) {
            return;
        }
        operationsWatchdogArmed = true;
        dispatchHandler.removeCallbacks(operationsWatchdog);
        dispatchHandler.postDelayed(operationsWatchdog, 2_000L);
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    @SuppressLint("MissingPermission")
    private void dispatchNextWrite() {
        if (pendingWrites.isEmpty()) {
            return;
        }
        if (writeInFlight) {
            return;
        }
        if (pendingOperations > 0) {
            LOG.debug("JblGattLink.dispatchNextWrite: {} non-write op(s) pending, deferring {} writes",
                    pendingOperations, pendingWrites.size());
            return;
        }
        final BluetoothGatt gatt;
        synchronized (gattMonitor) {
            gatt = bluetoothGatt;
        }
        if (gatt == null) {
            LOG.debug("JblGattLink.dispatchNextWrite: gatt is null, deferring {} writes", pendingWrites.size());
            return;
        }
        if (writeCharacteristic == null) {
            LOG.debug("JblGattLink.dispatchNextWrite: write characteristic not yet known, deferring");
            return;
        }
        final PendingWrite write = pendingWrites.peekFirst();
        if (!writeCharacteristic.getService().getUuid().equals(write.serviceUuid)
                || !writeCharacteristic.getUuid().equals(write.characteristicUuid)) {
            LOG.error("JblGattLink.dispatchNextWrite: queued write targets unexpected characteristic {}/{}",
                    write.serviceUuid, write.characteristicUuid);
            failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
            return;
        }
        try {
            writeCharacteristic.setValue(write.value);
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                consecutiveWriteRetries++;
                if (consecutiveWriteRetries > WRITE_RETRY_LIMIT) {
                    LOG.error("JblGattLink.dispatchNextWrite: failing link after {} write retries: {}",
                            consecutiveWriteRetries, GB.hexdump(write.value));
                    failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
                    return;
                }
                dispatchHandler.postDelayed(dispatchNext, WRITE_RETRY_DELAY_MS);
                return;
            }
            writeInFlight = true;
            consecutiveWriteRetries = 0;
        } catch (final SecurityException ex) {
            LOG.error("JblGattLink.dispatchNextWrite: SecurityException", ex);
            failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
        }
    }

    @SuppressLint("MissingPermission")
    void requestMtu(final int mtu) {
        dispatchHandler.post(() -> {
            final BluetoothGatt gatt;
            synchronized (gattMonitor) {
                gatt = bluetoothGatt;
            }
            if (gatt != null) {
                pendingOperations++;
                try {
                    if (gatt.requestMtu(mtu)) {
                        armOperationsWatchdog();
                    } else {
                        pendingOperations--;
                        dispatchNextWrite();
                    }
                } catch (final SecurityException ignored) {
                    pendingOperations--;
                    dispatchNextWrite();
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    void discoverServices() {
        dispatchHandler.post(() -> {
            final BluetoothGatt gatt;
            synchronized (gattMonitor) {
                gatt = bluetoothGatt;
            }
            if (gatt != null) {
                serviceDiscoveryRetries = 0;
                requestServiceDiscovery(gatt);
            }
        });
    }

    private final BluetoothGattCallback internalCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(@NonNull final BluetoothGatt gatt, final int status, final int newState) {
            dispatchHandler.post(() -> {
                if (!isCurrentGatt(gatt) || disposed.get()) {
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    sink.onConnectionStateChange(newState, status);
                    return;
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    finishCurrentGatt(gatt, status, newState != BluetoothProfile.STATE_DISCONNECTED);
                    return;
                }
                sink.onConnectionStateChange(newState, status);
            });
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(@NonNull final BluetoothGatt gatt, final int status) {
            dispatchHandler.post(() -> {
                if (!isCurrentGatt(gatt) || disposed.get()) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    retryServiceDiscovery(gatt, status);
                    return;
                }

                final List<BluetoothGattService> services = gatt.getServices();
                final BluetoothGattService service = gatt.getService(JblProtocol.SERVICE_UUID);
                if (service == null) {
                    LOG.warn("JblGattLink.onServicesDiscovered: required JBL service {} missing",
                            JblProtocol.SERVICE_UUID);
                    failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
                    return;
                }
                writeCharacteristic = service.getCharacteristic(JblProtocol.WRITE_CHARACTERISTIC_UUID);
                final BluetoothGattCharacteristic notifyCharacteristic =
                        service.getCharacteristic(JblProtocol.NOTIFY_CHARACTERISTIC_UUID);
                if (writeCharacteristic == null || notifyCharacteristic == null) {
                    LOG.warn("JblGattLink.onServicesDiscovered: required write/notify characteristic missing");
                    failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
                    return;
                }

                final BluetoothGattDescriptor descriptor =
                        notifyCharacteristic.getDescriptor(JblProtocol.CCC_DESCRIPTOR_UUID);
                try {
                    if (descriptor == null || !gatt.setCharacteristicNotification(notifyCharacteristic, true)
                            || !descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        LOG.warn("JblGattLink.onServicesDiscovered: failed to prepare notification subscription");
                        failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
                        return;
                    }
                } catch (final SecurityException ex) {
                    LOG.warn("JblGattLink.onServicesDiscovered: notification setup permission denied", ex);
                    failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
                    return;
                }

                discoveredServices = services;
                waitingForNotificationSetup = true;
                pendingOperations++;
                try {
                    if (!gatt.writeDescriptor(descriptor)) {
                        LOG.warn("JblGattLink.onServicesDiscovered: notification descriptor write was rejected");
                        failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
                        return;
                    }
                    armOperationsWatchdog();
                } catch (final SecurityException ex) {
                    LOG.warn("JblGattLink.onServicesDiscovered: notification descriptor write failed", ex);
                    failCurrentGatt(gatt, BluetoothGatt.GATT_FAILURE);
                    return;
                }

                serviceDiscoveryRetries = 0;

                // Request HIGH connection priority for lower latency,
                // matching the stock JBL app behavior.
                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    LOG.info("JblGattLink.onServicesDiscovered: requested CONNECTION_PRIORITY_HIGH");
                } catch (final SecurityException ignored) {
                }

                completeServiceDiscoveryIfReady();
                dispatchHandler.post(dispatchNext);
            });
        }

        @Override
        public void onCharacteristicWrite(@NonNull final BluetoothGatt gatt,
                                          @NonNull final BluetoothGattCharacteristic characteristic,
                                          final int status) {
            dispatchHandler.post(() -> {
                if (!isCurrentGatt(gatt) || disposed.get()) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    LOG.warn("JblGattLink.onCharacteristicWrite: failed status={}", status);
                    failCurrentGatt(gatt, status);
                    return;
                }
                writeInFlight = false;
                if (!pendingWrites.isEmpty()) {
                    pendingWrites.removeFirst();
                }
                sink.onCharacteristicWrite(characteristic, status);
                dispatchHandler.post(dispatchNext);
            });
        }

        @Override
        public void onCharacteristicChanged(@NonNull final BluetoothGatt gatt,
                                            @NonNull final BluetoothGattCharacteristic characteristic,
                                            @NonNull final byte[] value) {
            dispatchCharacteristicChanged(gatt, characteristic, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(@NonNull final BluetoothGatt gatt,
                                            @NonNull final BluetoothGattCharacteristic characteristic) {
            final byte[] value = characteristic.getValue();
            if (value != null) {
                dispatchCharacteristicChanged(gatt, characteristic, value);
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onMtuChanged(@NonNull final BluetoothGatt gatt, final int mtu, final int status) {
            dispatchHandler.post(() -> {
                if (!isCurrentGatt(gatt) || disposed.get()) {
                    return;
                }
                if (pendingOperations > 0) {
                    pendingOperations--;
                }
                if (pendingOperations == 0) {
                    dispatchHandler.removeCallbacks(operationsWatchdog);
                    operationsWatchdogArmed = false;
                }
                sink.onMtuChanged(mtu, status);
                dispatchNextWrite();
            });
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onDescriptorWrite(@NonNull final BluetoothGatt gatt,
                                      @NonNull final BluetoothGattDescriptor descriptor,
                                      final int status) {
            dispatchHandler.post(() -> {
                if (!isCurrentGatt(gatt) || disposed.get()) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    LOG.warn("JblGattLink.onDescriptorWrite: notification setup failed status={}", status);
                    failCurrentGatt(gatt, status);
                    return;
                }
                if (pendingOperations > 0) {
                    pendingOperations--;
                }
                dispatchHandler.removeCallbacks(operationsWatchdog);
                operationsWatchdogArmed = false;
                waitingForNotificationSetup = false;
                completeServiceDiscoveryIfReady();
                dispatchNextWrite();
            });
        }
    };

    private void dispatchCharacteristicChanged(@NonNull final BluetoothGatt gatt,
                                               @NonNull final BluetoothGattCharacteristic characteristic,
                                               @NonNull final byte[] value) {
        final byte[] copy = value.clone();
        dispatchHandler.post(() -> {
            if (isCurrentGatt(gatt) && !disposed.get()) {
                sink.onCharacteristicChanged(characteristic, copy);
            }
        });
    }

    private void completeServiceDiscoveryIfReady() {
        if (waitingForNotificationSetup || discoveredServices == null) {
            return;
        }
        final List<BluetoothGattService> services = discoveredServices;
        discoveredServices = null;
        protocolReady = true;
        sink.onServicesDiscovered(services);
    }

    private static final class PendingWrite {
        final UUID serviceUuid;
        final UUID characteristicUuid;
        final byte[] value;

        PendingWrite(@NonNull final UUID serviceUuid,
                     @NonNull final UUID characteristicUuid,
                     @NonNull final byte[] value) {
            this.serviceUuid = serviceUuid;
            this.characteristicUuid = characteristicUuid;
            this.value = value;
        }
    }
}
