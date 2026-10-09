/*  Copyright (C) 2026

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package nodomain.freeyourgadget.gadgetbridge.devices.jbl

import androidx.annotation.StringRes

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.JblTuneBuds2Support
import nodomain.freeyourgadget.gadgetbridge.util.Prefs

fun jblTuneBuds2Settings(): DeviceSettingsSpec = deviceSettings {
    category(
        key = "pref_jbl_tune_buds_2_category_noise_control",
        title = R.string.prefs_noise_control,
    ) {
        list(
            key = JblTuneBuds2Support.PREF_SOUND_MODE,
            title = R.string.prefs_noise_control,
            icon = R.drawable.ic_hearing,
            entriesRes = R.array.jbl_tune_buds_2_sound_mode_entries,
            entryValuesRes = R.array.jbl_tune_buds_2_sound_mode_values,
            defaultValue = SOUND_MODE_OFF,
        )
        switchSetting(
            key = JblTuneBuds2Support.PREF_ADAPTIVE_ANC,
            title = R.string.pref_adaptive_noise_cancelling_title,
            summary = R.string.pref_adaptive_noise_cancelling_summary,
            icon = R.drawable.ic_hearing,
            defaultValue = false,
            disableDependentsState = true,
            visibleWhen = prefIs(JblTuneBuds2Support.PREF_SOUND_MODE, SOUND_MODE_ANC, SOUND_MODE_OFF),
        )
        switchSetting(
            key = JblTuneBuds2Support.PREF_AUTO_COMPENSATION,
            title = R.string.pref_leakage_compensation,
            summary = R.string.pref_leakage_compensation_summary,
            icon = R.drawable.ic_hearing,
            defaultValue = true,
            visibleWhen = prefIs(JblTuneBuds2Support.PREF_SOUND_MODE, SOUND_MODE_ANC, SOUND_MODE_OFF),
        )
        seekbar(
            key = JblTuneBuds2Support.PREF_ANC_LEVEL,
            title = R.string.prefs_active_noise_cancelling_level,
            icon = R.drawable.ic_hearing,
            min = 1,
            max = 7,
            defaultValue = 4,
            dependency = JblTuneBuds2Support.PREF_ADAPTIVE_ANC,
            visibleWhen = prefIs(JblTuneBuds2Support.PREF_SOUND_MODE, SOUND_MODE_ANC, SOUND_MODE_OFF),
        )
        seekbar(
            key = JblTuneBuds2Support.PREF_AMBIENT_LEVEL,
            title = R.string.pref_transparency_level,
            icon = R.drawable.ic_surround,
            min = 1,
            max = 7,
            defaultValue = 4,
            visibleWhen = prefIs(JblTuneBuds2Support.PREF_SOUND_MODE, SOUND_MODE_AMBIENT, SOUND_MODE_OFF),
        )
    }

    category(
        key = "pref_jbl_tune_buds_2_category_equalizer",
        title = R.string.prefs_equalizer,
        visibleWhen = soundModeIsNot(SOUND_MODE_TALK_THRU),
    ) {
        for (band in EQ_BANDS) {
            seekbar(
                key = band.key,
                title = band.title,
                min = -6,
                max = 6,
                defaultValue = 0,
            )
        }
    }

    category(
        key = "pref_jbl_tune_buds_2_category_audio",
        title = R.string.pref_header_audio,
    ) {
        list(
            key = JblTuneBuds2Support.PREF_SPATIAL_AUDIO,
            title = R.string.pref_spatial_audio,
            icon = R.drawable.ic_surround,
            entriesRes = R.array.jbl_tune_buds_2_spatial_audio_entries,
            entryValuesRes = R.array.jbl_tune_buds_2_spatial_audio_values,
            defaultValue = "0",
            visibleWhen = soundModeIsNot(SOUND_MODE_TALK_THRU),
        )
        list(
            key = JblTuneBuds2Support.PREF_LATENCY_MODE,
            title = R.string.pref_latency_mode,
            icon = R.drawable.ic_volume_up,
            entriesRes = R.array.jbl_tune_buds_2_latency_mode_entries,
            entryValuesRes = R.array.jbl_tune_buds_2_latency_mode_values,
            defaultValue = "0",
        )
        switchSetting(
            key = JblTuneBuds2Support.PREF_SAFE_SOUND,
            title = R.string.pref_safe_sound,
            summary = R.string.pref_safe_sound_summary,
            icon = R.drawable.ic_hearing,
            defaultValue = false,
        )
        seekbar(
            key = JblTuneBuds2Support.PREF_CHANNEL_BALANCE,
            title = R.string.pref_channel_balance,
            icon = R.drawable.ic_surround,
            min = -10,
            max = 10,
            defaultValue = 0,
        )
    }

    category(
        key = "pref_jbl_tune_buds_2_category_touch",
        title = R.string.prefs_touch_lock_buds2,
    ) {
        list(
            key = JblTuneBuds2Support.PREF_GESTURE_LEFT,
            title = R.string.left_earbud,
            icon = R.drawable.ic_hearing,
            entriesRes = R.array.jbl_tune_buds_2_gesture_entries,
            entryValuesRes = R.array.jbl_tune_buds_2_gesture_values,
            defaultValue = GESTURE_AMBIENT_SOUND_CONTROL,
        )
        list(
            key = JblTuneBuds2Support.PREF_GESTURE_RIGHT,
            title = R.string.right_earbud,
            icon = R.drawable.ic_hearing,
            entriesRes = R.array.jbl_tune_buds_2_gesture_entries,
            entryValuesRes = R.array.jbl_tune_buds_2_gesture_values,
            defaultValue = GESTURE_PLAYBACK_CONTROL,
        )
        list(
            key = JblTuneBuds2Support.PREF_ASC_CYCLE,
            title = R.string.pref_jbl_tune_buds_2_asc_cycle,
            icon = R.drawable.ic_surround,
            entriesRes = R.array.jbl_tune_buds_2_asc_cycle_entries,
            entryValuesRes = R.array.jbl_tune_buds_2_asc_cycle_values,
            defaultValue = ASC_CYCLE_ALL,
            visibleWhen = { prefs ->
                prefs.getString(JblTuneBuds2Support.PREF_GESTURE_LEFT, GESTURE_AMBIENT_SOUND_CONTROL) ==
                    GESTURE_AMBIENT_SOUND_CONTROL ||
                    prefs.getString(JblTuneBuds2Support.PREF_GESTURE_RIGHT, GESTURE_PLAYBACK_CONTROL) ==
                    GESTURE_AMBIENT_SOUND_CONTROL
            },
        )
    }

    category(
        key = "pref_jbl_tune_buds_2_category_calls",
        title = R.string.phone_calls,
    ) {
        list(
            key = JblTuneBuds2Support.PREF_CALL_SOUND_EFFECT,
            title = R.string.pref_call_sound_effect,
            icon = R.drawable.ic_equalizer,
            entriesRes = R.array.jbl_tune_buds_2_call_sound_effect_entries,
            entryValuesRes = R.array.jbl_tune_buds_2_call_sound_effect_values,
            defaultValue = "0",
        )
        switchSetting(
            key = JblTuneBuds2Support.PREF_CALL_VOLUME_OPTIMIZER,
            title = R.string.pref_call_volume_optimizer,
            summary = R.string.pref_call_volume_optimizer_summary,
            icon = R.drawable.ic_volume_up,
            defaultValue = false,
        )
        list(
            key = JblTuneBuds2Support.PREF_SIDETONE,
            title = R.string.pref_sidetone,
            icon = R.drawable.ic_phone,
            entriesRes = R.array.jbl_tune_buds_2_sidetone_entries,
            entryValuesRes = R.array.jbl_tune_buds_2_sidetone_values,
            defaultValue = "0",
        )
    }

    category(
        key = "pref_jbl_tune_buds_2_category_system",
        title = R.string.pref_header_system,
    ) {
        list(
            key = JblTuneBuds2Support.PREF_VOICE_PROMPTS,
            title = R.string.pref_voice_prompts,
            icon = R.drawable.ic_volume_up,
            entriesRes = R.array.jbl_tune_buds_2_voice_prompt_entries,
            entryValuesRes = R.array.jbl_tune_buds_2_voice_prompt_values,
            defaultValue = "1",
        )
        list(
            key = JblTuneBuds2Support.PREF_AUTO_POWER_OFF,
            title = R.string.soundcore_auto_power_off_title,
            icon = R.drawable.ic_timer,
            entries = listOf(
                ListEntry.Res("0", R.string.never),
                ListEntry.Res("15", R.string.minutes_15),
                ListEntry.Res("30", R.string.minutes_30),
                ListEntry.Res("60", R.string.hours_1),
                ListEntry.Res("120", R.string.hours_2),
            ),
            defaultValue = "0",
        )
    }
}

private const val SOUND_MODE_OFF = "0"
private const val SOUND_MODE_ANC = "1"
private const val SOUND_MODE_TALK_THRU = "2"
private const val SOUND_MODE_AMBIENT = "3"

private const val GESTURE_AMBIENT_SOUND_CONTROL = "1"
private const val GESTURE_PLAYBACK_CONTROL = "2"
private const val ASC_CYCLE_ALL = "9"

private data class EqBand(val key: String, @StringRes val title: Int)

private val EQ_BANDS = listOf(
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_32, R.string.jbl_tune_buds_2_equalizer_band_32),
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_64, R.string.jbl_tune_buds_2_equalizer_band_64),
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_125, R.string.jbl_tune_buds_2_equalizer_band_125),
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_250, R.string.jbl_tune_buds_2_equalizer_band_250),
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_500, R.string.jbl_tune_buds_2_equalizer_band_500),
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_1K, R.string.jbl_tune_buds_2_equalizer_band_1k),
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_2K, R.string.jbl_tune_buds_2_equalizer_band_2k),
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_4K, R.string.jbl_tune_buds_2_equalizer_band_4k),
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_8K, R.string.jbl_tune_buds_2_equalizer_band_8k),
    EqBand(JblTuneBuds2Support.PREF_EQ_BAND_16K, R.string.jbl_tune_buds_2_equalizer_band_16k),
)

private fun prefIs(key: String, value: String, fallback: String): (Prefs) -> Boolean =
    { prefs -> prefs.getString(key, fallback) == value }

private fun soundModeIsNot(value: String): (Prefs) -> Boolean =
    { prefs -> prefs.getString(JblTuneBuds2Support.PREF_SOUND_MODE, SOUND_MODE_OFF) != value }