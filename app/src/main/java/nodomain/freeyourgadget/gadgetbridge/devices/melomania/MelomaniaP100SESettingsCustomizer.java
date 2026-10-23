/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.devices.melomania;

import android.content.SharedPreferences;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.preference.Preference;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class MelomaniaP100SESettingsCustomizer implements DeviceSpecificSettingsCustomizer {
    public static final Parcelable.Creator<MelomaniaP100SESettingsCustomizer> CREATOR =
            new Parcelable.Creator<MelomaniaP100SESettingsCustomizer>() {
                @Override
                public MelomaniaP100SESettingsCustomizer createFromParcel(final Parcel in) {
                    return new MelomaniaP100SESettingsCustomizer();
                }

                @Override
                public MelomaniaP100SESettingsCustomizer[] newArray(final int size) {
                    return new MelomaniaP100SESettingsCustomizer[size];
                }
            };

    @Override
    public void onPreferenceChange(final Preference preference,
                                   final DeviceSpecificSettingsHandler handler) {
        if (DeviceSettingsPreferenceConst.PREF_MELOMANIA_CODEC.equals(preference.getKey())) {
            updateCodecSummary(preference, handler);
        }
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler,
                                  final Prefs prefs,
                                  final String rootKey) {
        for (final String key : MelomaniaP100SEConstants.CONFIGURATION_KEYS) {
            handler.addPreferenceHandlerFor(key);
        }

        final Preference codec = handler.findPreference(DeviceSettingsPreferenceConst.PREF_MELOMANIA_CODEC);
        if (codec != null) {
            updateCodecSummary(codec, handler);
        }
    }

    private void updateCodecSummary(final Preference preference,
                                    final DeviceSpecificSettingsHandler handler) {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(
                handler.getDevice().getAddress());
        preference.setSummary(prefs.getString(DeviceSettingsPreferenceConst.PREF_MELOMANIA_CODEC,
                handler.getContext().getString(R.string.melomania_unknown)));
    }

    @Override
    public Set<String> getPreferenceKeysWithSummary() {
        final Set<String> keys = new LinkedHashSet<>();
        keys.add(DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_MODE);
        keys.add(DeviceSettingsPreferenceConst.PREF_MELOMANIA_AUTO_POWER_DOWN);
        keys.add(DeviceSettingsPreferenceConst.PREF_MELOMANIA_LANGUAGE);
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        // no state
    }
}
