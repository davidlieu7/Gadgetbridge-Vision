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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.Chart
import com.google.android.material.button.MaterialButtonToggleGroup
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.AbstractChartFragment
import nodomain.freeyourgadget.gadgetbridge.activities.charts.ChartsData
import nodomain.freeyourgadget.gadgetbridge.activities.charts.ChartsHost
import nodomain.freeyourgadget.gadgetbridge.activities.charts.badges.BadgesFragment
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileData
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.addStatTileGrid
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.FormatUtils
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * A weekly or monthly report like the vendor apps: the period's activity, sleep and workouts, each compared with the
 * period before.
 */
class ReportFragment : AbstractChartFragment<ReportFragment.ReportData>() {
    private lateinit var periodGroup: MaterialButtonToggleGroup
    private lateinit var periodLabel: TextView
    private lateinit var tiles: LinearLayout
    private lateinit var highlights: LinearLayout

    override fun getTitle(): String = getString(R.string.report_title)

    override fun init() {
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_report, container, false)
        periodGroup = view.findViewById(R.id.report_period_group)
        periodLabel = view.findViewById(R.id.report_period_label)
        tiles = view.findViewById(R.id.report_tiles)
        highlights = view.findViewById(R.id.report_highlights)
        periodGroup.check(if (period() == ReportPeriod.MONTH) R.id.report_period_month else R.id.report_period_week)
        periodGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val period = if (checkedId == R.id.report_period_month) ReportPeriod.MONTH else ReportPeriod.WEEK
                GBApplication.getPrefs().preferences.edit().putString(PREF_PERIOD, period.name).apply()
                refresh()
            }
        }
        view.setOnScrollChangeListener { _, _, scrollY, _, _ -> chartsHost.enableSwipeRefresh(scrollY == 0) }
        refresh()
        return view
    }

    private fun period(): ReportPeriod =
        if (GBApplication.getPrefs().getString(PREF_PERIOD, ReportPeriod.WEEK.name) == ReportPeriod.MONTH.name) ReportPeriod.MONTH else ReportPeriod.WEEK

    override fun refreshInBackground(chartsHost: ChartsHost, db: DBHandler, device: GBDevice): ReportData {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochSecond(tsEnd.toLong()).atZone(zone).toLocalDate()
        val period = period()
        val (current, full) = PeriodReport.ranges(period, day, WeekFields.of(Locale.getDefault()).firstDayOfWeek)
        val today = LocalDate.now(zone)
        val previous = PeriodReport.comparable(full, current, today)
        val stepsGoal = ActivityUser().stepsGoal
        // No data after today
        val days = ReportHistory.days(db, device, PeriodRange(previous.start, minOf(current.end, maxOf(today, current.start))), zone)
        return ReportData(
            period = period,
            partial = previous != full,
            current = PeriodReport.summarize(current, days, stepsGoal),
            previous = PeriodReport.summarize(previous, days, stepsGoal),
            currentDays = days.filter { it.date in current },
            currentPai = ReportHistory.paiAtEnd(db, device, current, zone),
            previousPai = ReportHistory.paiAtEnd(db, device, previous, zone),
            currentBadges = badgesEarnedIn(device, current),
            previousBadges = badgesEarnedIn(device, previous),
        )
    }

    /**
     * Badge tiers earned in [range], from the dates kept when the badges were last shown.
     */
    private fun badgesEarnedIn(device: GBDevice, range: PeriodRange): Int? {
        val dates = GBApplication.getDeviceSpecificSharedPrefs(device.address).getStringSet(BadgesFragment.PREF_DATES, null)
            ?: return null
        return dates.count { entry ->
            val date = entry.substringAfterLast('|', "")
            date.isNotEmpty() && LocalDate.parse(date) in range
        }
    }

    override fun updateChartsnUIThread(chartsData: ReportData) {
        val context = requireContext()
        val current = chartsData.current
        val previous = chartsData.previous
        val vs = getString(when {
            chartsData.partial && chartsData.period == ReportPeriod.MONTH -> R.string.report_vs_last_month_so_far
            chartsData.partial -> R.string.report_vs_last_week_so_far
            chartsData.period == ReportPeriod.MONTH -> R.string.report_vs_last_month
            else -> R.string.report_vs_last_week
        })
        periodLabel.text = when (chartsData.period) {
            ReportPeriod.WEEK -> getString(R.string.report_period_range,
                DateTimeUtils.formatChartDay(Date.from(current.range.start.atStartOfDay(ZoneId.systemDefault()).toInstant())),
                DateTimeUtils.formatChartDay(Date.from(current.range.end.atStartOfDay(ZoneId.systemDefault()).toInstant())))
            ReportPeriod.MONTH -> current.range.start.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }

        val number = NumberFormat.getInstance()
        val empty = getString(R.string.stats_empty_value)
        val user = ActivityUser()
        fun minutes(value: Double?) = value?.let { DateTimeUtils.formatDurationHoursMinutes(it.toLong(), TimeUnit.MINUTES) } ?: empty
        fun tile(value: String, label: Int, current: Double?, previous: Double?, lowerIsBetter: Boolean = false) =
            compared(value, getString(label), PeriodReport.change(current, previous), vs, lowerIsBetter)
        // A one-verdict-per-metric status word, reusing the sleep tab's verdict for sleep so they never disagree
        fun StatTileData.withStatus(status: MetricStatus?) =
            if (status == null) this else copy(status = getString(status.label), statusColor = ContextCompat.getColor(context, status.color))
        // The current period's daily values of one metric, for the trend line
        fun series(selector: (ReportDay) -> Float): FloatArray? =
            chartsData.currentDays.takeIf { it.size >= 2 }?.map(selector)?.toFloatArray()
        fun StatTileData.withSpark(values: FloatArray?) = if (values == null) this else copy(sparkline = values)

        val stats = mutableListOf(
            tile(current.stepsPerDay?.let { number.format(it.toLong()) } ?: empty, R.string.report_steps_per_day,
                current.stepsPerDay, previous.stepsPerDay)
                .withStatus(MetricStatus.forGoal(current.stepsPerDay, user.stepsGoal.toDouble()))
                .withSpark(series { it.steps.toFloat() }),
            tile(number.format(current.goalDays), R.string.report_goal_days,
                current.goalDays.toDouble(), previous.goalDays.toDouble()),
            tile(FormatUtils.getFormattedDistanceLabel(current.distanceMeters), R.string.distance,
                current.distanceMeters, previous.distanceMeters),
            tile(minutes(current.activeMinutesPerDay), R.string.report_active_time_per_day,
                current.activeMinutesPerDay, previous.activeMinutesPerDay)
                .withStatus(MetricStatus.forGoal(current.activeMinutesPerDay, user.activeTimeGoalMinutes.toDouble()))
                .withSpark(series { it.activeMinutes.toFloat() }),
            tile(minutes(current.sleepMinutesPerNight), R.string.report_sleep_per_night,
                current.sleepMinutesPerNight, previous.sleepMinutesPerNight)
                .withStatus(MetricStatus.forSleepDuration(current.sleepMinutesPerNight, user.sleepDurationGoal))
                .withSpark(series { it.sleepMinutes.toFloat() }),
            tile(current.sleepScore?.let { number.format(it.toLong()) } ?: empty, R.string.report_sleep_score,
                current.sleepScore, previous.sleepScore)
                .withStatus(MetricStatus.forSleepQuality(current.sleepScore)),
            tile(current.sleepingHeartRate?.let { getString(R.string.bpm_value_unit, it.toInt()) } ?: empty, R.string.report_sleeping_heart_rate,
                current.sleepingHeartRate, previous.sleepingHeartRate, lowerIsBetter = true),
            tile(number.format(current.workouts), R.string.report_workouts,
                current.workouts.toDouble(), previous.workouts.toDouble()),
        )
        chartsData.currentPai?.let { pai ->
            stats += tile(number.format(pai), R.string.report_pai, pai.toDouble(), chartsData.previousPai?.toDouble())
                .withStatus(MetricStatus.forPai(pai))
        }
        chartsData.currentBadges?.let { badges ->
            stats += tile(number.format(badges), R.string.report_badges, badges.toDouble(), chartsData.previousBadges?.toDouble())
        }
        tiles.removeAllViews()
        addStatTileGrid(tiles, context, stats)

        highlights.removeAllViews()
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val highlightStats = listOfNotNull(
            current.bestDay?.let {
                StatTileData(number.format(it.steps), getString(R.string.report_best_day, it.date.format(dateFormat)))
            },
            current.bestNight?.let {
                StatTileData(number.format(it.sleepScore ?: 0), getString(R.string.report_best_night, it.date.format(dateFormat)))
            },
        )
        addStatTileGrid(highlights, context, highlightStats)
    }

    /**
     * A tile with the change since the previous period under the value: an arrow and a percentage, green when better.
     */
    private fun compared(value: String, label: String, change: Double?, vs: String, lowerIsBetter: Boolean): StatTileData {
        if (change == null) {
            return StatTileData(value, label)
        }
        val percent = NumberFormat.getPercentInstance().format(abs(change))
        if (abs(change) < SAME_CHANGE) {
            return StatTileData(value, label, null, getString(R.string.report_same, vs), null, null)
        }
        val better = (change > 0) != lowerIsBetter
        return StatTileData(
            value, label, null,
            getString(R.string.report_change, (if (change > 0) "+" else "−") + percent, vs),
            if (change > 0) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward,
            ContextCompat.getColor(requireContext(), if (better) R.color.sleep_quality_excellent else R.color.sleep_quality_poor),
        )
    }

    override fun renderCharts() {
    }

    override fun setupLegend(chart: Chart<*>) {
    }

    class ReportData(
        val period: ReportPeriod,
        /**
         * The period isn't over: [previous] holds only as many days as have passed.
         */
        val partial: Boolean,
        val current: PeriodSummary,
        val previous: PeriodSummary,
        /** The current period's days, for the per-metric trend sparklines. */
        val currentDays: List<ReportDay>,
        val currentPai: Int?,
        val previousPai: Int?,
        val currentBadges: Int?,
        val previousBadges: Int?,
    ) : ChartsData()

    private companion object {
        const val PREF_PERIOD = "charts_report_period"

        /**
         * A change under 1% shows as "same".
         */
        const val SAME_CHANGE = 0.01
    }
}
