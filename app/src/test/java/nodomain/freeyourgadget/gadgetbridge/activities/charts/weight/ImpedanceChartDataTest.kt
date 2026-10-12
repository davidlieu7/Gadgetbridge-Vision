package nodomain.freeyourgadget.gadgetbridge.activities.charts.weight

import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSide
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.ImpedanceChartData.Group
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.ImpedanceChartData.Reading
import org.junit.Assert
import org.junit.Test

class ImpedanceChartDataTest {
    // Synthetic readings; nothing here is a real person's measurement.
    private val day1 = 1_000_000L
    private val day2 = 1_086_400L
    private val seconds = longArrayOf(day1, day2)
    private val weights = doubleArrayOf(80.0, 79.5)

    private fun addTo(base: ChartSpec, readings: List<Reading>): ChartSpec {
        val groups = ImpedanceChartData.groups(readings)
        return ImpedanceChartData.addTo(base, day1, day2, readings, groups, groups.map { it.toString() },
            IntArray(groups.size) { it }, "Ω")
    }

    private fun weightOnly(showWeight: Boolean = true) = WeightChartData.spec(
        day1, day2, seconds, weights, 75.0, "Weight", 1, 2, emptyList(), "%", false, showWeight,
    )

    private val bodyFat = WeightChartData.PercentLine("body_fat", "Body fat", 3, doubleArrayOf(25.0, 24.5))

    @Test
    fun `alone, one reading per weigh-in is a single line on the start axis`() {
        val spec = addTo(ChartSpec.EMPTY, listOf(Reading(day2, 0, -1, 480.0), Reading(day1, 0, -1, 500.0)))

        Assert.assertEquals(1, spec.series.size)
        Assert.assertEquals(AxisSide.START, spec.series[0].axis)
        Assert.assertEquals(listOf(day1.toDouble(), day2.toDouble()), spec.series[0].points.map { it.x })
        Assert.assertEquals(listOf(500.0, 480.0), spec.series[0].points.map { it.y })
        Assert.assertEquals(470.0, spec.yAxis.minimum!!, 0.001)
        Assert.assertEquals(510.0, spec.yAxis.maximum!!, 0.001)
        Assert.assertEquals("Ω", spec.yAxis.unit)
        Assert.assertNull(spec.endYAxis)
    }

    @Test
    fun `with the weight it goes on the end axis and leaves the weight axis alone`() {
        val base = weightOnly()
        val spec = addTo(base, listOf(Reading(day1, 0, -1, 500.0)))

        Assert.assertEquals(listOf("weight", "impedance_0_-1"), spec.series.map { it.key })
        Assert.assertEquals(AxisSide.END, spec.series[1].axis)
        Assert.assertEquals(base.yAxis, spec.yAxis)
        Assert.assertEquals("Ω", spec.endYAxis!!.unit)
        Assert.assertEquals(base.limitLines, spec.limitLines)
    }

    @Test
    fun `with a percent line and no weight it takes the start axis`() {
        val base = WeightChartData.spec(
            day1, day2, seconds, weights, 75.0, "Weight", 1, 2, listOf(bodyFat), "%", false, false,
        )
        val spec = addTo(base, listOf(Reading(day1, 0, -1, 500.0)))

        Assert.assertEquals(listOf("body_fat", "impedance_0_-1"), spec.series.map { it.key })
        Assert.assertEquals(AxisSide.START, spec.series[1].axis)
        Assert.assertEquals("Ω", spec.yAxis.unit)
        Assert.assertEquals(base.endYAxis, spec.endYAxis)
    }

    @Test
    fun `with both axes taken nothing is added`() {
        val base = WeightChartData.spec(
            day1, day2, seconds, weights, 75.0, "Weight", 1, 2, listOf(bodyFat), "%",
        )

        Assert.assertSame(base, addTo(base, listOf(Reading(day1, 0, -1, 500.0))))
    }

    @Test
    fun `each section and frequency gets its own line, ordered by section then frequency`() {
        val readings = listOf(
            Reading(day1, 1, 100_000, 300.0),
            Reading(day1, 0, 100_000, 450.0),
            Reading(day1, 1, 20_000, 320.0),
            Reading(day1, 0, 20_000, 470.0),
            Reading(day2, 0, 20_000, 465.0),
        )

        Assert.assertEquals(
            listOf(Group(0, 20_000), Group(0, 100_000), Group(1, 20_000), Group(1, 100_000)),
            ImpedanceChartData.groups(readings),
        )
        val spec = addTo(ChartSpec.EMPTY, readings)
        Assert.assertEquals(4, spec.series.size)
        Assert.assertEquals(listOf(470.0, 465.0), spec.series[0].points.map { it.y })
        Assert.assertEquals(listOf(300.0), spec.series[3].points.map { it.y })
    }

    @Test
    fun `readings without a value are left out`() {
        val readings = listOf(Reading(day1, 0, -1, 0.0), Reading(day2, 0, 6_250, 0.0))

        Assert.assertTrue(ImpedanceChartData.groups(readings).isEmpty())
        Assert.assertTrue(addTo(ChartSpec.EMPTY, readings).isEmpty)
    }

    @Test
    fun `only readings taken at a shown weigh-in are kept`() {
        val readings = listOf(Reading(day1, 0, 20_000, 470.0), Reading(day1 + 60, 0, 20_000, 520.0))

        val kept = ImpedanceChartData.atWeighIns(readings, longArrayOf(day1, day2))

        Assert.assertEquals(listOf(470.0), kept.map { it.ohm })
    }
}
