package nodomain.freeyourgadget.gadgetbridge.activities.charts.weight

import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSide
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartValueFormat
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.LimitLineSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.LineCurve
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.SeriesStyle
import kotlin.math.ceil
import kotlin.math.floor

object WeightChartData {
    private const val Y_MARGIN = 1.0

    /**
     * A body composition percentage per reading, drawn on the end axis. [values] is parallel to the readings, NaN
     * where a reading has no estimate (no impedance).
     */
    class PercentLine(
        val key: String,
        val label: String,
        val color: Int,
        val values: DoubleArray,
    )

    /**
     * Weight readings between [startTs] and [endTs] (epoch seconds), with the target as a dashed line, and any
     * [percentLines] against a percent axis on the end side. With [carriedIn], the first reading is from before the
     * range: it only leads the lines in from [startTs], without a dot and without being selectable. Without
     * [showWeight], only the percent lines are drawn.
     */
    @JvmStatic
    @JvmOverloads
    fun spec(
        startTs: Long,
        endTs: Long,
        seconds: LongArray,
        weights: DoubleArray,
        target: Double,
        label: String,
        color: Int,
        targetColor: Int,
        percentLines: List<PercentLine> = emptyList(),
        percentUnit: String = "%",
        carriedIn: Boolean = false,
        showWeight: Boolean = true,
    ): ChartSpec {
        val first = if (carriedIn) 1 else 0
        val points = (first until seconds.size)
            .filter { showWeight && weights[it] > 0 }
            .map { ChartPoint(seconds[it].toDouble(), weights[it]) }
        val weightLeadIn = if (showWeight && carriedIn && weights.isNotEmpty() && weights[0] > 0) {
            leadIn("weight_lead_in", startTs, endTs, weights[0], points, color, LineCurve.FLAT_AT_POINTS, AxisSide.START)
        } else {
            null
        }
        val goal = if (showWeight) target else 0.0
        val values = (points + weightLeadIn?.points.orEmpty()).map { it.y } + if (goal > 0) listOf(goal) else emptyList()
        val percentSeries = percentLines.flatMap { line ->
            val linePoints = (first until seconds.size)
                .filter { weights[it] > 0 && !line.values[it].isNaN() }
                .map { ChartPoint(seconds[it].toDouble(), line.values[it]) }
            val lineLeadIn = if (carriedIn && !line.values[0].isNaN()) {
                leadIn("${line.key}_lead_in", startTs, endTs, line.values[0], linePoints, line.color, LineCurve.STRAIGHT, AxisSide.END)
            } else {
                null
            }
            listOfNotNull(
                ChartSeries(line.key, line.label, linePoints, SeriesStyle.Line(line.color, showPoints = true), AxisSide.END),
                lineLeadIn,
            )
        }.filter { it.points.isNotEmpty() }
        if (points.isEmpty() && weightLeadIn == null && percentSeries.isEmpty()) {
            return ChartSpec.EMPTY
        }
        return ChartSpec(
            series = listOfNotNull(
                ChartSeries("weight", label, points, SeriesStyle.Line(color, curve = LineCurve.FLAT_AT_POINTS, showPoints = true)),
                weightLeadIn,
            ).filter { it.points.isNotEmpty() } + percentSeries,
            xAxis = AxisSpec(format = ChartValueFormat.DATE, minimum = startTs.toDouble(), maximum = endTs.toDouble()),
            yAxis = if (values.isEmpty()) {
                AxisSpec(format = ChartValueFormat.DECIMAL, showLabels = false)
            } else {
                AxisSpec(
                    format = ChartValueFormat.DECIMAL,
                    minimum = maxOf(0.0, floor(values.min() - Y_MARGIN)),
                    maximum = ceil(values.max() + Y_MARGIN),
                )
            },
            endYAxis = percentAxis(percentSeries, percentUnit),
            limitLines = if (goal > 0) listOf(LimitLineSpec(value = goal, color = targetColor)) else emptyList(),
        )
    }

    /**
     * A line from [value] at [startTs] to the first of [points], or on to [endTs] when there are none.
     */
    private fun leadIn(
        key: String,
        startTs: Long,
        endTs: Long,
        value: Double,
        points: List<ChartPoint>,
        color: Int,
        curve: LineCurve,
        axis: AxisSide,
    ): ChartSeries {
        val end = points.firstOrNull() ?: ChartPoint(endTs.toDouble(), value)
        return ChartSeries(
            key, "", listOf(ChartPoint(startTs.toDouble(), value), end),
            SeriesStyle.Line(color, curve = curve), axis, selectable = false,
        )
    }

    private fun percentAxis(series: List<ChartSeries>, unit: String): AxisSpec? {
        if (series.isEmpty()) {
            return null
        }
        val values = series.flatMap { line -> line.points.map { it.y } }
        return AxisSpec(
            format = ChartValueFormat.DECIMAL,
            minimum = maxOf(0.0, floor(values.min() - Y_MARGIN)),
            maximum = minOf(100.0, ceil(values.max() + Y_MARGIN)),
            unit = unit,
        )
    }
}
