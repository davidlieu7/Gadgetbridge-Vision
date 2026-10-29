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

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlansTest {
    private val start = LocalDate.of(2026, 10, 5)

    @Test
    fun walkPlan_countsDaysOverTheWeekTarget() {
        // Week 1 asks for 6000 steps on 5 days
        val steps = (0L until 7L).associate { start.plusDays(it) to if (it < 4) 6_500L else 3_000L }
        val progress = TrainingPlans.progress(TrainingPlan.WALK_MORE, start, start.plusDays(9), steps, emptyList())
        assertEquals(listOf(4, 0, 0, 0), progress.done)
        assertEquals(1, progress.currentWeek)
        assertEquals(0, progress.completedWeeks)
        assertFalse(progress.finished)
    }

    @Test
    fun runPlan_countsRunsLongEnough() {
        // Week 1 asks for 3 runs of 20 minutes; 18 minutes is within the tolerance, a walk doesn't count
        val workouts = listOf(
            PlanWorkout(start, ActivityKind.OUTDOOR_RUNNING, 25),
            PlanWorkout(start.plusDays(2), ActivityKind.RUNNING, 18),
            PlanWorkout(start.plusDays(3), ActivityKind.TREADMILL, 30),
            PlanWorkout(start.plusDays(4), ActivityKind.RUNNING, 10),
            PlanWorkout(start.plusDays(5), ActivityKind.WALKING, 60),
            PlanWorkout(start.plusDays(6), ActivityKind.RUNNING, 40),
        )
        val progress = TrainingPlans.progress(TrainingPlan.RUN_3K, start, start.plusDays(6), emptyMap(), workouts)
        assertEquals(3, progress.done[0])
        assertEquals(1, progress.completedWeeks)
    }

    @Test
    fun planIsFinishedAfterItsWeeks() {
        val progress = TrainingPlans.progress(TrainingPlan.WALK_MORE, start, start.plusWeeks(5), emptyMap(), emptyList())
        assertEquals(4, progress.currentWeek)
        assertTrue(progress.finished)
    }

    @Test
    fun weightPlan() {
        val plan = WeightPlan(80.0, 75.0, 0.5, start)
        assertEquals(10, plan.weeks)
        assertEquals(start.plusWeeks(10), plan.targetDate)
        assertEquals(550, plan.dailyDeficitKcal)
        assertEquals(79.0, plan.expectedKg(start.plusWeeks(2)), 1e-9)
        assertEquals(75.0, plan.expectedKg(start.plusWeeks(30)), 1e-9)
        assertEquals(0.4, plan.progress(78.0), 1e-9)
        assertEquals(1.0, plan.progress(74.0), 1e-9)
        // 275 kcal by walking: 275 / (0.5 * 80) = 6.875 km, with 75 cm steps
        assertEquals(9_166, plan.extraStepsPerDay(75))
    }

    @Test
    fun weightLog_parsesAndSkipsBadEntries() {
        val log = PlanStore.parseLog("2026-10-07=79.5;bad;2026-10-05=80.0;2026-10-06=x")
        assertEquals(listOf(start to 80.0, start.plusDays(2) to 79.5), log)
        assertEquals("2026-10-05=80.0;2026-10-07=79.5", PlanStore.formatLog(log))
    }
}
