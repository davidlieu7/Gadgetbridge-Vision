/*  Copyright (C) 2023-2026 Marc Nause, David Giron

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.pinecil;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.pinecil.PinecilConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BleNamesResolver;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;

public class PinecilDeviceSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(PinecilDeviceSupport.class);

    public PinecilDeviceSupport() {
        super(LOG);

        addSupportedService(GattService.SERVICE_GATT.uuid);
        addSupportedService(GattService.SERVICE_GAP.uuid);

        addSupportedService(PinecilConstants.UUID_SERVICE_BULK_DATA);
        addSupportedService(PinecilConstants.UUID_SERVICE_LIVE_DATA);
        addSupportedService(PinecilConstants.UUID_SERVICE_SETTINGS_DATA);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // ... custom initialization logic ...

        // set device firmware to prevent the following error when you (later) try to save data to database and
        // device firmware has not been set yet
        // Error executing 'the bind value at index 2 is null'java.lang.IllegalArgumentException: the bind value at index 2 is null
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        // request firmware version
        builder.read(getCharacteristic(PinecilConstants.UUID_CHARACTERISTIC_BULK_BUILD));

        // // lines below are just for trying things, will be removed later
        builder.read(getCharacteristic(GattCharacteristic.CHARACTERISTIC_DEVICE_NAME.uuid));
        builder.read(getCharacteristic(PinecilConstants.UUID_CHARACTERISTIC_LIVE_HANDLE_TEMP));
        builder.read(getCharacteristic(PinecilConstants.UUID_CHARACTERISTIC_LIVE_LIVE_TEMP));
        builder.read(getCharacteristic(PinecilConstants.UUID_CHARACTERISTIC_BULK_LIVE_DATA));
        builder.read(getCharacteristic(PinecilConstants.UUID_CHARACTERISTIC_SETTINGS_VALUE_1));

        builder.setDeviceState(GBDevice.State.INITIALIZED);

        return builder;
    }

    @Override
    public boolean onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        LOG.info("SERVICE: " + characteristic.getService().getUuid());
        LOG.info("STATUS: " + status);

        UUID uuid = characteristic.getUuid();
        if (uuid.equals(PinecilConstants.UUID_CHARACTERISTIC_LIVE_HANDLE_TEMP)) {
            LOG.info("HANDLE TEMP: " + characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_FLOAT, 0) / 10f + "°C");
        } else if (uuid.equals(GattCharacteristic.CHARACTERISTIC_DEVICE_NAME.uuid)) {
            LOG.info("NAME: " + new String(value, java.nio.charset.StandardCharsets.UTF_8));
        } else {
            LOG.info("DATA: " + Arrays.toString(characteristic.getValue()));
        }

        return true;
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

}
