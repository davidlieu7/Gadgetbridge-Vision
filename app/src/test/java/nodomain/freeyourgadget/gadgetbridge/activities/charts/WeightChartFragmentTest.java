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
package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericWeightSample;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.UserAttributes;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionCalculator;
import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionEstimates;

public class WeightChartFragmentTest extends TestBase {
    private static final long MINUTE = 60_000L;
    private static final long DAY = 24 * 60 * MINUTE;

    // Synthetic profile and reading; nothing here is a real person's measurement.
    private static final int HEIGHT_THEN_CM = 160;
    private static final int HEIGHT_NOW_CM = 180;
    private static final float WEIGHT_KG = 80f;
    private static final int IMPEDANCE_OHM = 500;

    /**
     * Profile says HEIGHT_NOW_CM (valid from now); an older record says the user was
     * HEIGHT_THEN_CM for the years before. Returns a measurement taken in that earlier period.
     */
    private GenericWeightSample measurementTakenWhenShorter(final Integer impedanceOhm) {
        GBApplication.getPrefs().getPreferences().edit()
                .putString(ActivityUser.PREF_USER_HEIGHT_CM, String.valueOf(HEIGHT_NOW_CM))
                .apply();
        final User user = DBHelper.getUser(daoSession);
        final long now = System.currentTimeMillis();
        final UserAttributes earlier = new UserAttributes();
        earlier.setUserId(user.getId());
        earlier.setHeightCM(HEIGHT_THEN_CM);
        earlier.setWeightKG(0);
        earlier.setValidFromUTC(new Date(now - 2_000 * DAY));
        earlier.setValidToUTC(new Date(now - 2 * MINUTE));
        daoSession.getUserAttributesDao().insert(earlier);
        user.resetUserAttributesList();

        final GenericWeightSample sample = new GenericWeightSample();
        sample.setTimestamp(now - 1_000 * DAY);
        sample.setWeightKg(WEIGHT_KG);
        sample.setImpedanceOhm(impedanceOhm);
        return sample;
    }

    @Test
    public void bmiUsesTheHeightRecordedAtTheTimeOfTheMeasurement() {
        final GenericWeightSample sample = measurementTakenWhenShorter(null);

        final Float bmi = BodyCompositionEstimates.bmi(daoSession, sample);

        assertNotNull(bmi);
        assertEquals(WEIGHT_KG / (1.6f * 1.6f), bmi, 0.001f);
        assertNotEquals(WEIGHT_KG / (1.8f * 1.8f), bmi, 0.01f);
        // BMI needs no impedance, unlike the rest of the estimates
        assertNull(BodyCompositionEstimates.composition(daoSession, sample));
        assertNull(BodyCompositionEstimates.bmi(daoSession, null));
    }

    @Test
    public void estimatesWithTheHeightRecordedAtTheTimeOfTheMeasurement() {
        final GenericWeightSample sample = measurementTakenWhenShorter(IMPEDANCE_OHM);
        final long measuredAt = sample.getTimestamp();

        final BodyCompositionCalculator.BodyComposition actual = BodyCompositionEstimates.composition(daoSession, sample);

        final ActivityUser prefsUser = new ActivityUser();
        final int ageThen = prefsUser.getAgeAt(Instant.ofEpochMilli(measuredAt).atZone(ZoneId.systemDefault()).toLocalDate());
        final BodyCompositionCalculator.BodyComposition withHeightThen =
                BodyCompositionCalculator.compute(prefsUser.getGender(), ageThen, HEIGHT_THEN_CM, WEIGHT_KG, IMPEDANCE_OHM);
        final BodyCompositionCalculator.BodyComposition withHeightNow =
                BodyCompositionCalculator.compute(prefsUser.getGender(), ageThen, HEIGHT_NOW_CM, WEIGHT_KG, IMPEDANCE_OHM);

        assertNotNull(actual);
        assertNotNull(withHeightThen);
        assertNotNull(withHeightNow);
        assertNotEquals("the two heights must give different estimates for this check to mean anything",
                withHeightThen.bodyFatPercent, withHeightNow.bodyFatPercent, 0.01f);
        assertEquals(withHeightThen.bodyFatPercent, actual.bodyFatPercent, 0.001f);
        assertEquals(withHeightThen.basalMetabolicRate, actual.basalMetabolicRate);
    }

    @Test
    public void hasNoEstimateWithoutImpedance() {
        final GenericWeightSample sample = new GenericWeightSample();
        sample.setTimestamp(System.currentTimeMillis());
        sample.setWeightKg(WEIGHT_KG);
        sample.setImpedanceOhm(null);

        assertNull(BodyCompositionEstimates.composition(daoSession, sample));
        assertNull(BodyCompositionEstimates.composition(daoSession, null));
    }
}
