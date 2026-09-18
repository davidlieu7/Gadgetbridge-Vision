/*  Copyright (C) 2025 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.devices.veepoo;

import android.bluetooth.le.ScanFilter;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class VeepooKR96ProCoordinator extends AbstractVeepooCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        // Advertised name is not yet confirmed by a live capture; the VeePoo app
        // matches on manufacturer-specific scan data instead. Adjust after capture.
        return Pattern.compile("^KR96.*", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_veepoo_kr96_pro;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_amazfit_bip;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.FITNESS_BAND;
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        return Collections.emptyList();
    }
}
