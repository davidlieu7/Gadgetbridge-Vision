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
            "pref_galaxywatch7_always_on_display",
            "pref_galaxywatch7_tilt_to_wake",
            "pref_galaxywatch7_touch_to_wake",
            "pref_galaxywatch7_show_unread_dot",
            "pref_galaxywatch7_use_mode_ringer",
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
