/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.OuraConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraProtocol;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Drives the support through ring notifications. Frames are the ones of a Ring 4 on firmware
 * 2.12.13; a second app connected to the ring shares the link, so its replies arrive here too.
 */
public class OuraRing4SupportTest extends TestBase {
    private static final String FIRMWARE = "0912020100020C0D01000105000FBF14E1F838A0";
    private static final String NONCE_1 = "2F102C89D2873AD5456CAF209B81C5893D16";
    private static final String NONCE_2 = "2F102CEDCC3C33C2884B78C343AC96699051";
    private static final String PROOF_ACCEPTED = "2F022E00";
    private static final String PROOF_REJECTED = "2F022E01";
    /** Set time reply: ring ticks 134493083, about 155 days after its boot. */
    private static final String SET_TIME = "13059B33040800";

    private GBDevice device;
    private OuraRing4Support support;
    private BluetoothGattCharacteristic notify;
    private final List<byte[]> written = new ArrayList<>();
    private final List<String> disconnects = new ArrayList<>();

    @Before
    public void setUpSupport() {
        device = new GBDevice("00:00:00:00:00:71", "Oura", null, null, DeviceType.OURA_RING_4);
        DBHelper.getDevice(device, daoSession);
        prefs().edit().putString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, "7d494ed26590b78a345efddc49414580").commit();
        support = new OuraRing4Support() {
            @Override
            protected void send(final String task, final byte[] frame) {
                written.add(frame);
            }

            @Override
            protected void disconnectWith(@Nullable final String toast) {
                disconnects.add(toast);
            }
        };
        support.setContext(device, BluetoothAdapter.getDefaultAdapter(), getContext());
        notify = new BluetoothGattCharacteristic(OuraProtocol.CHARACTERISTIC_NOTIFY,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
    }

    @Test
    public void secondFirmwareReplyStartsNoSecondHandshake() {
        ring(FIRMWARE);
        ring(FIRMWARE);

        assertEquals(1, count(OuraProtocol.EXT_NONCE));
    }

    @Test
    public void nonceOfAnotherAppGetsNoProof() {
        ring(FIRMWARE);
        ring(NONCE_1);
        ring(NONCE_2);

        assertEquals(1, count(OuraProtocol.EXT_PROOF));
    }

    @Test
    public void rejectionAfterAnotherAppsNonceBlamesTheOtherApp() {
        ring(FIRMWARE);
        ring(NONCE_1);
        ring(NONCE_2);
        ring(PROOF_REJECTED);

        assertEquals(List.of(getContext().getString(R.string.authentication_failed_other_app)), disconnects);
    }

    @Test
    public void rejectionWithoutAnotherAppBlamesTheKey() {
        ring(FIRMWARE);
        ring(NONCE_1);
        ring(PROOF_REJECTED);

        assertEquals(List.of(getContext().getString(R.string.authentication_failed_check_key)), disconnects);
    }

    @Test
    public void proofReplyAfterAuthenticationIsIgnored() {
        ring(FIRMWARE);
        ring(NONCE_1);
        ring(PROOF_ACCEPTED);
        ring(PROOF_REJECTED);

        assertTrue(disconnects.isEmpty());
    }

    @Test
    public void firstFetchReadsAllTheRingKeeps() {
        authenticate();

        assertEquals(0L, lastHistoryCursor());
    }

    @Test
    public void laterFetchStartsAtTheStoredCursor() {
        prefs().edit().putLong(OuraConstants.PREF_HISTORY_CURSOR, 1000L).commit();

        authenticate();

        assertEquals(1000L, lastHistoryCursor());
    }

    @Test
    public void eachBatchMovesTheCursorAsItEnds() {
        prefs().edit().putLong(OuraConstants.PREF_HISTORY_CURSOR, 1000L).commit();
        authenticate();
        final long start = lastHistoryCursor();

        ring(temperature(start + 10) + historyDone(5000));
        assertEquals(start + 11, prefs().getLong(OuraConstants.PREF_HISTORY_CURSOR, -1L));
        assertEquals(start + 11, lastHistoryCursor());

        ring(temperature(start + 20) + historyDone(0));
        assertEquals(start + 21, prefs().getLong(OuraConstants.PREF_HISTORY_CURSOR, -1L));
        assertFalse(device.isBusy());
    }

    private void authenticate() {
        ring(FIRMWARE);
        ring(NONCE_1);
        ring(PROOF_ACCEPTED);
        ring(SET_TIME);
    }

    private void ring(final String hex) {
        support.onCharacteristicChanged(null, notify, GB.hexStringToByteArray(hex));
    }

    private SharedPreferences prefs() {
        return GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
    }

    /** Extended frames written with this sub-command. */
    private int count(final int subCommand) {
        int n = 0;
        for (final byte[] frame : written) {
            if ((frame[0] & 0xff) == OuraProtocol.CMD_EXTENDED && frame.length > 2 && (frame[2] & 0xff) == subCommand) {
                n++;
            }
        }
        return n;
    }

    private long lastHistoryCursor() {
        for (int i = written.size() - 1; i >= 0; i--) {
            final byte[] frame = written.get(i);
            if ((frame[0] & 0xff) == OuraProtocol.CMD_HISTORY) {
                return OuraProtocol.u32(frame, 2);
            }
        }
        throw new AssertionError("no history request");
    }

    /** Skin temperature record of 35.6 °C at {@code ticks}. */
    private static String temperature(final long ticks) {
        return "460A" + u32(ticks) + "E80DE80DE80D";
    }

    private static String historyDone(final long storedAfter) {
        return "1108" + "0100" + u32(storedAfter) + "0300";
    }

    private static String u32(final long value) {
        return String.format("%02X%02X%02X%02X", value & 0xff, value >> 8 & 0xff, value >> 16 & 0xff, value >> 24 & 0xff);
    }
}
