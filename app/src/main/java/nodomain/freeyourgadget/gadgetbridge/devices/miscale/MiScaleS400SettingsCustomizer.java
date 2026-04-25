/*  Copyright (C) 2026 Viktor Karpov

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
package nodomain.freeyourgadget.gadgetbridge.devices.miscale;

import android.os.Parcel;
import android.text.InputType;

import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.miscale.MiScaleS400DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class MiScaleS400SettingsCustomizer implements DeviceSpecificSettingsCustomizer {
    public static final Creator<MiScaleS400SettingsCustomizer> CREATOR = new Creator<>() {
        @Override
        public MiScaleS400SettingsCustomizer createFromParcel(final Parcel in) {
            return new MiScaleS400SettingsCustomizer();
        }

        @Override
        public MiScaleS400SettingsCustomizer[] newArray(final int size) {
            return new MiScaleS400SettingsCustomizer[size];
        }
    };

    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        final EditTextPreference pref = handler.findPreference(MiScaleS400DeviceSupport.PREF_MISCALE_S400_BIND_KEY);
        if (pref != null) {
            pref.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                editText.setSelection(editText.getText().length());
            });
        }
        final EditTextPreference profile = handler.findPreference(MiScaleS400DeviceSupport.PREF_MISCALE_S400_PROFILE);
        if (profile != null) {
            profile.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setSelection(editText.getText().length());
            });
        }
    }

    @Override
    public Set<String> getPreferenceKeysWithSummary() {
        return Collections.singleton(MiScaleS400DeviceSupport.PREF_MISCALE_S400_PROFILE);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
    }
}
