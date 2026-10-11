/* Copyright 2026 Gadgetbridge Contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.services;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.devices.huami.zeppos.watches.AmazfitBalanceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.HuamiDevicePrefs;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.ZeppOsCommunicator;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.ZeppOsSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.ZeppOsTransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ZeppOsTodoRoutingTest extends TestBase {
    private static final short LEGACY_ENDPOINT = 0x0038;
    private ZeppOsSupport support;
    private ZeppOsTransactionBuilder builder;
    private HuamiDevicePrefs prefs;

    @Before
    public void support() {
        final GBDevice device = mock(GBDevice.class);
        when(device.getAddress()).thenReturn("00:00:00:00:12:03");
        when(device.getName()).thenReturn("Balance");
        when(device.getType()).thenReturn(DeviceType.AMAZFITBALANCE);
        when(device.getDeviceCoordinator()).thenReturn(new AmazfitBalanceCoordinator());
        prefs = new HuamiDevicePrefs(GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()), device);
        prefs.getPreferences().edit().clear().commit();
        final ZeppOsCommunicator communicator = mock(ZeppOsCommunicator.class);
        builder = mock(ZeppOsTransactionBuilder.class);
        when(communicator.createZeppOsTransactionBuilder(anyString())).thenReturn(builder);
        support = mock(ZeppOsSupport.class, withSettings().useConstructor(communicator).defaultAnswer(CALLS_REAL_METHODS));
        doReturn(device).when(support).getDevice();
        doReturn(prefs).when(support).getDevicePrefs();
        doAnswer(inv -> {
            ((GBDeviceEvent) inv.getArgument(0)).evaluate(getContext(), device);
            return null;
        }).when(support).evaluateGBDeviceEvent(any());
        doNothing().when(support).writeToChunked2021(eq(builder), anyShort(), any(byte[].class), anyBoolean());
        DBHelper.getDevice(device, daoSession);
    }

    @Test
    public void todoSuppressesLegacyInitializationAndUnsolicitedLegacySync() {
        support.addSupportedService(LEGACY_ENDPOINT, false);
        support.addSupportedService(ZeppOsTodoService.ENDPOINT, false);
        support.initializeServices();
        verify(support).writeToChunked2021(eq(builder), eq(ZeppOsTodoService.ENDPOINT), eq(new byte[]{1}), eq(false));
        verify(support, never()).writeToChunked2021(eq(builder), eq(LEGACY_ENDPOINT), any(byte[].class), anyBoolean());
        support.handle2021Payload(LEGACY_ENDPOINT, new byte[]{4, 0, 0});
        verify(support, never()).createZeppOsTransactionBuilder("send reminders");
        support.handle2021Payload(ZeppOsTodoService.ENDPOINT, new byte[]{2, 3, 10, 0});
        support.handle2021Payload(ZeppOsTodoService.ENDPOINT, new byte[]{4, 0, 0});
        support.onSetReminders(new ArrayList<>());
        verify(support, times(2)).writeToChunked2021(eq(builder), eq(ZeppOsTodoService.ENDPOINT), eq(new byte[]{3}), eq(false));
        verify(support, never()).writeToChunked2021(eq(builder), eq(LEGACY_ENDPOINT), any(byte[].class), anyBoolean());
    }

    @Test
    public void legacyOnlyWatchKeepsItsInitializationAndClearsStaleTodoCapabilities() {
        prefs.getPreferences().edit().putInt(ZeppOsTodoService.PREF_VERSION, 3)
                .putInt(ZeppOsTodoService.PREF_SLOT_COUNT, 100).commit();
        support.addSupportedService(LEGACY_ENDPOINT, false);
        support.initializeServices();
        verify(support).writeToChunked2021(eq(builder), eq(LEGACY_ENDPOINT), eq(new byte[]{1}), eq(false));
        verify(support).writeToChunked2021(eq(builder), eq(LEGACY_ENDPOINT), eq(new byte[]{3}), eq(false));
        assertFalse(ZeppOsTodoService.usesTodoService(prefs));
        assertEquals(0, ZeppOsTodoService.getSlotCount(prefs));
        support.handle2021Payload(LEGACY_ENDPOINT, new byte[]{2, 1, 22});
        assertEquals(22, support.getDevice().getDeviceCoordinator().getReminderSlotCount(support.getDevice()));
    }

    @Test
    public void unsupportedTodoVersionFallsBackOnlyWhenLegacyIsAdvertised() {
        support.addSupportedService(LEGACY_ENDPOINT, false);
        support.addSupportedService(ZeppOsTodoService.ENDPOINT, false);
        support.initializeServices();
        support.handle2021Payload(ZeppOsTodoService.ENDPOINT, new byte[]{2, 4, 100, 0});
        verify(support).writeToChunked2021(eq(builder), eq(LEGACY_ENDPOINT), eq(new byte[]{1}), eq(false));
        verify(support).writeToChunked2021(eq(builder), eq(LEGACY_ENDPOINT), eq(new byte[]{3}), eq(false));
        assertFalse(ZeppOsTodoService.usesTodoService(prefs));
        support.handle2021Payload(LEGACY_ENDPOINT, new byte[]{2, 1, 22});
        assertEquals(22, support.getDevice().getDeviceCoordinator().getReminderSlotCount(support.getDevice()));
        support.onSetReminders(new ArrayList<>());
        verify(support, never()).writeToChunked2021(eq(builder), eq(ZeppOsTodoService.ENDPOINT), eq(new byte[]{3}), anyBoolean());
    }

    @Test
    public void unsupportedTodoOnlyWatchDoesNotInventLegacySupport() {
        support.addSupportedService(ZeppOsTodoService.ENDPOINT, false);
        support.initializeServices();
        support.handle2021Payload(ZeppOsTodoService.ENDPOINT, new byte[]{2, 4, 100, 0});
        assertEquals(0, support.getDevice().getDeviceCoordinator().getReminderSlotCount(support.getDevice()));
        support.onSetReminders(new ArrayList<>());
        verify(support, never()).writeToChunked2021(eq(builder), eq(LEGACY_ENDPOINT), any(byte[].class), anyBoolean());
        verify(support, never()).writeToChunked2021(eq(builder), eq(ZeppOsTodoService.ENDPOINT), eq(new byte[]{3}), anyBoolean());
    }

    @Test
    public void todoUsesTheEncryptionAdvertisedByTheWatch() {
        support.handle2021Payload((short) 0x0000, new byte[]{4, 1, 0, 0x12, 0, 1});
        verify(support).writeToChunked2021(eq(builder), eq(ZeppOsTodoService.ENDPOINT), eq(new byte[]{1}), eq(true));
        support.handle2021Payload(ZeppOsTodoService.ENDPOINT, new byte[]{2, 3, 10, 0});
        verify(support).writeToChunked2021(eq(builder), eq(ZeppOsTodoService.ENDPOINT), eq(new byte[]{3}), eq(true));
    }
}
