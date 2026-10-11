/* Copyright 2026 Gadgetbridge Contributors. SPDX-License-Identifier: AGPL-3.0-only */
package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.Intent;
import android.content.BroadcastReceiver;
import android.os.Looper;
import android.os.Handler;
import org.mockito.ArgumentCaptor;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import org.junit.Test;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class DeviceHelperTest extends TestBase {
    @Test
    public void deviceListRemainsResponsiveWhileAnotherReaderHoldsTheDatabase() throws Exception {
        final GBDevice device = new GBDevice("00:01:02:03:04:05", "Test watch", null, null,
                DeviceType.AMAZFITBALANCE3);
        DBHelper.getDevice(device, daoSession);
        dbHandler.close();
        dbHandler = null;
        final DeviceManager manager = new DeviceManager(app);

        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final ExecutorService databaseExecutor = ReflectionHelpers.getField(manager, "databaseExecutor");
        try {
            workers.submit(() -> {
                try (DBHandler ignored = GBApplication.acquireDbReadOnly()) {
                    reading.countDown();
                    release.await();
                }
                return null;
            });
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            final Future<Set<GBDevice>> available = workers.submit(
                    () -> DeviceHelper.getInstance().getAvailableDevices());
            assertTrue(available.get(5, TimeUnit.SECONDS).contains(device));
            device.setState(GBDevice.State.INITIALIZED);
            device.setFirmwareVersion("1.2.3");
            final BroadcastReceiver receiver = ReflectionHelpers.getField(manager, "mReceiver");
            final Future<?> changed = workers.submit(() -> receiver.onReceive(app,
                    new Intent(GBDevice.ACTION_DEVICE_CHANGED).putExtra(GBDevice.EXTRA_DEVICE, device)));
            changed.get(5, TimeUnit.SECONDS);
            workers.submit(() -> receiver.onReceive(app,
                    new Intent(DeviceManager.ACTION_REFRESH_DEVICELIST))).get(5, TimeUnit.SECONDS);
            release.countDown();
            databaseExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(manager.getDeviceByAddress(device.getAddress()).isInitialized());
            assertEquals("1.2.3", DBHelper.findDevice(device, daoSession)
                    .getDeviceAttributesList().get(0).getFirmwareVersion1());
        } finally {
            release.countDown();
            workers.shutdown();
            closeManager(manager, databaseExecutor);
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void knownDeviceUpdatesAreCoalescedIntoOneDelayedCallback() throws Exception {
        final GBDevice device = new GBDevice("00:01:02:03:04:05", "Test watch", null, null,
                DeviceType.AMAZFITBALANCE3);
        DBHelper.getDevice(device, daoSession);
        dbHandler.close();
        dbHandler = null;
        final DeviceManager manager = new DeviceManager(app);
        final Handler handler = mock(Handler.class);
        when(handler.postDelayed(any(Runnable.class), anyLong())).thenReturn(true);
        ReflectionHelpers.setField(manager, "handler", handler);
        final BroadcastReceiver receiver = ReflectionHelpers.getField(manager, "mReceiver");
        final ExecutorService databaseExecutor = ReflectionHelpers.getField(manager, "databaseExecutor");
        try {
            device.setState(GBDevice.State.INITIALIZED);
            receiver.onReceive(app, new Intent(GBDevice.ACTION_DEVICE_CHANGED)
                    .putExtra(GBDevice.EXTRA_DEVICE, device));
            device.setFirmwareVersion("2.3.4");
            receiver.onReceive(app, new Intent(GBDevice.ACTION_DEVICE_CHANGED)
                    .putExtra(GBDevice.EXTRA_DEVICE, device));
            databaseExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            final ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
            verify(handler, times(1)).postDelayed(callback.capture(), eq(250L));
            callback.getValue().run();
            assertFalse(ReflectionHelpers.<Boolean>getField(manager, "deviceChangedPending"));
            assertTrue(manager.getDeviceByAddress(device.getAddress()).isInitialized());
        } finally {
            closeManager(manager, databaseExecutor);
        }
    }

    private void closeManager(final DeviceManager manager, final ExecutorService databaseExecutor)
            throws InterruptedException {
        final BroadcastReceiver receiver = ReflectionHelpers.getField(manager, "mReceiver");
        LocalBroadcastManager.getInstance(app).unregisterReceiver(receiver);
        app.unregisterReceiver(receiver);
        final Handler handler = ReflectionHelpers.getField(manager, "handler");
        handler.removeCallbacksAndMessages(null);
        databaseExecutor.shutdown();
        assertTrue(databaseExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
