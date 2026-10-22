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
import nodomain.freeyourgadget.gadgetbridge.activities.charts.DefaultChartsProvider
import nodomain.freeyourgadget.gadgetbridge.activities.charts.DeviceChartsProvider
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.LabeledEntry
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.deviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreSleepSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30.SoundcoreSleepA30Protocol
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30.SoundcoreSleepA30DeviceSupport

class SoundcoreSleepA30Coordinator : SoundcoreEarbudsCoordinator() {
    override fun getModelName(): String = "D1301"

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("soundcore Sleep A30")

    override fun getDeviceNameResource(): Int = R.string.devicetype_soundcore_sleep_a30

    override fun getBatteryProperties(): BatteryProperties = BatteryProperties(multiplier = 10, offset = 1)

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

    override fun getAlarmSlotCount(device: GBDevice): Int = 5

    override fun supportsAlarmSnoozing(device: GBDevice): Boolean = true

    override fun supportsDataFetching(device: GBDevice): Boolean = true
    override fun supportsActivityTracking(device: GBDevice): Boolean = true
    override fun supportsStepCounter(device: GBDevice): Boolean = false
    override fun supportsSpeedzones(device: GBDevice): Boolean = false

    override fun supportsSleepMeasurement(device: GBDevice): Boolean = true
    override fun supportsSleepScore(device: GBDevice): Boolean = true
    override fun supportsAwakeSleep(device: GBDevice): Boolean = true

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        SoundcoreSleepA30DeviceSupport::class.java

    override fun createProtocol(device: GBDevice) = SoundcoreSleepA30Protocol(device)

    override fun findInstallHandler(uri: android.net.Uri, options: android.os.Bundle, context: android.content.Context): nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler? =
        SoundcoreSleepA30AudioInstallHandler(uri, context).takeIf { it.isValid }

    // Show only Sleep activity chart
    override fun getChartsProvider(): DeviceChartsProvider = object : DefaultChartsProvider() {
        override fun getSupportedCharts(device: GBDevice): List<String> = listOf("sleep")
    }

    override fun getSampleProvider(device: GBDevice, session: DaoSession) =
        SoundcoreSleepSampleProvider(device, session)

    override fun getSleepScoreProvider(device: GBDevice, session: DaoSession) =
        nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepScoreSampleProvider(device, session)

    override fun getAllDeviceDao(session: DaoSession):
        MutableMap<de.greenrobot.dao.AbstractDao<*, *>, de.greenrobot.dao.Property> =
        super.getAllDeviceDao(session).toMutableMap().apply {
            put(session.genericSleepScoreSampleDao,
                nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepScoreSampleDao.Properties.DeviceId)
            put(session.soundcoreSnoreSampleDao,
                nodomain.freeyourgadget.gadgetbridge.entities.SoundcoreSnoreSampleDao.Properties.DeviceId)
            put(session.soundcoreSleepStageSampleDao,
                nodomain.freeyourgadget.gadgetbridge.entities.SoundcoreSleepStageSampleDao.Properties.DeviceId)
            put(session.soundcoreSleepSessionSampleDao,
                nodomain.freeyourgadget.gadgetbridge.entities.SoundcoreSleepSessionSampleDao.Properties.DeviceId)
        }

    override fun getCustomActions(): List<DeviceCardAction> = listOf(
        deviceCardAction {
            id = "soundcore_local_audio"
            isVisible = { device ->
                device.isConnected && GBApplication.getDevicePrefs(device)
                    .getString(
                        DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LISTENING_MODE,
                        AudioSource.BLUETOOTH.toString(),
                    ) == AudioSource.LOCAL.toString()
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

    enum class AudioSource(override val label: Int) : LabeledEntry {
        BLUETOOTH(R.string.media_source_bluetooth),
        LOCAL(R.string.media_source_local),
        ;

        override fun toString(): String = name.lowercase()

        companion object {
            @JvmStatic
            fun fromValue(value: Int): String? = entries.getOrNull(value)?.toString()
        }
    }
}
