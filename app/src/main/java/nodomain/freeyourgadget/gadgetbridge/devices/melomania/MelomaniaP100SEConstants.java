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

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;

/**
 * Shared preference keys and value names for the Cambridge Audio Melomania P100 SE.
 */
public final class MelomaniaP100SEConstants {
    public static final String ANC_MODE_OFF = "off";
    public static final String ANC_MODE_ANC = "anc";
    public static final String ANC_MODE_TRANSPARENCY = "transparency";

    public static final String LANGUAGE_TONES = "tones";
    public static final String LANGUAGE_ENGLISH = "english";
    public static final String LANGUAGE_GERMAN = "german";
    public static final String LANGUAGE_SPANISH = "spanish";
    public static final String LANGUAGE_ITALIAN = "italian";
    public static final String LANGUAGE_JAPANESE = "japanese";
    public static final String LANGUAGE_KOREAN = "korean";
    public static final String LANGUAGE_CANTONESE = "cantonese";
    public static final String LANGUAGE_MANDARIN = "mandarin";
    public static final String LANGUAGE_FRENCH = "french";
    public static final String LANGUAGE_CELEBRITY = "celebrity";
    public static final String LANGUAGE_OFF = "off";

    public static final String[] LANGUAGE_NAMES = {
            LANGUAGE_TONES,
            LANGUAGE_ENGLISH,
            LANGUAGE_GERMAN,
            LANGUAGE_SPANISH,
            LANGUAGE_ITALIAN,
            LANGUAGE_JAPANESE,
            LANGUAGE_KOREAN,
            LANGUAGE_CANTONESE,
            LANGUAGE_MANDARIN,
            LANGUAGE_FRENCH,
            LANGUAGE_CELEBRITY,
            LANGUAGE_OFF,
    };

    public static final String[] CONFIGURATION_KEYS = {
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_MODE,
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_NORMAL,
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_NOISE_CANCELLING,
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_TRANSPARENCY,
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_GAMING_MODE,
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_WEAR_DETECTION,
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_LOUDNESS,
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_SIDE_TONE,
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_AUTO_POWER_DOWN,
            DeviceSettingsPreferenceConst.PREF_MELOMANIA_LANGUAGE,
    };

    public static int languageId(final String name) {
        for (int i = 0; i < LANGUAGE_NAMES.length; i++) {
            if (LANGUAGE_NAMES[i].equals(name)) {
                return i;
            }
        }
        return 1; // english
    }

    public static String languageName(final int id) {
        if (id >= 0 && id < LANGUAGE_NAMES.length) {
            return LANGUAGE_NAMES[id];
        }
        if (id == 255) {
            return LANGUAGE_OFF;
        }
        return "unknown(" + id + ")";
    }

    public static String ancModeName(final int mode) {
        switch (mode) {
            case 1:
                return ANC_MODE_ANC;
            case 2:
                return ANC_MODE_TRANSPARENCY;
            default:
                return ANC_MODE_OFF;
        }
    }

    public static int ancModeId(final String name) {
        if (ANC_MODE_ANC.equals(name)) {
            return 1;
        }
        if (ANC_MODE_TRANSPARENCY.equals(name)) {
            return 2;
        }
        return 0;
    }

    private MelomaniaP100SEConstants() {
    }
}
