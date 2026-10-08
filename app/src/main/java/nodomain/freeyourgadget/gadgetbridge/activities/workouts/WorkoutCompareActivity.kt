/*  Copyright (C) 2026 Pebrd

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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.LegendEntry
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.LineScatterCandleRadarDataSet
import com.github.mikephil.charting.data.ScatterData
import com.github.mikephil.charting.data.ScatterDataSet
import com.github.mikephil.charting.formatter.DefaultAxisValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.activities.charts.DurationXLabelFormatter
import nodomain.freeyourgadget.gadgetbridge.activities.charts.HeartRateZoneChartUtils
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.charts.DefaultWorkoutCharts
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryGroup
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummarySimpleEntry
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryTableRowEntry
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryValue
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.TableSpec
import nodomain.freeyourgadget.gadgetbridge.databinding.ActivityWorkoutCompareBinding
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate
import nodomain.freeyourgadget.gadgetbridge.model.workout.Workout
import nodomain.freeyourgadget.gadgetbridge.model.workout.WorkoutChart
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.maps.MapsManager
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory

/**
 * Side-by-side comparison of two or more workouts of the same kind:
 * a shared summary table (one column per workout, plus a delta column
 * when exactly two are compared) and metric charts with one line per
 * workout, aligned on elapsed time.
 *
 * Launched from the workout list's multi-select action mode.
 */
class WorkoutCompareActivity : AbstractGBActivity() {
    private lateinit var binding: ActivityWorkoutCompareBinding
    private val workoutValueFormatter = WorkoutValueFormatter()
    private var mapsManager: MapsManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkoutCompareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Pan and pinch zoom come from the map's touch gesture handler; the on-screen
        // zoom buttons would just cover part of the small preview.
        binding.mapView.setBuiltInZoomControls(false)
        supportActionBar?.title = getString(R.string.compare_workouts_title)

        val ids = intent.getLongArrayExtra(EXTRA_WORKOUT_IDS)
        if (ids == null || ids.size < 2) {
            showError(getString(R.string.compare_workouts_select_at_least_two))
            return
        }

        binding.loadingSpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val workouts = loadWorkouts(ids)
            binding.loadingSpinner.visibility = View.GONE
            if (workouts.size < 2) {
                showError(getString(R.string.compare_workouts_select_at_least_two))
            } else {
                render(workouts)
            }
        }
    }

    private suspend fun loadWorkouts(ids: LongArray): List<Workout> {
        val workouts = ArrayList<Workout>(ids.size)
        for (id in ids) {
            val workout = withContext(Dispatchers.IO) { loadWorkout(id) }
            if (workout != null) {
                workouts.add(workout)
            }
        }
        // Oldest first, so the delta column reads "newer minus older".
        workouts.sortBy { it.summary.startTime.time }
        return workouts
    }

    /**
     * Loads and parses one workout the same way the workout details screen does, but tolerant:
     * a workout whose device is gone or whose data fails to parse still shows up in the
     * comparison with whatever summary data is stored.
     */
    private suspend fun loadWorkout(id: Long): Workout? {
        return withContext(Dispatchers.IO) {
            val summary = try {
                GBApplication.acquireDbReadOnly().use { dbHandler ->
                    dbHandler.daoSession.baseActivitySummaryDao.load(id)
                }
            } catch (e: Exception) {
                LOG.error("Failed to load workout {}", id, e)
                return@withContext null
            } ?: return@withContext null

            val device = summary.device
            val gbDevice = GBApplication.app().deviceManager.devices.firstOrNull { gbDevice ->
                device != null && gbDevice.address.equals(device.identifier, true)
            }
            if (gbDevice == null) {
                return@withContext Workout(summary, ActivitySummaryData.fromJson(summary.summaryData), emptyList())
            }

            try {
                val parsed = gbDevice.deviceCoordinator
                    .getActivitySummaryParser(gbDevice, this@WorkoutCompareActivity)
                    .parseWorkout(summary, true)
                if (parsed.charts.isNotEmpty()) {
                    return@withContext parsed
                }
                val trackProvider = gbDevice.deviceCoordinator
                    .getActivityTrackProvider(gbDevice, this@WorkoutCompareActivity)
                val points = trackProvider?.getActivityTrack(parsed.summary)?.allPoints
                if (!points.isNullOrEmpty()) {
                    val charts = DefaultWorkoutCharts.buildDefaultCharts(
                        this@WorkoutCompareActivity,
                        points,
                        ActivityKind.fromCode(parsed.summary.activityKind),
                        parsed.data,
                        gbDevice
                    )
                    return@withContext Workout(parsed.summary, parsed.data, charts)
                }
                parsed
            } catch (e: Exception) {
                LOG.warn("Failed to parse workout {} for comparison", id, e)
                Workout(summary, ActivitySummaryData.fromJson(summary.summaryData), emptyList())
            }
        }
    }

    private fun render(workouts: List<Workout>) {
        workoutValueFormatter.setActivityKind(ActivityKind.fromCode(workouts.first().summary.activityKind))
        binding.compareScroll.visibility = View.VISIBLE
        addLegend(workouts)
        addSummaryTable(workouts)
        addMapSection(workouts)
        addCharts(workouts)
    }

    override fun onDestroy() {
        mapsManager?.onDestroy()
        mapsManager = null
        super.onDestroy()
    }

    private fun showError(message: String) {
        binding.loadingSpinner.visibility = View.GONE
        binding.compareScroll.visibility = View.GONE
        binding.errorMessage.visibility = View.VISIBLE
        binding.errorMessage.text = message
    }

    // region legend

    private fun addLegend(workouts: List<Workout>) {
        binding.legendRow.removeAllViews()
        workouts.forEachIndexed { index, workout ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorFor(index, workouts.size))
                }
            }
            val label = TextView(this).apply {
                text = workoutLabel(workout)
                textSize = 14f
                setTextColor(GBApplication.getTextColor(this@WorkoutCompareActivity))
            }
            row.addView(dot)
            row.addView(label)
            binding.legendRow.addView(row)
        }
    }

    /** Short, stable label: activity kind plus date, prefixed by the workout's custom name. */
    private fun workoutLabel(workout: Workout): String {
        val summary = workout.summary
        val kind = ActivityKind.fromCode(summary.activityKind).getLabel(this)
        val name = summary.name?.takeIf { StringUtils.isNotBlank(it) }
        val date = DateTimeUtils.formatDateTimeRelative(this, summary.startTime)
        return if (name != null && name != kind) "$name · $date" else "$kind · $date"
    }

    // endregion

    // region summary table

    private fun addSummaryTable(workouts: List<Workout>) {
        binding.summaryTable.removeAllViews()
        binding.summaryTable.visibility = View.VISIBLE

        val groupsPerWorkout = workouts.map { ActivitySummaryGroup.buildGroupedList(it.data) }
        val groupOrder = mutableListOf<String>()
        val keysByGroup = LinkedHashMap<String, MutableList<String>>()
        groupsPerWorkout.forEach { groups ->
            groups.forEach { (group, entries) ->
                if (group in SKIP_GROUPS) return@forEach
                val keys = keysByGroup.getOrPut(group) {
                    groupOrder.add(group)
                    mutableListOf()
                }
                entries.forEach { (key, _) -> if (key !in keys) keys.add(key) }
            }
        }

        // Column headers: one per workout, plus the delta column when comparing exactly two.
        val withDelta = workouts.size == 2
        val headerCells = ArrayList<ActivitySummaryValue>(workouts.size + 2)
        headerCells.add(raw(""))
        workouts.forEach { headerCells.add(raw(workoutLabel(it))) }
        if (withDelta) {
            headerCells.add(raw(getString(R.string.compare_workouts_delta)))
        }
        val headerRow = ActivitySummaryTableRowEntry(null, headerCells, true, true)

        // Title each column in the workout's own colour, the one it has in the legend,
        // the charts and the map; the label and delta columns stay muted.
        val headerColors = mutableListOf<Int?>(null)
        workouts.forEachIndexed { index, _ -> headerColors.add(colorFor(index, workouts.size)) }
        if (withDelta) {
            headerColors.add(null)
        }

        // Collect the whole table first: one TableSpec for everything, so the column
        // header, every group's rows and the delta column all line up.
        val tableGroups = ArrayList<Pair<String, ArrayList<ActivitySummaryTableRowEntry>>>()
        for (group in groupOrder) {
            val rows = ArrayList<Pair<String, List<ActivitySummarySimpleEntry?>>>()
            for (key in keysByGroup.getValue(group)) {
                val cells = groupsPerWorkout.map { groups ->
                    groups[group]?.firstOrNull { (entryKey, _) -> entryKey == key }?.right
                        as? ActivitySummarySimpleEntry
                }
                if (cells.all { it?.value == null }) continue
                rows.add(key to cells)
            }
            if (rows.isEmpty()) continue

            val tableRows = ArrayList<ActivitySummaryTableRowEntry>(rows.size)
            for ((key, cells) in rows) {
                val values = ArrayList<ActivitySummaryValue>(headerCells.size)
                values.add(raw(safeLabel(key)))
                for (cell in cells) {
                    values.add(
                        if (cell == null) ActivitySummaryValue(null, "")
                        else ActivitySummaryValue(cell.value, cell.unit)
                    )
                }
                if (withDelta) {
                    values.add(formatDelta(cells) ?: ActivitySummaryValue(null, ""))
                }
                tableRows.add(ActivitySummaryTableRowEntry(null, values, false, true))
            }
            if (tableRows.isNotEmpty()) {
                tableGroups.add(group to tableRows)
            }
        }

        if (tableGroups.isEmpty()) {
            binding.summaryTable.visibility = View.GONE
            return
        }

        // The header decides nothing: only the data rows give each column its kind.
        // Columns are centred: the values sit under their workout's header as a block,
        // rather than some right-aligned and some left-aligned column fighting each other.
        val spec = TableSpec.from(
            listOf(headerRow) + tableGroups.flatMap { it.second },
            centered = true,
            headerColors = headerColors
        )
        populateTableRow(headerRow, binding.summaryTable, workoutValueFormatter, spec, false)

        for ((group, tableRows) in tableGroups) {
            // Every group's last row ends on its own, so the section header's rule below
            // it is the only line — the same way grouped tables look on the overview tab.
            addSectionHeader(binding.summaryTable, this, workoutValueFormatter.getStringResourceByName(group), true)
            tableRows.forEachIndexed { index, row ->
                populateTableRow(
                    row, binding.summaryTable, workoutValueFormatter, spec,
                    index == tableRows.lastIndex
                )
            }
        }
    }

    /**
     * Signed difference between the two workouts, when both report the same unit numerically.
     * Returned as a numeric cell (not a formatted string) so the table renders it like every
     * other value — unit small and muted after the number; positive deltas keep a "+", which
     * the formatter never produces on its own.
     */
    private fun formatDelta(cells: List<ActivitySummarySimpleEntry?>): ActivitySummaryValue? {
        val first = cells.getOrNull(0) ?: return null
        val second = cells.getOrNull(1) ?: return null
        val firstValue = first.value as? Number ?: return null
        val secondValue = second.value as? Number ?: return null
        if (first.unit != second.unit) return null
        val delta = secondValue.toDouble() - firstValue.toDouble()
        return ActivitySummaryValue(delta, first.unit, if (delta > 0) "+" else "")
    }

    private fun safeLabel(key: String): String {
        return try {
            workoutValueFormatter.getStringResourceByName(key)
        } catch (e: Exception) {
            key
        }
    }

/** A literal cell: dates and metric labels must not be looked up as string resources. */
    private fun raw(value: String?): ActivitySummaryValue =
        ActivitySummaryValue(value, ActivitySummaryEntries.UNIT_RAW_STRING)

    // endregion

    // region map

    /**
     * Draws every compared workout's GPS route on one map, each in the workout's color.
     * Hidden when fewer than two workouts have a usable track.
     */
    private fun addMapSection(workouts: List<Workout>) {
        binding.mapSection.visibility = View.GONE
        lifecycleScope.launch {
            val tracks = loadTracks(workouts)
            if (tracks.size < MIN_TRACKS_FOR_MAP) {
                return@launch
            }

            val manager = mapsManager ?: MapsManager(this@WorkoutCompareActivity, binding.mapView).also {
                it.loadMaps()
                mapsManager = it
                binding.missingMapsWarning.visibility = if (it.isMapLoaded) View.GONE else View.VISIBLE
            }
            binding.mapSection.visibility = View.VISIBLE

            val colors = IntArray(tracks.size) { i ->
                val position = workouts.indexOfFirst { workout -> workout === tracks[i].first }
                colorFor(if (position >= 0) position else i, workouts.size)
            }
            // Draw after layout: the zoom level depends on the view's pixel size.
            binding.mapSection.post {
                try {
                    manager.setTracks(tracks.map { it.second }, colors)
                } catch (e: Exception) {
                    LOG.error("Failed to draw compared tracks", e)
                    binding.mapSection.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun loadTracks(workouts: List<Workout>): List<Pair<Workout, List<GPSCoordinate>>> {
        return withContext(Dispatchers.IO) {
            val tracks = ArrayList<Pair<Workout, List<GPSCoordinate>>>()
            for (workout in workouts) {
                try {
                    val summary = workout.summary
                    val device = summary.device ?: continue
                    val gbDevice = GBApplication.app().deviceManager.devices
                        .firstOrNull { it.address.equals(device.identifier, true) } ?: continue
                    val trackProvider = gbDevice.deviceCoordinator
                        .getActivityTrackProvider(gbDevice, this@WorkoutCompareActivity) ?: continue
                    val points = trackProvider.getActivityTrack(summary)?.allPoints
                        ?.mapNotNull { it.location }
                    if (!points.isNullOrEmpty()) {
                        tracks.add(workout to points)
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to load a track for comparison", e)
                }
            }
            tracks
        }
    }

    // endregion

    // region charts

    private fun addCharts(workouts: List<Workout>) {
        binding.chartsContainer.removeAllViews()

        val byGroup = LinkedHashMap<String, MutableList<Pair<Workout, WorkoutChart>>>()
        workouts.forEach { workout ->
            workout.charts.forEach { chart ->
                byGroup.getOrPut(chart.group) { mutableListOf() }.add(workout to chart)
            }
        }

        var added = 0
        for ((_, series) in byGroup) {
            // A metric only makes sense to overlay when at least two workouts recorded it.
            if (series.size < 2) continue
            binding.chartsContainer.addView(buildChartView(series, workouts))
            added++
        }
        binding.noMetricsMessage.visibility = if (added == 0) View.VISIBLE else View.GONE
        if (added == 0) {
            binding.noMetricsMessage.text = getString(R.string.compare_workouts_no_metrics)
        }
    }

    private fun buildChartView(series: List<Pair<Workout, WorkoutChart>>, workouts: List<Workout>): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        addSectionHeader(container, this, series.first().second.title, true)

        val chartTextColor = GBApplication.getSecondaryTextColor(this)
        val chart = CombinedChart(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(CHART_HEIGHT_DP)
            )
            description.isEnabled = false
        }
        chart.xAxis.apply {
            isDrawLabelsEnabled = true
            isDrawGridLinesEnabled = false
            isDrawLimitLinesBehindDataEnabled = true
            isEnabled = true
            textColor = chartTextColor
            position = XAxis.XAxisPosition.BOTTOM
            valueFormatter = DurationXLabelFormatter()
            labelCount = X_LABEL_COUNT
        }
        chart.axisLeft.apply {
            isDrawGridLinesEnabled = false
            isDrawTopYLabelEntryEnabled = true
            textColor = chartTextColor
            isEnabled = true
        }
        // All workouts are plotted against the same metric, so a second Y scale would only
        // confuse: drop the right axis and use the left one for everything.
        chart.axisRight.isEnabled = false
        chart.legend.apply {
            textColor = GBApplication.getTextColor(this@WorkoutCompareActivity)
            isWordWrapEnabled = true
        }

        val lineData = LineData()
        val scatterData = ScatterData()
        val legendEntries = mutableListOf<LegendEntry>()

        series.forEachIndexed { index, (workout, workoutChart) ->
            // Color by the workout's position in the full comparison, not its position in this
            // chart's series: a workout missing from one metric must keep its color everywhere.
            val position = workouts.indexOfFirst { it === workout }.takeIf { it >= 0 } ?: index
            val color = colorFor(position, workouts.size)
            var legendAdded = false
            workoutChart.chartData.dataSets.forEach { rawDataSet ->
                // HR-zone bands travel inside the HR chart's data and would repeat per workout.
                if (rawDataSet is HeartRateZoneChartUtils.ZoneAreaDataSet) return@forEach
                val dataSet = rawDataSet as? LineScatterCandleRadarDataSet<*> ?: return@forEach
                dataSet.color = color
                dataSet.highlightColor = ContextCompat.getColor(this, R.color.chart_highline_dolor)
                dataSet.highlightLineWidth = 1f
                when (dataSet) {
                    is LineDataSet<*> -> lineData.addDataSet(dataSet)
                    is ScatterDataSet<*> -> scatterData.addDataSet(dataSet)
                    else -> return@forEach
                }
                if (!legendAdded) {
                    legendEntries.add(
                        LegendEntry(
                            workoutLabel(workout),
                            dataSet.form,
                            dataSet.formSize,
                            dataSet.formLineWidth,
                            dataSet.formLineDashEffect,
                            color
                        )
                    )
                    legendAdded = true
                }
            }
            if (index == 0) {
                chart.axisLeft.valueFormatter =
                    workoutChart.chartYLabelFormatter ?: DefaultAxisValueFormatter(0)
            }
        }

        chart.legend.entries = legendEntries
        val combinedData = CombinedData()
        combinedData.lineData = lineData
        combinedData.scatterData = scatterData
        chart.data = combinedData
        container.addView(chart)
        return container
    }

    // endregion

    /** Distinct, theme-independent hue per workout column/line. */
    private fun colorFor(index: Int, total: Int): Int {
        val hue = (FIRST_HUE + index * (360f / total.coerceAtLeast(1))) % 360f
        return Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.85f))
    }

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private val LOG = LoggerFactory.getLogger(WorkoutCompareActivity::class.java)

        const val EXTRA_WORKOUT_IDS = "workout_ids"

        /** Groups that are lists (laps, intervals, sets...) rather than comparable stats. */
        private val SKIP_GROUPS = setOf(
            ActivitySummaryEntries.GROUP_LAPS,
            ActivitySummaryEntries.GROUP_INTERVALS
        )

        private const val MIN_TRACKS_FOR_MAP = 2
        private const val CHART_HEIGHT_DP = 300
        private const val X_LABEL_COUNT = 5
        private const val FIRST_HUE = 210f

        fun createIntent(context: Context, workoutIds: LongArray): Intent {
            return Intent(context, WorkoutCompareActivity::class.java).apply {
                putExtra(EXTRA_WORKOUT_IDS, workoutIds)
            }
        }
    }
}
