package nodomain.freeyourgadget.gadgetbridge.activities.charts.weight

import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One weight measurement in a list, with the change from the measurement before it. [manual] is true for a
 * weight the user entered rather than a reading of the device.
 */
data class WeightMeasurementRow(val timestamp: Long, val weightKg: Double, val deltaKg: Double?, val manual: Boolean = false)

object WeightMeasurements {
    private const val MINUS = '−'

    /**
     * [timestamps] and [weightsKg] as rows, newest first. [manual] marks the weights the user entered.
     */
    @JvmStatic
    fun rows(timestamps: LongArray, weightsKg: DoubleArray, manual: BooleanArray): List<WeightMeasurementRow> {
        val order = timestamps.indices.sortedByDescending { timestamps[it] }
        return order.mapIndexed { position, i ->
            val older = order.getOrNull(position + 1)
            WeightMeasurementRow(timestamps[i], weightsKg[i], older?.let { weightsKg[i] - weightsKg[it] }, manual[i])
        }
    }

    /**
     * The change in [unit] with one decimal and a sign, or an empty string for none.
     */
    @JvmStatic
    fun deltaText(deltaKg: Double?, unit: WeightUnit): String {
        deltaKg ?: return ""
        val delta = (WeightUnit.convertWeight(deltaKg, unit) * 10).roundToInt() / 10.0
        val number = String.format(Locale.getDefault(), "%.1f", abs(delta))
        return when {
            delta > 0 -> "+$number"
            delta < 0 -> "$MINUS$number"
            else -> number
        }
    }

    /**
     * The weight in [unit] as a number and a unit label. Stones are shown with their pounds, without a separate label.
     */
    @JvmStatic
    fun valueParts(weightKg: Double, unit: WeightUnit, unitLabel: String): Pair<String, String> {
        val converted = WeightUnit.convertWeight(weightKg, unit)
        if (unit == WeightUnit.STONE) {
            val totalPounds = (converted * 14).roundToInt()
            return "${totalPounds / 14} st ${totalPounds % 14} lb" to ""
        }
        return String.format(Locale.getDefault(), "%.1f", converted) to unitLabel
    }

    /**
     * "Today", "Yesterday", the month and day, or also the year when it is not [today]'s year.
     */
    @JvmStatic
    fun dateText(timestamp: Long, today: LocalDate, zone: ZoneId, locale: Locale, todayText: String, yesterdayText: String): String {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return when {
            date == today -> todayText
            date == today.minusDays(1) -> yesterdayText
            date.year == today.year -> date.format(DateTimeFormatter.ofPattern("MMM d", locale))
            else -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        }
    }

    @JvmStatic
    fun monthText(timestamp: Long, zone: ZoneId, locale: Locale): String =
        Instant.ofEpochMilli(timestamp).atZone(zone).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
}
