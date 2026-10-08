package nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart

import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.fixedLabelValues
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tooltip content for a selected x. [description] is for accessibility.
 */
data class ChartSelection(val title: String, val rows: List<Row>, val description: String) {
    data class Row(val color: Int, val text: String)
}

internal object ChartSlots {
    /**
     * Slots of a period axis, or null for other axes.
     */
    fun of(xAxis: AxisSpec): IntRange? {
        if (fixedLabelValues(xAxis) == null) return null
        return xAxis.minimum!!.roundToInt()..xAxis.maximum!!.roundToInt()
    }

    /**
     * Sorted x values a selection snaps to: the slots, or every point's x.
     */
    fun targets(spec: ChartSpec): DoubleArray {
        of(spec.xAxis)?.let { slots -> return DoubleArray(slots.count()) { (slots.first + it).toDouble() } }
        return spec.series.flatMap { series -> series.points.map { it.x } }.distinct().sorted().toDoubleArray()
    }

    fun nearest(x: Double, targets: DoubleArray): Double? {
        if (targets.isEmpty()) return null
        val index = targets.binarySearch(x)
        if (index >= 0) return targets[index]
        val after = -index - 1
        val before = after - 1
        return when {
            after >= targets.size -> targets[before]
            before < 0 -> targets[after]
            abs(targets[after] - x) < abs(x - targets[before]) -> targets[after]
            else -> targets[before]
        }
    }

    fun toggle(selected: Double?, tapped: Double): Double? = if (selected == tapped) null else tapped

    /**
     * Left edge of the tooltip: right of the guide in the left half, left of it in the right half.
     */
    fun tooltipLeft(guideX: Float, centerX: Float, width: Float, gap: Float, minLeft: Float, maxRight: Float): Float {
        val left = if (guideX < centerX) guideX + gap else guideX - gap - width
        return left.coerceIn(minLeft, maxOf(minLeft, maxRight - width))
    }
}
