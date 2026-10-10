/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.devices.veryfit;

import android.os.Parcel;

import androidx.preference.Preference;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.XDatePreference;

/**
 * Turns the pair/unpair buttons for calls, and the cycle settings, into configuration changes the
 * support acts on.
 */
public class VeryFitSettingsCustomizer implements DeviceSpecificSettingsCustomizer {
    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        for (final String key : new String[]{VeryFitConstants.PREF_CALLS_PAIR, VeryFitConstants.PREF_CALLS_UNPAIR}) {
            final Preference button = handler.findPreference(key);
            if (button != null) {
                button.setOnPreferenceClickListener(preference -> {
                    handler.notifyPreferenceChanged(key);
                    return true;
                });
            }
        }
        for (final String key : new String[]{
                VeryFitConstants.PREF_CYCLE_ENABLED,
                VeryFitConstants.PREF_CYCLE_PERIOD_DAYS,
                VeryFitConstants.PREF_CYCLE_LENGTH_DAYS,
                VeryFitConstants.PREF_CYCLE_LAST_PERIOD,
                VeryFitConstants.PREF_CYCLE_REMINDER,
        }) {
            handler.addPreferenceHandlerFor(key);
        }

        // A date preference without a stored value keeps its 1970 placeholder and persists that
        // on first use, so start it on what is stored, or on today.
        final XDatePreference lastPeriod = handler.findPreference(VeryFitConstants.PREF_CYCLE_LAST_PERIOD);
        if (lastPeriod != null) {
            final LocalDate date = prefs.getLocalDate(VeryFitConstants.PREF_CYCLE_LAST_PERIOD, LocalDate.now().toString());
            lastPeriod.setValue(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        }
    }

    @Override
    public Set<String> getPreferenceKeysWithSummary() {
        return Collections.emptySet();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
    }

    public static final Creator<VeryFitSettingsCustomizer> CREATOR = new Creator<VeryFitSettingsCustomizer>() {
        @Override
        public VeryFitSettingsCustomizer createFromParcel(final Parcel in) {
            return new VeryFitSettingsCustomizer();
        }

        @Override
        public VeryFitSettingsCustomizer[] newArray(final int size) {
            return new VeryFitSettingsCustomizer[size];
        }
    };
}
