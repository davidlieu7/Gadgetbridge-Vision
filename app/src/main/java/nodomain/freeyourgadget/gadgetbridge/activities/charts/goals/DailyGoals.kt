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
package nodomain.freeyourgadget.gadgetbridge.activities.charts.goals

import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors

/**
 * The daily goals of the goals tab, in the order of their rows. The first [RINGS] are also drawn as rings, outside in.
 */
enum class DailyGoal(@StringRes val label: Int, @DrawableRes val icon: Int, val color: Int) {
    CALORIES(R.string.calories, R.drawable.ic_calories, WidgetColors.exercise),
    STEPS(R.string.steps, R.drawable.ic_steps, WidgetColors.activity),
    ACTIVE_TIME(R.string.activity_list_summary_active_time, R.drawable.ic_timer, WidgetColors.activeTime),
    MOVING_HOURS(R.string.goals_moving_hours, R.drawable.ic_activity_walking, Color.rgb(0x26, 0xa6, 0x9a)),
    DISTANCE(R.string.distance, R.drawable.ic_map, WidgetColors.distance),
    SLEEP(R.string.menuitem_sleep, R.drawable.ic_activity_sleep, WidgetColors.lightSleep),
    ;

    companion object {
        const val RINGS = 4
    }
}

/**
 * How far [goal] got on a day: [current] against [target], in kcal, steps, minutes, hours, meters or minutes.
 * [estimated] when the value is computed rather than measured.
 */
data class DailyGoalProgress(val goal: DailyGoal, val current: Double, val target: Double, val estimated: Boolean = false) {
    val fraction: Float get() = if (target > 0) (current / target).toFloat() else 0f
    val reached: Boolean get() = target > 0 && current >= target
}

object DailyGoals {
    /**
     * An hour counts as a moving hour with at least this many steps.
     */
    const val MOVING_HOUR_STEPS = 250f

    /**
     * Walking burns about this many kcal per kg of body weight and per km, beyond the resting energy.
     */
    private const val KCAL_PER_KG_KM = 0.5

    @JvmStatic
    fun movingHours(hourlySteps: FloatArray): Int = hourlySteps.count { it >= MOVING_HOUR_STEPS }

    /**
     * Active kcal from the walked distance, for devices that don't measure calories.
     */
    @JvmStatic
    fun estimatedActiveKcal(distanceMeters: Double, weightKg: Int): Double = distanceMeters / 1000 * weightKg * KCAL_PER_KG_KM
}
