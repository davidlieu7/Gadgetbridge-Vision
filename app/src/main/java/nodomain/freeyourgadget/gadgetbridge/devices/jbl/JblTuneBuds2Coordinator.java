/*  Copyright (C) 2026

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package nodomain.freeyourgadget.gadgetbridge.devices.jbl;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.generic_headphones.GenericHeadphonesCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BluetoothCompanyIdentifiers;
import nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.JblProtocol;
import nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.JblTuneBuds2Support;

/** Coordinator for JBL Tune Buds 2 (BLE control channel). */
public class JblTuneBuds2Coordinator extends GenericHeadphonesCoordinator {
    @Override
    public int getOrderPriority() {
        return 0;
    }

    @Override
    public DeviceCoordinator.ConnectionType getConnectionType() {
        return ConnectionType.BLE;
    }

    @Override
    public int getBondingStyle() {
        // The headset is already bonded at the Android level (Classic ACL is
        // active on the user's behalf before the app even starts). Skip the
        // Gadgetbridge pairing prompt so the user does not see the Android
        // "looking for device" dialog. The support registers the device in
        // the Gadgetbridge database on the first successful GATT connect.
        return BONDING_STYLE_NONE;
    }

    @Override
    public boolean suggestUnbindBeforePair() {
        return false;
    }

    @Override
    public boolean supportsFindDevice(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supports(@NonNull GBDeviceCandidate candidate) {
        // The manufacturer ID is shared by many JBL devices, and the Harman
        // service UUID is not model-specific. Only accept the Tune Buds 2 PID from
        // manufacturer data; the scanner applies the same PID check before connecting.

        final byte[] product = candidate.getManufacturerSpecificData().get(
                BluetoothCompanyIdentifiers.ZHONGSHAN_CITY_RICHSOUND_ELECTRONIC_INDUSTRIAL_LTD);
        if (product != null && product.length >= 2
                && BLETypeConversions.toUint16(product) == JblProtocol.TUNE_BUDS_2_PRODUCT_ID) {
            return true;
        }

        final String name = candidate.getName();
        return name != null && getSupportedDeviceName().matcher(name).matches();
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^JBL Tune Buds 2$", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public int getBatteryCount(@NonNull final GBDevice device) {
        return 3;
    }

    @Override
    public BatteryConfig[] getBatteryConfig(@NonNull final GBDevice device) {
        return new BatteryConfig[]{
                new BatteryConfig(0, R.drawable.ic_nothing_ear_l, R.string.left_earbud),
                new BatteryConfig(1, R.drawable.ic_nothing_ear_r, R.string.right_earbud),
                new BatteryConfig(2, R.drawable.ic_buds_pro_case, R.string.battery_case)
        };
    }

    @Override
    public DeviceSettingsSpec getDeviceSettings(@NonNull final GBDevice device) {
        return JblTuneBuds2SettingsKt.jblTuneBuds2Settings();
    }

    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(@NonNull final GBDevice device) {
        return JblTuneBuds2Support.class;
    }

    @Override
    public String getManufacturer() {
        return "JBL";
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_jbl_tune_buds_2;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_headphones;
    }

    @Override
    public DeviceCoordinator.DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.EARBUDS;
    }
}
