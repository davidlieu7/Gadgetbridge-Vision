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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StreaksTest {
    private val mon = LocalDate.of(2026, 6, 1)
    private fun days(vararg offsets: Long) = offsets.map { mon.plusDays(it) }.toSet()

    @Test
    fun consecutiveDaysCount() {
        assertEquals(3, Streaks.runLengths(days(0, 1, 2))[mon.plusDays(2)])
    }

    @Test
    fun singleMissedDayIsForgiven() {
        // off day on mon+1 is a rest day, it bridges the streak but doesn't add to it
        assertEquals(2, Streaks.runLengths(days(0, 2))[mon.plusDays(2)])
    }

    @Test
    fun twoMissedDaysInARowBreak() {
        assertEquals(1, Streaks.runLengths(days(0, 3))[mon.plusDays(3)])
    }

    @Test
    fun secondRestDayWithinAWeekBreaks() {
        val lengths = Streaks.runLengths(days(0, 2, 4))
        assertEquals(2, lengths[mon.plusDays(2)])
        assertEquals(1, lengths[mon.plusDays(4)]) // mon+3 is a second rest day in the week
    }

    @Test
    fun restDayAllowedAgainAfterAWeek() {
        assertEquals(9, Streaks.longest(days(0, 2, 3, 4, 5, 6, 7, 8, 10))?.first)
    }

    @Test
    fun longest_firstDayItWasReached() {
        assertEquals(3 to mon.plusDays(2), Streaks.longest(days(0, 1, 2, 5, 6)))
        assertNull(Streaks.longest(emptySet()))
    }

    @Test
    fun current_aliveWhileTodayPendingOrForgiven() {
        val g = days(0, 1, 2)
        assertEquals(3, Streaks.current(g, mon.plusDays(2)))  // today reached
        assertEquals(3, Streaks.current(g, mon.plusDays(3)))  // today still pending after yesterday
        assertEquals(3, Streaks.current(g, mon.plusDays(4)))  // yesterday can still be the rest day
        assertEquals(0, Streaks.current(g, mon.plusDays(5)))  // two days missed in a row
    }

    @Test
    fun current_noSecondRestDayWhenOneAlreadyUsed() {
        // mon+1 was already forgiven, so a miss on mon+3 can't be a second rest day
        assertEquals(0, Streaks.current(days(0, 2), mon.plusDays(4)))
    }
}
