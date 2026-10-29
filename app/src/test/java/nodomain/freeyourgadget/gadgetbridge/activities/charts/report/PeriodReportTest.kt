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
package nodomain.freeyourgadget.gadgetbridge.activities.charts.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class PeriodReportTest {
    private val wednesday = LocalDate.of(2026, 10, 28)

    @Test
    fun weekRanges_startOnTheFirstDayOfTheWeek() {
        val (current, previous) = PeriodReport.ranges(ReportPeriod.WEEK, wednesday, DayOfWeek.MONDAY)
        assertEquals(PeriodRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)), current)
        assertEquals(PeriodRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)), previous)

        val (sunday, _) = PeriodReport.ranges(ReportPeriod.WEEK, wednesday, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 10, 25), sunday.start)
    }

    @Test
    fun comparable_unfinishedPeriod_usesTheSameDays() {
        val (current, previous) = PeriodReport.ranges(ReportPeriod.WEEK, wednesday, DayOfWeek.MONDAY)
        assertEquals(PeriodRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21)),
            PeriodReport.comparable(previous, current, wednesday))
        assertEquals(previous, PeriodReport.comparable(previous, current, current.end))
        assertEquals(previous, PeriodReport.comparable(previous, current, current.end.plusDays(3)))

        // March 31st against a 28-day February
        val (march, february) = PeriodReport.ranges(ReportPeriod.MONTH, LocalDate.of(2026, 3, 30), DayOfWeek.MONDAY)
        assertEquals(february, PeriodReport.comparable(february, march, LocalDate.of(2026, 3, 30)))
    }

    @Test
    fun monthRanges() {
        val (current, previous) = PeriodReport.ranges(ReportPeriod.MONTH, LocalDate.of(2026, 3, 15), DayOfWeek.MONDAY)
        assertEquals(PeriodRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)), current)
        assertEquals(PeriodRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)), previous)
    }

    @Test
    fun summary_averagesOverTheDaysWithData() {
        val range = PeriodRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1))
        val days = listOf(
            ReportDay(LocalDate.of(2026, 10, 25), steps = 50_000), // outside the range
            ReportDay(LocalDate.of(2026, 10, 26), steps = 12_000, sleepMinutes = 480, sleepScore = 90, sleepingHeartRate = 55),
            ReportDay(LocalDate.of(2026, 10, 27), steps = 6_000, sleepMinutes = 360, sleepScore = 60, workouts = 1, workoutMinutes = 30),
            ReportDay(LocalDate.of(2026, 10, 28)), // not worn
        )
        val summary = PeriodReport.summarize(range, days, 10_000)
        assertEquals(18_000L, summary.totalSteps)
        assertEquals(9_000.0, summary.stepsPerDay!!, 0.0)
        assertEquals(1, summary.goalDays)
        assertEquals(420.0, summary.sleepMinutesPerNight!!, 0.0)
        assertEquals(75.0, summary.sleepScore!!, 0.0)
        assertEquals(55.0, summary.sleepingHeartRate!!, 0.0)
        assertEquals(1, summary.workouts)
        assertEquals(30L, summary.workoutMinutes)
        assertEquals(LocalDate.of(2026, 10, 26), summary.bestDay!!.date)
        assertEquals(90, summary.bestNight!!.sleepScore)
    }

    @Test
    fun emptyPeriod_hasNoAverages() {
        val range = PeriodRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1))
        val summary = PeriodReport.summarize(range, emptyList(), 10_000)
        assertNull(summary.stepsPerDay)
        assertNull(summary.sleepScore)
        assertNull(summary.bestDay)
    }

    @Test
    fun change() {
        assertEquals(0.5, PeriodReport.change(150.0, 100.0)!!, 1e-9)
        assertEquals(-0.25, PeriodReport.change(75.0, 100.0)!!, 1e-9)
        assertNull(PeriodReport.change(75.0, 0.0))
        assertNull(PeriodReport.change(null, 100.0))
        assertNull(PeriodReport.change(75.0, null))
    }
}
