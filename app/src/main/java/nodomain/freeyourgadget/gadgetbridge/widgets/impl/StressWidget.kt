package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.StressFragment
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant

/**
 * Stress as a gauge: segmented by zone, simple, or as a time-in-zone breakdown. The larger variants show the
 * segmented gauge with the day's samples.
 */
object StressWidget : MetricWidget<StressData?>() {
    val SEGMENTED = WidgetVariant("segmented", R.string.menuitem_stress_segmented, 3, WidgetVariant.GAUGE_HEIGHT_DP)
    val SIMPLE = WidgetVariant("simple", R.string.menuitem_stress_simple, 3, WidgetVariant.GAUGE_HEIGHT_DP)
    val BREAKDOWN = WidgetVariant("breakdown", R.string.menuitem_stress_breakdown, 3, WidgetVariant.GAUGE_HEIGHT_DP)

    override val id = "stress"
    override val label = R.string.menuitem_stress
    override val icon = R.drawable.ic_stress
    override val chartTab = "stress"
    override val variants = listOf(
        SEGMENTED,
        SIMPLE,
        BREAKDOWN,
        WidgetVariant.NUMBER,
        WidgetVariant.TALL,
        WidgetVariant.WIDE,
        WidgetVariant.METRIC_LARGE,
    )

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsStressMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): StressData? =
        computeStress(scope, config.needsHistory, config.isFullWidth || config.isTall)

    override fun previewData(): StressData {
        val hourly = floatArrayOf(
            18f, 15f, 14f, 16f, 15f, 19f, 22f, 38f, 45f, 52f, 41f, 36f,
            30f, 48f, 63f, 44f, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
            Float.NaN, Float.NaN, Float.NaN, Float.NaN,
        )
        val offsets = floatArrayOf(-4f, 3f, -1f, 2f)
        return StressData(
            value = 30,
            ranges = intArrayOf(0, 40, 60, 80),
            totalTime = intArrayOf(35400, 15600, 4800, 1200),
            hourly = hourly,
            hoursStarted = 16,
            quarters = FloatArray(hourly.size * offsets.size) {
                hourly[it / offsets.size] + offsets[it % offsets.size]
            },
        )
    }

    override fun model(context: Context, config: WidgetConfig, data: StressData?): TileModel = when (config.variant.id) {
        SIMPLE.id -> simple(context, data)
        BREAKDOWN.id -> breakdown(context, data)
        else -> segmented(context, config, data)
    }

    private fun segmented(context: Context, config: WidgetConfig, data: StressData?): TileModel {
        val colors = stressZoneColors(context)
        if (data == null) {
            return emptyModel(
                context,
                GaugeSpec.Zones(colors, floatArrayOf(40 / 100f, 20 / 100f, 20 / 100f, 20 / 100f)),
                if (config.needsHistory) emptyStressBars() else null,
            )
        }
        val segments = floatArrayOf(
            (data.ranges[1] - data.ranges[0]) / 100f,
            (data.ranges[2] - data.ranges[1]) / 100f,
            (data.ranges[3] - data.ranges[2]) / 100f,
            1 - data.ranges[3] / 100f,
        )
        return stressTile(context, config, data, data.value.toString(), GaugeSpec.Zones(colors, segments, data.value / 100f))
    }

    private fun simple(context: Context, data: StressData?): TileModel {
        if (data == null) {
            return emptyModel(context)
        }
        val color = StressFragment.StressType.fromStress(data.value, data.ranges).getColor(context)
        return TileModel(
            value = data.value.toString(),
            gauge = GaugeSpec.Progress(color, data.value / 100f),
            status = stressStatus(context, data),
        )
    }

    private fun breakdown(context: Context, data: StressData?): TileModel {
        if (data == null) {
            return emptyModel(context)
        }
        val segments = FloatArray(4)
        val sum = data.totalTime.sum()
        if (sum != 0) {
            for (i in 0 until 4) {
                segments[i] = data.totalTime[i] / sum.toFloat()
            }
        }
        return TileModel(
            value = data.value.toString(),
            gauge = GaugeSpec.Zones(stressZoneColors(context), segments),
            status = stressStatus(context, data),
        )
    }
}
