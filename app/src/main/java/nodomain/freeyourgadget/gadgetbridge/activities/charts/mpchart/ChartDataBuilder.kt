package nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import com.github.mikephil.charting.components.YAxis.AxisDependency
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IFillFormatter
import com.github.mikephil.charting.utils.Fill
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSide
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.SeriesStyle

/**
 * Bar width and the gap between grouped bars, in x units.
 */
internal data class BarLayout(val width: Float, val gap: Float) {
    companion object {
        private const val FEW_BARS = 7
        private const val FEW_BARS_SHARE = 0.75f
        private const val FEW_BARS_MAX_DP = 40f
        private const val MANY_BARS_SHARE = 0.7f
        private const val MANY_BARS_MAX_DP = 16f
        private const val GROUPED_BAR_SHARE = 16f / 68f
        private const val GROUPED_BAR_MAX_DP = 16f
        private const val GROUP_GAP_SHARE = 0.25f

        /**
         * Width is a share of one x unit, capped in dp. Up to [FEW_BARS] bars get wider ones.
         */
        fun of(pxPerX: Float, pxPerDp: Float, barCount: Int, grouped: Boolean): BarLayout {
            val few = barCount <= FEW_BARS
            val share = when {
                grouped -> GROUPED_BAR_SHARE
                few -> FEW_BARS_SHARE
                else -> MANY_BARS_SHARE
            }
            val maxWidthDp = when {
                grouped -> GROUPED_BAR_MAX_DP
                few -> FEW_BARS_MAX_DP
                else -> MANY_BARS_MAX_DP
            }
            val width = if (pxPerX > 0f) minOf(share, maxWidthDp * pxPerDp / pxPerX) else share
            return BarLayout(width, if (grouped) width * GROUP_GAP_SHARE else 0f)
        }
    }
}

/**
 * Builds MPAndroidChart data from a [ChartSpec]. Entry data holds the original x, since grouped bars are offset.
 */
internal object ChartDataBuilder {
    private const val LINE_WIDTH_DP = 2f
    private const val POINT_RADIUS_DP = 3.5f
    private const val AREA_FILL_ALPHA = 0.45f
    private const val MAX_CANDLE_SPACE = 0.45f
    private const val SOLID_FILL_ALPHA = 255
    private const val BAND_FILL_ALPHA = 0x40

    fun columns(spec: ChartSpec) = spec.series.filter { it.style is SeriesStyle.Column && it.points.isNotEmpty() }

    /**
     * Columns drawn side by side at one x; series sharing a [SeriesStyle.Column.stackKey] stack into one.
     */
    fun columnGroups(spec: ChartSpec): List<List<ChartSeries>> =
        columns(spec).groupBy { (it.style as SeriesStyle.Column).stackKey ?: it.key }.values.toList()

    fun build(spec: ChartSpec, layout: BarLayout, cornerRadiusPx: Float): CombinedData {
        val columns = columnGroups(spec)
        val ranges = spec.series.filter { it.style is SeriesStyle.Range && it.points.isNotEmpty() }
        val bands = ranges.filter { (it.style as SeriesStyle.Range).width == null }
        val candles = ranges - bands.toSet()
        val lines = spec.series.filter { it.style is SeriesStyle.Line && it.points.isNotEmpty() }
        require(columns.isEmpty() || bands.isEmpty()) { "Range and column series can't share a chart" }
        val step = layout.width + layout.gap
        return CombinedData().apply {
            if (columns.isNotEmpty()) {
                barData = BarData(columns.mapIndexed { index, group ->
                    val offset = (index - (columns.size - 1) / 2f) * step
                    if (group.size == 1) barDataSet(group.single(), offset, cornerRadiusPx) else stackedDataSet(group, offset)
                }).apply { barWidth = layout.width }
            }
            if (bands.isNotEmpty()) {
                barData = BarData(bands.map { rangeDataSet(it) }).apply { barWidth = 1f }
            }
            if (candles.isNotEmpty()) {
                candleData = CandleData(candles.map { candleDataSet(it) })
            }
            if (lines.isNotEmpty()) {
                lineData = LineData(lines.flatMap { series -> segments(series).map { lineDataSet(series, it) } })
            }
        }
    }

    private fun barDataSet(series: ChartSeries, offset: Float, cornerRadiusPx: Float): BarDataSet<Float> {
        val style = series.style as SeriesStyle.Column
        val entries = series.points.map { BarEntry(x = it.x.toFloat() + offset, y = it.y.toFloat(), data = it.x.toFloat()) }
        return BarDataSet(entries, series.label).apply {
            color = style.color
            fills = listOf(Fill(topRounded(style.color, cornerRadiusPx)))
            highlightAlpha = 0
            isDrawValuesEnabled = false
            axisDependency = axisDependency(series)
        }
    }

    private fun stackedDataSet(group: List<ChartSeries>, offset: Float): BarDataSet<Float> {
        val xs = group.flatMap { series -> series.points.map { it.x } }.distinct().sorted()
        val entries = xs.map { x ->
            val values = group.map { series -> (series.points.firstOrNull { it.x == x }?.y ?: 0.0).toFloat() }
            BarEntry(x = x.toFloat() + offset, stackValues = values, data = x.toFloat())
        }
        return BarDataSet(entries, group.first().label).apply {
            colors = group.map { (it.style as SeriesStyle.Column).color }
            highlightAlpha = 0
            isDrawValuesEnabled = false
            axisDependency = axisDependency(group.first())
        }
    }

    private fun rangeDataSet(series: ChartSeries): BarDataSet<Float> {
        val style = series.style as SeriesStyle.Range
        val entries = series.points.map { point ->
            val low = (point.low ?: 0.0).toFloat()
            BarEntry(x = point.x.toFloat(), stackValues = listOf(low, point.y.toFloat() - low), data = point.x.toFloat())
        }
        return BarDataSet(entries, series.label).apply {
            colors = listOf(Color.TRANSPARENT, style.color)
            isHighlightEnabled = false
            isDrawValuesEnabled = false
            axisDependency = axisDependency(series)
        }
    }

    private fun candleDataSet(series: ChartSeries): CandleDataSet<Float> {
        val style = series.style as SeriesStyle.Range
        val entries = series.points.map { point ->
            val low = (point.low ?: 0.0).toFloat()
            val high = point.y.toFloat()
            CandleEntry(x = point.x.toFloat(), high = high, low = low, open = low, close = high, data = point.x.toFloat())
        }
        return CandleDataSet(entries, series.label).apply {
            shadowColor = style.color
            increasingColor = style.color
            increasingPaintStyle = Paint.Style.FILL
            decreasingColor = style.color
            decreasingPaintStyle = Paint.Style.FILL
            neutralColor = style.color
            barSpace = ((1f - (style.width ?: 1f)) / 2f).coerceIn(0f, MAX_CANDLE_SPACE)
            isHighlightEnabled = false
            isDrawValuesEnabled = false
            axisDependency = axisDependency(series)
        }
    }

    /**
     * The points of [series], split wherever neighbours are further apart than its [SeriesStyle.Line.maxGap].
     */
    fun segments(series: ChartSeries): List<List<ChartPoint>> {
        val maxGap = (series.style as SeriesStyle.Line).maxGap ?: return listOf(series.points)
        val segments = mutableListOf<MutableList<ChartPoint>>()
        for (point in series.points) {
            val current = segments.lastOrNull()
            if (current == null || point.x - current.last().x > maxGap) {
                segments += mutableListOf(point)
            } else {
                current += point
            }
        }
        return segments
    }

    private fun lineDataSet(series: ChartSeries, points: List<ChartPoint>): LineDataSet<Float> {
        val style = series.style as SeriesStyle.Line
        val entries = points.map { Entry(x = it.x.toFloat(), y = it.y.toFloat(), data = it.x.toFloat()) }
        return LineDataSet(entries, series.label).apply {
            color = if (style.showLine) style.color else Color.TRANSPARENT
            lineWidth = LINE_WIDTH_DP
            isDrawCirclesEnabled = style.showPoints
            circleColor = style.color
            circleRadius = POINT_RADIUS_DP
            isDrawCircleHoleEnabled = false
            mode = if (style.curved) LineDataSet.Mode.CUBIC_BEZIER else LineDataSet.Mode.LINEAR
            isDrawFilledEnabled = style.filled
            val fillBase = style.fillBase
            if (style.filled && fillBase != null) {
                fillColor = style.color
                fillAlpha = BAND_FILL_ALPHA
                fillFormatter = IFillFormatter { _, _ -> fillBase.toFloat() }
            } else if (style.filled && style.solidFill) {
                fillColor = style.color
                fillAlpha = SOLID_FILL_ALPHA
            } else if (style.filled) {
                fillDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(ColorUtils.setAlphaComponent(style.color, (AREA_FILL_ALPHA * 255).toInt()), 0),
                )
            }
            isVerticalHighlightIndicatorEnabled = false
            isHorizontalHighlightIndicatorEnabled = false
            isDrawValuesEnabled = false
            axisDependency = axisDependency(series)
        }
    }

    private fun axisDependency(series: ChartSeries) =
        if (series.axis == AxisSide.END) AxisDependency.RIGHT else AxisDependency.LEFT

    private fun topRounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
    }
}
