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

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.Chart
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.runBlocking
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.AbstractChartFragment
import nodomain.freeyourgadget.gadgetbridge.activities.charts.ChartsData
import nodomain.freeyourgadget.gadgetbridge.activities.charts.ChartsHost
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GoalRingsView
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.applyStatTileStyle
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.FormatUtils
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardQuery
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The daily goals like the activity rings of the vendor apps: calories, steps, active time and moving hours as
 * rings, and a row per goal with its progress.
 */
class GoalsFragment : AbstractChartFragment<GoalsFragment.GoalsData>() {
    private lateinit var dateView: TextView
    private lateinit var rings: GoalRingsView
    private lateinit var summary: TextView
    private lateinit var rows: LinearLayout

    override fun getTitle(): String = getString(R.string.goals_title)

    override fun init() {
    }

    override fun isSingleDay() = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_goals, container, false)
        dateView = view.findViewById(R.id.goals_date)
        rings = view.findViewById(R.id.goals_rings)
        summary = view.findViewById(R.id.goals_summary)
        rows = view.findViewById(R.id.goals_rows)
        view.setOnScrollChangeListener { _, _, scrollY, _, _ -> chartsHost.enableSwipeRefresh(scrollY == 0) }
        refresh()
        return view
    }

    override fun refreshInBackground(chartsHost: ChartsHost, db: DBHandler, device: GBDevice): GoalsData {
        val day = Instant.ofEpochSecond(tsEnd.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
        val scope = WidgetDataScope(DashboardQuery.forDay(day), listOf(device))
        val coordinator = device.deviceCoordinator
        val user = ActivityUser()
        val goals = runBlocking {
            buildList {
                if (coordinator.supportsStepCounter(device)) {
                    val distance = scope.distanceTotal().toDouble()
                    if (coordinator.supportsActiveCalories(device)) {
                        add(DailyGoalProgress(DailyGoal.CALORIES, scope.activeCaloriesTotal().toDouble(), user.caloriesBurntGoal.toDouble()))
                    } else {
                        add(DailyGoalProgress(DailyGoal.CALORIES, DailyGoals.estimatedActiveKcal(distance, user.weightKg),
                            user.caloriesBurntGoal.toDouble(), estimated = true))
                    }
                    add(DailyGoalProgress(DailyGoal.STEPS, scope.stepsTotal().toDouble(), user.stepsGoal.toDouble()))
                    add(DailyGoalProgress(DailyGoal.ACTIVE_TIME, scope.activeMinutesTotal().toDouble(), user.activeTimeGoalMinutes.toDouble()))
                    val hourlySteps = scope.hourlySums(listOf(device)) { it.steps.toFloat() }
                    add(DailyGoalProgress(DailyGoal.MOVING_HOURS, DailyGoals.movingHours(hourlySteps).toDouble(),
                        user.standingTimeGoalHours.toDouble()))
                    add(DailyGoalProgress(DailyGoal.DISTANCE, distance, user.distanceGoalMeters.toDouble()))
                }
                if (coordinator.supportsSleepMeasurement(device)) {
                    add(DailyGoalProgress(DailyGoal.SLEEP, scope.sleepMinutesTotal().toDouble(), user.sleepDurationGoal.toDouble()))
                }
            }
        }
        return GoalsData(goals)
    }

    override fun updateChartsnUIThread(chartsData: GoalsData) {
        val context = requireContext()
        dateView.text = DateTimeUtils.formatChartDate(endDate)
        rings.setRings(chartsData.goals.filter { it.goal.ordinal < DailyGoal.RINGS }
            .map { GoalRingsView.Ring(it.goal.color, it.fraction.coerceAtMost(1f)) })
        summary.text = getString(R.string.goals_summary, chartsData.goals.count { it.reached }, chartsData.goals.size)
        rows.removeAllViews()
        for (progress in chartsData.goals) {
            rows.addView(row(context, progress))
        }
    }

    private fun row(context: Context, goal: DailyGoalProgress): View {
        val card = MaterialCardView(context).apply { applyStatTileStyle(this) }
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(context, 8) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padding = dp(context, 14)
            setPadding(padding, dp(context, 12), padding, dp(context, 12))
        }
        content.addView(ImageView(context).apply {
            setImageResource(goal.goal.icon)
            imageTintList = ColorStateList.valueOf(goal.goal.color)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(context, 28), dp(context, 28)))

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val labelRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val label = if (goal.estimated) {
            getString(R.string.goals_estimated, getString(goal.goal.label))
        } else {
            getString(goal.goal.label)
        }
        labelRow.addView(TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(MaterialColors.getColor(context, R.attr.textColorPrimary, "Goals"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        labelRow.addView(TextView(context).apply {
            text = NumberFormat.getPercentInstance().format(goal.fraction.toDouble())
            textSize = 13f
            setTextColor(if (goal.reached) goal.goal.color else secondaryColor(context))
        })
        texts.addView(labelRow)
        texts.addView(TextView(context).apply {
            text = getString(R.string.goals_value_of, format(context, goal.goal, goal.current), format(context, goal.goal, goal.target))
            textSize = 13f
            setTextColor(secondaryColor(context))
        })
        texts.addView(LinearProgressIndicator(context).apply {
            max = PROGRESS_STEPS
            progress = (goal.fraction * PROGRESS_STEPS).toInt().coerceIn(0, PROGRESS_STEPS)
            trackCornerRadius = dp(context, 3)
            trackThickness = dp(context, 6)
            setIndicatorColor(goal.goal.color)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 6)
        })
        content.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(context, 14)
        })
        card.addView(content)
        return card
    }

    private fun format(context: Context, goal: DailyGoal, value: Double): String = when (goal) {
        DailyGoal.CALORIES -> getString(R.string.body_composition_kcal, value.toInt())
        DailyGoal.STEPS -> NumberFormat.getInstance().format(value.toLong())
        DailyGoal.ACTIVE_TIME, DailyGoal.SLEEP -> DateTimeUtils.formatDurationHoursMinutes(value.toLong(), TimeUnit.MINUTES)
        DailyGoal.MOVING_HOURS -> context.resources.getQuantityString(R.plurals.goals_hours, value.toInt(), value.toInt())
        DailyGoal.DISTANCE -> FormatUtils.getFormattedDistanceLabel(value)
    }

    override fun renderCharts() {
    }

    override fun setupLegend(chart: Chart<*>) {
    }

    private fun secondaryColor(context: Context) =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, "Goals")

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    class GoalsData(val goals: List<DailyGoalProgress>) : ChartsData()

    private companion object {
        const val PROGRESS_STEPS = 1000
    }
}
