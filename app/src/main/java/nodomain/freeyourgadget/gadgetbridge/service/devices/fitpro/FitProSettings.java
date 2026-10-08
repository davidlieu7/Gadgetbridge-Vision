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

package nodomain.freeyourgadget.gadgetbridge.service.devices.fitpro;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_END;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_START;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_INACTIVITY_END;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_INACTIVITY_START;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD_EXTENDED;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_VIBRATION_ENABLE;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProConstants;

final class FitProSettings {
    private FitProSettings() {
    }

    static Map<String, Object> decode(final byte[] data) {
        if (data == null || data.length < 8 || data[0] != FitProConstants.DATA_HEADER ||
                unsignedShort(data, 1) != data.length - 3 ||
                data[3] != FitProConstants.CMD_GROUP_REQUEST_DATA || data[4] != 1 ||
                unsignedShort(data, 6) != data.length - 8) {
            return Collections.emptyMap();
        }
        return switch (data[5]) {
            case FitProConstants.CMD_GET_DISPLAY_ON_LIFT -> decodeDisplayOnLift(data);
            case FitProConstants.CMD_GET_LONG_SIT_REMINDER -> decodeLongSitReminder(data);
            case FitProConstants.CMD_GET_DEVICE_VIBRATIONS -> decodeVibrations(data);
            default -> Collections.emptyMap();
        };
    }

    private static boolean isBoolean(final byte value) {
        return value == 0 || value == 1;
    }

    private static Map<String, Object> decodeVibrations(final byte[] data) {
        if (data.length != 16 || !isBoolean(data[11])) {
            return Collections.emptyMap();
        }
        return Collections.singletonMap(PREF_VIBRATION_ENABLE, data[11] == 1);
    }

    private static Map<String, Object> decodeDisplayOnLift(final byte[] data) {
        if (data.length != 13 || !isBoolean(data[8])) {
            return Collections.emptyMap();
        }
        final int start = unsignedShort(data, 9);
        final int end = unsignedShort(data, 11);
        if (start >= 24 * 60 || end >= 24 * 60) {
            return Collections.emptyMap();
        }
        final Map<String, Object> preferences = new HashMap<>();
        preferences.put(PREF_ACTIVATE_DISPLAY_ON_LIFT, data[8] == 1 ? "scheduled" : "off");
        preferences.put(PREF_DISPLAY_ON_LIFT_START, formatTime(start));
        preferences.put(PREF_DISPLAY_ON_LIFT_END, formatTime(end));
        return preferences;
    }

    private static Map<String, Object> decodeLongSitReminder(final byte[] data) {
        if (data.length != 16 || !isBoolean(data[9]) ||
                data[12] < 3 || data[12] > 8 || (data[13] & 0xff) >= 24 ||
                (data[14] & 0xff) >= 24 || data[15] != 0x7f) {
            return Collections.emptyMap();
        }
        final Map<String, Object> preferences = new HashMap<>();
        preferences.put(PREF_INACTIVITY_ENABLE, data[9] == 1);
        preferences.put(PREF_INACTIVITY_THRESHOLD_EXTENDED, Integer.toString(data[12]));
        preferences.put(PREF_INACTIVITY_START, formatTime((data[13] & 0xff) * 60));
        preferences.put(PREF_INACTIVITY_END, formatTime((data[14] & 0xff) * 60));
        return preferences;
    }

    private static int unsignedShort(final byte[] data, final int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static String formatTime(final int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
