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
package nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import kotlin.math.roundToInt

/**
 * The level of a sleep score, shown as a face from very happy to sad.
 */
enum class SleepQualityLevel(
    val minimumScore: Int,
    @StringRes val label: Int,
    @StringRes val summary: Int,
    @DrawableRes val face: Int,
    @ColorRes val color: Int,
) {
    EXCELLENT(85, R.string.sleep_quality_excellent, R.string.sleep_quality_summary_excellent, R.drawable.ic_sleep_face_excellent, R.color.sleep_quality_excellent),
    GOOD(70, R.string.sleep_quality_good, R.string.sleep_quality_summary_good, R.drawable.ic_sleep_face_good, R.color.sleep_quality_good),
    FAIR(55, R.string.sleep_quality_fair, R.string.sleep_quality_summary_fair, R.drawable.ic_sleep_face_fair, R.color.sleep_quality_fair),
    POOR(0, R.string.sleep_quality_poor, R.string.sleep_quality_summary_poor, R.drawable.ic_sleep_face_poor, R.color.sleep_quality_poor);

    companion object {
        @JvmStatic
        fun of(score: Int): SleepQualityLevel = entries.first { score >= it.minimumScore }
    }
}

/**
 * A sleep score from 0 to 100 for devices that don't compute one. Points:
 * - duration, 50: the share of the sleep goal, squared below the goal; 10 points less per hour beyond 2 hours over it
 * - deep sleep, 25: full from 20% of the sleep
 * - awake time, 15: full up to 10 minutes, none from 60 minutes
 * - REM sleep, 10: full between 15% and 30% of the sleep
 * Without deep or REM data (the device doesn't report that stage), the other parts are scaled to 100.
 */
object SleepQuality {
    private const val DURATION_POINTS = 50.0
    private const val DEEP_POINTS = 25.0
    private const val AWAKE_POINTS = 15.0
    private const val REM_POINTS = 10.0
    private const val OVERSLEEP_TOLERANCE_MINUTES = 120.0
    private const val OVERSLEEP_POINTS_PER_HOUR = 10.0
    private const val DEEP_FULL_SHARE = 0.20
    private const val AWAKE_FULL_MINUTES = 10.0
    private const val AWAKE_NONE_MINUTES = 60.0
    private const val REM_LOW_SHARE = 0.15
    private const val REM_HIGH_SHARE = 0.30
    private const val REM_EXCESS_POINTS_PER_SHARE = 50.0

    /**
     * @return the score, or null without sleep
     */
    @JvmStatic
    fun score(sleepMinutes: Double, deepMinutes: Double, remMinutes: Double, awakeMinutes: Double, goalMinutes: Int): Int? {
        if (sleepMinutes <= 0 || goalMinutes <= 0) {
            return null
        }
        val goalShare = sleepMinutes / goalMinutes
        val duration = if (goalShare < 1) {
            DURATION_POINTS * goalShare * goalShare
        } else {
            val overHours = (sleepMinutes - goalMinutes - OVERSLEEP_TOLERANCE_MINUTES).coerceAtLeast(0.0) / 60
            (DURATION_POINTS - overHours * OVERSLEEP_POINTS_PER_HOUR).coerceAtLeast(0.0)
        }
        val awake = AWAKE_POINTS * ((AWAKE_NONE_MINUTES - awakeMinutes) / (AWAKE_NONE_MINUTES - AWAKE_FULL_MINUTES)).coerceIn(0.0, 1.0)
        var points = duration + awake
        var maximum = DURATION_POINTS + AWAKE_POINTS
        if (deepMinutes > 0) {
            points += DEEP_POINTS * (deepMinutes / sleepMinutes / DEEP_FULL_SHARE).coerceIn(0.0, 1.0)
            maximum += DEEP_POINTS
        }
        if (remMinutes > 0) {
            val remShare = remMinutes / sleepMinutes
            points += when {
                remShare < REM_LOW_SHARE -> REM_POINTS * remShare / REM_LOW_SHARE
                remShare <= REM_HIGH_SHARE -> REM_POINTS
                else -> (REM_POINTS - (remShare - REM_HIGH_SHARE) * REM_EXCESS_POINTS_PER_SHARE).coerceAtLeast(0.0)
            }
            maximum += REM_POINTS
        }
        val total = points * 100 / maximum
        return total.roundToInt().coerceIn(0, 100)
    }
}
