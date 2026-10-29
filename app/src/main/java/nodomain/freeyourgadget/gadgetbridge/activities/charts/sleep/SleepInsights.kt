/*  Copyright (C) 2026 theupsidedownabyss

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep

import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Details of a night: time in bed from the first session's start to the last one's end of the main sleep ([fellAsleep] and [wokeUp],
 * epoch milliseconds), the times awake during it, and how much the bedtime varied over the last nights
 * ([bedtimeSpreadMinutes], a standard deviation, null with too few nights).
 */
data class SleepDetails(
    val inBedSeconds: Long,
    val fellAsleep: Long,
    val wokeUp: Long,
    val wakeUps: Int,
    val bedtimeSpreadMinutes: Int?,
)

/**
 * A tip for the weakest parts of a night, like the advice of the vendor apps.
 */
enum class SleepAdvice(@StringRes val text: Int) {
    SHORT(R.string.sleep_advice_short),
    DEEP_LOW(R.string.sleep_advice_deep_low),
    AWAKE_HIGH(R.string.sleep_advice_awake_high),
    IRREGULAR(R.string.sleep_advice_irregular),
    LATE(R.string.sleep_advice_late),
    KEEP_IT_UP(R.string.sleep_advice_keep_it_up),
}

object SleepInsights {
    private const val MIN_NIGHTS_FOR_SPREAD = 3
    private const val SHORT_SHARE_OF_GOAL = 0.85
    private const val DEEP_LOW_SHARE = 0.15
    private const val AWAKE_HIGH_MINUTES = 30
    private const val WAKE_UPS_HIGH = 4
    private const val IRREGULAR_SPREAD_MINUTES = 45

    /**
     * Bedtime after 00:30, in minutes since the previous noon.
     */
    private const val LATE_BEDTIME_MINUTES = 12 * 60 + 30
    private const val MAX_ADVICE = 3

    /**
     * Sessions less than this apart belong to the same sleep, longer gaps separate a nap from the night.
     */
    private const val MAX_NIGHT_GAP_MILLIS = 2 * 60 * 60 * 1000L

    /**
     * The sessions of the main sleep: [sessions] (sorted by start) grouped by gaps under [MAX_NIGHT_GAP_MILLIS], the
     * group with the most sleep. Naps outside it are left out.
     */
    @JvmStatic
    fun mainSleep(sessions: List<SleepSession>): List<SleepSession> {
        if (sessions.size < 2) {
            return sessions
        }
        val groups = mutableListOf(mutableListOf<SleepSession>())
        for (session in sessions.sortedBy { it.startTime }) {
            val group = groups.last()
            if (group.isNotEmpty() && session.startTime - group.maxOf { it.endTime } >= MAX_NIGHT_GAP_MILLIS) {
                groups += mutableListOf(session)
            } else {
                group += session
            }
        }
        return groups.maxBy { group -> group.sumOf { it.totalSleepDuration } }
    }

    /**
     * @param sessions the sessions of the night, naps included: only the [mainSleep] counts
     * @param previousBedtimes the bedtimes of the previous nights, epoch milliseconds
     */
    @JvmStatic
    fun details(sessions: List<SleepSession>, previousBedtimes: List<Long>, zone: ZoneId): SleepDetails? {
        if (sessions.isEmpty()) {
            return null
        }
        val night = mainSleep(sessions)
        val fellAsleep = night.minOf { it.startTime }
        val wokeUp = night.maxOf { it.endTime }
        val awakeStages = night.sumOf { session -> session.stages.count { it.kind == ActivityKind.AWAKE_SLEEP } }
        val bedtimes = (previousBedtimes + fellAsleep).map { minutesSinceNoon(it, zone) }
        return SleepDetails(
            inBedSeconds = (wokeUp - fellAsleep) / 1000,
            fellAsleep = fellAsleep,
            wokeUp = wokeUp,
            wakeUps = awakeStages + night.size - 1,
            bedtimeSpreadMinutes = if (bedtimes.size >= MIN_NIGHTS_FOR_SPREAD) standardDeviation(bedtimes).roundToInt() else null,
        )
    }

    /**
     * At most [MAX_ADVICE] tips for the weak parts of the night, or [SleepAdvice.KEEP_IT_UP] when it went well. No
     * deep sleep at all means the device doesn't report it, so it gets no tip.
     */
    @JvmStatic
    fun advice(sleepMinutes: Double, deepMinutes: Double, awakeMinutes: Double, goalMinutes: Int,
               details: SleepDetails?, zone: ZoneId): List<SleepAdvice> {
        if (sleepMinutes <= 0) {
            return emptyList()
        }
        val advice = mutableListOf<SleepAdvice>()
        if (sleepMinutes < goalMinutes * SHORT_SHARE_OF_GOAL) advice += SleepAdvice.SHORT
        if (deepMinutes > 0 && deepMinutes / sleepMinutes < DEEP_LOW_SHARE) advice += SleepAdvice.DEEP_LOW
        if (awakeMinutes >= AWAKE_HIGH_MINUTES || (details?.wakeUps ?: 0) >= WAKE_UPS_HIGH) advice += SleepAdvice.AWAKE_HIGH
        if ((details?.bedtimeSpreadMinutes ?: 0) > IRREGULAR_SPREAD_MINUTES) advice += SleepAdvice.IRREGULAR
        if (details != null && minutesSinceNoon(details.fellAsleep, zone) > LATE_BEDTIME_MINUTES) advice += SleepAdvice.LATE
        return if (advice.isEmpty()) listOf(SleepAdvice.KEEP_IT_UP) else advice.take(MAX_ADVICE)
    }

    /**
     * Minutes since the noon before [epochMillis], so that bedtimes around midnight stay close together.
     */
    fun minutesSinceNoon(epochMillis: Long, zone: ZoneId): Double {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
        val minutes = time.hour * 60 + time.minute
        return ((minutes - 12 * 60 + 24 * 60) % (24 * 60)).toDouble()
    }

    private fun standardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
}
