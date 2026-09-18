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

public enum NothingAudioCodec implements LabeledEntry {
    AAC(0x00, R.string.prefs_codec_aac), // Or generic string resource
    LDAC(0x02, R.string.prefs_codec_ldac);

    private final int code;
    @StringRes
    private final int label;

    NothingAudioCodec(final int code, @StringRes final int label) {
        this.code = code;
        this.label = label;
    }

    @Nullable
    public static NothingAudioCodec fromCode(final int code) {
        for (NothingAudioCodec type : values()) {
            if (type.code == code) return type;
        }
        return null;
    }

    public static NothingAudioCodec fromPreferences(final SharedPreferences prefs, final String key) {
        return NothingAudioCodec.valueOf(prefs.getString(key, NothingAudioCodec.AAC.name()).toUpperCase(Locale.ROOT));
    }

    @Override
    public int getLabel() { return label; }

    public int getCode() { return code; }

    public Map<String, Object> toPreferences(String key) {
        return new HashMap<>() {{
            put(key, name().toLowerCase(Locale.ROOT));
        }};
    }
}