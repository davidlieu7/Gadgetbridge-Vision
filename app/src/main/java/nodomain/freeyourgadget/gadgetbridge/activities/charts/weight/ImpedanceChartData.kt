package nodomain.freeyourgadget.gadgetbridge.activities.charts.weight

import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartValueFormat
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.SeriesStyle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The impedance history below the weight chart. Ohms do not fit the weight or the percent axis, so it gets a chart
 * of its own, with one line per body section and frequency the scale measured.
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

    /** One line per entry of [groups], labelled and coloured by the matching entries of [labels] and [colors]. */
    @JvmStatic
    fun spec(
        startTs: Long,
        endTs: Long,
        readings: List<Reading>,
        groups: List<Group>,
        labels: List<String>,
        colors: IntArray,
        unit: String,
    ): ChartSpec {
        val series = groups.mapIndexed { i, group ->
            val points = readings
                .filter { it.ohm > 0 && it.section == group.section && it.frequencyHz == group.frequencyHz }
                .sortedBy { it.seconds }
                .map { ChartPoint(it.seconds.toDouble(), it.ohm) }
            ChartSeries("impedance_${group.section}_${group.frequencyHz}", labels[i], points,
                SeriesStyle.Line(colors[i], showPoints = true))
        }.filter { it.points.isNotEmpty() }
        if (series.isEmpty()) {
            return ChartSpec.EMPTY
        }
        val values = series.flatMap { line -> line.points.map { it.y } }
        return ChartSpec(
            series = series,
            xAxis = AxisSpec(format = ChartValueFormat.DATE, minimum = startTs.toDouble(), maximum = endTs.toDouble()),
            yAxis = AxisSpec(
                format = ChartValueFormat.INTEGER,
                minimum = maxOf(0.0, floor(values.min() - Y_MARGIN)),
                maximum = ceil(values.max() + Y_MARGIN),
                unit = unit,
            ),
        )
    }
}
