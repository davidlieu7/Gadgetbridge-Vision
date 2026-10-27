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

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.entities.GlucoseSample;

/**
 * Continuous glucose monitor state as reported by xDrip+, all values in mg/dL.
 */
public class CgmSnapshot {
    public static class Reading {
        public final long timestamp;
        public final int mgdl;
        /** Change over 5 minutes, NaN if unknown. */
        public final double deltaMgdl;
        /** Nightscout trend name, e.g. "Flat", "SingleUp"; empty if unknown. */
        public final String direction;

        public Reading(final long timestamp, final int mgdl, final double deltaMgdl, final String direction) {
            this.timestamp = timestamp;
            this.mgdl = mgdl;
            this.deltaMgdl = deltaMgdl;
            this.direction = direction;
        }
    }

    /** Newest first. */
    public final List<Reading> readings;
    public final boolean mgdlUnits;
    public final double lowMgdl;
    public final double highMgdl;

    public CgmSnapshot(final List<Reading> readings, final boolean mgdlUnits, final double lowMgdl, final double highMgdl) {
        this.readings = Collections.unmodifiableList(readings);
        this.mgdlUnits = mgdlUnits;
        this.lowMgdl = lowMgdl;
        this.highMgdl = highMgdl;
    }

    /**
     * @param samples stored readings, oldest first
     * @param latest  the newest reading as broadcast by the CGM app, which also knows its delta and trend;
     *                replaces the stored sample with the same timestamp
     */
    public static CgmSnapshot fromSamples(final List<GlucoseSample> samples, @Nullable final Reading latest,
                                          final boolean mgdlUnits, final double lowMgdl, final double highMgdl) {
        final List<Reading> readings = new ArrayList<>(samples.size() + 1);
        if (latest != null) {
            readings.add(latest);
        }
        for (int i = samples.size() - 1; i >= 0; i--) {
            final GlucoseSample sample = samples.get(i);
            if (latest != null && sample.getTimestamp() >= latest.timestamp) {
                continue;
            }
            readings.add(new Reading(sample.getTimestamp(), (int) Math.round(sample.getValueMgDl()), Double.NaN, ""));
        }
        return new CgmSnapshot(readings, mgdlUnits, lowMgdl, highMgdl);
    }

    public Reading latest() {
        return readings.isEmpty() ? null : readings.get(0);
    }
}
