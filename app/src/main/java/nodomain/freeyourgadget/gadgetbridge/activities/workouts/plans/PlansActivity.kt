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

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.GbChartView
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.WeightChartData
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.SectionHeaderView
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.applyStatTileStyle
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.DailyTotals
import org.slf4j.LoggerFactory
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.GregorianCalendar
import kotlin.math.abs

/**
 * Training plans and a weight-loss plan, like the plans of the vendor apps. Sessions are counted from the
 * recorded steps and runs.
 */
class PlansActivity : AbstractGBActivity() {
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plans)
        container = findViewById(R.id.plans_container)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        lifecycleScope.launch {
            val training = PlanStore.trainingPlan()
            val progress = training?.let { (plan, start) ->
                withContext(Dispatchers.IO) {
                    try {
                        loadProgress(plan, start)
                    } catch (e: Exception) {
                        LOG.error("Failed to load the progress of {}", plan.key, e)
                        null
                    }
                }
            }
            render(progress)
        }
    }

    private fun loadProgress(plan: TrainingPlan, start: LocalDate): PlanProgress {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val devices = GBApplication.app().deviceManager.devices.filter { it.deviceCoordinator.supportsActivityTracking(it) }
        val steps = HashMap<LocalDate, Long>()
        val workouts = mutableListOf<PlanWorkout>()
        GBApplication.acquireDbReadOnly().use { db ->
            var day = start
            while (!day.isAfter(today) && plan.kind == SessionKind.STEPS_DAY) {
                val calendar = GregorianCalendar.getInstance()
                calendar.timeInMillis = day.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3600 * 1000L
                steps[day] = devices.sumOf { DailyTotals.getDailyStepsForDevice(it, calendar, db) }
                day = day.plusDays(1)
            }
            val deviceIds = devices.mapNotNull { DBHelper.findDevice(it, db.daoSession)?.id }
            if (deviceIds.isNotEmpty()) {
                db.daoSession.baseActivitySummaryDao.queryBuilder().where(
                    BaseActivitySummaryDao.Properties.DeviceId.`in`(deviceIds),
                    BaseActivitySummaryDao.Properties.StartTime.ge(Date(start.atStartOfDay(zone).toInstant().toEpochMilli())),
                ).list().forEach {
                    workouts += PlanWorkout(
                        Instant.ofEpochMilli(it.startTime.time).atZone(zone).toLocalDate(),
                        ActivityKind.fromCode(it.activityKind),
                        (it.endTime.time - it.startTime.time) / 60_000,
                    )
                }
            }
        }
        return TrainingPlans.progress(plan, start, today, steps, workouts)
    }

    private fun render(progress: PlanProgress?) {
        container.removeAllViews()
        container.addView(header(R.string.plans_training, first = true))
        if (progress != null) {
            container.addView(activePlanCard(progress))
        }
        TrainingPlan.entries.filter { it != progress?.plan }.forEach { container.addView(planCard(it)) }

        container.addView(header(R.string.plans_weight))
        val weightPlan = PlanStore.weightPlan()
        container.addView(if (weightPlan == null) createWeightPlanCard() else weightPlanCard(weightPlan))
    }

    private fun activePlanCard(progress: PlanProgress): View {
        val plan = progress.plan
        val content = cardContent()
        content.addView(text(getString(plan.title), 18f, bold = true))
        if (progress.finished) {
            content.addView(text(getString(R.string.plans_complete), 15f, color = getColor(R.color.sleep_quality_excellent)))
        } else {
            val week = plan.weeks[progress.currentWeek]
            content.addView(text(getString(R.string.plans_week_of, progress.currentWeek + 1, plan.weeks.size), 14f, color = accent()))
            content.addView(text(getString(week.instructions, NumberFormat.getInstance().format(week.target)), 14f).apply { topMargin(6) })
            val done = progress.done[progress.currentWeek]
            content.addView(progressBar(done.toDouble() / week.sessions, accent()))
            content.addView(text(getString(R.string.plans_sessions, done, week.sessions), 13f, color = secondary()))
        }
        content.addView(text(getString(R.string.plans_weeks_done, progress.completedWeeks, plan.weeks.size), 13f, color = secondary()).apply { topMargin(6) })
        content.addView(weekList(progress))
        if (plan.kind == SessionKind.RUN_MINUTES) {
            content.addView(text(getString(R.string.plans_runs_note), 12f, color = secondary()).apply { topMargin(6) })
        }
        content.addView(button(R.string.plans_stop) {
            PlanStore.stopTrainingPlan()
            load()
        })
        return card(content)
    }

    /**
     * One line per week: done when all its sessions are.
     */
    private fun weekList(progress: PlanProgress): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, 0)
        progress.plan.weeks.forEachIndexed { index, week ->
            val done = progress.done[index]
            val mark = when {
                done >= week.sessions -> "✓"
                index == progress.currentWeek -> "•"
                else -> " "
            }
            addView(text("$mark  " + getString(R.string.plans_week_of, index + 1, progress.plan.weeks.size) + "  $done/${week.sessions}", 13f,
                color = if (done >= week.sessions) getColor(R.color.sleep_quality_excellent) else secondary()))
        }
    }

    private fun planCard(plan: TrainingPlan): View {
        val content = cardContent()
        content.addView(text(getString(plan.title), 17f, bold = true))
        content.addView(text(getString(plan.summary), 14f, color = secondary()).apply { topMargin(4) })
        content.addView(button(R.string.plans_start) {
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.plans_start_confirm, getString(plan.title)))
                .setPositiveButton(R.string.plans_start) { _, _ ->
                    PlanStore.startTrainingPlan(plan, LocalDate.now())
                    load()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })
        return card(content)
    }

    private fun createWeightPlanCard(): View {
        val content = cardContent()
        content.addView(text(getString(R.string.plans_weight_intro), 14f))
        content.addView(button(R.string.plans_weight_create) { editWeightPlan(null) })
        content.addView(text(getString(R.string.plans_weight_disclaimer), 12f, color = secondary()).apply { topMargin(6) })
        return card(content)
    }

    private fun weightPlanCard(plan: WeightPlan): View {
        val number = NumberFormat.getInstance().apply { maximumFractionDigits = 1 }
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val today = LocalDate.now()
        val log = PlanStore.weightLog().filter { !it.first.isBefore(plan.start) }
        val latest = log.lastOrNull()?.second ?: plan.startKg
        val expected = plan.expectedKg(today)

        val content = cardContent()
        content.addView(text(getString(R.string.plans_weight_goal, number.format(plan.toLoseKg), plan.targetDate.format(dateFormat)), 17f, bold = true))
        content.addView(progressBar(plan.progress(latest), getColor(R.color.sleep_quality_excellent)))
        content.addView(text(status(latest, expected, number), 14f, color = statusColor(latest, expected)))
        content.addView(row(getString(R.string.plans_weight_latest), "${number.format(latest)} kg"))
        content.addView(row(getString(R.string.plans_weight_expected), "${number.format(expected)} kg"))
        content.addView(row(getString(R.string.plans_weight_deficit_label), getString(R.string.plans_weight_deficit, plan.dailyDeficitKcal)))
        content.addView(row(getString(R.string.plans_weight_steps_label),
            getString(R.string.plans_weight_steps, NumberFormat.getInstance().format(plan.extraStepsPerDay(ActivityUser().stepLengthCm)))))

        if (log.isNotEmpty()) {
            val zone = ZoneId.systemDefault()
            val chart = GbChartView(this)
            val seconds = log.map { it.first.atStartOfDay(zone).toEpochSecond() }
            chart.setSpec(WeightChartData.spec(
                plan.start.atStartOfDay(zone).toEpochSecond(),
                maxOf(today, plan.targetDate).atStartOfDay(zone).toEpochSecond(),
                seconds.toLongArray(),
                log.map { it.second }.toDoubleArray(),
                plan.targetKg,
                getString(R.string.menuitem_weight),
                accent(),
                getColor(R.color.sleep_quality_excellent),
            ))
            content.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)).apply { topMargin = dp(10) })
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button(R.string.plans_weight_log) { logWeight() })
        buttons.addView(button(R.string.plans_edit) { editWeightPlan(plan) }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
        buttons.addView(button(R.string.plans_stop) {
            PlanStore.stopWeightPlan()
            load()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
        content.addView(buttons)
        return card(content)
    }

    private fun status(latest: Double, expected: Double, number: NumberFormat): String = when {
        abs(latest - expected) <= ON_TRACK_KG -> getString(R.string.plans_weight_on_track)
        latest > expected -> getString(R.string.plans_weight_behind, number.format(latest - expected))
        else -> getString(R.string.plans_weight_ahead, number.format(expected - latest))
    }

    private fun statusColor(latest: Double, expected: Double): Int =
        getColor(if (latest - expected > ON_TRACK_KG) R.color.sleep_quality_fair else R.color.sleep_quality_excellent)

    private fun editWeightPlan(plan: WeightPlan?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val current = numberField(R.string.plans_weight_current, plan?.startKg ?: PlanStore.weightLog().lastOrNull()?.second
            ?: ActivityUser().weightKg.toDouble())
        val target = numberField(R.string.plans_weight_target, plan?.targetKg)
        form.addView(current.first)
        form.addView(current.second)
        form.addView(target.first)
        form.addView(target.second)
        form.addView(text(getString(R.string.plans_weight_pace), 13f, color = secondary()).apply { topMargin(10) })
        val paces = com.google.android.material.chip.ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        WeightPlan.PACES.forEachIndexed { index, pace ->
            paces.addView(com.google.android.material.chip.Chip(this).apply {
                id = View.generateViewId()
                text = getString(R.string.plans_weight_pace_value, NumberFormat.getInstance().format(pace))
                isCheckable = true
                tag = pace
                isChecked = pace == (plan?.kgPerWeek ?: 0.5) || (plan == null && index == 1)
            })
        }
        form.addView(paces)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plans_weight)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val start = current.second.text.toString().replace(',', '.').toDoubleOrNull()
                val goal = target.second.text.toString().replace(',', '.').toDoubleOrNull()
                val pace = paces.findViewById<View>(paces.checkedChipId)?.tag as? Double ?: 0.5
                if (start == null || goal == null || start !in WEIGHT_RANGE || goal !in WEIGHT_RANGE || goal >= start) {
                    MaterialAlertDialogBuilder(this).setMessage(R.string.plans_weight_invalid).setPositiveButton(android.R.string.ok, null).show()
                    return@setPositiveButton
                }
                PlanStore.saveWeightPlan(WeightPlan(start, goal, pace, plan?.start ?: LocalDate.now()))
                if (plan == null) PlanStore.logWeight(LocalDate.now(), start)
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun logWeight() {
        val field = numberField(R.string.plans_weight_current, PlanStore.weightLog().lastOrNull()?.second)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field.first)
            addView(field.second)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plans_weight_log)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val kg = field.second.text.toString().replace(',', '.').toDoubleOrNull()
                if (kg == null || kg !in WEIGHT_RANGE) {
                    MaterialAlertDialogBuilder(this).setMessage(R.string.plans_weight_invalid).setPositiveButton(android.R.string.ok, null).show()
                    return@setPositiveButton
                }
                PlanStore.logWeight(LocalDate.now(), kg)
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun numberField(label: Int, value: Double?): Pair<TextView, EditText> = text(getString(label), 13f, color = secondary()) to
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            value?.let { setText(NumberFormat.getInstance().apply { maximumFractionDigits = 1; isGroupingUsed = false }.format(it)) }
        }

    private fun header(title: Int, first: Boolean = false): View = SectionHeaderView(this).apply {
        this.title = getString(title)
        if (first) showDivider = false
    }

    private fun cardContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun card(content: View): View = MaterialCardView(this).apply {
        applyStatTileStyle(this)
        addView(content)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) }
    }

    private fun row(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, 0)
        addView(text(label, 14f, color = secondary()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(value, 14f, bold = true).apply { gravity = Gravity.END })
    }

    private fun progressBar(fraction: Double, color: Int): View = LinearProgressIndicator(this).apply {
        max = PROGRESS_STEPS
        progress = (fraction * PROGRESS_STEPS).toInt().coerceIn(0, PROGRESS_STEPS)
        trackCornerRadius = dp(3)
        trackThickness = dp(8)
        setIndicatorColor(color)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10); bottomMargin = dp(6) }
    }

    private fun button(label: Int, onClick: () -> Unit): Button = MaterialButton(this).apply {
        text = getString(label)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, color: Int? = null): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(color ?: MaterialColors.getColor(this@PlansActivity, R.attr.textColorPrimary, "Plans"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun TextView.topMargin(valueDp: Int) {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(valueDp)
    }

    private fun accent() = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, "Plans")

    private fun secondary() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, "Plans")

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val LOG = LoggerFactory.getLogger(PlansActivity::class.java)
        const val PROGRESS_STEPS = 1000
        const val ON_TRACK_KG = 0.3
        val WEIGHT_RANGE = 30.0..300.0
    }
}
