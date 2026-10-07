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

import java.util.UUID
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.components.multipointPairing
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcoreDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1

abstract class SoundcoreEarbudsCoordinator : AbstractBLClassicDeviceCoordinator() {
    enum class Capability {
        FindDevice,
        FindIndividualEarbuds,
        Multipoint,
        Transparency,
        NoiseControl,
        NoiseCancellationModes,
        AdaptiveNoiseCancellation,
        AdaptiveNoiseCancellationMode,
        AmbientSound,
        WindNoiseReduction,
        VocalMode,
        TouchControls,
        TouchControlDisable,
        WearingDetection,
        WearingTone,
        SurroundSound,
        FitTest,
        AutoPowerOff,
        BatteryLowTone,
        TouchTone,
        AudioEqualizer,
        AudioEqualizer2,
        GamingMode,
        VoicePrompts,
        ButtonBrightness,
        LDAC,
        AdaptiveDirection,
    }

    enum class ButtonAction(
        @androidx.annotation.StringRes val title: Int,
        val leftPreference: String,
        val rightPreference: String,
    ) {
        SINGLE_TAP(
            R.string.single_tap,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_LEFT,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_RIGHT,
        ),
        DOUBLE_TAP(
            R.string.double_tap,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT,
        ),
        TRIPLE_TAP(
            R.string.triple_tap,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_LEFT,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_RIGHT,
        ),
        LONG_PRESS(
            R.string.long_press,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT,
            DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT,
        ),
    }

    enum class TapFunction(
        val code: Int,
        @androidx.annotation.StringRes val label: Int,
    ) {
        VOLUME_UP(0, R.string.pref_media_volumeup),
        VOLUME_DOWN(1, R.string.pref_media_volumedown),
        MEDIA_PREV(2, R.string.pref_media_previous),
        MEDIA_NEXT(3, R.string.pref_media_next),
        PLAYPAUSE(6, R.string.pref_media_playpause),
        VOICE_ASSISTANT(5, R.string.pref_title_touch_voice_assistant),
        AMBIENT_SOUND_CONTROL(4, R.string.sony_button_mode_ambient_sound_control),
        NONE(15, R.string.none),

        ;

        val value: String
            get() = name
    }

    open val supportedButtonActions: List<ButtonAction>
        get() = emptyList()

    open val supportedTapFunctions: List<TapFunction>
        get() = emptyList()

    /* TIP: Override this function in case you need conditions/exclusions based on Action. */
    open fun getSupportedTapFunctions(action: ButtonAction): List<TapFunction> = supportedTapFunctions

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_galaxy_buds

    /* IMPORTANT: To dynamically set the UUID, set this string in your coordinator. */
    open fun getModelName(): String = "0"

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        SoundcoreDeviceSupport::class.java

    /** Creates the protocol implementation for this model. */
    open fun createProtocol(device: GBDevice): SoundcoreProtocolImplV1 =
        throw UnsupportedOperationException("Soundcore coordinator must provide its protocol factory")

    /** Overrides the model-derived control service for devices using a different service. */
    open fun getServiceUUID(): UUID? = null

    /** Delay before the first initialization command, after the socket is connected. */
    open fun getConnectDelayMillis(): Int = 0

    /** Whether this model supports the session initialization request. */
    open fun supportsSessionInitRequest(): Boolean = true

    /** Whether this model supports the extended-info initialization request. */
    open fun supportsExtendedInfoRequest(): Boolean = true

    override fun getManufacturer(): String = "Anker"

    override fun getBondingStyle(): Int = BONDING_STYLE_NONE

    override fun getBatteryCount(device: GBDevice): Int = 3

    override fun getBatteryConfig(device: GBDevice): Array<BatteryConfig> =
        when (getBatteryCount(device)) {
            1 -> arrayOf(BatteryConfig(0, R.drawable.ic_battery, R.string.battery))
            2 -> arrayOf(
                BatteryConfig(0, R.drawable.ic_nothing_ear_l, R.string.left_earbud),
                BatteryConfig(1, R.drawable.ic_nothing_ear_r, R.string.right_earbud),
            )
            else -> arrayOf(
                BatteryConfig(0, R.drawable.ic_buds_pro_case, R.string.battery_case),
                BatteryConfig(1, R.drawable.ic_nothing_ear_l, R.string.left_earbud),
                BatteryConfig(2, R.drawable.ic_nothing_ear_r, R.string.right_earbud),
            )
        }

    override fun supportsFindDevice(device: GBDevice): Boolean =
        getCapabilities(device).contains(Capability.FindDevice)

    override fun getSupportedDeviceSpecificConnectionSettings(): IntArray =
        connectionSettings + super.getSupportedDeviceSpecificConnectionSettings()

    protected open val connectionSettings: IntArray = intArrayOf()

    protected fun getAutoPowerOffEntries(): List<ListEntry> = buildList {
        add(ListEntry.Res("0", R.string.pref_button_action_disabled))
        val context = GBApplication.getContext()
        getAutoPowerOffMinutes().forEachIndexed { index, minutes ->
            val label = context.resources.getIdentifier("minutes_$minutes", "string", context.packageName)
            require(label != 0) {
                "Missing string resource for auto power off duration: minutes_$minutes"
            }
            // Preferences store the protocol option index, not the duration in minutes.
            add(ListEntry.Res((index + 1).toString(), label))
        }
    }

    open fun getDefaultCapabilities(): Set<Capability> = emptySet()

    open fun getCapabilities(device: GBDevice): Set<Capability> {
        val devicePrefs = GBApplication.getDevicePrefs(device)
        if (devicePrefs.getBoolean(DeviceSettingsPreferenceConst.PREF_OVERRIDE_FEATURES_ENABLED, false)) {
            return devicePrefs.getStringSet(
                DeviceSettingsPreferenceConst.PREF_OVERRIDE_FEATURES_LIST,
                emptySet(),
            ).mapNotNullTo(mutableSetOf()) { name -> Capability.values().firstOrNull { it.name == name } }
        }

        return getDefaultCapabilities()
    }

    fun supports(device: GBDevice, capability: Capability): Boolean =
        getCapabilities(device).contains(capability)

    open fun getNoiseCancellationLevelRange(): IntRange = 0..2

    open fun getAutoPowerOffMinutes(): Array<Int> = arrayOf(10, 20, 30, 60)

    open fun getSupportedEqualizerBands(): IntArray = intArrayOf()

    /**
     * Returns preset label resources whose values map to zero-based preset indices.
     * The Custom option (value 254) is appended automatically and must not be included here.
     */
    open fun getCustomEqualizerPresetNames(): Array<Int> = emptyArray()

    open fun getCustomEqualizerPresetArray(): Int? = null

    open fun getDefaultEqualizerPresetNames(): Array<Int> = arrayOf(
        R.string.soundcore_equalizer_preset_signature,
        R.string.soundcore_equalizer_preset_xtra_bass,
        R.string.soundcore_equalizer_preset_voice,
        R.string.soundcore_equalizer_preset_balanced,
    )

    private fun getEqualizerPresetEntries(): List<ListEntry> {
        val customPresetNames = getCustomEqualizerPresetNames()
        if (customPresetNames.isNotEmpty()) {
            return customPresetNames.mapIndexed { index, name -> ListEntry.Res(index.toString(), name) } +
                ListEntry.Res("254", R.string.custom)
        }

        val presetNamesArray = getCustomEqualizerPresetArray()
        val presetNames = if (presetNamesArray == null) {
            getDefaultEqualizerPresetNames().toList()
        } else {
            val typedArray = GBApplication.getContext().resources.obtainTypedArray(presetNamesArray)
            try {
                (0 until typedArray.length()).mapNotNull { index ->
                    typedArray.getResourceId(index, 0).takeIf { it != 0 && it != R.string.custom }
                }
            } finally {
                typedArray.recycle()
            }
        }
        require(presetNames.isNotEmpty()) { "Equalizer preset names must not be empty" }
        return presetNames.mapIndexed { index, name -> ListEntry.Res(index.toString(), name) } +
            ListEntry.Res("254", R.string.custom)
    }

    private fun equalizerBandTitleResource(frequency: Int): Int = when (frequency) {
        100 -> R.string.soundcore_equalizer_band1_freq
        200 -> R.string.soundcore_equalizer_band2_freq
        400 -> R.string.soundcore_equalizer_band3_freq
        800 -> R.string.soundcore_equalizer_band4_freq
        1600 -> R.string.soundcore_equalizer_band5_freq
        3200 -> R.string.soundcore_equalizer_band6_freq
        6400 -> R.string.soundcore_equalizer_band7_freq
        12800 -> R.string.soundcore_equalizer_band8_freq
        else -> R.string.soundcore_equalizer_value
    }

    /**
     * Builds the standardized modern settings tree for Soundcore earbuds. A model that needs a
     * completely isolated settings tree can override [getDeviceSettings].
     */
    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec =
        soundcoreEarbudsDeviceSettings(device)

    protected fun soundcoreEarbudsDeviceSettings(device: GBDevice): DeviceSettingsSpec {
        val capabilities = getCapabilities(device)
        fun has(capability: Capability) = capabilities.contains(capability)
        fun has(anyOf: Array<Capability>) = anyOf.any { capabilities.contains(it) }

        return deviceSettings {
            if (has(arrayOf(Capability.TouchControls, Capability.TouchControlDisable, Capability.TouchTone))) {
                screen(
                    key = "pref_soundcore_touch_options",
                    title = R.string.prefs_galaxy_touch_options,
                    icon = R.drawable.ic_touch,
                ) {
                    if (has(Capability.TouchTone)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE,
                            title = R.string.pref_touch_tone,
                            summary = R.string.pref_touch_tone_summary,
                            icon = R.drawable.ic_volume_up,
                            defaultValue = false,
                        )
                    }
                    switchSetting(
                        key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_TOUCH_DISABLED,
                        title = R.string.prefs_touch_lock_buds2,
                        summary = R.string.prefs_touch_lock_summary,
                        icon = R.drawable.ic_lock_open,
                        defaultValue = false,
                        disableDependentsState = true,
                        visibleWhen = { _ -> has(Capability.TouchControlDisable) },
                    )
                    if (has(Capability.TouchControls)) {
                        list(
                            key = DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_CONTROL_BUTTON_MODE,
                            title = R.string.sony_ambient_sound_control_button_modes,
                            icon = R.drawable.ic_hearing,
                            entriesRes = R.array.sony_ambient_sound_control_button_mode_names,
                            entryValuesRes = R.array.sony_ambient_sound_control_button_mode_values,
                            defaultValue = "nc_as_off",
                            visibleWhen = { _ -> TapFunction.AMBIENT_SOUND_CONTROL in supportedTapFunctions },
                        )
                        supportedButtonActions.forEach { action ->
                            category(key = "pref_header_${action.name.lowercase()}", title = action.title) {
                                list(
                                    key = action.leftPreference,
                                    title = R.string.prefs_left,
                                    icon = R.drawable.ic_touch,
                                    entries = getSupportedTapFunctions(action).map { ListEntry.Res(it.value, it.label) },
                                    dependency = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_TOUCH_DISABLED,
                                )
                                list(
                                    key = action.rightPreference,
                                    title = R.string.prefs_right,
                                    icon = R.drawable.ic_touch,
                                    entries = getSupportedTapFunctions(action).map { ListEntry.Res(it.value, it.label) },
                                    dependency = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_TOUCH_DISABLED,
                                )
                            }
                        }
                    }
                }
            }

            if (has(arrayOf(Capability.AudioEqualizer, Capability.AudioEqualizer2, Capability.SurroundSound, Capability.GamingMode, Capability.LDAC))) {
                screen(
                    key = "pref_soundcore_audio",
                    title = R.string.pref_header_audio,
                    icon = R.drawable.ic_music_note,
                ) {
                    if (has(Capability.GamingMode)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_GAMING_MODE,
                            title = R.string.soundcore_gaming_mode_title,
                            summary = R.string.soundcore_gaming_mode_summary,
                            defaultValue = false,
                        )
                    }
                    if (has(Capability.LDAC)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LDAC_MODE,
                            title = R.string.soundcore_ldac_mode_title,
                            summary = R.string.soundcore_ldac_mode_summary,
                            icon = R.drawable.ic_music_note,
                            defaultValue = false,
                        )
                    }
                    if (has(Capability.SurroundSound)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_3D_SURROUND,
                            title = R.string.soundcore_3d_surround_title,
                            summary = R.string.soundcore_3d_surround_summary,
                        )
                    }
                    if (has(arrayOf(Capability.AudioEqualizer, Capability.AudioEqualizer2))) {
                        val equalizerPresetEntries = getEqualizerPresetEntries()
                        category(key = "pref_header_soundcore_equalizer", title = R.string.pref_header_equalizer) {
                            if (has(Capability.AdaptiveDirection)) {
                                switchSetting(
                                    key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_DIRECTION,
                                    title = R.string.soundcore_adaptive_direction_title,
                                    summary = R.string.soundcore_adaptive_direction_summary,
                                    icon = R.drawable.ic_switch_left,
                                    defaultValue = true,
                                )
                            }
                            list(
                                key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_PRESET,
                                title = R.string.soundcore_equalizer_preset,
                                icon = R.drawable.ic_equalizer,
                                entries = equalizerPresetEntries,
                                defaultValue = "0",
                            )
                            screen(
                                key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_CUSTOM,
                                title = R.string.soundcore_equalizer_custom_title,
                                summary = R.string.soundcore_equalizer_custom_summary,
                                icon = R.drawable.ic_graphic_eq,
                                enabled = { prefs ->
                                    prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_PRESET, "0") == "254"
                                },
                            ) {
                                if (has(Capability.AdaptiveDirection)) {
                                    list(
                                        key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_DIRECTION,
                                        title = R.string.soundcore_equalizer_direction,
                                        icon = R.drawable.ic_switch_left,
                                        entriesRes = R.array.soundcore_equalizer_direction_names,
                                        entryValuesRes = R.array.soundcore_equalizer_direction_values,
                                        defaultValue = "0",
                                        visibleWhen = { prefs -> prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_DIRECTION, true) },
                                    )
                                }
                                val equalizerBandKeys = listOf(
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND1_VALUE,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND2_VALUE,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND3_VALUE,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND4_VALUE,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND5_VALUE,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND6_VALUE,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND7_VALUE,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND8_VALUE,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND9_VALUE,
                                )
                                val equalizerFrequencyKeys = listOf(
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND1_FREQ,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND2_FREQ,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND3_FREQ,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND4_FREQ,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND5_FREQ,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND6_FREQ,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND7_FREQ,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND8_FREQ,
                                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_BAND9_FREQ,
                                )
                                action(
                                    key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_RESET,
                                    title = R.string.soundcore_equalizer_reset_title,
                                    summary = R.string.soundcore_equalizer_reset_summary,
                                    icon = R.drawable.ic_history,
                                    onClick = { _, device ->
                                        device?.let {
                                            val adjustableFrequencies = has(Capability.AudioEqualizer2)
                                            val bandKeys = if (adjustableFrequencies) equalizerBandKeys else equalizerBandKeys.take(getSupportedEqualizerBands().size)
                                            val prefs = GBApplication.getDevicePrefs(it).preferences
                                            prefs.edit().apply {
                                                bandKeys.forEach { key -> putInt(key, if (adjustableFrequencies) 60 else 0) }
                                                if (adjustableFrequencies) {
                                                    equalizerFrequencyKeys.forEachIndexed { index, key ->
                                                        putString(key, when (index) { 7 -> "6"; 8 -> "1"; else -> "7" })
                                                    }
                                                }
                                                apply()
                                            }
                                            val lastKey = if (adjustableFrequencies) equalizerFrequencyKeys.last() else bandKeys.lastOrNull()
                                            lastKey?.let { key -> GBApplication.deviceService(it).onSendConfiguration(key) }
                                        }
                                        true
                                    },
                                )
                                // Simple Equalizer for Sport X20
                                if (!has(Capability.AudioEqualizer2)) {
                                    getSupportedEqualizerBands().take(equalizerBandKeys.size).forEachIndexed { index, frequency ->
                                        val key = equalizerBandKeys[index]
                                        category(
                                            key = "${key}_category",
                                            title = equalizerBandTitleResource(frequency),
                                        ) {
                                            seekbar(
                                                key = key,
                                                title = R.string.soundcore_equalizer_value,
                                                icon = R.drawable.ic_graphic_eq,
                                                min = -6,
                                                max = 6,
                                                defaultValue = 0,
                                            )
                                        }
                                    }
                                }
                                // Equalizer for Motion 300
                                if (has(Capability.AudioEqualizer2)) {
                                    val bandTitles = listOf(
                                        R.string.soundcore_equalizer_band1,
                                        R.string.soundcore_equalizer_band2,
                                        R.string.soundcore_equalizer_band3,
                                        R.string.soundcore_equalizer_band4,
                                        R.string.soundcore_equalizer_band5,
                                        R.string.soundcore_equalizer_band6,
                                        R.string.soundcore_equalizer_band7,
                                        R.string.soundcore_equalizer_band8,
                                        R.string.soundcore_equalizer_band9,
                                    )
                                    val frequencyEntries = listOf(
                                        R.array.soundcore_equalizer_band1_freq_names,
                                        R.array.soundcore_equalizer_band2_freq_names,
                                        R.array.soundcore_equalizer_band3_freq_names,
                                        R.array.soundcore_equalizer_band4_freq_names,
                                        R.array.soundcore_equalizer_band5_freq_names,
                                        R.array.soundcore_equalizer_band6_freq_names,
                                        R.array.soundcore_equalizer_band7_freq_names,
                                        R.array.soundcore_equalizer_band8_freq_names,
                                        R.array.soundcore_equalizer_band9_freq_names,
                                    )
                                    equalizerBandKeys.forEachIndexed { index, key ->
                                        val band = index + 1
                                        category(
                                            key = "${key}_category",
                                            title = bandTitles[index],
                                        ) {
                                            list(
                                                key = equalizerFrequencyKeys[index],
                                                title = R.string.soundcore_equalizer_frequency,
                                                icon = R.drawable.ic_hearing,
                                                entriesRes = frequencyEntries[index],
                                                entryValuesRes = R.array.soundcore_equalizer_freq_values,
                                                defaultValue = when (band) { 8 -> "6"; 9 -> "1"; else -> "7" },
                                            )
                                            seekbar(
                                                key = key,
                                                title = R.string.soundcore_equalizer_value,
                                                icon = R.drawable.ic_graphic_eq,
                                                min = 0,
                                                max = 120,
                                                defaultValue = 60,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (has(Capability.FindIndividualEarbuds)) {
                listOf(
                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_FIND_LEFT_EARBUD to R.string.earfun_earbud_mode_left,
                    DeviceSettingsPreferenceConst.PREF_SOUNDCORE_FIND_RIGHT_EARBUD to R.string.earfun_earbud_mode_right,
                ).forEach { (key, title) ->
                    list(
                        key = key,
                        title = title,
                        summary = R.string.earfun_find_headphones,
                        icon = R.drawable.ic_action_find_lost_device,
                        entries = listOf(
                            ListEntry.Res("0", R.string.off),
                            ListEntry.Res("1", R.string.earfun_find_headphones_hint),
                        ),
                        defaultValue = "0",
                    )
                }
            }

            if (has(arrayOf(Capability.NoiseControl, Capability.AmbientSound))) {
                val ambientSoundControlEntries = buildList {
                    add(ListEntry.Res("off", R.string.sony_ambient_sound_off))
                    if (has(Capability.Transparency)) {
                        add(ListEntry.Res("transparency", R.string.prefs_active_noise_cancelling_transparency))
                    }
                    if (has(Capability.NoiseControl)) {
                        add(ListEntry.Res("noise_cancelling", R.string.sony_ambient_sound_noise_cancelling))
                    }
                    if (has(Capability.AdaptiveNoiseCancellationMode)) {
                        add(ListEntry.Res("adaptive_anc", R.string.pref_adaptive_noise_cancelling_title))
                    }
                    if (has(Capability.AmbientSound)) {
                        add(ListEntry.Res("ambient_sound", R.string.sony_ambient_sound_ambient_sound))
                    }
                }
                category(key = "pref_header_soundcore_ambient_sound_control", title = R.string.pref_header_sony_ambient_sound_control) {
                    list(
                        key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL,
                        title = R.string.sony_ambient_sound,
                        icon = R.drawable.ic_hearing,
                        entries = ambientSoundControlEntries,
                        defaultValue = "off",
                    )
                    if (has(Capability.NoiseCancellationModes)) {
                        list(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ANC_MODE,
                            title = R.string.earfun_anc_mode,
                            icon = R.drawable.ic_hearing,
                            entriesRes = R.array.soundcore_anc_mode_names,
                            entryValuesRes = R.array.soundcore_anc_mode_values,
                            defaultValue = "transport",
                        )
                    }
                    val separateAdaptiveToggle = !has(Capability.AdaptiveNoiseCancellationMode)
                    if (has(Capability.AdaptiveNoiseCancellation) && separateAdaptiveToggle) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_NOISE_CANCELLING,
                            title = R.string.pref_adaptive_noise_cancelling_title,
                            summary = R.string.pref_adaptive_noise_cancelling_summary,
                            icon = R.drawable.ic_hearing,
                            defaultValue = true,
                            disableDependentsState = true,
                        )
                    }
                    if (has(Capability.AdaptiveNoiseCancellation)) {
                        val levelRange = getNoiseCancellationLevelRange()
                        seekbar(
                            key = DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL,
                            title = R.string.prefs_active_noise_cancelling_level,
                            icon = R.drawable.ic_hearing,
                            min = levelRange.first,
                            max = levelRange.last,
                            defaultValue = levelRange.first,
                            dependency = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ADAPTIVE_NOISE_CANCELLING,
                        )
                    }
                    if (has(Capability.WindNoiseReduction)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION,
                            title = R.string.sony_ambient_sound_wind_noise_reduction,
                            icon = R.drawable.ic_block,
                        )
                    }
                    if (has(Capability.VocalMode)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TRANSPARENCY_VOCAL_MODE,
                            title = R.string.sony_ambient_sound_focus_voice,
                            icon = R.drawable.ic_voice,
                        )
                    }
                }
            }
            if (has(arrayOf(Capability.WearingDetection, Capability.WearingTone, Capability.FitTest, Capability.AutoPowerOff, Capability.BatteryLowTone, Capability.VoicePrompts, Capability.ButtonBrightness))) {
                category(key = "pref_header_soundcore_general", title = R.string.pref_header_general) {
                    if (has(Capability.WearingDetection)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WEARING_DETECTION,
                            title = R.string.nothing_prefs_inear_title,
                            summary = R.string.nothing_prefs_inear_summary,
                            defaultValue = false,
                        )
                    }
                    if (has(Capability.WearingTone)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WEARING_TONE,
                            title = R.string.pref_wearing_tone,
                            summary = R.string.pref_wearing_tone_summary,
                            defaultValue = false,
                        )
                    }
                    if (has(Capability.BatteryLowTone)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_BATTERY_LOW_TONE,
                            title = R.string.soundcore_battery_low_tone_title,
                            summary = R.string.soundcore_battery_low_tone_summary,
                            defaultValue = false,
                        )
                    }
                    if (has(Capability.VoicePrompts)) {
                        switchSetting(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_VOICE_PROMPTS,
                            title = R.string.soundcore_voice_prompts,
                            icon = R.drawable.ic_voice,
                            defaultValue = true,
                            connectedOnly = false,
                        )
                    }
                    if (has(Capability.ButtonBrightness)) {
                        list(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_BUTTON_BRIGHTNESS,
                            title = R.string.soundcore_button_brightness,
                            icon = R.drawable.ic_wb_sunny,
                            entries = listOf(
                                ListEntry.Res("0", R.string.off),
                                ListEntry.Res("20", R.string.low),
                                ListEntry.Res("70", R.string.medium),
                                ListEntry.Res("100", R.string.high),
                            ),
                            defaultValue = "70",
                            connectedOnly = false,
                        )
                    }
                    if (has(Capability.FitTest)) {
                        action(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_FIT_TEST,
                            title = R.string.pref_soundcore_fit_test_title,
                            summary = R.string.pref_soundcore_fit_test_summary,
                            icon = R.drawable.ic_verified,
                            onClick = { _, selectedDevice ->
                                selectedDevice?.let {
                                    GBApplication.deviceService(it).onSendConfiguration(
                                        DeviceSettingsPreferenceConst.PREF_SOUNDCORE_FIT_TEST
                                    )
                                }
                                true
                            },
                        )
                    }
                    if (has(Capability.AutoPowerOff)) {
                        list(
                            key = DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AUTO_POWER_OFF,
                            title = R.string.soundcore_auto_power_off_title,
                            summary = R.string.soundcore_auto_power_off_summary,
                            icon = R.drawable.ic_power_settings_new,
                            entries = getAutoPowerOffEntries(),
                            defaultValue = "3",
                        )
                    }
                }
            }

            screen(
                key = DeviceSpecificSettingsScreen.CONNECTION.key,
                title = R.string.pref_header_connection,
                icon = R.drawable.ic_mtu,
            ) {
                if (has(Capability.Multipoint)) {
                    multipointPairing()
                }
            }

            xmlScreen(
                DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS,
                R.xml.devicesettings_headphones,
                connectedOnly = false,
            )
        }
    }

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.EARBUDS
}
