package nodomain.freeyourgadget.gadgetbridge.activities.charts.vico

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.DashedShape
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

private const val AREA_FILL_ALPHA = 0.24f
private const val LIMIT_LINE_THICKNESS_DP = 1.5f
private const val POINT_SIZE_DP = 5f
private const val DATA_LABEL_SIZE_SP = 10f

/** Gentle, so a line through sparse points does not overshoot them far. */
private const val CURVATURE = 0.3f
private const val DASH_LENGTH_DP = 6f
private const val DASH_GAP_DP = 4f

/**
 * A vico Cartesian chart drawing one or more [SeriesStyle.Line] series against a shared x-axis,
 * with optional horizontal limit lines (e.g. a daily goal).
 */
@Composable
fun GbLineChart(spec: ChartSpec, theme: ChartTheme, modifier: Modifier = Modifier) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(spec) {
        modelProducer.runTransaction {
            lineModel {
                for (chartSeries in spec.series) {
                    series(
                        x = chartSeries.points.map { it.x },
                        y = chartSeries.points.map { it.y },
                        key = chartSeries.key,
                    )
                }
            }
        }
    }

    val lines = spec.series.map { chartSeries ->
        val style = chartSeries.style as? SeriesStyle.Line ?: SeriesStyle.Line(color = theme.textColor)
        val color = Color(style.color)
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            areaFill = if (style.filled) {
                LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = AREA_FILL_ALPHA)))
            } else {
                null
            },
            pointProvider = if (style.showPoints) {
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(
                        rememberShapeComponent(fill = Fill(color), shape = CircleShape),
                        POINT_SIZE_DP.dp,
                    )
                )
            } else {
                null
            },
            interpolator = if (style.curved) {
                LineCartesianLayer.Interpolator.cubic(CURVATURE)
            } else {
                LineCartesianLayer.Interpolator.Sharp
            },
            dataLabel = if (style.pointLabel != null) {
                rememberTextComponent(
                    style = TextStyle(color = Color(theme.secondaryTextColor), fontSize = DATA_LABEL_SIZE_SP.sp),
                )
            } else {
                null
            },
            dataLabelValueFormatter = remember(style.pointLabel) {
                style.pointLabel?.let { label ->
                    CartesianValueFormatter { _, value, _ -> label(value) }
                } ?: CartesianValueFormatter.decimal()
            },
        )
    }

    val rangeProvider = remember(spec.xAxis, spec.yAxis) {
        val minX = spec.xAxis.minimum
        val maxX = spec.xAxis.maximum
        val minY = spec.yAxis.minimum
        val maxY = spec.yAxis.maximum
        if (minX == null && maxX == null && minY == null && maxY == null) {
            CartesianLayerRangeProvider.auto()
        } else {
            CartesianLayerRangeProvider.fixed(minX = minX, maxX = maxX, minY = minY, maxY = maxY)
        }
    }
    val lineLayer = rememberLineCartesianLayer(
        LineCartesianLayer.LineProvider.series(lines),
        rangeProvider = rangeProvider,
    )
    val startAxis = VerticalAxis.rememberStart(
        valueFormatter = valueFormatterFor(spec.yAxis.format),
        itemPlacer = verticalItemPlacer(spec.yAxis),
    )
    val bottomItemPlacer = remember(spec.xAxis.format, spec.xAxis.minimum, spec.xAxis.maximum) {
        val minX = spec.xAxis.minimum
        val maxX = spec.xAxis.maximum
        val isTimeFormat = spec.xAxis.format == ChartValueFormat.TIME_OF_DAY ||
                spec.xAxis.format == ChartValueFormat.DATE
        if (isTimeFormat && minX != null && maxX != null) {
            GbTimeAxisItemPlacer(minX, maxX)
        } else {
            HorizontalAxis.ItemPlacer.aligned()
        }
    }
    val bottomAxis = HorizontalAxis.rememberBottom(
        valueFormatter = valueFormatterFor(spec.xAxis.format),
        itemPlacer = bottomItemPlacer,
    )

    val decorations = spec.limitLines.map { limit ->
        HorizontalLine(
            y = { limit.value },
            line = rememberLineComponent(
                fill = Fill(Color(limit.color)),
                thickness = LIMIT_LINE_THICKNESS_DP.dp,
                shape = if (limit.dashed) {
                    DashedShape(shape = RectangleShape, dashLength = DASH_LENGTH_DP.dp, gapLength = DASH_GAP_DP.dp)
                } else {
                    RectangleShape
                },
            ),
        )
    }

    // vico defaults to zoomed-in. Fit to the axis's configured range
    val zoomState = rememberVicoZoomState(initialZoom = Zoom.Content)

    val markerValueFormatter = markerFormatterFor(spec)
    val marker = rememberDefaultCartesianMarker(
        label = rememberTextComponent(style = TextStyle(color = Color(theme.textColor))),
        valueFormatter = remember(markerValueFormatter) { SingleValueFormatter(markerValueFormatter) },
        guideline = rememberLineComponent(fill = Fill(Color(theme.secondaryTextColor)), thickness = 1.dp),
    )

    CartesianChartHost(
        chart = rememberCartesianChart(
            lineLayer,
            startAxis = startAxis,
            bottomAxis = bottomAxis,
            decorations = decorations,
            marker = marker,
            persistentMarkers = spec.markedX?.let { x -> { marker.at(x) } },
        ),
        modelProducer = modelProducer,
        modifier = modifier,
        zoomState = zoomState,
        // Disable animations (should we keep them?)
        animationSpec = null,
        initialAnimationSpec = null,
    )
}

/**
 * Avoid repeated values on steep segments. [formatter] renders the value the way the chart does,
 * so a weight keeps its decimal instead of being shown as a whole number.
 */
private class SingleValueFormatter(private val formatter: CartesianValueFormatter) : DefaultCartesianMarker.ValueFormatter {
    override fun format(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>): CharSequence {
        val latestPoint = targets
            .filterIsInstance<LineCartesianLayerMarkerTarget>()
            .flatMap { it.points }
            .maxByOrNull { it.entry.x }
            ?: return ""
        return formatter.format(context, latestPoint.entry.y, Axis.Position.Vertical.Start)
    }
}

/**
 * How the marker renders a value: [ChartSpec.markerLabel] when given; the series' own
 * [SeriesStyle.Line.pointLabel] when the chart has a single series (with several, the marker
 * cannot tell which series a value came from, so it must not borrow one series' units for
 * another); otherwise the y-axis format, so the marker reads like the axis beside it.
 */
@Composable
private fun markerFormatterFor(spec: ChartSpec): CartesianValueFormatter {
    val label = spec.markerLabel
        ?: spec.series.singleOrNull()?.let { (it.style as? SeriesStyle.Line)?.pointLabel }
    return if (label != null) {
        remember(label) { CartesianValueFormatter { _, value, _ -> label(value) } }
    } else {
        valueFormatterFor(spec.yAxis.format)
    }
}

/**
 * Labels every [AxisSpec.step] units where the spec asks for it; otherwise vico's own placement,
 * which is what every chart got before the step existed.
 */
@Composable
private fun verticalItemPlacer(axis: AxisSpec): VerticalAxis.ItemPlacer {
    val step = axis.step
    return remember(step) {
        if (step != null && step > 0) {
            VerticalAxis.ItemPlacer.step({ step })
        } else {
            VerticalAxis.ItemPlacer.step()
        }
    }
}

@Composable
private fun valueFormatterFor(format: ChartValueFormat): CartesianValueFormatter = when (format) {
    ChartValueFormat.TIME_OF_DAY -> remember { timeOfDayValueFormatter("HH:mm") }
    ChartValueFormat.DATE -> remember { timeOfDayValueFormatter("MMM d") }
    ChartValueFormat.DURATION_SECONDS -> remember { durationValueFormatter() }
    ChartValueFormat.INTEGER -> CartesianValueFormatter.decimal(decimalCount = 0)
    ChartValueFormat.DECIMAL -> CartesianValueFormatter.decimal()
}
