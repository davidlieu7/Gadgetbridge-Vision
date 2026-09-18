/*  Copyright (C) 2026 pwnie69

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
package nodomain.freeyourgadget.gadgetbridge.devices.galaxywatch7;

import java.util.regex.Pattern;

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.galaxywatch7.GalaxyWatch7DeviceSupport;

/**
 * Phase 1 proof-of-concept coordinator for the Samsung Galaxy Watch7. Standard Android BT
 * bonding is used for pairing (the stock Galaxy Wearable app's SAK/GAK hardware-attestation
 * verification step was confirmed, via its own logs, to be skipped entirely on non-Samsung
 * phones on Android 13+ — no special pairing dance is required here).
 * <p/>
 * See the project's research notes for the full reverse-engineered protocol writeup this is
 * based on, and {@link GalaxyWatch7DeviceSupport}/{@link nodomain.freeyourgadget.gadgetbridge.service.devices.galaxywatch7.WearDataLayerProtocol}
 * for what's actually implemented so far.
 */
public class GalaxyWatch7Coordinator extends AbstractBLClassicDeviceCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        // Observed on the wire as both "Galaxy Watch7" and "Galaxy Watch7 (CLMP)".
        return Pattern.compile("Galaxy Watch7.*");
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_BOND;
    }

    @Override
    public String getManufacturer() {
        return "Samsung";
    }

    @Override
    public boolean supportsFindDevice(@NonNull final GBDevice device) {
        // "Find My Watch" appears to use the same Data Layer message mechanism as everything
        // else here, but isn't wired up yet.
        return false;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return GalaxyWatch7DeviceSupport.class;
    }

    @Override
    public final DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.WATCH;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_galaxy_watch7;
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(final GBDevice device) {
        return new int[]{
                R.xml.devicesettings_galaxy_watch7,
        };
    }

    @Override
    public nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(final GBDevice device) {
        // Attaches change handlers so the settings switches actually reach onSendConfiguration.
        return new GalaxyWatch7SettingsCustomizer();
    }
}
