/*  Copyright (C) 2026 Davide Gessa

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.casio.ggb100;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.devices.casio.CasioConstants;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.Casio2C2DSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.CasioLocationIndicatorLibrary;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.CasioLocationPoint;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.webview.CurrentPosition;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_FIND_PHONE;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_FIND_PHONE_DURATION;

public class CasioGGB100DeviceSupport extends Casio2C2DSupport
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(CasioGGB100DeviceSupport.class);
    private final Handler mFindPhoneHandler = new Handler(Looper.getMainLooper());

    public CasioGGB100DeviceSupport() {
        super(LOG);
    }

    @Override
    public DevicePreference[] supportedDevicePreferences() {
        return new DevicePreference[] {
        };
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }


    // ── Initialization ────────────────────────────────────────────────────────

    private void resetState() {
        mFindPhoneHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void dispose() {
        resetState();
        super.dispose();
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        resetState();

        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, true);
        builder.writeLegacy(
                getCharacteristic(CasioConstants.CASIO_READ_REQUEST_FOR_ALL_FEATURES_CHARACTERISTIC_UUID),
                new byte[]{Casio2C2DSupport.FEATURE_APP_INFORMATION});

        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        return builder;
    }

    // ── Write helpers ─────────────────────────────────────────────────────────

    private void req(byte feat) {
        try {
            TransactionBuilder b = createTransactionBuilder("req_" + String.format("%02x", feat));
            b.setCallback(this);
            writeAllFeaturesRequest(b, new byte[]{feat});
            b.queueImmediately();
        } catch (IOException e) {
            LOG.error("req failed: {}", e.getMessage());
        }
    }

    private void req(byte feat, byte slot) {
        try {
            TransactionBuilder b = createTransactionBuilder("req_" + String.format("%02x", feat));
            b.setCallback(this);
            writeAllFeaturesRequest(b, new byte[]{feat, slot});
            b.queueImmediately();
        } catch (IOException e) {
            LOG.error("req slot failed: {}", e.getMessage());
        }
    }

    private void write(byte[] data) {
        try {
            TransactionBuilder b = createTransactionBuilder("write_" + String.format("%02x", data[0]));
            b.setCallback(this);
            writeAllFeatures(b, data);
            b.queueImmediately();
        } catch (IOException e) {
            LOG.error("write failed: {}", e.getMessage());
        }
    }

    // ── App information ──────────────────────────────────────────────────────

    // What the official app writes to APP_INFO at pairing time. The watch keeps it
    // across connections. Last byte = capabilities, 2 = BT time adjustment supported.
    private static final byte[] APP_INFO_TOKEN = {
            0x22, 0x55, (byte) 0xf6, 0x55, 0x69,
            0x26, (byte) 0x2c, (byte) 0x6b, (byte) 0xb9, (byte) 0x7b, 0x02
    };

    /**
     * Leave the stored token alone: the official app writes it once at pairing.
     */
    private void checkAppInformation(byte[] data) {
        if (data.length >= 12 && data[11] == 0x02) {
            return;
        }
        LOG.info("APP_INFO on watch is {}, writing ours", GB.hexdump(data));
        byte[] arr = new byte[1 + APP_INFO_TOKEN.length];
        arr[0] = Casio2C2DSupport.FEATURE_APP_INFORMATION;
        System.arraycopy(APP_INFO_TOKEN, 0, arr, 1, APP_INFO_TOKEN.length);
        write(arr);
    }


    // ── Phone finder ──────────────────────────────────────────────────────────

    private void onReverseFindDevice(boolean start) {
        if (start) {
            String findPhone = getDevicePrefs().getString(PREF_FIND_PHONE,
                    getContext().getString(R.string.p_off));
            if (findPhone.equals(getContext().getString(R.string.p_off))) return;

            GBDeviceEventFindPhone ev = new GBDeviceEventFindPhone();
            ev.event = GBDeviceEventFindPhone.Event.START;
            evaluateGBDeviceEvent(ev);

            if (findPhone.equals(getContext().getString(R.string.p_on))) {
                int duration = getDevicePrefs().getInt(PREF_FIND_PHONE_DURATION, 0);
                if (duration > 0) {
                    mFindPhoneHandler.postDelayed(() -> {
                        GBDeviceEventFindPhone stop = new GBDeviceEventFindPhone();
                        stop.event = GBDeviceEventFindPhone.Event.STOP;
                        evaluateGBDeviceEvent(stop);
                    }, duration * 1000L);
                }
            }
        } else {
            GBDeviceEventFindPhone ev = new GBDeviceEventFindPhone();
            ev.event = GBDeviceEventFindPhone.Event.STOP;
            evaluateGBDeviceEvent(ev);
        }
    }

    // ── Location Indicator ──────────────────────────────────────────────────────────

    private void onLocationIndicator(boolean indicate, byte[] data) {
        if (indicate) {
            LOG.debug("Watch asked us to indicate the active location point");

            final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
            final CasioLocationIndicatorLibrary lib = CasioLocationIndicatorLibrary.fromJson(
                    prefs.getString(CasioLocationIndicatorLibrary.PREF_LOCATION_INDICATOR_LIBRARY, null));
            final CasioLocationPoint active = lib.getActive();

            byte[] data_reply = new byte[9];
            data_reply[0] = Casio2C2DSupport.FEATURE_LOCATION_INDICATOR;
            data_reply[1] = 0x02;

            final Location l = new CurrentPosition().getLastKnownLocation();
            if (active == null || l == null) {
                data_reply[2] = 0x01; // status: 0 -> ok, 1 -> nothing to give
                write(data_reply);
                return;
            }

            // phone → ALL_FEAT  35 <mode> <st> <dist LE32> <bearing LE16>
            final GPSCoordinate current = new GPSCoordinate(l.getLongitude(), l.getLatitude());
            final long dist = Math.round(GPSCoordinate.distanceHaversine(current, active.waypoint));
            final int bearing = (int) Math.round(GPSCoordinate.bearing(current, active.waypoint)) % 360;

            data_reply[2] = 0x00; // status: ok
            data_reply[3] = (byte) (dist & 0xff);
            data_reply[4] = (byte) ((dist >> 8) & 0xff);
            data_reply[5] = (byte) ((dist >> 16) & 0xff);
            data_reply[6] = (byte) ((dist >> 24) & 0xff);
            data_reply[7] = (byte) (bearing & 0xff);
            data_reply[8] = (byte) ((bearing >> 8) & 0xff);
            write(data_reply);
        } else {
            LOG.debug("Watch asked us to memorize the current location.");

            Location l = new CurrentPosition().getLastKnownLocation();

            byte[] data_reply = new byte[9];
            data_reply[0] = Casio2C2DSupport.FEATURE_LOCATION_INDICATOR;

            if (l == null) {
                LOG.warn("No GPS fix, cannot memorize location");
                data_reply[2] = 0x01; // status: 0 -> ok, 1 -> no fix
                write(data_reply);
                return;
            }

            final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
            final CasioLocationIndicatorLibrary lib = CasioLocationIndicatorLibrary.fromJson(
                    prefs.getString(CasioLocationIndicatorLibrary.PREF_LOCATION_INDICATOR_LIBRARY, null));

            CasioLocationPoint point = new CasioLocationPoint();
            point.label = getContext().getString(
                    R.string.casio_location_indicator_default_label, lib.locations.size() + 1);
            point.timestamp = System.currentTimeMillis();
            point.waypoint = new GPSCoordinate(l.getLongitude(), l.getLatitude(),
                    l.hasAltitude() ? l.getAltitude() : GPSCoordinate.UNKNOWN_ALTITUDE);

            lib.add(point);
            prefs.edit()
                    .putString(CasioLocationIndicatorLibrary.PREF_LOCATION_INDICATOR_LIBRARY, lib.toJson())
                    .apply();

            data_reply[2] = 0x00; // status: ok
            write(data_reply);
        }
    }


    // ── Characteristic change handler ─────────────────────────────────────────────────────────

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           byte[] data) {
        UUID uuid = characteristic.getUuid();

        if (data == null || data.length == 0) return true;

        if (!uuid.equals(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID)) {
            return super.onCharacteristicChanged(gatt, characteristic, data);
        }

        byte feat = data[0];

        if(feat == Casio2C2DSupport.FEATURE_APP_INFORMATION) {
            LOG.debug("Init[APP_INFO] → requesting BLE_FEAT");
            checkAppInformation(data);
            req(Casio2C2DSupport.FEATURE_BLE_FEATURES);
            return true;
        } else if (feat == Casio2C2DSupport.FEATURE_BLE_FEATURES) {
            LOG.debug("Init[BLE_FEAT] → writing WATCH_NAME");
            // Identity confirm: write device name to ALL_FEAT
            byte[] nameBytes = "CASIO GG-B100\0\0\0\0\0\0".getBytes(StandardCharsets.US_ASCII);
            byte[] namePacket = new byte[1 + nameBytes.length];
            namePacket[0] = Casio2C2DSupport.FEATURE_WATCH_NAME;
            System.arraycopy(nameBytes, 0, namePacket, 1, nameBytes.length);
            write(namePacket);
            setInitialized();

            byte connectionReason = data[8];
            LOG.debug("Connection reason is: " + Byte.toString(connectionReason));

            if (connectionReason == 0x02) {
                LOG.debug("Watch requests phone finder");
                // Already handled by the feature, this is redundant.
                return true;
            } else if (connectionReason == 0x07) {
                LOG.debug("Watch requests a location indicator");
                req(Casio2C2DSupport.FEATURE_LOCATION_INDICATOR);
                return true;
            }
            // else if (connectionReason == 0x00) {
            //     LOG.debug("Watch fresh pairing connection");
            // } else if (connectionReason == 0x01) {
            //     LOG.debug("Watch connected");
            // } else if (connectionReason == 0x03) {
            //     LOG.debug("Watch requests a scheduled sync");
            // } else if (connectionReason == 0x04) {
            //     LOG.debug("Watch requests a manual sync");
            // } else if (connectionReason == 0x08) {
            //     LOG.debug("Watch requests a mission log connection");
            // }

            if (connectionReason != 0x04) {
                // if await link.request_echo(FEAT_BLE_SETTINGS, label="request BLE_SETTINGS 0x11") is None:
                //     return False
                // await cmd_status(link)
                // await cmd_newdata(link)
            }

            // if not await version_round(link):
            //     return False

            // if not await link.city_block(gps_chunks()):
            //         return False
            if (connectionReason != 0x04) {
                // await link.request(FEAT_MODE_CUST, label="request MODE_CUST 0x38")
            }
            // await link.write_time()

            return true;
        } else if (feat == Casio2C2DSupport.FEATURE_ALERT_LEVEL) {
            // Phone finder: 0x02 = start, other = stop
            LOG.debug("Phone finder");
            onReverseFindDevice(data.length > 1 && data[1] == 0x02);
            return true;
        } else if (feat == Casio2C2DSupport.FEATURE_LOCATION_INDICATOR) {
            LOG.debug("Location Indicator Polling: " + GB.hexdump(data));
            onLocationIndicator(data[1] == 0x02, data);
            return true;
        }

        LOG.info("Unhandled characteristic change: {} feat=0x{}", uuid,
                String.format("%02x", data[0]));
        return super.onCharacteristicChanged(gatt, characteristic, data);
    }

    @Override
    public boolean onCharacteristicRead(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic characteristic,
                                        byte[] value, int status) {
        return super.onCharacteristicRead(gatt, characteristic, value, status);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (!isConnected()) return;
        if (key == null) return;
    }
}
