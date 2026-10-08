package nodomain.freeyourgadget.gadgetbridge.activities.workouts.statistics

import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * A sport and its workout count.
 */
data class SportCount(val kindCode: Int, val name: String, val count: Int)

/**
 * Values of one workout used for aggregation. Missing values are 0.
 */
data class WorkoutInput(
    val startMillis: Long,
    val endMillis: Long,
    val distanceMeters: Double = 0.0,
    val calories: Double = 0.0,
    val ascentMeters: Double = 0.0,
    val descentMeters: Double = 0.0,
    val steps: Double = 0.0,
    val avgSpeedMetersPerSecond: Double = 0.0,
    val avgHeartRate: Double = 0.0,
    val avgPowerWatts: Double = 0.0,
    val avgSwolf: Double = 0.0,
    val aerobicEffect: Double = 0.0,
    val anaerobicEffect: Double = 0.0,
    val trainingLoad: Double = 0.0,
    val sets: Double = 0.0,
) {
    val totals: MetricTotals
        get() {
            val durationSeconds = (endMillis - startMillis).coerceAtLeast(0L) / MILLIS_PER_SECOND
            val movingSeconds = if (distanceMeters > 0.0 && avgSpeedMetersPerSecond > 0.0) {
                distanceMeters / avgSpeedMetersPerSecond
            } else {
                durationSeconds.toDouble()
            }
            return MetricTotals(
                durationSeconds = durationSeconds,
                distanceMeters = distanceMeters,
                calories = calories,
                ascentMeters = ascentMeters,
                descentMeters = descentMeters,
                steps = steps,
                speed = WeightedSum.of(avgSpeedMetersPerSecond, movingSeconds),
                heartRate = WeightedSum.of(avgHeartRate, durationSeconds.toDouble()),
                power = WeightedSum.of(avgPowerWatts, durationSeconds.toDouble()),
                swolf = WeightedSum.of(avgSwolf, durationSeconds.toDouble()),
                aerobicEffect = WeightedSum.of(aerobicEffect, durationSeconds.toDouble()),
                anaerobicEffect = WeightedSum.of(anaerobicEffect, durationSeconds.toDouble()),
                trainingLoad = trainingLoad,
                sets = sets,
            )
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/**
 * Sum and weight of a weighted average, so partial sums can be added.
 */
data class WeightedSum(val sum: Double = 0.0, val weight: Double = 0.0) {
    val average: Double get() = if (weight > 0.0) sum / weight else 0.0

    operator fun plus(other: WeightedSum) = WeightedSum(sum + other.sum, weight + other.weight)

    companion object {
        fun of(value: Double, weight: Double) = if (value > 0.0) WeightedSum(value * weight, weight) else WeightedSum()
    }
}

/**
 * Metric sums over workouts, in raw units.
 */
data class MetricTotals(
    val durationSeconds: Long = 0L,
    val distanceMeters: Double = 0.0,
    val calories: Double = 0.0,
    val ascentMeters: Double = 0.0,
    val descentMeters: Double = 0.0,
    val steps: Double = 0.0,
    val speed: WeightedSum = WeightedSum(),
    val heartRate: WeightedSum = WeightedSum(),
    val power: WeightedSum = WeightedSum(),
    val swolf: WeightedSum = WeightedSum(),
    val aerobicEffect: WeightedSum = WeightedSum(),
    val anaerobicEffect: WeightedSum = WeightedSum(),
    val trainingLoad: Double = 0.0,
    val sets: Double = 0.0,
) {
    operator fun plus(other: MetricTotals) = MetricTotals(
        durationSeconds = durationSeconds + other.durationSeconds,
        distanceMeters = distanceMeters + other.distanceMeters,
        calories = calories + other.calories,
        ascentMeters = ascentMeters + other.ascentMeters,
        descentMeters = descentMeters + other.descentMeters,
        steps = steps + other.steps,
        speed = speed + other.speed,
        heartRate = heartRate + other.heartRate,
        power = power + other.power,
        swolf = swolf + other.swolf,
        aerobicEffect = aerobicEffect + other.aerobicEffect,
        anaerobicEffect = anaerobicEffect + other.anaerobicEffect,
        trainingLoad = trainingLoad + other.trainingLoad,
        sets = sets + other.sets,
    )
}

/**
 * Metrics a sport's chart can plot. Speed is in m/s, pace in s/m.
 */
enum class SportMetric(val average: Boolean = false, val valueOf: (MetricTotals) -> Double) {
    DISTANCE(valueOf = { it.distanceMeters }),
    DURATION(valueOf = { it.durationSeconds.toDouble() }),
    AVG_PACE(average = true, valueOf = { if (it.speed.average > 0.0) 1.0 / it.speed.average else 0.0 }),
    AVG_SPEED(average = true, valueOf = { it.speed.average }),
    AVG_HEART_RATE(average = true, valueOf = { it.heartRate.average }),
    AVG_POWER(average = true, valueOf = { it.power.average }),
    AVG_SWOLF(average = true, valueOf = { it.swolf.average }),
    AVG_AEROBIC_EFFECT(average = true, valueOf = { it.aerobicEffect.average }),
    AVG_ANAEROBIC_EFFECT(average = true, valueOf = { it.anaerobicEffect.average }),
    CALORIES(valueOf = { it.calories }),
    ASCENT(valueOf = { it.ascentMeters }),
    DESCENT(valueOf = { it.descentMeters }),
    STEPS(valueOf = { it.steps }),
    TRAINING_LOAD(valueOf = { it.trainingLoad }),
    SETS(valueOf = { it.sets }),
}

enum class PeriodKind { WEEK, MONTH, YEAR }

/**
 * A calendar week (Mon–Sun), month or year. Create with [containing].
 */
data class StatisticsPeriod(val kind: PeriodKind, val start: LocalDate) {
    val endInclusive: LocalDate get() = next().start.minusDays(1)

    val barCount: Int
        get() = when (kind) {
            PeriodKind.WEEK -> DAYS_PER_WEEK
            PeriodKind.MONTH -> start.lengthOfMonth()
            PeriodKind.YEAR -> MONTHS_PER_YEAR
        }

    fun previous() = StatisticsPeriod(kind, shifted(-1))

    fun next() = StatisticsPeriod(kind, shifted(1))

    private fun shifted(amount: Long): LocalDate = when (kind) {
        PeriodKind.WEEK -> start.plusWeeks(amount)
        PeriodKind.MONTH -> start.plusMonths(amount)
        PeriodKind.YEAR -> start.plusYears(amount)
    }

    /**
     * 1-based bar for [date].
     */
    internal fun barOf(date: LocalDate): Int = when (kind) {
        PeriodKind.WEEK -> date.dayOfWeek.value
        PeriodKind.MONTH -> date.dayOfMonth
        PeriodKind.YEAR -> date.monthValue
    }

    companion object {
        private const val DAYS_PER_WEEK = 7
        private const val MONTHS_PER_YEAR = 12

        fun containing(kind: PeriodKind, date: LocalDate) = StatisticsPeriod(
            kind,
            when (kind) {
                PeriodKind.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                PeriodKind.MONTH -> date.withDayOfMonth(1)
                PeriodKind.YEAR -> date.withDayOfYear(1)
            },
        )
    }
}

/**
 * One bar of a period; [x] is 1-based.
 */
data class PeriodBar(val x: Double, val totals: MetricTotals)

/**
 * Totals and bars of a sport over one period.
 */
data class PeriodStats(
    val period: StatisticsPeriod,
    val bars: List<PeriodBar>,
    val count: Int,
    val totals: MetricTotals,
)

/**
 * Date range and metrics with data for a sport.
 */
data class SportOverview(val firstDate: LocalDate?, val lastDate: LocalDate?, val availableMetrics: List<SportMetric>)

object StatisticsAggregation {
    /**
     * Sorted by workout count, then name.
     */
    fun sportCounts(
        kindCodes: List<Int>,
        nameOf: (Int) -> String,
        collator: Comparator<in String>,
    ): List<SportCount> =
        kindCodes.groupingBy { it }.eachCount()
            .map { (code, count) -> SportCount(code, nameOf(code), count) }
            .sortedWith(compareByDescending<SportCount> { it.count }.thenBy(collator) { it.name })

    /**
     * Workouts count in the period they start in.
     */
    fun aggregate(workouts: List<WorkoutInput>, period: StatisticsPeriod, zone: ZoneId): PeriodStats {
        val bars = Array(period.barCount) { MetricTotals() }
        var count = 0
        var totals = MetricTotals()

        for (workout in workouts) {
            val date = Instant.ofEpochMilli(workout.startMillis).atZone(zone).toLocalDate()
            if (date.isBefore(period.start) || date.isAfter(period.endInclusive)) {
                continue
            }
            val workoutTotals = workout.totals
            val index = period.barOf(date) - 1
            bars[index] = bars[index] + workoutTotals
            count++
            totals += workoutTotals
        }

        return PeriodStats(
            period = period,
            bars = bars.mapIndexed { index, bar -> PeriodBar((index + 1).toDouble(), bar) },
            count = count,
            totals = totals,
        )
    }

    fun overview(workouts: List<WorkoutInput>, zone: ZoneId, paceSport: Boolean): SportOverview {
        if (workouts.isEmpty()) {
            return SportOverview(firstDate = null, lastDate = null, availableMetrics = listOf(SportMetric.DURATION))
        }
        val totals = workouts.map { it.totals }
        fun dateOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return SportOverview(
            firstDate = dateOf(workouts.minOf { it.startMillis }),
            lastDate = dateOf(workouts.maxOf { it.startMillis }),
            availableMetrics = SportMetric.entries
                .filter { it != if (paceSport) SportMetric.AVG_SPEED else SportMetric.AVG_PACE }
                .filter { metric -> metric == SportMetric.DURATION || totals.any { metric.valueOf(it) > 0.0 } },
        )
    }

    /**
     * Average speed or pace in m/s. 0 for other units.
     */
    fun speedMetersPerSecond(value: Double, unit: String): Double {
        if (value <= 0.0) {
            return 0.0
        }
        return when (unit) {
            ActivitySummaryEntries.UNIT_METERS_PER_SECOND -> value
            ActivitySummaryEntries.UNIT_KMPH -> value / KMPH_PER_METER_PER_SECOND
            ActivitySummaryEntries.UNIT_SECONDS_PER_KM -> METERS_PER_KM / value
            ActivitySummaryEntries.UNIT_SECONDS_PER_100_METERS -> METERS_PER_100_M / value
            else -> 0.0
        }
    }

    private const val KMPH_PER_METER_PER_SECOND = 3.6
    private const val METERS_PER_KM = 1000.0
    private const val METERS_PER_100_M = 100.0
}
