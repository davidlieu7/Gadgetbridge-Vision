/*  Copyright (C) 2026 pwnie69

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
package nodomain.freeyourgadget.gadgetbridge.devices.galaxywatch7;

import android.os.Parcel;

import androidx.preference.Preference;

import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Wires the Galaxy Watch7 settings switches to the device support: without a change handler, a
 * plain preference change never reaches {@code onSendConfiguration}, so toggles would not be sent
 * to the watch. {@code addPreferenceHandlerFor} attaches the listener that dispatches the change.
 */
public class GalaxyWatch7SettingsCustomizer implements DeviceSpecificSettingsCustomizer {
    private static final String[] SENT_PREFERENCES = new String[]{
            "pref_galaxywatch7_advanced_action_button_siren_enabled",
            "pref_galaxywatch7_advanced_gestures_dismiss_alerts_and_reject_calls",
            "pref_galaxywatch7_advanced_gestures_quick_action",
            "pref_galaxywatch7_advanced_gestures_quick_action_watchface_second_action_enabled",
            "pref_galaxywatch7_advanced_gestures_raise_to_talk_enabled",
            "pref_galaxywatch7_advanced_labs_quicklaunch_enabled",
            "pref_galaxywatch7_advanced_media_control_auto_launch_media_controls",
            "pref_galaxywatch7_advanced_media_control_show_ongoing_icon",
            "pref_galaxywatch7_bedtime_turn_on_scheduled",
            "pref_galaxywatch7_display_always_on_display",
            "pref_galaxywatch7_display_auto_low_brightness",
            "pref_galaxywatch7_display_bold_font",
            "pref_galaxywatch7_display_show_charging_info",
            "pref_galaxywatch7_display_touch_sensitivity",
            "pref_galaxywatch7_display_wake_on_touch_screen",
            "pref_galaxywatch7_display_wake_on_turning_bezel",
            "pref_galaxywatch7_display_wake_on_wrist_raised",
            "pref_galaxywatch7_general_touch_bezel",
            "pref_galaxywatch7_general_touch_bezel_feedback",
            "pref_galaxywatch7_sound_settings_system_sounds_charging_sound",
            "pref_galaxywatch7_sound_settings_system_sounds_dialing_keypad_tone",
            "pref_galaxywatch7_sound_settings_system_sounds_hourly_chime",
            "pref_galaxywatch7_sound_settings_system_sounds_samsung_keyboard",
            "pref_galaxywatch7_sound_settings_system_sounds_ticking_sound",
            "pref_galaxywatch7_sound_settings_system_sounds_touch_sound",
            "pref_galaxywatch7_sound_settings_system_sounds_unlock_sound",
            "pref_galaxywatch7_sound_settings_system_vibration_charging",
            "pref_galaxywatch7_sound_settings_system_vibration_dialing_keypad",
            "pref_galaxywatch7_sound_settings_system_vibration_samsung_keyboard",
    };

    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        for (final String prefKey : SENT_PREFERENCES) {
            handler.addPreferenceHandlerFor(prefKey);
        }
    }

    @Override
    public Set<String> getPreferenceKeysWithSummary() {
        return Collections.emptySet();
    }

    public static final Creator<GalaxyWatch7SettingsCustomizer> CREATOR = new Creator<GalaxyWatch7SettingsCustomizer>() {
        @Override
        public GalaxyWatch7SettingsCustomizer createFromParcel(final Parcel in) {
            return new GalaxyWatch7SettingsCustomizer();
        }

        @Override
        public GalaxyWatch7SettingsCustomizer[] newArray(final int size) {
            return new GalaxyWatch7SettingsCustomizer[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
    }
}
