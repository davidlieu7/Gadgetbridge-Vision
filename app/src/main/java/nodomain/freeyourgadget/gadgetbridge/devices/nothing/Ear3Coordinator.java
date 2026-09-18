/*  Copyright (C) 2026 Jackson Attwood (adapted from Nothing Ear (2), José Rebelo)

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
package nodomain.freeyourgadget.gadgetbridge.devices.nothing;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.nothing.prefs.NothingAudioMode;

public class Ear3Coordinator extends AbstractEarCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("(Nothing )?Ear \\(3\\)(?: - .*)?");
    }

    @Override
    public boolean supports(nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate candidate) {
        // 1. Ensure the device name matches the Nothing Ear (3) regex
        if (!super.supports(candidate)) {
            return false;
        }

        // 2. Filter out the BLE (Low Energy) randomized MAC address
        android.bluetooth.BluetoothDevice device = candidate.getDevice();
        if (device != null) {
            // If Android reports this specific MAC as exclusively Low Energy, ignore it.
            // We only want to accept DEVICE_TYPE_CLASSIC (1) or DEVICE_TYPE_DUAL (3).
            return device.getType() != android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE;
        }

        return true;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_nothingear3;
    }

    @Override
    public boolean incrementCounter() {
        return false;
    }

    @Override
    public boolean supportsSpatialAudio() { return true; }

    @Override
    public boolean supportsLowLatency() { return true; }

    @Override
    public boolean supportsAutoTransparency() { return true; }

    @Override
    public boolean supportsInEarDetection() { return true; }

    @Override
    public boolean supportsAudioCodec() { return true; }

    @Override
    public int getBondingStyle() {
        // Enforces "DON'T PAIR" automatically in Gadgetbridge.
        // Requires the user to pair via Android System Settings first.
        return AbstractEarCoordinator.BONDING_STYLE_NONE;
    }

    @NonNull
    @Override
    public List<NothingAudioMode> getAudioModes() {
        return Arrays.asList(
                NothingAudioMode.ANC,
                NothingAudioMode.ANCMEDIUM,
                NothingAudioMode.ANCLIGHT,
                NothingAudioMode.ANCADAPTIVE,
                NothingAudioMode.TRANSPARENCY,
                NothingAudioMode.OFF
        );
    }
}
