/*  Copyright (C) 2026 Vitaliy Tomin

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

package nodomain.freeyourgadget.gadgetbridge.devices.samsung;

import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.preference.Preference;

import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsUtils;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthCycle;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3MenuOrder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3PhoneAlarm;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Settings;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Sos;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Fills the tiles / quick panel / apps sortable lists with the items the band
 * itself reported (stored by the device support as possible values and
 * labels), hides any the band hasn't reported yet, and forwards changes - for
 * those and for the other device-specific keys in {@link Fit3Settings},
 * {@link Fit3Sos}, {@link Fit3HealthCycle} and
 * {@link Fit3PhoneAlarm}.
 */
public class SamsungFit3SettingsCustomizer implements DeviceSpecificSettingsCustomizer {
    public static final Creator<SamsungFit3SettingsCustomizer> CREATOR = new Creator<SamsungFit3SettingsCustomizer>() {
        @Override
        public SamsungFit3SettingsCustomizer createFromParcel(final Parcel in) {
            return new SamsungFit3SettingsCustomizer();
        }

        @Override
        public SamsungFit3SettingsCustomizer[] newArray(final int size) {
            return new SamsungFit3SettingsCustomizer[size];
        }
    };

    @Override
    public void onPreferenceChange(final Preference preference, final DeviceSpecificSettingsHandler handler) {
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs, final String rootKey) {
        for (Fit3MenuOrder.Menu menu : Fit3MenuOrder.Menu.values()) {
            DeviceSettingsUtils.populateOrHideListPreference(menu.prefKey, handler, prefs);
            handler.addPreferenceHandlerFor(menu.prefKey);
        }
        handler.addPreferenceHandlerFor(Fit3Settings.PREF_DOUBLE_PRESS);
        handler.addPreferenceHandlerFor(Fit3Settings.PREF_DISCONNECTION_ALERT);
        handler.addPreferenceHandlerFor(Fit3Settings.PREF_STATUS_INDICATOR);
        handler.addPreferenceHandlerFor(Fit3Sos.PREF_FALL_DETECTION);
        handler.addPreferenceHandlerFor(Fit3Sos.PREF_WHEN_TO_DETECT);
        handler.addPreferenceHandlerFor(Fit3Sos.PREF_SEND_SOS);
        handler.addPreferenceHandlerFor(Fit3Sos.PREF_COUNTDOWN);
        handler.addPreferenceHandlerFor(Fit3HealthCycle.PREF_ENABLE);
        handler.addPreferenceHandlerFor(Fit3HealthCycle.PREF_LAST_START);
        handler.addPreferenceHandlerFor(Fit3HealthCycle.PREF_PERIOD_DAYS);
        handler.addPreferenceHandlerFor(Fit3HealthCycle.PREF_CYCLE_DAYS);
        handler.addPreferenceHandlerFor(Fit3HealthCycle.PREF_FACTOR);
        handler.addPreferenceHandlerFor(Fit3PhoneAlarm.PREF_ENABLE);
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
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
    }
}
