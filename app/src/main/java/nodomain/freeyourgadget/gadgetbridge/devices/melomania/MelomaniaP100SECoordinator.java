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
package nodomain.freeyourgadget.gadgetbridge.devices.melomania;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.GBException;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.melomania.MelomaniaP100SESupport;

public class MelomaniaP100SECoordinator extends AbstractBLClassicDeviceCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("(?i)^Melomania P100 SE.*$");
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(@NonNull final GBDevice device) {
        return MelomaniaP100SESupport.class;
    }

    @Override
    public String getManufacturer() {
        return "Cambridge Audio";
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_cambridge_audio_melomania_p100_se;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_headphones;
    }

    @Override
    public BatteryConfig[] getBatteryConfig(final GBDevice device) {
        return new BatteryConfig[]{
                new BatteryConfig(
                        0,
                        GBDevice.BATTERY_ICON_DEFAULT,
                        GBDevice.BATTERY_LABEL_DEFAULT,
                        15,
                        100
                )
        };
    }

    @NonNull
    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(@NonNull final GBDevice device) {
        final DeviceSpecificSettings settings = new DeviceSpecificSettings();
        settings.addRootScreen(R.xml.devicesettings_melomania_p100_se);
        return settings;
    }

    @NonNull
    @Override
    public DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(@NonNull final GBDevice device) {
        return new MelomaniaP100SESettingsCustomizer();
    }

    @Override
    public boolean suggestUnbindBeforePair() {
        return false;
    }

    @NonNull
    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.HEADPHONES;
    }

    @Override
    protected void deleteDevice(@NonNull final GBDevice gbDevice,
                                @NonNull final Device device,
                                @NonNull final DaoSession session) throws GBException {
        // nothing to delete
    }
}
