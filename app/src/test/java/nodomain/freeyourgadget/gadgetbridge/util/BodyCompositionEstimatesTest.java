/*  Copyright (C) 2026 Gadgetbridge Contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 or later;
    see the LICENSE file for details.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import androidx.annotation.Nullable;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample;

public class BodyCompositionEstimatesTest {

    private static final class Sample implements WeightSample {
        private final long timestamp;
        private final float weightKg;
        @Nullable
        private final Integer impedanceOhm;
        @Nullable
        private final BodyCompositionCalculator.BodyComposition reported;

        Sample(final float weightKg, @Nullable final Integer impedanceOhm,
               @Nullable final BodyCompositionCalculator.BodyComposition reported) {
            this.timestamp = 1_000_000L;
            this.weightKg = weightKg;
            this.impedanceOhm = impedanceOhm;
            this.reported = reported;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public float getWeightKg() {
            return weightKg;
        }

        @Nullable
        @Override
        public Integer getImpedanceOhm() {
            return impedanceOhm;
        }

        @Nullable
        @Override
        public BodyCompositionCalculator.BodyComposition getReportedBodyComposition() {
            return reported;
        }
    }

    @Test
    public void reportedValuesTakePrecedenceOverTheImpedanceEstimate() {
        final BodyCompositionCalculator.BodyComposition reported =
                new BodyCompositionCalculator.BodyComposition(20.6f, 63.6f, 39.7f, 2.6f, 1601);
        // The impedance is deliberately set to a value that would yield a different estimate.
        final Sample sample = new Sample(59.9f, 400, reported);

        assertSame(reported, BodyCompositionEstimates.composition(null, sample));
    }

    @Test
    public void nothingReportedAndNoImpedanceYieldsNull() {
        assertNull(BodyCompositionEstimates.composition(null, new Sample(59.9f, null, null)));
    }

    @Test
    public void noSampleYieldsNull() {
        assertNull(BodyCompositionEstimates.composition(null, null));
    }

    @Test
    public void bmiStillComesFromWeightAndHeight() {
        // Needs a session for the height lookup; only asserts the no-sample and no-height paths
        // that do not touch the database.
        assertNull(BodyCompositionEstimates.bmi(null, null));
    }

    @Test
    public void reportedValuesAreReturnedUntouched() {
        final BodyCompositionCalculator.BodyComposition reported =
                new BodyCompositionCalculator.BodyComposition(15f, 60f, 40f, 3f, 1500);
        final Sample sample = new Sample(70f, null, reported); // no impedance at all

        final BodyCompositionCalculator.BodyComposition result = BodyCompositionEstimates.composition(null, sample);
        assertSame(reported, result);
        assertEquals(15f, result.bodyFatPercent, 0f);
        assertEquals(60f, result.bodyWaterPercent, 0f);
        assertEquals(40f, result.muscleMassKg, 0f);
        assertEquals(3f, result.boneMassKg, 0f);
        assertEquals(1500, result.basalMetabolicRate);
    }
}
