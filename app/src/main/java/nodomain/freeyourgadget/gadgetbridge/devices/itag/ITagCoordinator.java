/*  Copyright (C) 2020-2024 Andreas Shimokawa, Damien Gaignon, Daniel Dakhno,
    José Rebelo, Petr Vaněk, Taavi Eomäe

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
package nodomain.freeyourgadget.gadgetbridge.devices.itag;

import android.bluetooth.le.ScanFilter;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.itag.ITagSupport;

public class ITagCoordinator extends AbstractBLEDeviceCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("itag.*", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public int getBondingStyle() {
        // Some iTag devices do not support bonding but some do
        return BONDING_STYLE_ASK;
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        ScanFilter filter = new ScanFilter.Builder()
                .setDeviceName("iTag")
                .setDeviceName("iTAG")
                .setDeviceName("ITAG")
                .setDeviceName("ITag")
                .setDeviceName("Itag")
                .setDeviceName("itag")
                .build();
        return Collections.singletonList(filter);
    }

    @Override
    public String getManufacturer() {
        return "Unknown"; //TODO: Show chip manufacturer?
    }

    @Override
    public boolean supportsRealtimeData(@NonNull GBDevice device) {
        return false; //TODO: RRSI
    }

    @Override
    public boolean supportsFindDevice(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(final GBDevice device) {
        return new ITagSettingsCustomizer();
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return ITagSupport.class;
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(final GBDevice device) {
        final DeviceSpecificSettings deviceSpecificSettings = new DeviceSpecificSettings();

        final List<Integer> generic = deviceSpecificSettings.addRootScreen(DeviceSpecificSettingsScreen.GENERIC);
        generic.add(R.xml.devicesettings_itag_alert_force_mild);
        generic.add(R.xml.devicesettings_itag_button_events);

        final List<Integer> connection = deviceSpecificSettings.addRootScreen(DeviceSpecificSettingsScreen.CONNECTION);
        connection.add(R.xml.devicesettings_itag_alert_link_loss);

        deviceSpecificSettings.addConnectedPreferences(ITagConstants.PREF_ITAG_ALERT_LINK_LOSS);

        return deviceSpecificSettings;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_itag;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_itag;
    }

    @Override
    public DeviceCoordinator.DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceCoordinator.DeviceKind.UNKNOWN;
    }
}
