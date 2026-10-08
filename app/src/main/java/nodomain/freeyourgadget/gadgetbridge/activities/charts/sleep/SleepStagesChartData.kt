package nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep

import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSide
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartValueFormat
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.LimitLineSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.SeriesStyle

object SleepStagesChartData {
    private const val MAX_INTENSITY = 1.0

    /**
     * Movement per stage as solid areas over the night, with heart rate on an end axis. [stages] index into
     * [labels] and [colors]; stage [notWornStage] gets no ramp to or from its neighbours.
     */
    @JvmStatic
    fun daySpec(
        seconds: LongArray,
        stages: IntArray,
        values: DoubleArray,
        notWornStage: Int,
        labels: Array<String>,
        colors: IntArray,
        markerColor: Int,
        hrSeconds: LongArray,
        hrBpm: IntArray,
        hrMaxGapSeconds: Double,
        hrAverage: Int,
        showHrAverage: Boolean,
        hrLabel: String,
        hrColor: Int,
        hrAverageColor: Int,
        hrMinimum: Double,
        hrMaximum: Double,
    ): ChartSpec {
        if (seconds.isEmpty()) {
            return ChartSpec.EMPTY
        }
        val areas = List(labels.size) { mutableListOf<ChartPoint>() }
        for (i in seconds.indices) {
            val x = seconds[i].toDouble()
            val stage = stages[i]
            if (i == 0) {
                areas[stage].add(ChartPoint(x, 0.0))
            } else if (stages[i - 1] != stage) {
                val previous = stages[i - 1]
                val ramp = previous != notWornStage && stage != notWornStage
                areas[stage].add(ChartPoint(x, 0.0))
                areas[previous].add(ChartPoint(x, if (ramp) values[i] else values[i - 1]))
                areas[previous].add(ChartPoint(x, 0.0))
            }
            areas[stage].add(ChartPoint(x, values[i]))
        }
        val hrPoints = hrSeconds.indices
            .filter { hrBpm[it] > 0 }
            .map { ChartPoint(hrSeconds[it].toDouble(), hrBpm[it].toDouble()) }

        val series = areas.mapIndexedNotNull { stage, points ->
            points.takeIf { it.isNotEmpty() }?.let {
                ChartSeries("stage_$stage", labels[stage], it, SeriesStyle.Line(colors[stage], filled = true, solidFill = true), selectable = false)
            }
        } + if (hrPoints.isEmpty()) {
            listOf(ChartSeries("samples", "", seconds.indices.map { ChartPoint(seconds[it].toDouble(), values[it]) }, SeriesStyle.Line(markerColor, showLine = false)))
        } else {
            listOf(ChartSeries("hr", hrLabel, hrPoints, SeriesStyle.Line(hrColor, curved = true, maxGap = hrMaxGapSeconds), AxisSide.END))
        }
        return ChartSpec(
            series = series,
            xAxis = AxisSpec(
                format = ChartValueFormat.TIME_OF_DAY,
                minimum = seconds.first().toDouble(),
                maximum = seconds.last().toDouble(),
            ),
            yAxis = AxisSpec(format = ChartValueFormat.DECIMAL, minimum = 0.0, maximum = MAX_INTENSITY, showLabels = false),
            endYAxis = if (hrPoints.isEmpty()) null else AxisSpec(format = ChartValueFormat.INTEGER, minimum = hrMinimum, maximum = hrMaximum),
            limitLines = if (hrPoints.isNotEmpty() && showHrAverage && hrAverage > 0) {
                listOf(LimitLineSpec(value = hrAverage.toDouble(), color = hrAverageColor, axis = AxisSide.END))
            } else {
                emptyList()
            },
        )
    }
}
