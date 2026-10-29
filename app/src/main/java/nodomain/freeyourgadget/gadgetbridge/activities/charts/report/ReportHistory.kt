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
package nodomain.freeyourgadget.gadgetbridge.activities.charts.report

import nodomain.freeyourgadget.gadgetbridge.activities.charts.StepAnalysis
import nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep.SleepQuality
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.util.SleepRangeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/**
 * Reads the [ReportDay]s of a range of days for [PeriodReport].
 */
object ReportHistory {
    private const val MIN_HEART_RATE = 30
    private const val MAX_HEART_RATE = 220

    /**
     * Reads the samples, sleep sessions and workouts of the whole range at once, then splits them by day.
     */
    fun days(db: DBHandler, device: GBDevice, range: PeriodRange, zone: ZoneId = ZoneId.systemDefault()): List<ReportDay> {
        val coordinator = device.deviceCoordinator
        val user = ActivityUser()
        val from = range.start.atStartOfDay(zone).toEpochSecond().toInt()
        val to = range.end.atTime(LocalTime.MAX).atZone(zone).toEpochSecond().toInt()
        val rangeMode = SleepRangeUtils.getMode()
        val nights = generateSequence(range.start) { it.plusDays(1) }.takeWhile { !it.isAfter(range.end) }
            .associateWith { SleepRangeUtils.getSleepRange(it.atTime(LocalTime.MAX).atZone(zone).toEpochSecond().toInt(), rangeMode) }

        val sleepProvider = if (coordinator.supportsSleepMeasurement(device)) {
            coordinator.getSleepSessionProvider(device, db.daoSession)
        } else {
            null
        }
        val sessions = sleepProvider?.getSleepSessionsEndingIn(nights.getValue(range.start)[0], nights.getValue(range.end)[1]).orEmpty()
        // From the start of the first night, for the heart rate while asleep
        val samplesFrom = minOf(from, sessions.minOfOrNull { (it.startTime / 1000).toInt() } ?: from)
        val samples = coordinator.getSampleProvider(device, db.daoSession)
            ?.getAllActivitySamples(samplesFrom, to).orEmpty()
            .sortedBy { it.timestamp }
        val samplesByDay: Map<LocalDate, List<ActivitySample>> = samples
            .filter { it.timestamp >= from }
            .groupBy { dateOf(it.timestamp.toLong() * 1000, zone) }

        val workoutsByDay = DBHelper.findDevice(device, db.daoSession)?.let { dbDevice ->
            db.daoSession.baseActivitySummaryDao.queryBuilder().where(
                BaseActivitySummaryDao.Properties.DeviceId.eq(dbDevice.id),
                BaseActivitySummaryDao.Properties.StartTime.ge(Date(from * 1000L)),
                BaseActivitySummaryDao.Properties.StartTime.le(Date(to * 1000L)),
            ).list().groupBy { dateOf(it.startTime.time, zone) }
        }.orEmpty()

        val stepAnalysis = StepAnalysis()
        return nights.map { (date, night) ->
            val daySamples = samplesByDay[date].orEmpty()
            val steps = daySamples.sumOf { it.steps.coerceAtLeast(0).toLong() }
            val stepSessions = stepAnalysis.calculateStepSessions(daySamples, emptyList())
            val activeMinutes = if (stepSessions.isEmpty()) 0L else {
                stepAnalysis.calculateSummary(stepSessions, false).endTime.time / 60_000
            }

            var sleepMinutes = 0L
            var sleepScore: Int? = null
            var sleepingHeartRate: Int? = null
            val nightSessions = sessions.filter { (it.endTime / 1000) in (night[0] + 1L)..night[1] }
            if (nightSessions.isNotEmpty()) {
                val sleepSeconds = nightSessions.sumOf { it.totalSleepDuration }
                sleepMinutes = sleepSeconds / 60
                sleepScore = SleepQuality.score(
                    sleepSeconds / 60.0,
                    nightSessions.sumOf { it.deepSleepDuration } / 60.0,
                    nightSessions.sumOf { it.remSleepDuration } / 60.0,
                    nightSessions.sumOf { it.awakeSleepDuration } / 60.0,
                    user.sleepDurationGoal,
                )
                sleepingHeartRate = nightSessions
                    .flatMap { samplesBetween(samples, (it.startTime / 1000).toInt(), (it.endTime / 1000).toInt()) }
                    .map { it.heartRate }
                    .filter { it in MIN_HEART_RATE..MAX_HEART_RATE }
                    .takeIf { it.isNotEmpty() }
                    ?.average()?.toInt()
            }

            val workouts = workoutsByDay[date].orEmpty()
            ReportDay(
                date = date,
                steps = steps,
                distanceMeters = steps * user.stepLengthCm / 100.0,
                activeMinutes = activeMinutes,
                sleepMinutes = sleepMinutes,
                sleepScore = sleepScore,
                sleepingHeartRate = sleepingHeartRate,
                workouts = workouts.size,
                workoutMinutes = workouts.sumOf { (it.endTime.time - it.startTime.time) / 60_000 },
            )
        }
    }

    /**
     * The samples from [from] to [to] (epoch seconds, both included) of [samples], sorted by time.
     */
    private fun samplesBetween(samples: List<ActivitySample>, from: Int, to: Int): List<ActivitySample> {
        val start = samples.binarySearch { if (it.timestamp < from) -1 else 1 }.let { -(it + 1) }
        val end = samples.binarySearch { if (it.timestamp <= to) -1 else 1 }.let { -(it + 1) }
        return samples.subList(start, end)
    }

    /**
     * The 7-day PAI at the end of [range], or null without PAI.
     */
    fun paiAtEnd(db: DBHandler, device: GBDevice, range: PeriodRange, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val coordinator = device.deviceCoordinator
        if (!coordinator.supportsPai(device)) {
            return null
        }
        val from = range.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = range.end.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()
        return coordinator.getPaiSampleProvider(device, db.daoSession)
            ?.getAllSamples(from, to)
            ?.lastOrNull()
            ?.paiTotal?.toInt()
    }

    private fun dateOf(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
