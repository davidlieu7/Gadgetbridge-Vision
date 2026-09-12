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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

/**
 * The two feature tables a watch reports, kept as the raw bytes it sent plus the decoded flags.
 * Devices that never answer the queries end up with empty tables, which is what drives the
 * fallback to the shorter, older payload variants everywhere else.
 */
public class VeryFitCapabilities {
    public static final String PREF_FEATURES = "veryfit_features";
    public static final String PREF_FEATURES_EXTRA = "veryfit_features_extra";
    public static final String PREF_LIMITS = "veryfit_limits";

    private static final int DEFAULT_ALARM_SLOTS = 3;

    private final byte[] base;
    private final byte[] extra;
    private final byte[] limits;
    private final Set<VeryFitFeature> features = EnumSet.noneOf(VeryFitFeature.class);

    public VeryFitCapabilities(final byte[] base, final byte[] extra, final byte[] limits) {
        this.base = base != null ? base : new byte[0];
        this.extra = extra != null ? extra : new byte[0];
        this.limits = limits != null ? limits : new byte[0];

        for (final VeryFitFeature feature : VeryFitFeature.values()) {
            final byte[] table = feature.getTable() == VeryFitFeature.Table.BASE ? this.base : this.extra;
            if (feature.isSetIn(table)) {
                features.add(feature);
            }
        }
    }

    public static VeryFitCapabilities fromPreferences(final Prefs prefs) {
        return new VeryFitCapabilities(
                fromHex(prefs.getString(PREF_FEATURES, "")),
                fromHex(prefs.getString(PREF_FEATURES_EXTRA, "")),
                fromHex(prefs.getString(PREF_LIMITS, ""))
        );
    }

    public boolean supports(final VeryFitFeature feature) {
        return features.contains(feature);
    }

    public Set<VeryFitFeature> getFeatures() {
        return Collections.unmodifiableSet(features);
    }

    /** True once the watch has told us anything about itself. */
    public boolean isKnown() {
        return base.length != 0;
    }

    public int getAlarmSlots() {
        return base.length > 1 ? base[1] & 0xff : DEFAULT_ALARM_SLOTS;
    }

    /** Favourite contacts the watch has room for; none until it has said. */
    public int getContactSlots() {
        return limits.length > 1 ? (limits[0] & 0xff) | ((limits[1] & 0xff) << 8) : 0;
    }

    public String getBaseHex() {
        return GB.hexdump(base);
    }

    public String getExtraHex() {
        return GB.hexdump(extra);
    }

    private static byte[] fromHex(final String hex) {
        if (StringUtils.isNullOrEmpty(hex)) {
            return new byte[0];
        }
        try {
            return GB.hexStringToByteArray(hex);
        } catch (final Exception e) {
            return new byte[0];
        }
    }
}
