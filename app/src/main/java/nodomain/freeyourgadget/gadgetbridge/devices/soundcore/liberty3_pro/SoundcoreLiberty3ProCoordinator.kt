/*  Copyright (C) 2024-2026 Severin von Wnuck-Lipinski, David Giron

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
package nodomain.freeyourgadget.gadgetbridge.devices.soundcore.liberty3_pro

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.liberty.SoundcoreLibertyProtocol
import java.util.regex.Pattern

class SoundcoreLiberty3ProCoordinator : SoundcoreEarbudsCoordinator() {
    override fun getModelName(): String = "A3952"

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("Soundcore Liberty 3 Pro")

    override fun getDeviceNameResource(): Int = R.string.devicetype_soundcore_liberty3_pro

    override fun createProtocol(device: GBDevice): SoundcoreLibertyProtocol =
        SoundcoreLibertyProtocol(device)

    override fun supportsExtendedInfoRequest(): Boolean = false

    override fun supportsSessionInitRequest(): Boolean = false

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
        xmlScreen(
            DeviceSpecificSettingsScreen.TOUCH_OPTIONS,
            R.xml.devicesettings_sony_headphones_ambient_sound_control_button_modes,
            R.xml.devicesettings_soundcore_liberty_touch_options,
        )
        xmlScreen(DeviceSpecificSettingsScreen.AUDIO, R.xml.devicesettings_soundcore_liberty)
        xmlScreen(DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS, R.xml.devicesettings_headphones)
    }
}
