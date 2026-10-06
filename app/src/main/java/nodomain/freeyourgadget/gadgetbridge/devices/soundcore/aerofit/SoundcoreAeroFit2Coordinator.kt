/*  Copyright (C) 2026 NachtigallersLehrling, David Giron

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.soundcore.aerofit

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.aeroFit.SoundcoreAeroFitProtocol
import java.util.regex.Pattern

class SoundcoreAeroFit2Coordinator : SoundcoreEarbudsCoordinator() {
    override fun getModelName(): String = "A3874"

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("soundcore AeroFit 2")

    override fun getDeviceNameResource(): Int = R.string.devicetype_soundcore_aerofit2

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_sony_wf_800n

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.HEADPHONES

    override fun getDefaultCapabilities(): Set<Capability> = setOf(
        Capability.FindDevice
    )

    override fun createProtocol(device: GBDevice): SoundcoreAeroFitProtocol =
        SoundcoreAeroFitProtocol(device)

    override fun supportsExtendedInfoRequest(): Boolean = false

    override fun supportsSessionInitRequest(): Boolean = false

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
        xmlScreen(DeviceSpecificSettingsScreen.AUDIO, R.xml.devicesettings_soundcore_aerofit)
        xmlScreen(DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS, R.xml.devicesettings_headphones)
    }
}
