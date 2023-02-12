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
import android.bluetooth.BluetoothGattService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /*
     * At present the UUIDs of the characteristics of the Pinecil v2 are not really unique.
     *
     * Gadgetbridge only supports truly unique UUIDs. That's the reason why we have to roll our
     * own characteristics handling for the Pinecil v2. This workaround can be deleted if the
     * IronOS community accepts the proposed change in https://github.com/Ralim/IronOS/issues/1569
     *
     * First UUID: service UUID
     * Second UUID. characteristic UUID
     */
    private final Map<UUID, Map<UUID, BluetoothGattCharacteristic>> mAvailableCharacteristics = new HashMap<>();

    private final Object characteristicsMonitor = new Object();

    public PinecilDeviceSupport() {
        super(LOG);

        addSupportedService(GattService.UUID_SERVICE_GENERIC_ATTRIBUTE);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);

        /*
         * Pinecil uses same UUID for characteristics in 3 different services. This causes problems
         * which I am not able to solve with my limited knowledge. Need to lear more about BLE and
         * Gadgetbridge.
         *
         * For no I use only one of the services below.
         */

        addSupportedService(PinecilConstants.UUID_SERVICE_BULK_DATA);
        addSupportedService(PinecilConstants.UUID_SERVICE_LIVE_DATA);
        addSupportedService(PinecilConstants.UUID_SERVICE_SETTINGS_DATA);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        // mark the device as initializing
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));

        // ... custom initialization logic ...

        // set device firmware to prevent the following error when you (later) try to save data to database and
        // device firmware has not been set yet
        // Error executing 'the bind value at index 2 is null'java.lang.IllegalArgumentException: the bind value at index 2 is null
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        // mark the device as initialized
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));

        builder.read(getCharacteristic(GattCharacteristic.UUID_CHARACTERISTIC_DEVICE_NAME));
        builder.read(getCharacteristic(PinecilConstants.UUID_SERVICE_LIVE_DATA,
                PinecilConstants.UUID_CHARACTERISTIC_LIVE_HANDLE_TEMP));
        builder.read(getCharacteristic(PinecilConstants.UUID_SERVICE_LIVE_DATA,
                PinecilConstants.UUID_CHARACTERISTIC_LIVE_LIVE_TEMP));
        builder.read(getCharacteristic(PinecilConstants.UUID_SERVICE_BULK_DATA,
                PinecilConstants.UUID_CHARACTERISTIC_BULK_LIVE_DATA));
        builder.read(getCharacteristic(PinecilConstants.UUID_SERVICE_SETTINGS_DATA,
                PinecilConstants.UUID_CHARACTERISTIC_SETTINGS_VALUE_1));

        return builder;
    }

    @Override
    public boolean onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        LOG.info("SERVICE: " + characteristic.getService().getUuid());
        LOG.info("STATUS: " + status);

        UUID uuid = characteristic.getUuid();
        if (uuid.equals(PinecilConstants.UUID_CHARACTERISTIC_LIVE_HANDLE_TEMP)) {
            LOG.info("HANDLE TEMP: " + characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_FLOAT, 0) / 10f + "°C");
        } else if (uuid.equals(GattCharacteristic.UUID_CHARACTERISTIC_DEVICE_NAME)) {
            LOG.info("NAME: " + characteristic.getStringValue(0));
        } else {
            LOG.info("DATA: " + Arrays.toString(characteristic.getValue()));
        }

        return true;
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    /**
     * Returns the characteristic matching the given UUID. Only characteristics
     * are returned whose service is marked as supported.
     *
     * @param uuid UUID of the characteristic
     * @return the characteristic for the given UUID or <code>null</code>
     * @see #addSupportedService(UUID)
     */
    public BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
        synchronized (characteristicsMonitor) {
            for (Map<UUID, BluetoothGattCharacteristic> characteristics : mAvailableCharacteristics.values()) {
                BluetoothGattCharacteristic characteristic = characteristics.get(uuid);
                if (characteristic != null) {
                    return characteristic;
                }
            }
        }
        return null;
    }

    /**
     * Returns the characteristic matching the given UUID in a given service. Only characteristics
     * are returned whose service is marked as supported.
     * <p>
     * Usually it should not be necessary to provide service UUID in addition to the characteristic
     * UUID, since the characteristic UUID should be unique, but for some devices the characteristic
     * UUIDs are only unique in the scope of their service. (Looking at you, Pinecil!)
     *
     * @param serviceUUID UUID of the service
     * @param uuid        UUID of the characteristic
     * @return the characteristic for the given UUID or <code>null</code>
     * @see #addSupportedService(UUID)
     * @see #getCharacteristic(UUID)
     */
    public BluetoothGattCharacteristic getCharacteristic(UUID serviceUUID, UUID uuid) {
        synchronized (characteristicsMonitor) {

            Map<UUID, BluetoothGattCharacteristic> characteristics = mAvailableCharacteristics.get(serviceUUID);
            return characteristics == null ? null : characteristics.get(uuid);
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt) {
        gattServicesDiscovered(gatt.getServices());

        if (getDevice().getState().compareTo(GBDevice.State.INITIALIZING) >= 0) {
            LOG.warn("Services discovered, but device state is already " + getDevice().getState() + " for device: " + getDevice() + ", so ignoring");
            return;
        }
        initializeDevice(createTransactionBuilder("Initializing device")).queue(getQueue());
    }

    private void gattServicesDiscovered(List<BluetoothGattService> discoveredGattServices) {
        if (discoveredGattServices == null) {
            LOG.warn("No gatt services discovered: null!");
            return;
        }
        Set<UUID> supportedServices = getSupportedServices();
        for (BluetoothGattService service : discoveredGattServices) {
            if (supportedServices.contains(service.getUuid())) {
                LOG.debug("discovered supported service: " + BleNamesResolver.resolveServiceName(service.getUuid().toString()) + ": " + service.getUuid());
                List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
                if (characteristics == null || characteristics.isEmpty()) {
                    LOG.warn("Supported LE service " + service.getUuid() + "did not return any characteristics");
                    continue;
                }
                HashMap<UUID, BluetoothGattCharacteristic> intmAvailableCharacteristics = new HashMap<>(characteristics.size());
                for (BluetoothGattCharacteristic characteristic : characteristics) {
                    intmAvailableCharacteristics.put(characteristic.getUuid(), characteristic);
                    LOG.info("    characteristic: " + BleNamesResolver.resolveCharacteristicName(characteristic.getUuid().toString()) + ": " + characteristic.getUuid());
                }

                synchronized (characteristicsMonitor) {
                    mAvailableCharacteristics.put(service.getUuid(), intmAvailableCharacteristics);
                }
            } else {
                LOG.debug("discovered unsupported service: " + BleNamesResolver.resolveServiceName(service.getUuid().toString()) + ": " + service.getUuid());
            }
        }
    }

}
