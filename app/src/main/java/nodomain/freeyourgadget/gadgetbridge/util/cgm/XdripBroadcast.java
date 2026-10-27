/*  Copyright (C) 2026 ChrisB85

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
package nodomain.freeyourgadget.gadgetbridge.util.cgm;

import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * The glucose broadcast of xDrip+ ("Broadcast locally" in its Inter-app settings).
 * Names and units as in xDrip+ Intents and BroadcastGlucose.
 */
public final class XdripBroadcast {
    public static final String ACTION_BG_ESTIMATE = "com.eveningoutpost.dexdrip.BgEstimate";

    private static final String EXTRA_BG_ESTIMATE = "com.eveningoutpost.dexdrip.Extras.BgEstimate";
    private static final String EXTRA_BG_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope";
    private static final String EXTRA_BG_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName";
    private static final String EXTRA_TIMESTAMP = "com.eveningoutpost.dexdrip.Extras.Time";
    private static final String EXTRA_DISPLAY_UNITS = "com.eveningoutpost.dexdrip.Extras.Display.Units";
    private static final String UNITS_MGDL = "mg/dl";

    /** The slope is in mg/dL per ms; the delta shown by CGM apps is the change over 5 minutes. */
    private static final double DELTA_INTERVAL_MS = 5 * 60 * 1000;

    private XdripBroadcast() {
    }

    /** @return the reading, or null if the broadcast has no value or time */
    @Nullable
    public static CgmSnapshot.Reading reading(@Nullable final Bundle extras) {
        if (extras == null || !extras.containsKey(EXTRA_BG_ESTIMATE) || !extras.containsKey(EXTRA_TIMESTAMP)) {
            return null;
        }
        final double delta = extras.containsKey(EXTRA_BG_SLOPE)
                ? extras.getDouble(EXTRA_BG_SLOPE) * DELTA_INTERVAL_MS
                : Double.NaN;
        final String direction = extras.getString(EXTRA_BG_SLOPE_NAME);
        return new CgmSnapshot.Reading(
                extras.getLong(EXTRA_TIMESTAMP),
                (int) Math.round(extras.getDouble(EXTRA_BG_ESTIMATE)),
                delta,
                direction != null ? direction : "");
    }

    /** @return whether the CGM app shows mg/dL (false: mmol/L), null if the broadcast does not say */
    @Nullable
    public static Boolean mgdlUnits(@Nullable final Bundle extras) {
        final String units = extras != null ? extras.getString(EXTRA_DISPLAY_UNITS) : null;
        return units == null ? null : UNITS_MGDL.equals(units);
    }
}
