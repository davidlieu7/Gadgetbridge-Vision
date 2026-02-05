/*  Copyright (C) 2020-2024 Arjan Schrijver, Taavi Eomäe

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.itag;

import static nodomain.freeyourgadget.gadgetbridge.devices.itag.ITagConstants.PREF_BUTTON_EVENT;
import static nodomain.freeyourgadget.gadgetbridge.devices.itag.ITagConstants.PREF_ITAG_ALERT_FORCE_MILD;
import static nodomain.freeyourgadget.gadgetbridge.devices.itag.ITagConstants.PREF_ITAG_ALERT_LINK_LOSS;
import static nodomain.freeyourgadget.gadgetbridge.devices.itag.ITagConstants.UUID_BUTTON_CHARACTERISTIC;
import static nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic.UUID_CHARACTERISTIC_ALERT_LEVEL;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventCameraRemote;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.devices.itag.ITagConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.alertnotification.AlertLevel;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;

public class ITagSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(ITagSupport.class);
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private final DeviceInfoProfile<ITagSupport> deviceInfoProfile;
    private final BatteryInfoProfile<ITagSupport> batteryInfoProfile;

    private final GBDeviceEventFindPhone findPhoneEvent = new GBDeviceEventFindPhone();
    private final GBDeviceEventCameraRemote cameraRemoteEvent = new GBDeviceEventCameraRemote();

    private BluetoothGatt gatt;

    private final IntentListener mListener = new IntentListener() {
        @Override
        public void notify(Intent intent) {
            String s = intent.getAction();
            if (s.equals(DeviceInfoProfile.ACTION_DEVICE_INFO)) {
                handleDeviceInfo((nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo) intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO));
            } else if (s.equals(BatteryInfoProfile.ACTION_BATTERY_INFO)) {
                handleBatteryInfo((nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo) intent.getParcelableExtra(BatteryInfoProfile.EXTRA_BATTERY_INFO));
            }
        }
    };

    public ITagSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ATTRIBUTE);
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);
        addSupportedService(GattService.UUID_SERVICE_IMMEDIATE_ALERT);
        addSupportedService(GattService.UUID_SERVICE_LINK_LOSS);

        addSupportedService(ITagConstants.UUID_SERVICE_ITAG);

        deviceInfoProfile = new DeviceInfoProfile<>(this);
        deviceInfoProfile.addListener(mListener);
        batteryInfoProfile = new BatteryInfoProfile<>(this);
        batteryInfoProfile.addListener(mListener);

        addSupportedProfile(deviceInfoProfile);
        addSupportedProfile(batteryInfoProfile);
    }

    private void handleBatteryInfo(nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo info) {
        batteryCmd.level = (short) info.getPercentCharged();
        handleGBDeviceEvent(batteryCmd);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(UUID_BUTTON_CHARACTERISTIC, true);
        requestDeviceInfo(builder);
        setInitialized(builder);
        batteryInfoProfile.requestBatteryInfo(builder);
        return builder;
    }

    @Override
    public void onSendConfiguration(String config) {
        switch (config) {
            case PREF_ITAG_ALERT_LINK_LOSS -> handleLinkLossConfiguration();
        }
    }

    private void requestDeviceInfo(TransactionBuilder builder) {
        LOG.debug("Requesting device info!");
        deviceInfoProfile.requestDeviceInfo(builder);
    }

    private void setInitialized(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZED);
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt) {
        this.gatt = gatt;
        super.onServicesDiscovered(gatt);
    }

    private void handleDeviceInfo(nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo info) {
    }

    private void handleLinkLossConfiguration() {
        BluetoothGattCharacteristic linkLossCharacteristic;

        var STANDARD_LINK_LOSS_SERVICE = gatt.getService(GattService.UUID_SERVICE_LINK_LOSS);
        var CUSTOM_LINK_LOSS_SERVICE = gatt.getService(ITagConstants.UUID_SERVICE_ITAG);

        if (STANDARD_LINK_LOSS_SERVICE != null) {
            linkLossCharacteristic = STANDARD_LINK_LOSS_SERVICE.getCharacteristic(UUID_CHARACTERISTIC_ALERT_LEVEL);
        } else if (CUSTOM_LINK_LOSS_SERVICE != null) {
            linkLossCharacteristic = CUSTOM_LINK_LOSS_SERVICE.getCharacteristic(ITagConstants.UUID_LINK_LOSS_CHARACTERISTIC);
        } else {
            LOG.warn("This iTag does not contain any supported link loss service.");
            return;
        }

        boolean alertOnLinkLoss = this.getDevicePrefs().getBoolean(PREF_ITAG_ALERT_LINK_LOSS, false);

        AlertLevel alertLevel;

        if (alertOnLinkLoss) {
            alertLevel = getHighestSupportedAlertLevel();
        } else {
            alertLevel = AlertLevel.NoAlert;
        }

        TransactionBuilder builder = createTransactionBuilder("link loss");
        builder.write(linkLossCharacteristic, (byte)alertLevel.getId());
        builder.queue();
    }

    private AlertLevel getHighestSupportedAlertLevel() {
        boolean forceMild = this.getDevicePrefs().getBoolean(PREF_ITAG_ALERT_FORCE_MILD, false);

        if (forceMild) {
            return AlertLevel.MildAlert;
        } else {
            return AlertLevel.HighAlert;
        }
    }

    private void setAlertLevel(AlertLevel alertLevel) {
        BluetoothGattCharacteristic characteristic = getCharacteristic(UUID_CHARACTERISTIC_ALERT_LEVEL);

        try {
            TransactionBuilder builder = performInitialized("setting iTag alert level");
            builder.write(characteristic, (byte) alertLevel.getId());
            builder.queue();
        } catch (IOException e) {
            LOG.error("error while setting iTag alert level", e);
        }
    }

    @Override
    public void onFindDevice(boolean start) {
        if (start) {
            setAlertLevel(getHighestSupportedAlertLevel());
        } else {
            setAlertLevel(AlertLevel.NoAlert);
        }
    }

    @Override
    public void onSetConstantVibration(int intensity) {
        if ( intensity > 127 ) {
            this.setAlertLevel(getHighestSupportedAlertLevel());
            return;
        }

        if ( intensity > 0 ) {
            this.setAlertLevel(AlertLevel.MildAlert);
            return;
        }

        this.setAlertLevel(AlertLevel.NoAlert);
    }

    private void onButtonPressed() {
        String button_event = this.getDevicePrefs().getString(PREF_BUTTON_EVENT, getContext().getString(R.string.p_off));
        LOG.debug("Button pressed!");

        if (button_event.equals(getContext().getString(R.string.p_menuitem_takephoto))) {
            cameraRemoteEvent.event = GBDeviceEventCameraRemote.Event.TAKE_PICTURE;
            evaluateGBDeviceEvent(cameraRemoteEvent);
        } else if (button_event.equals(getContext().getString(R.string.p_menuitem_findphone))) {
            findPhoneEvent.event = GBDeviceEventFindPhone.Event.START;
            evaluateGBDeviceEvent(findPhoneEvent);
        }
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }

        if (characteristic.getUuid().equals(UUID_BUTTON_CHARACTERISTIC)) {
            onButtonPressed();
            return true;
        }

        UUID characteristicUUID = characteristic.getUuid();
        LOG.info("Unhandled characteristic changed: " + characteristicUUID);
        return false;
    }

    @Override
    public boolean onCharacteristicRead(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic characteristic, byte[] value,
                                        int status) {
        if (super.onCharacteristicRead(gatt, characteristic, value, status)) {
            return true;
        }
        UUID characteristicUUID = characteristic.getUuid();

        LOG.info("Unhandled characteristic read: " + characteristicUUID);
        return false;
    }

    @Override
    public boolean getImplicitCallbackModify() {
        return true;
    }

    @Override
    public boolean getSendWriteRequestResponse() {
        return false;
    }
}
