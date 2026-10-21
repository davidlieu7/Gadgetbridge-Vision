/*  Copyright (C) 2023-2026 Marc Nause, David Giron

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.pinecil

import android.bluetooth.le.ScanFilter
import java.util.regex.Pattern
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.pinecil.PinecilDeviceSupport

class PinecilCoordinator : AbstractBLEDeviceCoordinator() {
    override fun isExperimental(): Boolean = true 

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("^Pinecil.*")

    override fun getManufacturer(): String = "Pine64"

    override fun createBLEScanFilters(): Collection<ScanFilter> = listOf(
        ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(PinecilConstants.UUID_SERVICE_BULK_DATA))
            .build(),
    )

    override fun getBondingStyle(): Int = BONDING_STYLE_LAZY

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        PinecilDeviceSupport::class.java

    override fun getDeviceNameResource(): Int = R.string.devicetype_pinecil

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_unknown

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.UNKNOWN
}
