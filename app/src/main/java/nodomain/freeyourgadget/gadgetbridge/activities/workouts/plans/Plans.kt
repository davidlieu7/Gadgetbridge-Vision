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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts.plans

import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * What a training plan counts as a session.
 */
enum class SessionKind {
    /**
     * A day with at least [PlanWeek.target] steps.
     */
    STEPS_DAY,

    /**
     * A running workout of at least [PlanWeek.target] minutes.
     */
    RUN_MINUTES,
}

/**
 * A week of a plan: [sessions] to do, each reaching [target], following [instructions].
 */
data class PlanWeek(val sessions: Int, val target: Int, @StringRes val instructions: Int)

/**
 * Beginner plans: more walking, then running 3 or 5 km with run/walk intervals getting longer each week.
 */
enum class TrainingPlan(val key: String, @StringRes val title: Int, @StringRes val summary: Int, val kind: SessionKind, val weeks: List<PlanWeek>) {
    WALK_MORE("walk_more", R.string.plan_walk_more, R.string.plan_walk_more_summary, SessionKind.STEPS_DAY, listOf(
        PlanWeek(5, 6_000, R.string.plan_walk_more_week),
        PlanWeek(5, 7_000, R.string.plan_walk_more_week),
        PlanWeek(5, 8_500, R.string.plan_walk_more_week),
        PlanWeek(5, 10_000, R.string.plan_walk_more_week),
    )),
    RUN_3K("run_3k", R.string.plan_run_3k, R.string.plan_run_3k_summary, SessionKind.RUN_MINUTES, listOf(
        PlanWeek(3, 20, R.string.plan_run_week_1),
        PlanWeek(3, 22, R.string.plan_run_week_2),
        PlanWeek(3, 25, R.string.plan_run_week_3),
        PlanWeek(3, 25, R.string.plan_run_week_4),
        PlanWeek(3, 28, R.string.plan_run_week_5),
        PlanWeek(3, 30, R.string.plan_run_week_6),
    )),
    RUN_5K("run_5k", R.string.plan_run_5k, R.string.plan_run_5k_summary, SessionKind.RUN_MINUTES, listOf(
        PlanWeek(3, 25, R.string.plan_run_week_1),
        PlanWeek(3, 25, R.string.plan_run_week_2),
        PlanWeek(3, 28, R.string.plan_run_week_3),
        PlanWeek(3, 30, R.string.plan_run_week_4),
        PlanWeek(3, 30, R.string.plan_run_week_5),
        PlanWeek(3, 32, R.string.plan_run_week_6),
        PlanWeek(3, 35, R.string.plan_run_week_7),
        PlanWeek(3, 40, R.string.plan_run_week_8),
    )),
    ;

    companion object {
        fun byKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * A recorded workout: its day, kind and minutes.
 */
data class PlanWorkout(val date: LocalDate, val kind: ActivityKind, val minutes: Long)

/**
 * The progress of a plan started on [start]: the sessions done each week, and the current week (index into
 * [TrainingPlan.weeks], or the number of weeks once over).
 */
data class PlanProgress(val plan: TrainingPlan, val start: LocalDate, val done: List<Int>, val currentWeek: Int) {
    val completedWeeks: Int get() = plan.weeks.indices.count { done[it] >= plan.weeks[it].sessions }
    val finished: Boolean get() = currentWeek >= plan.weeks.size
}

object TrainingPlans {
    /**
     * A run counts when it lasts at least this share of the session's minutes.
     */
    private const val RUN_TOLERANCE = 0.9

    fun isRun(kind: ActivityKind) = kind.name.contains("RUN") || kind == ActivityKind.TREADMILL

    /**
     * @param steps steps per day
     */
    fun progress(plan: TrainingPlan, start: LocalDate, today: LocalDate, steps: Map<LocalDate, Long>, workouts: List<PlanWorkout>): PlanProgress {
        val done = plan.weeks.mapIndexed { index, week ->
            val first = start.plusWeeks(index.toLong())
            val days = (0L until 7L).map { first.plusDays(it) }.filter { !it.isAfter(today) }
            val count = when (plan.kind) {
                SessionKind.STEPS_DAY -> days.count { (steps[it] ?: 0) >= week.target }
                SessionKind.RUN_MINUTES -> workouts.count {
                    it.date in days && isRun(it.kind) && it.minutes >= week.target * RUN_TOLERANCE
                }
            }
            count.coerceAtMost(week.sessions)
        }
        val currentWeek = (ChronoUnit.DAYS.between(start, today) / 7).toInt().coerceIn(0, plan.weeks.size)
        return PlanProgress(plan, start, done, currentWeek)
    }
}

/**
 * A weight-loss plan: from [startKg] on [start] to [targetKg], losing [kgPerWeek].
 */
data class WeightPlan(val startKg: Double, val targetKg: Double, val kgPerWeek: Double, val start: LocalDate) {
    val toLoseKg: Double get() = (startKg - targetKg).coerceAtLeast(0.0)

    val weeks: Int get() = if (kgPerWeek > 0) ceil(toLoseKg / kgPerWeek).toInt() else 0

    val targetDate: LocalDate get() = start.plusWeeks(weeks.toLong())

    /**
     * The daily energy deficit for [kgPerWeek], 7700 kcal per kg of body fat.
     */
    val dailyDeficitKcal: Int get() = (kgPerWeek * KCAL_PER_KG / 7).toInt()

    /**
     * The weight expected on [date] at the plan's pace.
     */
    fun expectedKg(date: LocalDate): Double {
        val weeks = ChronoUnit.DAYS.between(start, date).coerceAtLeast(0) / 7.0
        return (startKg - weeks * kgPerWeek).coerceAtLeast(targetKg)
    }

    /**
     * The share of the weight to lose that [currentKg] has lost.
     */
    fun progress(currentKg: Double): Double = if (toLoseKg > 0) ((startKg - currentKg) / toLoseKg).coerceIn(0.0, 1.0) else 1.0

    /**
     * Extra steps a day that burn [ACTIVITY_SHARE] of the daily deficit by walking, about 0.5 kcal per kg and km.
     */
    fun extraStepsPerDay(stepLengthCm: Int): Int {
        if (stepLengthCm <= 0 || startKg <= 0) return 0
        val km = dailyDeficitKcal * ACTIVITY_SHARE / (KCAL_PER_KG_KM * startKg)
        return (km * 100_000 / stepLengthCm).toInt()
    }

    companion object {
        const val KCAL_PER_KG = 7700.0
        private const val KCAL_PER_KG_KM = 0.5

        /**
         * Half of the deficit from more activity, half from food.
         */
        const val ACTIVITY_SHARE = 0.5

        /**
         * Paces to choose from, kg per week. Above 1 kg a week isn't advised.
         */
        val PACES = doubleArrayOf(0.25, 0.5, 0.75, 1.0)
    }
}
