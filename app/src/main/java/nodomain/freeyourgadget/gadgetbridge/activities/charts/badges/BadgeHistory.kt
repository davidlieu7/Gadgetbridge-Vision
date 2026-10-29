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
package nodomain.freeyourgadget.gadgetbridge.activities.charts.badges

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.util.SleepRangeUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * Reads the days and workouts of a device for [BadgeCalculator]. The day totals are kept in a cache file, so that only
 * the days since the last read are read again.
 */
object BadgeHistory {
    private val LOG = LoggerFactory.getLogger(BadgeHistory::class.java)

    private const val CHUNK_SECONDS = 30 * 24 * 3600
    private const val MAX_SAMPLE_SECONDS = 600L
    private const val CACHE_VERSION = 1

    /**
     * The whole history is read again after this long, to pick up older data imported since.
     */
    private const val CACHE_MAX_AGE_DAYS = 7L

    /**
     * Totals of a day in seconds, sleep of the night that ended on it.
     */
    private class DayTotals(var steps: Long = 0, var sleep: Long = 0, var deep: Long = 0, var rem: Long = 0, var awake: Long = 0)

    /**
     * Synchronized: the badges and the streak widget share the cache file.
     */
    @Synchronized
    fun days(db: DBHandler, device: GBDevice, zone: ZoneId = ZoneId.systemDefault()): List<BadgeDay> {
        val provider = device.deviceCoordinator.getSampleProvider(device, db.daoSession) ?: return emptyList()
        val first = provider.firstActivitySample ?: return emptyList()
        val mode = SleepRangeUtils.getMode()
        val nightShift = nightShiftSeconds(mode)
        val key = "$CACHE_VERSION|${first.timestamp}|$mode|${zone.id}"
        val today = LocalDate.now(zone)
        val cacheFile = File(GBApplication.getContext().cacheDir, "badge_days_" + device.address.replace(Regex("[^A-Za-z0-9]"), "_"))

        val totals = TreeMap<LocalDate, DayTotals>()
        // Days from this one on are read again: the last one read may have been incomplete
        var readFrom = LocalDate.MIN
        readCache(cacheFile, key, today)?.let { (computed, cached) ->
            totals.putAll(cached)
            readFrom = computed.minusDays(1)
            totals.tailMap(readFrom, true).clear()
        }

        // Each sample lasts until the next one, at most MAX_SAMPLE_SECONDS
        var previousTimestamp = -1L
        var previousKind: ActivityKind? = null
        val end = (System.currentTimeMillis() / 1000).toInt()
        var from = if (readFrom == LocalDate.MIN) {
            first.timestamp
        } else {
            maxOf(first.timestamp.toLong(), readFrom.atStartOfDay(zone).toEpochSecond() - nightShift - MAX_SAMPLE_SECONDS).toInt()
        }
        while (from <= end) {
            val to = minOf(from.toLong() + CHUNK_SECONDS - 1, end.toLong()).toInt()
            for (sample in provider.getAllActivitySamples(from, to)) {
                val timestamp = sample.timestamp.toLong()
                val kind = previousKind
                if (kind != null && ActivityKind.isSleep(kind)) {
                    val night = dateOf(previousTimestamp + nightShift, zone)
                    if (!night.isBefore(readFrom)) {
                        val duration = (timestamp - previousTimestamp).coerceIn(0, MAX_SAMPLE_SECONDS)
                        val day = totals.getOrPut(night) { DayTotals() }
                        when (kind) {
                            ActivityKind.AWAKE_SLEEP -> day.awake += duration
                            ActivityKind.DEEP_SLEEP -> day.deep += duration
                            ActivityKind.REM_SLEEP -> day.rem += duration
                            else -> {}
                        }
                        // Awake time in bed isn't sleep
                        if (kind != ActivityKind.AWAKE_SLEEP) {
                            day.sleep += duration
                        }
                    }
                }
                if (sample.steps > 0) {
                    val date = dateOf(timestamp, zone)
                    if (!date.isBefore(readFrom)) {
                        totals.getOrPut(date) { DayTotals() }.steps += sample.steps
                    }
                }
                previousTimestamp = timestamp
                previousKind = sample.kind
            }
            from = to + 1
        }
        writeCache(cacheFile, key, today, totals)

        return totals.map { (date, day) ->
            BadgeDay(date, day.steps, day.sleep / 60, day.deep / 60, day.rem / 60, day.awake / 60)
        }
    }

    /**
     * How far sleep is moved forward to fall on the day its night ends, like the sleep range of the sleep charts:
     * noon to noon, 18:00 to 18:00, or the calendar day.
     */
    private fun nightShiftSeconds(mode: SleepRangeUtils.SleepRangeMode): Long = when (mode) {
        SleepRangeUtils.SleepRangeMode.NOON -> 12 * 3600L
        SleepRangeUtils.SleepRangeMode.EVENING -> 6 * 3600L
        SleepRangeUtils.SleepRangeMode.ROLLING_24H -> 0L
    }

    /**
     * The date the cache was written and its days, or null when it is missing, stale or for other settings. Format:
     * the key, the date, then one "date,steps,sleep,deep,rem,awake" line per day.
     */
    private fun readCache(file: File, key: String, today: LocalDate): Pair<LocalDate, Map<LocalDate, DayTotals>>? {
        if (!file.isFile) {
            return null
        }
        return try {
            val lines = file.readLines()
            if (lines.size < 2 || lines[0] != key) {
                return null
            }
            val computed = LocalDate.parse(lines[1])
            if (computed.isAfter(today) || computed.plusDays(CACHE_MAX_AGE_DAYS).isBefore(today)) {
                return null
            }
            computed to lines.drop(2).filter { it.isNotBlank() }.associate { line ->
                val values = line.split(',')
                LocalDate.parse(values[0]) to DayTotals(values[1].toLong(), values[2].toLong(), values[3].toLong(), values[4].toLong(), values[5].toLong())
            }
        } catch (e: Exception) {
            LOG.warn("Ignoring the badge cache", e)
            null
        }
    }

    private fun writeCache(file: File, key: String, today: LocalDate, totals: Map<LocalDate, DayTotals>) {
        try {
            // Written aside then renamed, so that a failed write never leaves half a cache
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.bufferedWriter().use { writer ->
                writer.appendLine(key)
                writer.appendLine(today.toString())
                for ((date, day) in totals) {
                    writer.appendLine("$date,${day.steps},${day.sleep},${day.deep},${day.rem},${day.awake}")
                }
            }
            if (!temp.renameTo(file)) {
                temp.delete()
            }
        } catch (e: Exception) {
            LOG.warn("Failed to write the badge cache", e)
        }
    }

    fun workouts(db: DBHandler, device: GBDevice, zone: ZoneId = ZoneId.systemDefault()): List<BadgeWorkout> {
        val dbDevice = DBHelper.findDevice(device, db.daoSession) ?: return emptyList()
        return db.daoSession.baseActivitySummaryDao.queryBuilder()
            .where(BaseActivitySummaryDao.Properties.DeviceId.eq(dbDevice.id))
            .list()
            .map { BadgeWorkout(dateOf(it.startTime.time / 1000, zone), (it.endTime.time - it.startTime.time) / 60_000) }
    }

    private fun dateOf(epochSecond: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalDate()
}
