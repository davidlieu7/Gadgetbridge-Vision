/*  Copyright (C) 2026 theupsidedownabyss

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
package nodomain.freeyourgadget.gadgetbridge.activities.charts.badges

import nodomain.freeyourgadget.gadgetbridge.activities.charts.goals.DailyGoals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PersonalRecordsTest {
    private val day = LocalDate.of(2026, 6, 1)

    @Test
    fun records() {
        val days = listOf(
            BadgeDay(day, 12_000, 480, deepMinutes = 120, awakeMinutes = 5),
            BadgeDay(day.plusDays(1), 15_000, 300, deepMinutes = 30),
            BadgeDay(day.plusDays(2), 11_000, 520, deepMinutes = 60),
            BadgeDay(day.plusDays(4), 15_000, 0),
        )
        val workouts = listOf(BadgeWorkout(day, 30), BadgeWorkout(day.plusDays(2), 45))
        val records = PersonalRecords.compute(days, workouts, 10_000, 480).associateBy { it.kind }

        // The first day a record was reached
        assertEquals(PersonalRecord(RecordKind.BEST_DAY, 15_000, day.plusDays(1)), records[RecordKind.BEST_DAY])
        // day+3 has no data (an off day) but is forgiven, so the streak runs day..day+4 = 4
        assertEquals(PersonalRecord(RecordKind.LONGEST_STREAK, 4, day.plusDays(4)), records[RecordKind.LONGEST_STREAK])
        assertEquals(PersonalRecord(RecordKind.LONGEST_NIGHT, 520, day.plusDays(2)), records[RecordKind.LONGEST_NIGHT])
        assertEquals(PersonalRecord(RecordKind.BEST_SLEEP_SCORE, 100, day), records[RecordKind.BEST_SLEEP_SCORE])
        assertEquals(PersonalRecord(RecordKind.LONGEST_WORKOUT, 45, day.plusDays(2)), records[RecordKind.LONGEST_WORKOUT])
    }

    @Test
    fun noData_noRecords() {
        assertTrue(PersonalRecords.compute(emptyList(), emptyList(), 10_000, 480).isEmpty())
    }

    @Test
    fun dailyGoals() {
        val hours = FloatArray(24).also {
            it[8] = 300f
            it[9] = 249f
            it[18] = 1200f
        }
        assertEquals(2, DailyGoals.movingHours(hours))
        // 5 km at 70 kg: 175 kcal
        assertEquals(175.0, DailyGoals.estimatedActiveKcal(5000.0, 70), 1e-9)
    }
}
