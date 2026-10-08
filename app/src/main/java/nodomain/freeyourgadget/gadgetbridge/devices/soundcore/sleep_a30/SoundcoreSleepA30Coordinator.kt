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
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.deviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30.SoundcoreSleepA30Protocol
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30.SoundcoreSleepA30DeviceSupport

class SoundcoreSleepA30Coordinator : SoundcoreEarbudsCoordinator() {
    override fun getModelName(): String = "D1301"

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("soundcore Sleep A30")

    override fun getDeviceNameResource(): Int = R.string.devicetype_soundcore_sleep_a30

    override fun getServiceUUID(): UUID = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7d1301")

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        SoundcoreSleepA30DeviceSupport::class.java

    override fun findInstallHandler(uri: android.net.Uri, options: android.os.Bundle, context: android.content.Context): nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler? =
        SoundcoreSleepA30AudioInstallHandler(uri, context).takeIf { it.isValid }

    override fun getAlarmSlotCount(device: GBDevice): Int = 5

    override fun supportsAlarmSnoozing(device: GBDevice): Boolean = true

    override fun createProtocol(device: GBDevice) = SoundcoreSleepA30Protocol(device)

    override fun getCustomActions(): List<DeviceCardAction> = listOf(
        deviceCardAction {
            isVisible = { device ->
                device.isConnected && GBApplication.getDevicePrefs(device)
                    .getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LISTENING_MODE, "0") == "1"
            }
            icon = { device ->
                if (GBApplication.getDevicePrefs(device).getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LOCAL_PLAYBACK, false)) {
                    R.drawable.ic_pause
                } else {
                    R.drawable.ic_play
                }
            }
            description = { device, context ->
                context.getString(
                    if (GBApplication.getDevicePrefs(device).getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LOCAL_PLAYBACK, false)) {
                        R.string.soundcore_local_playback_pause
                    } else {
                        R.string.soundcore_local_playback
                    }
                )
            }
            onClick = { device, _ ->
                if (device.isConnected) {
                    GBApplication.deviceService(device)
                        .onSendConfiguration(SoundcoreSleepA30Protocol.ACTION_TOGGLE_LOCAL_PLAYBACK)
                }
            }
        }
    )

    override fun getDefaultCapabilities(): Set<Capability> = setOf(
        Capability.FindDevice,
        Capability.NoiseControl,
        Capability.NoiseCancellationTone,
        Capability.TouchControls,
        Capability.TouchControlDisable,
        Capability.TouchControlReset,
        Capability.ListeningMode,
        Capability.ListeningModeTone,
        Capability.DefaultListeningMode,
        Capability.LocalAudioPlayback,
        Capability.LocalAudioVolume,
        Capability.LocalAudioUpload,
        Capability.LocalAudioAutoStop,
        Capability.DynamicSnoreMasking,
        Capability.SleepAudioAutoSwitch,
        Capability.BluetoothCalls,
        Capability.PowerOffTone,
        Capability.BatteryLowTone,
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
}
