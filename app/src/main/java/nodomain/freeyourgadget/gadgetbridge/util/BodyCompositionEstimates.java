/*  Copyright (C) 2026 oddballza

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
package nodomain.freeyourgadget.gadgetbridge.util;

import androidx.annotation.Nullable;

import java.time.Instant;
import java.time.ZoneId;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.UserAttributes;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample;

/**
 * Values derived from a weight measurement when it is displayed: BMI from the weight and the
 * height, and the body composition from the device-reported values or, where the device does not
 * report them, from the impedance. Nothing here is persisted; the profile of the measurement's
 * day (height, age) is used, not today's.
 */
public final class BodyCompositionEstimates {
    private BodyCompositionEstimates() {
    }

    /**
     * Body mass index of a measurement, from its weight and the height recorded at the time of
     * the measurement. Needs no impedance, so it is available for any scale.
     *
     * @return the BMI, or null without a sample or a usable height
     */
    @Nullable
    public static Float bmi(final DaoSession session, @Nullable final WeightSample sample) {
        if (sample == null) {
            return null;
        }
        final int heightCm = heightCmAt(session, sample.getTimestamp());
        if (heightCm <= 0) {
            return null;
        }
        final float heightM = heightCm / 100f;
        return sample.getWeightKg() / (heightM * heightM);
    }

    /**
     * The user's height at the given time: the attributes recorded back then, or the current
     * profile when there is no usable record.
     */
    public static int heightCmAt(final DaoSession session, final long timestampMillis) {
        final User user = DBHelper.getUser(session);
        final UserAttributes attributes = DBHelper.getUserAttributesAt(user, timestampMillis);
        return attributes != null && attributes.getHeightCM() > 0
                ? attributes.getHeightCM()
                : new ActivityUser().getHeightCm();
    }

    /**
     * The body composition of a measurement: the finished values reported by the device when it
     * reports them, otherwise an estimate from its raw impedance and the user profile as it was at
     * the time of the measurement (height and age change over the years, so an old measurement is
     * evaluated against the attributes recorded back then). Nothing is persisted; the values are
     * recomputed whenever they are shown.
     *
     * @return the composition, or null when the sample carries neither reported values nor a usable impedance
     */
    @Nullable
    public static BodyCompositionCalculator.BodyComposition composition(final DaoSession session, @Nullable final WeightSample sample) {
        if (sample == null) {
            return null;
        }
        final BodyCompositionCalculator.BodyComposition reported = sample.getReportedBodyComposition();
        if (reported != null) {
            return reported;
        }
        if (sample.getImpedanceOhm() == null) {
            return null;
        }
        final ActivityUser prefsUser = new ActivityUser();
        final int heightCm = heightCmAt(session, sample.getTimestamp());
        final int age = prefsUser.getAgeAt(Instant.ofEpochMilli(sample.getTimestamp()).atZone(ZoneId.systemDefault()).toLocalDate());
        return BodyCompositionCalculator.compute(prefsUser.getGender(), age, heightCm, sample.getWeightKg(), sample.getImpedanceOhm());
    }

}
