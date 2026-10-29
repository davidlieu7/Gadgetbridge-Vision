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
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class BadgeCalculatorTest {
    private val monday = LocalDate.of(2026, 6, 1)

    private fun compute(
        days: List<BadgeDay>,
        workouts: List<LocalDate> = emptyList(),
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ) = BadgeCalculator.compute(days, workouts, 10_000, 420, firstDayOfWeek).associateBy { it.family }

    private fun steps(vararg values: Long) = values.mapIndexed { i, steps -> BadgeDay(monday.plusDays(i.toLong()), steps, 0) }

    @Test
    fun goalDaysAndStreak() {
        val badges = compute(steps(12_000, 12_000, 12_000, 5_000, 12_000, 12_000, 12_000, 12_000).shuffled())

        val goalDays = badges.getValue(BadgeFamily.GOAL_DAYS)
        assertEquals(7L, goalDays.value)
        assertEquals(listOf(monday, null, null, null, null, null), goalDays.earned)

        // The single off day (index 3) is forgiven as a rest day, so the whole span is one streak of 7 goal days
        val streak = badges.getValue(BadgeFamily.STREAK)
        assertEquals(7L, streak.value)
        assertEquals(monday.plusDays(2), streak.earned[0])
        assertEquals(1, streak.highestTier)
        assertEquals(14L, streak.nextThreshold)
    }

    @Test
    fun aSingleMissedDayIsForgiven() {
        // One off day between two goal days is a rest day: the streak carries over it
        val days = listOf(BadgeDay(monday, 12_000, 0), BadgeDay(monday.plusDays(2), 12_000, 0))
        assertEquals(2L, compute(days).getValue(BadgeFamily.STREAK).value)
    }

    @Test
    fun twoMissedDaysInARowBreakTheStreak() {
        val days = listOf(BadgeDay(monday, 12_000, 0), BadgeDay(monday.plusDays(3), 12_000, 0))
        assertEquals(1L, compute(days).getValue(BadgeFamily.STREAK).value)
    }

    @Test
    fun aSecondRestDayWithinAWeekBreaksTheStreak() {
        // monday+1 is forgiven; monday+3 is a second rest day inside the week, so the streak resets there
        val days = listOf(BadgeDay(monday, 12_000, 0), BadgeDay(monday.plusDays(2), 12_000, 0), BadgeDay(monday.plusDays(4), 12_000, 0))
        assertEquals(2L, compute(days).getValue(BadgeFamily.STREAK).value)
    }

    @Test
    fun aRestDayIsAllowedAgainAfterAWeek() {
        // monday+1 forgiven, then a week of goal days, then monday+9 forgiven: the window has reset
        val days = (listOf(0L, 2, 3, 4, 5, 6, 7, 8, 10)).map { BadgeDay(monday.plusDays(it), 12_000, 0) }
        assertEquals(9L, compute(days).getValue(BadgeFamily.STREAK).value)
    }

    @Test
    fun perfectWeek_followsTheFirstDayOfTheWeek() {
        val week = steps(12_000, 12_000, 12_000, 12_000, 12_000, 12_000, 12_000)

        val mondayFirst = compute(week).getValue(BadgeFamily.PERFECT_WEEK)
        assertEquals(1L, mondayFirst.value)
        assertEquals(monday.plusDays(6), mondayFirst.earned[0])

        // From Sunday, the days are split over two weeks
        assertEquals(0L, compute(week, firstDayOfWeek = DayOfWeek.SUNDAY).getValue(BadgeFamily.PERFECT_WEEK).value)
    }

    @Test
    fun bigDayAndTotalSteps() {
        val badges = compute(steps(16_000, 21_000))

        val bigDay = badges.getValue(BadgeFamily.BIG_DAY)
        assertEquals(21_000L, bigDay.value)
        assertEquals(listOf(monday, monday.plusDays(1), null, null, null), bigDay.earned)

        val total = badges.getValue(BadgeFamily.TOTAL_STEPS)
        assertEquals(37_000L, total.value)
        assertEquals(-1, total.highestTier)
        assertEquals(100_000L, total.nextThreshold)
    }

    @Test
    fun goodNights() {
        val days = listOf(BadgeDay(monday, 0, 430), BadgeDay(monday.plusDays(1), 0, 300))
        val nights = compute(days).getValue(BadgeFamily.GOOD_NIGHTS)
        assertEquals(1L, nights.value)
        assertEquals(monday, nights.earned[0])
    }

    @Test
    fun workouts_earnedOnTheFirst() {
        val workouts = compute(emptyList(), listOf(monday.plusDays(3), monday)).getValue(BadgeFamily.WORKOUTS)
        assertEquals(2L, workouts.value)
        assertEquals(monday, workouts.earned[0])
    }

    @Test
    fun ids_areUniquePerTier() {
        val ids = BadgeFamily.entries.flatMap { family -> family.thresholds.indices.map { family.id(it) } }
        assertEquals(ids.size, ids.toSet().size)
    }
}
