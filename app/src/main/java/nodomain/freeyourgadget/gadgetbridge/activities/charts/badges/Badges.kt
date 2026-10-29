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

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep.SleepQuality
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The tiers of a badge family, from the first one earned to the last.
 */
enum class BadgeTier(@StringRes val label: Int, @ColorRes val color: Int) {
    BRONZE(R.string.badges_tier_bronze, R.color.badge_tier_bronze),
    SILVER(R.string.badges_tier_silver, R.color.badge_tier_silver),
    GOLD(R.string.badges_tier_gold, R.color.badge_tier_gold),
    PLATINUM(R.string.badges_tier_platinum, R.color.badge_tier_platinum),
    DIAMOND(R.string.badges_tier_diamond, R.color.badge_tier_diamond),
    LEGEND(R.string.badges_tier_legend, R.color.badge_tier_legend),
}

/**
 * A family of badges: one tier per threshold of its value, in [BadgeTier] order.
 */
enum class BadgeFamily(
    val key: String,
    @StringRes val title: Int,
    @PluralsRes val description: Int,
    @DrawableRes val icon: Int,
    val thresholds: LongArray,
) {
    GOAL_DAYS("goal_days", R.string.badges_goal_days, R.plurals.badges_goal_days_description,
        R.drawable.ic_badge_flag, longArrayOf(1, 10, 50, 100, 365, 1000)),
    STREAK("streak", R.string.badges_streak, R.plurals.badges_streak_description,
        R.drawable.ic_badge_flame, longArrayOf(3, 7, 14, 30, 60, 100)),
    PERFECT_WEEK("perfect_week", R.string.badges_perfect_week, R.plurals.badges_perfect_week_description,
        R.drawable.ic_calendar_month, longArrayOf(1, 4, 12, 26, 52)),
    BIG_DAY("big_day", R.string.badges_big_day, R.plurals.badges_big_day_description,
        R.drawable.ic_bolt, longArrayOf(15_000, 20_000, 25_000, 30_000, 40_000)),
    TOTAL_STEPS("total_steps", R.string.badges_total_steps, R.plurals.badges_total_steps_description,
        R.drawable.ic_public, longArrayOf(100_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 10_000_000)),
    GOOD_NIGHTS("good_nights", R.string.badges_good_nights, R.plurals.badges_good_nights_description,
        R.drawable.ic_nights_stay, longArrayOf(1, 10, 30, 100, 365)),
    WORKOUTS("workouts", R.string.badges_workouts, R.plurals.badges_workouts_description,
        R.drawable.ic_badge_trophy, longArrayOf(1, 10, 25, 50, 100)),
    ;

    init {
        require(thresholds.size <= BadgeTier.entries.size) { "More thresholds than tiers in $key" }
    }

    fun tier(index: Int) = BadgeTier.entries[index]

    /**
     * Id of the tier [index] of this family, kept to know which badges were seen.
     */
    fun id(index: Int) = "$key:$index"
}

/**
 * The totals of one day: its steps, and the sleep of the night that ended on it, with its deep, REM and awake minutes.
 */
data class BadgeDay(
    val date: LocalDate,
    val steps: Long,
    val sleepMinutes: Long,
    val deepMinutes: Long = 0,
    val remMinutes: Long = 0,
    val awakeMinutes: Long = 0,
)

/**
 * A workout of the history: its start date and duration.
 */
data class BadgeWorkout(val date: LocalDate, val minutes: Long)

/**
 * The value of a [family] and the date each of its tiers was earned, null for those not earned yet.
 */
data class BadgeProgress(val family: BadgeFamily, val value: Long, val earned: List<LocalDate?>) {
    val earnedCount: Int get() = earned.count { it != null }

    /**
     * Index of the highest tier earned, or -1.
     */
    val highestTier: Int get() = earnedCount - 1

    /**
     * Threshold of the next tier, or null when all are earned.
     */
    val nextThreshold: Long? get() = family.thresholds.getOrNull(earnedCount)
}

/**
 * Computes the badges from the history, in date order. A tier is earned on the day its threshold is first reached.
 */
object BadgeCalculator {
    /**
     * @param days the days with data, in any order
     * @param workouts the start dates of the workouts
     * @param firstDayOfWeek the first day of a perfect week
     */
    fun compute(
        days: List<BadgeDay>,
        workouts: List<LocalDate>,
        stepsGoal: Int,
        sleepGoalMinutes: Int,
        firstDayOfWeek: DayOfWeek,
    ): List<BadgeProgress> {
        val trackers = BadgeFamily.entries.associateWith { Tracker(it) }
        val sorted = days.sortedBy { it.date }
        val goalDays = sorted.filter { it.steps >= stepsGoal }.map { it.date }.toSet()
        // Streak lengths with forgiveness, the same definition the records and the widget use
        val streakLengths = Streaks.runLengths(goalDays)

        var goalCount = 0L
        var bestStreak = 0L
        var bestDay = 0L
        var totalSteps = 0L
        var goodNights = 0L
        for (day in sorted) {
            if (day.date in goalDays) {
                goalCount++
                bestStreak = maxOf(bestStreak, streakLengths.getValue(day.date).toLong())
            }
            bestDay = maxOf(bestDay, day.steps)
            totalSteps += day.steps
            if (sleepGoalMinutes > 0 && day.sleepMinutes >= sleepGoalMinutes) {
                goodNights++
            }
            trackers.getValue(BadgeFamily.GOAL_DAYS).update(goalCount, day.date)
            trackers.getValue(BadgeFamily.STREAK).update(bestStreak, day.date)
            trackers.getValue(BadgeFamily.BIG_DAY).update(bestDay, day.date)
            trackers.getValue(BadgeFamily.TOTAL_STEPS).update(totalSteps, day.date)
            trackers.getValue(BadgeFamily.GOOD_NIGHTS).update(goodNights, day.date)
        }

        // A perfect week has its 7 days on the goal; it is earned on its last day
        val weekStarts = goalDays.map { it.with(TemporalAdjusters.previousOrSame(firstDayOfWeek)) }.toSortedSet()
        var perfectWeeks = 0L
        for (start in weekStarts) {
            if ((0L until 7L).all { start.plusDays(it) in goalDays }) {
                perfectWeeks++
                trackers.getValue(BadgeFamily.PERFECT_WEEK).update(perfectWeeks, start.plusDays(6))
            }
        }

        var workoutCount = 0L
        for (date in workouts.sorted()) {
            workoutCount++
            trackers.getValue(BadgeFamily.WORKOUTS).update(workoutCount, date)
        }

        return BadgeFamily.entries.map { trackers.getValue(it).progress() }
    }

    private class Tracker(private val family: BadgeFamily) {
        private var value = 0L
        private val earned = arrayOfNulls<LocalDate>(family.thresholds.size)

        fun update(newValue: Long, date: LocalDate) {
            value = newValue
            for (i in family.thresholds.indices) {
                if (earned[i] == null && newValue >= family.thresholds[i]) {
                    earned[i] = date
                }
            }
        }

        fun progress() = BadgeProgress(family, value, earned.toList())
    }
}

/**
 * A personal best: its value and the day it was set, or null without data.
 */
data class PersonalRecord(val kind: RecordKind, val value: Long, val date: LocalDate)

enum class RecordKind(@StringRes val label: Int) {
    BEST_DAY(R.string.records_best_day),
    LONGEST_STREAK(R.string.records_longest_streak),
    LONGEST_NIGHT(R.string.records_longest_night),
    BEST_SLEEP_SCORE(R.string.records_best_sleep_score),
    LONGEST_WORKOUT(R.string.records_longest_workout),
}

object PersonalRecords {
    /**
     * The records set in the history, the first day a record was reached when it was matched later.
     * The best sleep score uses [SleepQuality].
     */
    fun compute(days: List<BadgeDay>, workouts: List<BadgeWorkout>, stepsGoal: Int, sleepGoalMinutes: Int): List<PersonalRecord> {
        val sorted = days.sortedBy { it.date }
        val records = mutableListOf<PersonalRecord>()
        sorted.filter { it.steps > 0 }.maxWithOrNull(compareBy<BadgeDay> { it.steps }.thenByDescending { it.date })
            ?.let { records += PersonalRecord(RecordKind.BEST_DAY, it.steps, it.date) }

        val goalDays = sorted.filter { it.steps >= stepsGoal }.map { it.date }.toSet()
        Streaks.longest(goalDays)?.let { (length, date) ->
            records += PersonalRecord(RecordKind.LONGEST_STREAK, length.toLong(), date)
        }

        sorted.filter { it.sleepMinutes > 0 }.maxWithOrNull(compareBy<BadgeDay> { it.sleepMinutes }.thenByDescending { it.date })
            ?.let { records += PersonalRecord(RecordKind.LONGEST_NIGHT, it.sleepMinutes, it.date) }

        sorted.mapNotNull { day ->
            SleepQuality.score(
                day.sleepMinutes.toDouble(), day.deepMinutes.toDouble(), day.remMinutes.toDouble(),
                day.awakeMinutes.toDouble(), sleepGoalMinutes,
            )?.let { day.date to it }
        }.maxWithOrNull(compareBy<Pair<LocalDate, Int>> { it.second }.thenByDescending { it.first })
            ?.let { records += PersonalRecord(RecordKind.BEST_SLEEP_SCORE, it.second.toLong(), it.first) }

        workouts.filter { it.minutes > 0 }.maxWithOrNull(compareBy<BadgeWorkout> { it.minutes }.thenByDescending { it.date })
            ?.let { records += PersonalRecord(RecordKind.LONGEST_WORKOUT, it.minutes, it.date) }
        return records
    }
}
