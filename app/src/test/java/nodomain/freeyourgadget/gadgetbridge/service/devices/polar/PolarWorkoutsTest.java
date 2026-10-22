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
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;

public class PolarWorkoutsTest {
    private static PolarProto.PbLocalDateTime local(final int hour, final int minute) {
        return PolarProto.PbLocalDateTime.newBuilder()
                .setDate(PolarProto.PbDate.newBuilder().setYear(2026).setMonth(10).setDay(20))
                .setTime(PolarProto.PbTime.newBuilder().setHour(hour).setMinute(minute))
                .setTimeZoneOffset(120).build();
    }

    @Test
    public void sessionWithoutStartIsIgnored() {
        assertNull(PolarWorkouts.parse(PolarProto.PbTrainingSession.getDefaultInstance(), null, null));
    }

    @Test
    public void strengthSessionKeepsTheTotalsOfTheWatch() {
        final PolarWorkouts.Workout workout = PolarWorkouts.parse(PolarProto.PbTrainingSession.newBuilder()
                .setStart(local(15, 25))
                .setEnd(local(16, 43))
                .setDuration(PolarProto.PbDuration.newBuilder().setHours(1).setMinutes(17).setSeconds(10))
                .setCalories(454)
                .setHeartRate(PolarProto.PbSessionHeartRateStatistics.newBuilder().setAverage(97).setMaximum(135))
                .build(), PolarProto.PbExerciseBase.newBuilder()
                .setSport(PolarProto.PbSportIdentifier.newBuilder().setValue(15)).build(), new byte[]{1});

        assertEquals(ActivityKind.STRENGTH_TRAINING, workout.kind);
        assertEquals((16 * 60 + 43 - (15 * 60 + 25)) * 60_000L, workout.endMillis - workout.startMillis);
        assertTrue(workout.summaryJson.contains("\"value\":454"));
        assertTrue(workout.summaryJson.contains("\"value\":4630"));
    }
}
