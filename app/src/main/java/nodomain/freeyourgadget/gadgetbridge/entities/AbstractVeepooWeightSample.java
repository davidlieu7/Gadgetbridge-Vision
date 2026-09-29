/*  Copyright (C) 2026 Severin von Wnuck-Lipinski and Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.entities;

import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionCalculator;

/**
 * Base class for weight samples of devices that report finished body-composition values
 * instead of a raw impedance, so the generic widgets can display the vendor numbers.
 */
public abstract class AbstractVeepooWeightSample extends AbstractWeightSample {
    /** Satisfied by the greenDAO-generated getters of {@code VeepooWeightSample}. */
    public abstract Float getBodyFatPercent();

    /** Satisfied by the greenDAO-generated getters of {@code VeepooWeightSample}. */
    public abstract Float getBodyWaterPercent();

    /** Satisfied by the greenDAO-generated getters of {@code VeepooWeightSample}. */
    public abstract Float getMuscleMassKg();

    /** Satisfied by the greenDAO-generated getters of {@code VeepooWeightSample}. */
    public abstract Float getBoneMassKg();

    /** Satisfied by the greenDAO-generated getters of {@code VeepooWeightSample}. */
    public abstract Integer getBasalMetabolicRate();

    @Override
    @Nullable
    public BodyCompositionCalculator.BodyComposition getReportedBodyComposition() {
        final Float bodyFatPercent = getBodyFatPercent();
        final Float bodyWaterPercent = getBodyWaterPercent();
        final Float muscleMassKg = getMuscleMassKg();
        final Float boneMassKg = getBoneMassKg();
        final Integer basalMetabolicRate = getBasalMetabolicRate();
        if (bodyFatPercent == null || bodyWaterPercent == null || muscleMassKg == null
                || boneMassKg == null || basalMetabolicRate == null) {
            return null;
        }
        return new BodyCompositionCalculator.BodyComposition(
                bodyFatPercent, bodyWaterPercent, muscleMassKg, boneMassKg, basalMetabolicRate);
    }
}
