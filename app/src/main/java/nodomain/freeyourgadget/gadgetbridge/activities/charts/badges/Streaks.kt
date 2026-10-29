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

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Step-goal streaks with forgiveness. A single missed day is a "rest day" that keeps the streak alive, but only
 * one rest day per [FORGIVENESS_WINDOW_DAYS]: two missed days in a row, or a second rest day inside the window,
 * break it. Rest days don't add to the length, they only bridge it (like a streak freeze). This keeps a streak
 * from collapsing on one off day, as the UX research on streak motivation recommends, and is the single definition
 * the badges, the records and the dashboard widget all share so they agree.
 */
object Streaks {
    const val FORGIVENESS_WINDOW_DAYS = 7L

    /**
     * The running state of a streak at one of its goal days.
     * @property length the goal days reached so far in this run
     * @property lastForgiven the missed day last bridged in this run, or null
     */
    private data class Run(val length: Int, val lastForgiven: LocalDate?)

    private fun runs(goalDays: Set<LocalDate>): Map<LocalDate, Run> {
        val result = HashMap<LocalDate, Run>(goalDays.size)
        var length = 0
        var previous: LocalDate? = null
        var lastForgiven: LocalDate? = null
        for (day in goalDays.sorted()) {
            val prev = previous
            when {
                prev == null -> {
                    length = 1
                    lastForgiven = null
                }
                ChronoUnit.DAYS.between(prev, day) == 1L -> length += 1
                ChronoUnit.DAYS.between(prev, day) == 2L && canForgive(lastForgiven, prev.plusDays(1)) -> {
                    lastForgiven = prev.plusDays(1)
                    length += 1
                }
                else -> {
                    length = 1
                    lastForgiven = null
                }
            }
            previous = day
            result[day] = Run(length, lastForgiven)
        }
        return result
    }

    /**
     * A rest day on [missedDay] is allowed when none was taken in the [FORGIVENESS_WINDOW_DAYS] before it.
     */
    private fun canForgive(lastForgiven: LocalDate?, missedDay: LocalDate): Boolean =
        lastForgiven == null || ChronoUnit.DAYS.between(lastForgiven, missedDay) >= FORGIVENESS_WINDOW_DAYS

    /**
     * The streak length ending on each goal day (goal days counted, rest days not), with forgiveness.
     * @param goalDays the days the step goal was reached
     */
    fun runLengths(goalDays: Set<LocalDate>): Map<LocalDate, Int> = runs(goalDays).mapValues { it.value.length }

    /**
     * The longest streak and the first day it was reached, or null without any goal day.
     */
    fun longest(goalDays: Set<LocalDate>): Pair<Int, LocalDate>? {
        val runs = runs(goalDays)
        var best: Pair<Int, LocalDate>? = null
        for (day in goalDays.sorted()) {
            val length = runs.getValue(day).length
            if (best == null || length > best.first) {
                best = length to day
            }
        }
        return best
    }

    /**
     * The streak as of [today]: the length of the run still alive, counting a goal reached today. Alive when today
     * is a goal day, the day before was, or (with forgiveness still available) the day before that was and today can
     * still be the rest day. 0 once the streak is broken.
     */
    fun current(goalDays: Set<LocalDate>, today: LocalDate): Int {
        val lastGoal = goalDays.filter { !it.isAfter(today) }.maxOrNull() ?: return 0
        val run = runs(goalDays).getValue(lastGoal)
        return when (ChronoUnit.DAYS.between(lastGoal, today)) {
            0L, 1L -> run.length // today reached, or today still pending after yesterday
            2L -> if (canForgive(run.lastForgiven, lastGoal.plusDays(1))) run.length else 0 // yesterday can be the rest day
            else -> 0 // two or more days missed in a row
        }
    }
}
