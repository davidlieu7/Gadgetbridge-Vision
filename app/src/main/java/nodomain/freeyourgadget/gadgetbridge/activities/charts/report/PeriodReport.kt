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

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class ReportPeriod { WEEK, MONTH }

/**
 * The days from [start] to [end], both included.
 */
data class PeriodRange(val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(end)
}

/**
 * The totals of one day. [sleepMinutes], [sleepScore] and [sleepingHeartRate] belong to the night that ended on it.
 */
data class ReportDay(
    val date: LocalDate,
    val steps: Long = 0,
    val distanceMeters: Double = 0.0,
    val activeMinutes: Long = 0,
    val sleepMinutes: Long = 0,
    val sleepScore: Int? = null,
    val sleepingHeartRate: Int? = null,
    val workouts: Int = 0,
    val workoutMinutes: Long = 0,
)

/**
 * The summary of a period. Averages are over the days with data of their kind, null without any.
 */
data class PeriodSummary(
    val range: PeriodRange,
    val totalSteps: Long,
    val stepsPerDay: Double?,
    val goalDays: Int,
    val distanceMeters: Double,
    val activeMinutesPerDay: Double?,
    val sleepMinutesPerNight: Double?,
    val sleepScore: Double?,
    val sleepingHeartRate: Double?,
    val workouts: Int,
    val workoutMinutes: Long,
    val bestDay: ReportDay?,
    val bestNight: ReportDay?,
)

object PeriodReport {
    /**
     * The period of [period] containing [day], and the one before it.
     */
    fun ranges(period: ReportPeriod, day: LocalDate, firstDayOfWeek: DayOfWeek): Pair<PeriodRange, PeriodRange> = when (period) {
        ReportPeriod.WEEK -> {
            val start = day.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            PeriodRange(start, start.plusDays(6)) to PeriodRange(start.minusWeeks(1), start.minusDays(1))
        }
        ReportPeriod.MONTH -> {
            val start = day.withDayOfMonth(1)
            PeriodRange(start, start.plusMonths(1).minusDays(1)) to PeriodRange(start.minusMonths(1), start.minusDays(1))
        }
    }

    /**
     * The part of [previous] as long as [current] up to [today], to compare an unfinished period with the same days
     * of the one before. [previous] itself once [current] is over.
     */
    fun comparable(previous: PeriodRange, current: PeriodRange, today: LocalDate): PeriodRange {
        if (today !in current || today == current.end) {
            return previous
        }
        val end = previous.start.plusDays(ChronoUnit.DAYS.between(current.start, today))
        return PeriodRange(previous.start, minOf(end, previous.end))
    }

    fun summarize(range: PeriodRange, days: List<ReportDay>, stepsGoal: Int): PeriodSummary {
        val inRange = days.filter { it.date in range }
        val stepDays = inRange.filter { it.steps > 0 }
        val nights = inRange.filter { it.sleepMinutes > 0 }
        return PeriodSummary(
            range = range,
            totalSteps = stepDays.sumOf { it.steps },
            stepsPerDay = stepDays.averageOrNull { it.steps.toDouble() },
            goalDays = stepDays.count { it.steps >= stepsGoal },
            distanceMeters = stepDays.sumOf { it.distanceMeters },
            activeMinutesPerDay = stepDays.averageOrNull { it.activeMinutes.toDouble() },
            sleepMinutesPerNight = nights.averageOrNull { it.sleepMinutes.toDouble() },
            sleepScore = nights.mapNotNull { it.sleepScore }.averageOrNull { it.toDouble() },
            sleepingHeartRate = nights.mapNotNull { it.sleepingHeartRate }.averageOrNull { it.toDouble() },
            workouts = inRange.sumOf { it.workouts },
            workoutMinutes = inRange.sumOf { it.workoutMinutes },
            bestDay = stepDays.maxByOrNull { it.steps },
            bestNight = nights.filter { it.sleepScore != null }.maxByOrNull { it.sleepScore!! },
        )
    }

    /**
     * The relative change from [previous] to [current], null when either is missing or [previous] is 0.
     */
    fun change(current: Double?, previous: Double?): Double? {
        if (current == null || previous == null || previous == 0.0) {
            return null
        }
        return (current - previous) / previous
    }

    private fun <T> List<T>.averageOrNull(value: (T) -> Double): Double? = if (isEmpty()) null else sumOf(value) / size
}
