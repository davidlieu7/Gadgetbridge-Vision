package nodomain.freeyourgadget.gadgetbridge.activities.workouts.charts

import android.content.Context
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.activities.charts.HeartRateZoneChartUtils
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.ChartSelection
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.GbChartView
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSide
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartValueFormat
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.LimitLineSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.SeriesStyle
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.durationLabel
import nodomain.freeyourgadget.gadgetbridge.model.heartratezones.HeartRateZonesResolver
import nodomain.freeyourgadget.gadgetbridge.model.workout.WorkoutChart
import java.util.Locale
import kotlin.math.abs

/**
 * Turns [WorkoutChart]s into a [ChartSpec] over the seconds since the workout started. The first chart uses the
 * start axis, a second one the end axis.
 */
object WorkoutChartSpecs {
    private const val MS_PER_SECOND = 1000.0

    @JvmStatic
    fun spec(context: Context, charts: List<WorkoutChart>, showZones: Boolean, view: GbChartView): ChartSpec {
        val metricSeries = charts.mapIndexed { index, chart -> series(chart, sideOf(index)) }
        val points = metricSeries.flatten().flatMap { it.points }
        if (points.isEmpty()) {
            return ChartSpec.EMPTY
        }
        val zoneSeries = mutableListOf<ChartSeries>()
        val zoneLines = mutableListOf<LimitLineSpec>()
        if (showZones) {
            charts.forEachIndexed { index, chart ->
                zoneSeries += zoneBands(context, chart, metricSeries[index], sideOf(index))
                zoneLines += zoneLines(context, chart, sideOf(index))
            }
        }
        return ChartSpec(
            series = zoneSeries + metricSeries.flatten(),
            xAxis = AxisSpec(
                format = ChartValueFormat.DURATION_SECONDS,
                minimum = points.minOf { it.x },
                maximum = points.maxOf { it.x },
            ),
            yAxis = yAxis(charts[0], view.axisLeft),
            endYAxis = charts.getOrNull(1)?.let { yAxis(it, view.axisRight) },
            limitLines = zoneLines,
        )
    }

    /**
     * Tooltip content for the charts: the elapsed time, then each chart's nearest value.
     */
    @JvmStatic
    fun selection(charts: List<WorkoutChart>, view: GbChartView): (Double) -> ChartSelection {
        val points = charts.mapIndexed { index, chart -> series(chart, sideOf(index)).flatMap { it.points }.sortedBy { it.x } }
        val xs = points.map { list -> DoubleArray(list.size) { list[it].x } }
        return { x ->
            val title = durationLabel(x)
            val rows = charts.indices.mapNotNull { index ->
                val chart = charts[index]
                val nearest = nearest(points[index], xs[index], x) ?: return@mapNotNull null
                val axis = if (index == 0) view.axisLeft else view.axisRight
                val value = chart.chartYLabelFormatter?.getFormattedValue(nearest.y.toFloat(), axis)
                    ?: String.format(Locale.getDefault(), "%.1f", nearest.y)
                val text = listOfNotNull(value, chart.unitString?.takeIf { it.isNotEmpty() }).joinToString(" ")
                Triple(chart.title, colorOf(chart), text)
            }
            ChartSelection(
                title = title,
                rows = rows.map { ChartSelection.Row(it.second, it.third) },
                description = (listOf(title) + rows.map { "${it.first} ${it.third}" }).joinToString(". ", postfix = "."),
            )
        }
    }

    private fun nearest(points: List<ChartPoint>, xs: DoubleArray, x: Double): ChartPoint? {
        if (points.isEmpty()) return null
        val found = xs.binarySearch(x)
        if (found >= 0) return points[found]
        val after = -found - 1
        return when {
            after >= points.size -> points.last()
            after == 0 -> points.first()
            abs(xs[after] - x) < abs(x - xs[after - 1]) -> points[after]
            else -> points[after - 1]
        }
    }

    @JvmStatic
    fun colorOf(chart: WorkoutChart): Int =
        chart.chartData.dataSets.firstOrNull { it !is HeartRateZoneChartUtils.ZoneAreaDataSet }?.color ?: 0

    private fun sideOf(index: Int) = if (index == 0) AxisSide.START else AxisSide.END

    private fun series(chart: WorkoutChart, side: AxisSide): List<ChartSeries> =
        chart.chartData.dataSets
            .filter { it !is HeartRateZoneChartUtils.ZoneAreaDataSet }
            .mapIndexed { index, set ->
                val points = (0 until set.entryCount).map {
                    val entry = set.getEntryForIndex(it)
                    ChartPoint(entry.x / MS_PER_SECOND, entry.y.toDouble())
                }
                val style = if (set is ILineDataSet<*>) {
                    SeriesStyle.Line(set.color, curved = set.mode != LineDataSet.Mode.LINEAR, showPoints = set.isDrawCirclesEnabled)
                } else {
                    SeriesStyle.Line(set.color, showLine = false, showPoints = true)
                }
                ChartSeries("${chart.id}_$index", chart.title, points, style, side)
            }

    private fun zoneBands(context: Context, chart: WorkoutChart, metric: List<ChartSeries>, side: AxisSide): List<ChartSeries> {
        val zones = chart.zoneThresholds ?: return emptyList()
        val points = metric.flatMap { it.points }.sortedBy { it.x }
        if (points.isEmpty()) {
            return emptyList()
        }
        val chartMax = maxOf(HeartRateUtils.getInstance().maxHeartRate, zones.zone5 + 1)
        val peak = points.maxOf { it.y }
        return (1..5).mapNotNull { zone ->
            val top = HeartRateZonesResolver.upperBoundOf(zone, zones, chartMax).toDouble()
            val bottom = HeartRateZonesResolver.lowerBoundOf(zone, zones).toDouble()
            if (top <= bottom || peak <= bottom) {
                return@mapNotNull null
            }
            ChartSeries(
                "zone_$zone", "",
                points.map { ChartPoint(it.x, it.y.coerceIn(bottom, top)) },
                SeriesStyle.Line(HeartRateZonesResolver.colorForZone(context, zone), filled = true, showLine = false, fillBase = bottom),
                side,
                selectable = false,
            )
        }
    }

    private fun zoneLines(context: Context, chart: WorkoutChart, side: AxisSide): List<LimitLineSpec> {
        val zones = chart.zoneThresholds ?: return emptyList()
        return listOf(2 to zones.zone2, 3 to zones.zone3, 4 to zones.zone4, 5 to zones.zone5)
            .filter { it.second > 0 }
            .map { (zone, bpm) -> LimitLineSpec(bpm.toDouble(), HeartRateZonesResolver.colorForZone(context, zone), axis = side) }
    }

    private fun yAxis(chart: WorkoutChart, axis: AxisBase): AxisSpec {
        val formatter = chart.chartYLabelFormatter
        return AxisSpec(
            format = ChartValueFormat.DECIMAL,
            labeler = formatter?.let { { value: Double -> it.getFormattedValue(value.toFloat(), axis) } },
        )
    }
}
