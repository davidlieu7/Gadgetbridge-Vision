/*  Copyright (C) 2024-2026 Severin von Wnuck-Lipinski, David Giron

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
package nodomain.freeyourgadget.gadgetbridge.devices.soundcore.motion300

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.motion300.SoundcoreMotion300DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.motion300.SoundcoreMotion300Protocol
import java.util.regex.Pattern

class SoundcoreMotion300Coordinator : SoundcoreEarbudsCoordinator() {
    override fun getAutoPowerOffMinutes(): Array<Int> = arrayOf(5, 10, 20, 60)

    override fun getDefaultCapabilities(): Set<Capability> = setOf(
        Capability.AutoPowerOff,
        Capability.VoicePrompts,
        Capability.ButtonBrightness,
        Capability.LDAC,
        Capability.AudioEqualizer,
        Capability.AdaptiveDirection,
        Capability.AdjustableEqualizerFrequencies,
    )

    override fun getModelName(): String = "A3135"

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("soundcore Motion 300")

    override fun getDeviceNameResource(): Int = R.string.devicetype_soundcore_motion300

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_speaker

    override fun getBondingStyle(): Int = BONDING_STYLE_ASK

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.SPEAKER

    override fun getBatteryCount(device: GBDevice): Int = 1

    override fun supportsPowerOff(device: GBDevice): Boolean = true

    override fun createProtocol(device: GBDevice): SoundcoreMotion300Protocol =
        SoundcoreMotion300Protocol(device)

    override fun supportsExtendedInfoRequest(): Boolean = false

    override fun supportsSessionInitRequest(): Boolean = false

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        SoundcoreMotion300DeviceSupport::class.java

    override fun getDeviceSpecificSettingsCustomizer(device: GBDevice): DeviceSpecificSettingsCustomizer =
        SoundcoreMotion300SettingsCustomizer()
}
