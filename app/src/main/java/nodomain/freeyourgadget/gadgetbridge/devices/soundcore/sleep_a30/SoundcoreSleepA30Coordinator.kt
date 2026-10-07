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
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30.SoundcoreSleepA30Protocol

class SoundcoreSleepA30Coordinator : SoundcoreEarbudsCoordinator() {
    override fun getModelName(): String = "D1301"

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("soundcore Sleep A30")

    override fun getDeviceNameResource(): Int = R.string.devicetype_soundcore_sleep_a30

    override fun getServiceUUID(): UUID = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7d1301")

    override fun createProtocol(device: GBDevice) = SoundcoreSleepA30Protocol(device)

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
