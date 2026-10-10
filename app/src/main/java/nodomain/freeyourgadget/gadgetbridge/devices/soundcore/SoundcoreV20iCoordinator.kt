/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.devices.soundcore

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointPairingActivity
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcoreV20iProtocol
import java.util.regex.Pattern

class SoundcoreV20iCoordinator : SoundcoreEarbudsCoordinator() {
    override fun getModelName(): String = "A3876"

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("^soundcore V20i$")

    override fun getDeviceNameResource(): Int = R.string.devicetype_soundcore_v20i

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_galaxy_buds

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.EARBUDS

    override fun isExperimental(): Boolean = true

    override fun getBatteryCount(device: GBDevice): Int = 2

    override fun getDefaultCapabilities(): Set<Capability> = setOf(
        Capability.FindDevice,
        Capability.Multipoint,
        Capability.TouchControls,
        Capability.AutoPowerOff,
        Capability.AudioEqualizer,
        Capability.GamingMode,
    )

    override val supportedButtonActions: List<ButtonAction> = listOf(
        ButtonAction.SINGLE_TAP,
        ButtonAction.DOUBLE_TAP,
        ButtonAction.TRIPLE_TAP,
        ButtonAction.LONG_PRESS,
    )

    override val supportedTapFunctions: List<TapFunction> = listOf(
        TapFunction.VOLUME_DOWN,
        TapFunction.VOLUME_UP,
        TapFunction.MEDIA_NEXT,
        TapFunction.MEDIA_PREV,
        TapFunction.PLAYPAUSE,
        TapFunction.VOICE_ASSISTANT,
        TapFunction.NONE,
    )

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec {
        val context = GBApplication.getContext()
        val lightingSettings = deviceSettings {
            screen(key = "pref_soundcore_light_screen", title = R.string.soundcore_colorful_lights) {
                switchSetting(
                    key = PREF_LIGHTS_ENABLED,
                    title = R.string.soundcore_colorful_lights
                )
                list(
                    key = PREF_LIGHT_MODE,
                    title = R.string.soundcore_light_mode,
                    entries = (0..3).map { mode ->
                        ListEntry.Text(
                            mode.toString(),
                            context.getString(R.string.soundcore_light_mode_i, mode + 1),
                        )
                    },
                    defaultValue = "0",
                    dependency = PREF_LIGHTS_ENABLED,
                )
            }
        }
        return DeviceSettingsSpec(super.getDeviceSettings(device).items + lightingSettings.items)
    }

    override fun getCustomEqualizerPresetArray(): Int = R.array.soundcore_sport_x20_equalizer_preset_names

    override fun createProtocol(device: GBDevice): SoundcoreV20iProtocol =
        SoundcoreV20iProtocol(device)

    companion object {
        const val PREF_LIGHTS_ENABLED = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LIGHTS_ENABLED
        const val PREF_LIGHT_MODE = "pref_soundcore_light_mode"
    }
}
