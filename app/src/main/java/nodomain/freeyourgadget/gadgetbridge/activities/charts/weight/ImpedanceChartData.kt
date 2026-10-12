package nodomain.freeyourgadget.gadgetbridge.activities.charts.weight

import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSide
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartValueFormat
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.SeriesStyle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The impedance history on the weight chart, one line per body section and frequency the scale measured. Ohms fit
 * neither the weight nor the percent axis, so the lines take whichever y axis the other selected data set leaves free.
 */
object ImpedanceChartData {
    private const val Y_MARGIN = 10.0

    /** One reading, [seconds] since the epoch; [frequencyHz] is -1 when the scale does not say. */
    class Reading(val seconds: Long, val section: Int, val frequencyHz: Int, val ohm: Double)

    data class Group(val section: Int, val frequencyHz: Int)

    /**
     * Keeps the readings taken at one of the [weighIns] (epoch seconds). The impedance table has no notion of which
     * person on a shared scale a reading belongs to, but the weight samples do, so this drops the other people's.
     */
    @JvmStatic
    fun atWeighIns(readings: List<Reading>, weighIns: LongArray): List<Reading> {
        val times = weighIns.toHashSet()
        return readings.filter { it.seconds in times }
    }

    /** The section and frequency combinations present, by section and then frequency. */
    @JvmStatic
    fun groups(readings: List<Reading>): List<Group> = readings
        .filter { it.ohm > 0 }
        .map { Group(it.section, it.frequencyHz) }
        .distinct()
        .sortedWith(compareBy({ it.section }, { it.frequencyHz }))

    /**
     * [base] with one line per entry of [groups], labelled and coloured by the matching entries of [labels] and
     * [colors]. The lines go on the start axis when [base] draws nothing there (no weight), else on the end axis;
     * when both axes are taken, [base] is returned unchanged.
     */
    @JvmStatic
    fun addTo(
        base: ChartSpec,
        startTs: Long,
        endTs: Long,
        readings: List<Reading>,
        groups: List<Group>,
        labels: List<String>,
        colors: IntArray,
        unit: String,
    ): ChartSpec {
        val start = base.series.none { it.axis == AxisSide.START && it.points.isNotEmpty() }
        if (!start && base.endYAxis != null) {
            return base
        }
        val side = if (start) AxisSide.START else AxisSide.END
        val series = groups.mapIndexed { i, group ->
            val points = readings
                .filter { it.ohm > 0 && it.section == group.section && it.frequencyHz == group.frequencyHz }
                .sortedBy { it.seconds }
                .map { ChartPoint(it.seconds.toDouble(), it.ohm) }
            ChartSeries("impedance_${group.section}_${group.frequencyHz}", labels[i], points,
                SeriesStyle.Line(colors[i], showPoints = true), side)
        }.filter { it.points.isNotEmpty() }
        if (series.isEmpty()) {
            return base
        }
        val values = series.flatMap { line -> line.points.map { it.y } }
        val axis = AxisSpec(
            format = ChartValueFormat.INTEGER,
            minimum = maxOf(0.0, floor(values.min() - Y_MARGIN)),
            maximum = ceil(values.max() + Y_MARGIN),
            unit = unit,
        )
        return base.copy(
            series = base.series + series,
            xAxis = AxisSpec(format = ChartValueFormat.DATE, minimum = startTs.toDouble(), maximum = endTs.toDouble()),
            yAxis = if (start) axis else base.yAxis,
            endYAxis = if (start) base.endYAxis else axis,
        )
    }
}
