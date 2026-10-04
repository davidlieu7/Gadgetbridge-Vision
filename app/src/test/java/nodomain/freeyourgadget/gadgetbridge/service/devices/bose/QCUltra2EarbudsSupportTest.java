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
package nodomain.freeyourgadget.gadgetbridge.service.devices.bose;

import android.bluetooth.BluetoothAdapter;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdateDeviceState;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Responses observed on QC Ultra Earbuds (2nd Gen), firmware 8.4.8+gbb2cb60,
 * BMAP 1.2.0, on 2026-10-04. Only protocol payloads are retained here.
 * Battery ordering uses distinct synthetic levels to catch component swaps.
 */
public class QCUltra2EarbudsSupportTest extends TestBase {
    private final List<GBDeviceEvent> events = new ArrayList<>();
    private final TransactionBuilder builder = mock(TransactionBuilder.class);
    private QCUltra2EarbudsSupport support;
    private GBDevice device;
    private SharedPreferences prefs;
    private static final String MODE = DeviceSettingsPreferenceConst.PREF_BOSE_AUDIO_MODE;

    @Before
    public void setUpSupport() {
        device = new GBDevice("00:11:22:33:44:66", "Bose QC Ultra 2 Earbuds", null, null,
                DeviceType.BOSE_QC_ULTRA2_EARBUDS);
        prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        prefs.edit().clear().apply();
        support = new QCUltra2EarbudsSupport() {
            @Override
            public void evaluateGBDeviceEvent(final GBDeviceEvent event) {
                events.add(event);
                if (event instanceof GBDeviceEventUpdatePreferences || event instanceof GBDeviceEventUpdateDeviceState) {
                    super.evaluateGBDeviceEvent(event);
                }
            }

            @Override
            public TransactionBuilder createTransactionBuilder(final String taskName) {
                return builder;
            }
        };
        support.setContext(device, BluetoothAdapter.getDefaultAdapter(), getContext());
    }

    @After
    public void disposeSupport() {
        support.dispose();
    }

    private static byte[] hex(final String value) {
        final byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    @Test
    public void waitsForHandshakeBeforeReportingInitialized() {
        support.initializeDevice(builder);
        verify(builder).setDeviceState(GBDevice.State.INITIALIZING);
        verify(builder, never()).setDeviceState(GBDevice.State.INITIALIZED);
        device.setState(GBDevice.State.INITIALIZING);
        support.onSocketRead(hex("00010305312e322e30"));
        assertTrue(device.isInitialized());
    }

    @Test
    public void parsesCapturedModeLoopAcrossEveryPossibleSplit() {
        final byte[] loop = hex("1f030301021f030601021f030301001f030601001f030301011f03060101");
        for (int split = 0; split <= loop.length; split++) {
            support.initializeDevice(builder);
            events.clear();
            support.onSocketRead(Arrays.copyOfRange(loop, 0, split));
            support.onSocketRead(Arrays.copyOfRange(loop, split, loop.length));
            assertEquals("Split at " + split, 6, events.size());
            assertEquals("1", prefs.getString(MODE, ""));
        }
    }

    @Test
    public void resultWithoutStatusConfirmsUnchangedSelection() {
        prefs.edit().putString(MODE, "2").apply();
        support.onSendConfiguration(MODE);
        verify(builder).write(hex("1f0305020201"));
        support.onSocketRead(hex("1f03060102"));
        assertEquals("2", prefs.getString(MODE, ""));
        assertEquals(1, events.size());
    }

    @Test
    public void unknownPresetDoesNotAppearAsQuiet() {
        support.onSocketRead(hex("1f03030103"));
        assertEquals("3", prefs.getString(MODE, ""));
        support.onSendConfiguration(MODE);
        verifyNoInteractions(builder);
        support.onSocketRead(hex("1f0303001f0303020001"));
        assertEquals("3", prefs.getString(MODE, ""));
    }

    @Test
    public void readsModeOnceAfterRejectedChangeWithoutErrorLoop() {
        prefs.edit().putString(MODE, "0").apply();
        support.onSendConfiguration(MODE);
        clearInvocations(builder);
        support.onSocketRead(hex("1f03040106"));
        assertEquals("", prefs.getString(MODE, "missing"));
        verify(builder).write(hex("1f030100"));
        verify(builder).queue();
        clearInvocations(builder);
        support.onSocketRead(hex("1f03040106"));
        verifyNoInteractions(builder);
        support.onSocketRead(hex("1f03030101"));
        assertEquals("1", prefs.getString(MODE, ""));
    }

    @Test
    public void mapsCaseLeftAndRightAndIgnoresCombinedBattery() {
        support.onSocketRead(hex("0202031050ffff033cffff0446ffff023cffff01"));
        assertEquals(3, events.size());
        for (int index = 0; index < 3; index++) {
            final GBDeviceEventBatteryInfo event = (GBDeviceEventBatteryInfo) events.get(index);
            assertEquals(index, event.batteryIndex);
            assertEquals(80 - 10 * index, event.level);
        }
    }

    @Test
    public void parsesCapturedFirmware() {
        support.onSocketRead(hex("0005030e382e342e382b6762623263623630"));
        assertEquals(1, events.size());
        assertEquals("8.4.8+gbb2cb60", ((GBDeviceEventVersionInfo) events.get(0)).fwVersion);
    }

    @Test
    public void reconnectDiscardsPartialFrameFromOldConnection() {
        support.onSocketRead(hex("1f0303"));
        support.initializeDevice(builder);
        support.onSocketRead(hex("1f03030101"));
        assertEquals(1, events.size());
        assertEquals("1", prefs.getString(MODE, ""));
    }
}
