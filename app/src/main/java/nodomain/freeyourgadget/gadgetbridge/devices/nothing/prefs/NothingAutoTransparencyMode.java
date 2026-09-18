/*  Copyright (C) 2026 Jackson Attwood
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

package nodomain.freeyourgadget.gadgetbridge.devices.nothing.prefs;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.LabeledEntry;

public enum NothingAutoTransparencyMode implements LabeledEntry {
    OFF(0x00, R.string.sony_button_mode_off),
    ON(0x01, R.string.on);

    private final int code;
    @StringRes
    private final int label;

    NothingAutoTransparencyMode(final int code, @StringRes final int label) {
        this.code = code;
        this.label = label;
    }

    @Nullable
    public static NothingAutoTransparencyMode fromCode(final int code) {
        for (NothingAutoTransparencyMode type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }

    public static NothingAutoTransparencyMode fromPreferences(final SharedPreferences prefs, final String key) {
        return NothingAutoTransparencyMode.valueOf(prefs.getString(key, NothingAudioMode.OFF.name()).toUpperCase(Locale.ROOT));
    }

    @Override
    public int getLabel() {
        return label;
    }

    public int getCode() {
        return code;
    }

    public Map<String, Object> toPreferences(String key) {
        return new HashMap<>() {{
            put(key, name().toLowerCase(Locale.ROOT));
        }};
    }
}