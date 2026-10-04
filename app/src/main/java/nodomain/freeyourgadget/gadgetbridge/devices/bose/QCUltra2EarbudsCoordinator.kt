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
package nodomain.freeyourgadget.gadgetbridge.devices.bose

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.bose.BoseProtocol
import nodomain.freeyourgadget.gadgetbridge.service.devices.bose.QCUltra2EarbudsSupport
import java.util.regex.Pattern

class QCUltra2EarbudsCoordinator : AbstractBLClassicDeviceCoordinator() {
    override fun getSupportedDeviceName(): Pattern = Pattern.compile("(LE-)?Bose QC Ultra 2 Earbuds")

    override fun getManufacturer(): String = "Bose"

    override fun getDeviceNameResource(): Int = R.string.devicetype_bose_qc_ultra2_earbuds

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.EARBUDS

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_galaxy_buds

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        QCUltra2EarbudsSupport::class.java

    override fun getBatteryCount(device: GBDevice): Int = 3

    override fun getBatteryConfig(device: GBDevice): Array<BatteryConfig> = arrayOf(
        BatteryConfig(0, R.drawable.ic_tws_case, R.string.battery_case),
        BatteryConfig(1, R.drawable.ic_galaxy_buds_l, R.string.left_earbud),
        BatteryConfig(2, R.drawable.ic_galaxy_buds_r, R.string.right_earbud),
    )

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
        list(
            key = DeviceSettingsPreferenceConst.PREF_BOSE_AUDIO_MODE,
            title = R.string.nothing_prefs_audiomode_title,
            icon = R.drawable.ic_noise_control_on,
            entriesProvider = { prefs ->
                val modes = mutableListOf<ListEntry>(
                    ListEntry.Res(BoseProtocol.AUDIO_MODE_QUIET.toString(), R.string.bose_audio_mode_quiet),
                    ListEntry.Res(BoseProtocol.AUDIO_MODE_AWARE.toString(), R.string.bose_audio_mode_aware),
                    ListEntry.Res(BoseProtocol.AUDIO_MODE_IMMERSION.toString(), R.string.bose_audio_mode_immersion),
                )
                val current = prefs.getString(DeviceSettingsPreferenceConst.PREF_BOSE_AUDIO_MODE, "")
                    ?.toIntOrNull()
                if (current != null && current > BoseProtocol.AUDIO_MODE_IMMERSION) {
                    modes.add(ListEntry.Res(current.toString(), R.string.bose_audio_mode_other))
                }
                modes
            },
            connectedOnly = true,
        )
        xmlScreen(
            DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS,
            R.xml.devicesettings_headphones,
            connectedOnly = false,
        )
    }
}
