/*  Copyright (C) 2024 José Rebelo
    Copyright (C) 2026 NTeditor

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
package nodomain.freeyourgadget.gadgetbridge.devices.oppo

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.util.Pair
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.OppoHeadphonesSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigSide
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigType
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigValue

abstract class OppoHeadphonesCoordinator : AbstractBLClassicDeviceCoordinator() {
    override fun getManufacturer(): String = "Oppo"

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        OppoHeadphonesSupport::class.java

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_nothingear

    override fun getBatteryCount(device: GBDevice): Int = 3

    override fun supports(candidate: GBDeviceCandidate): Boolean {
        if (!super.supports(candidate)) return false
        val majorDeviceClass = candidate.device?.bluetoothClass?.majorDeviceClass
        return majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    override fun getBatteryConfig(device: GBDevice): Array<BatteryConfig> = arrayOf(
        BatteryConfig(0, R.drawable.ic_nothing_ear_l, R.string.left_earbud),
        BatteryConfig(1, R.drawable.ic_nothing_ear_r, R.string.right_earbud),
        BatteryConfig(2, R.drawable.ic_tws_case, R.string.battery_case)
    )

    protected abstract val touchOptions: Map<Pair<TouchConfigSide, TouchConfigType>, List<TouchConfigValue>>

    override fun getDeviceSpecificSettings(device: GBDevice): DeviceSpecificSettings {
        val settings = DeviceSpecificSettings()

        settings.addRootScreen(DeviceSpecificSettingsScreen.TOUCH_OPTIONS)
        settings.addSubScreen(
            DeviceSpecificSettingsScreen.TOUCH_OPTIONS,
            R.xml.devicesettings_oppo_headphones_touch_options
        )

        settings.addRootScreen(DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS)
        settings.addSubScreen(
            DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS,
            R.xml.devicesettings_headphones
        )

        if (supportsLdac(device) || supportsAnc(device)) {
            settings.addRootScreen(DeviceSpecificSettingsScreen.AUDIO)
            if (supportsLdac(device)) {
                settings.addSubScreen(
                    DeviceSpecificSettingsScreen.AUDIO,
                    R.xml.devicesettings_ldac_toggle
                )
            }
            if (supportsAnc(device)) {
                settings.addSubScreen(
                    DeviceSpecificSettingsScreen.AUDIO,
                    R.xml.devicesettings_onemore_noise_control_selector
                )
                settings.addSubScreen(
                    DeviceSpecificSettingsScreen.TOUCH_OPTIONS,
                    R.xml.devicesettings_oppo_headphones_touch_options_anc
                )
            }
        }

        if (supportsMultipoint(device) || supportsGameMode(device)) {
            settings.addRootScreen(DeviceSpecificSettingsScreen.CONNECTION)
            if (supportsMultipoint(device)) {
                settings.addSubScreen(
                    DeviceSpecificSettingsScreen.CONNECTION,
                    R.xml.devicesettings_oppo_headphones_multipoint
                )
            }
            if (supportsGameMode(device)) {
                settings.addSubScreen(
                    DeviceSpecificSettingsScreen.CONNECTION,
                    R.xml.devicesettings_oppo_headphones_game_mode
                )
            }
        }

        return settings
    }

    override fun getDeviceSpecificSettingsCustomizer(device: GBDevice): DeviceSpecificSettingsCustomizer =
        OppoHeadphonesSettingsCustomizer(
            touchOptions,
            supportsLdac(device),
            supportsMultipoint(device),
            supportsGameMode(device),
            supportsAnc(device)
        )


    final override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.EARBUDS

    open fun supportsLdac(device: GBDevice): Boolean = false
    open fun supportsMultipoint(device: GBDevice): Boolean = false
    open fun supportsGameMode(device: GBDevice): Boolean = false
    open fun supportsAnc(device: GBDevice): Boolean = false
}

