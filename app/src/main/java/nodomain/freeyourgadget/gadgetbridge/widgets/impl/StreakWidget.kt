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
package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.badges.BadgeFamily
import nodomain.freeyourgadget.gadgetbridge.activities.charts.badges.BadgeHistory
import nodomain.freeyourgadget.gadgetbridge.activities.charts.badges.BadgesFragment
import nodomain.freeyourgadget.gadgetbridge.activities.charts.badges.Streaks
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.Stat
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope

/**
 * Days in a row with the step goal reached, up to the selected day, against the best streak and the next streak badge.
 * A single missed day is forgiven as a rest day (see [Streaks]), so one off day doesn't reset it; a broken streak
 * shows the best one instead of a bare zero.
 */
object StreakWidget : MetricWidget<StreakWidget.Data>() {
    override val id = "streak"
    override val label = R.string.widget_streak_title
    override val icon = R.drawable.ic_badge_flame
    override val chartTab = "badges"

    override fun isSupportedBy(device: GBDevice): Boolean = device.deviceCoordinator.supportsStepCounter(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val goal = ActivityUser().stepsGoal
        val devices = scope.devices.filter { isSupportedBy(it) }
        // The day totals of the badges, read again only since their last read
        val stepsByDay = scope.db { db ->
            devices.flatMap { BadgeHistory.days(db, it, scope.zone) }
                .groupingBy { it.date }
                .fold(0L) { total, day -> total + day.steps }
        }
        // Days the goal was reached, and the streak with forgiveness (shared with the badges and records)
        val goalDays = stepsByDay.filterValues { it >= goal }.keys
        val todayDone = scope.day in goalDays
        val streak = Streaks.current(goalDays, scope.day)
        // The best streak of the whole history, kept when the badges were last computed
        val best = devices.maxOfOrNull {
            GBApplication.getDeviceSpecificSharedPrefs(it.address).getLong(BadgesFragment.PREF_BEST_STREAK, 0)
        } ?: 0
        return Data(streak, maxOf(best, streak.toLong()).toInt(), todayDone)
    }

    override fun previewData() = Data(days = 5, best = 12, todayDone = true)

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel {
        val next = BadgeFamily.STREAK.thresholds.firstOrNull { it > data.days }
        val fraction = if (next != null) data.days / next.toFloat() else 1f
        val status = context.getString(if (data.todayDone) R.string.widget_streak_today_done else R.string.widget_streak_today_pending)
        // A broken streak shows the best one, so it never reads as a bare "0"
        val chip = if (data.days > 0) {
            context.resources.getQuantityString(R.plurals.widget_streak_days, data.days, data.days)
        } else {
            context.getString(R.string.widget_streak_best_chip, data.best)
        }
        return TileModel(
            value = data.days.toString(),
            gauge = GaugeSpec.Progress(WidgetColors.exercise, fraction),
            status = StatusLine.Chip(if (data.todayDone) WidgetColors.activity else WidgetColors.exercise, chip),
            stats = if (config.isLargeStyle) {
                listOfNotNull(
                    Stat(data.best.toString(), context.getString(R.string.widget_streak_best)),
                    next?.let { Stat(it.toString(), context.getString(R.string.widget_streak_next_badge)) },
                    Stat(status, ""),
                )
            } else {
                emptyList()
            },
        )
    }

    class Data(val days: Int, val best: Int, val todayDone: Boolean)
}
