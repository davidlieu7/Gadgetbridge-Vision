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
package nodomain.freeyourgadget.gadgetbridge.devices.soundcore.sleep_a30

import java.util.UUID
import java.util.regex.Pattern
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.*
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30.SoundcoreSleepA30Protocol

class SoundcoreSleepA30Coordinator : SoundcoreEarbudsCoordinator() {
    override fun getModelName(): String = "D1301"

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("soundcore Sleep A30")

    override fun getDeviceNameResource(): Int = R.string.devicetype_soundcore_sleep_a30

    override fun getServiceUUID(): UUID = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7d1301")

    // override fun getConnectDelayMillis(): Int = 500

    override fun createProtocol(device: GBDevice) = SoundcoreSleepA30Protocol(device)

    override fun getDefaultCapabilities(): Set<Capability> = setOf(
        Capability.NoiseControl,
        Capability.TouchControls,
        Capability.TouchControlDisable,
        Capability.BatteryLowTone,
        Capability.FindDevice,
        Capability.FindIndividualEarbuds,
    )

    override val supportedButtonActions = listOf(
        ButtonAction.DOUBLE_TAP,
        ButtonAction.TRIPLE_TAP
    )

    override val supportedTapFunctions = listOf(
        TapFunction.ANC_TOGGLE,
        TapFunction.SWITCH_MODE,
        TapFunction.VOLUME_UP,
        TapFunction.VOLUME_DOWN,
        TapFunction.PLAYPAUSE,
        TapFunction.MEDIA_NEXT,
        TapFunction.MEDIA_PREV,
        TapFunction.NONE,
    )

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec {
        val sleepSettings = deviceSettings {
            val modeEntries = listOf(
                ListEntry.Res("0", R.string.soundcore_bluetooth_mode),
                ListEntry.Res("1", R.string.soundcore_local_mode),
            )
            category(key = "soundcore_sleep_audio", title = R.string.pref_header_audio) {
                list(key = PREF_SOUNDCORE_LISTENING_MODE, title = R.string.soundcore_listening_mode,
                    entries = modeEntries, defaultValue = "0")
                list(key = PREF_SOUNDCORE_DEFAULT_LISTENING_MODE, title = R.string.soundcore_default_listening_mode,
                    entries = modeEntries, defaultValue = "0")
                switchSetting(key = PREF_SOUNDCORE_LOCAL_PLAYBACK, title = R.string.soundcore_local_playback)
                seekbar(key = PREF_SOUNDCORE_LOCAL_VOLUME, title = R.string.soundcore_local_volume,
                    min = 0, max = 65, defaultValue = 0)
                switchSetting(key = PREF_SOUNDCORE_SNORE_MASKING, title = R.string.soundcore_snore_masking)
                list(key = PREF_SOUNDCORE_SLEEP_AUDIO, title = R.string.soundcore_sleep_audio,
                    entries = listOf(
                        ListEntry.Res("local", R.string.soundcore_sleep_local),
                        ListEntry.Res("pause", R.string.soundcore_sleep_pause),
                        ListEntry.Res("keep", R.string.soundcore_sleep_keep),
                    ), defaultValue = "keep")
            }
            category(key = "soundcore_sleep_tones", title = R.string.pref_header_general) {
                switchSetting(key = PREF_SOUNDCORE_CALLS_ENABLED, title = R.string.soundcore_calls_enabled)
                switchSetting(key = PREF_SOUNDCORE_POWER_OFF_TONE, title = R.string.soundcore_power_off_tone)
                switchSetting(key = PREF_SOUNDCORE_ANC_TONE, title = R.string.soundcore_anc_tone)
                switchSetting(key = PREF_SOUNDCORE_MODE_TONE, title = R.string.soundcore_mode_tone)
                action(key = PREF_SOUNDCORE_CONTROL_RESET, title = R.string.soundcore_control_reset,
                    onClick = { _, selected ->
                        selected?.let { GBApplication.deviceService(it).onSendConfiguration(PREF_SOUNDCORE_CONTROL_RESET) }
                        true
                    })
            }
        }
        return DeviceSettingsSpec(super.getDeviceSettings(device).items + sleepSettings.items)
    }
}
