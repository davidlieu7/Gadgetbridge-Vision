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

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep.SleepQualityLevel

/**
 * A one-word qualitative verdict for a metric, like the "Optimal / Within range" labels of the vendor dashboards.
 * There is a single verdict per metric: sleep quality reuses [SleepQualityLevel] (so the report and the sleep tab
 * never disagree), and the other verdicts cover a dimension the sleep face doesn't (duration, goal, PAI). Metrics
 * that already read as a plain count (distance, workouts, badges) get none. Verdicts avoid medical judgements.
 */
data class MetricStatus(@StringRes val label: Int, @ColorRes val color: Int) {
    companion object {
        // Reused across metrics so the colour always means the same thing
        @ColorRes private val GOOD = R.color.sleep_quality_excellent
        @ColorRes private val OK = R.color.sleep_quality_fair
        @ColorRes private val LOW = R.color.sleep_quality_poor

        /** On track / below, for a daily average against a goal (steps, active time). */
        fun forGoal(average: Double?, goal: Double): MetricStatus? {
            if (average == null || goal <= 0) return null
            return if (average >= goal) MetricStatus(R.string.status_on_track, GOOD)
            else MetricStatus(R.string.status_below_goal, OK)
        }

        /** Enough / short / long, for sleep duration against the sleep goal (a different question than quality). */
        fun forSleepDuration(minutesPerNight: Double?, goalMinutes: Int): MetricStatus? {
            if (minutesPerNight == null || goalMinutes <= 0) return null
            return when {
                minutesPerNight < goalMinutes * 0.85 -> MetricStatus(R.string.status_sleep_short, OK)
                minutesPerNight > goalMinutes + 120 -> MetricStatus(R.string.status_sleep_long, OK)
                else -> MetricStatus(R.string.status_sleep_enough, GOOD)
            }
        }

        /** The sleep quality level (Excellent/Good/Fair/Poor), reused so the report matches the sleep tab. */
        fun forSleepQuality(score: Double?): MetricStatus? {
            if (score == null) return null
            val level = SleepQualityLevel.of(score.toInt())
            return MetricStatus(level.label, level.color)
        }

        /** Goal reached / building up, for PAI against its 100 goal. */
        fun forPai(pai: Int?): MetricStatus? {
            if (pai == null) return null
            return if (pai >= 100) MetricStatus(R.string.status_pai_reached, GOOD)
            else MetricStatus(R.string.status_pai_building, OK)
        }
    }
}
