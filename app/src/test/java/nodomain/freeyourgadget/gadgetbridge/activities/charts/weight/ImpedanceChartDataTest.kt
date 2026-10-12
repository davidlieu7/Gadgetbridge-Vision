package nodomain.freeyourgadget.gadgetbridge.activities.charts.weight

import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.ImpedanceChartData.Group
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.ImpedanceChartData.Reading
import org.junit.Assert
import org.junit.Test

class ImpedanceChartDataTest {
    // Synthetic readings; nothing here is a real person's measurement.
    private val day1 = 1_000_000L
    private val day2 = 1_086_400L

    private fun spec(readings: List<Reading>): ChartSpec {
        val groups = ImpedanceChartData.groups(readings)
        return ImpedanceChartData.spec(day1, day2, readings, groups, groups.map { it.toString() },
            IntArray(groups.size) { it }, "Ω")
    }

    @Test
    fun `one reading per weigh-in is a single line`() {
        val spec = spec(listOf(Reading(day2, 0, -1, 480.0), Reading(day1, 0, -1, 500.0)))

        Assert.assertEquals(1, spec.series.size)
        Assert.assertEquals(listOf(day1.toDouble(), day2.toDouble()), spec.series[0].points.map { it.x })
        Assert.assertEquals(listOf(500.0, 480.0), spec.series[0].points.map { it.y })
        Assert.assertEquals(470.0, spec.yAxis.minimum!!, 0.001)
        Assert.assertEquals(510.0, spec.yAxis.maximum!!, 0.001)
        Assert.assertEquals("Ω", spec.yAxis.unit)
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
        val spec = spec(readings)
        Assert.assertEquals(4, spec.series.size)
        Assert.assertEquals(listOf(470.0, 465.0), spec.series[0].points.map { it.y })
        Assert.assertEquals(listOf(300.0), spec.series[3].points.map { it.y })
    }

    @Test
    fun `readings without a value are left out`() {
        val readings = listOf(Reading(day1, 0, -1, 0.0), Reading(day2, 0, 6_250, 0.0))

        Assert.assertTrue(ImpedanceChartData.groups(readings).isEmpty())
        Assert.assertTrue(spec(readings).isEmpty)
    }

    @Test
    fun `only readings taken at a shown weigh-in are kept`() {
        val readings = listOf(Reading(day1, 0, 20_000, 470.0), Reading(day1 + 60, 0, 20_000, 520.0))

        val kept = ImpedanceChartData.atWeighIns(readings, longArrayOf(day1, day2))

        Assert.assertEquals(listOf(470.0), kept.map { it.ohm })
    }
}
