/*  Copyright (C) 2026 Manuel Marcatili, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.util.sleep

import android.content.Context
import de.greenrobot.dao.query.WhereCondition
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.GenericEstimatedSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericRawSleepSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericEstimatedSleepStageSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericEstimatedSleepStageSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRawSleepSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Sleep of devices that do not stage it, estimated per sleep episode from their stored minutes.
 *
 * Such a device hands over its minutes in pieces (a Garmin watch cuts a new file at midnight and at
 * every sync, a ring drains its history at every connection), so a night spans many imports. Each
 * import's minutes are stored, and the stretch of the stored stream around them is estimated again:
 * every episode between two long breaks is stored as stage rows, and the charts cut the days as they
 * do for any other device.
 *
 * Stage rows sit at the end of the span they cover, as [ActivityKind] codes, after a row with
 * [ONSET] at the session's onset, so a reader mapping timestamps to the next row finds no stage
 * before a session starts.
 *
 * Nothing here depends on the calendar or on the order the pieces arrive in. The stretch fetched
 * around a piece reaches a day each way, so it always holds the breaks on both sides of any sleep
 * the piece touches, as long as no episode runs longer than about seventeen hours without a break.
 */
open class RawSleepNights {

    /**
     * Stores the minutes of one import, then estimates again the stretch of the stored stream they
     * belong to. An episode without enough sleep loses any earlier estimate and shows none.
     */
    fun importMinutes(device: GBDevice, session: DaoSession, context: Context?, minutes: List<SleepMinute>) {
        if (minutes.isEmpty()) {
            return
        }
        val rawProvider = GenericRawSleepSampleProvider(device, session)
        rawProvider.persistSamples(minutes.map(::toSample), context)

        val from = minutes.minOf { it.time }.minusSeconds(REACH_SECONDS).toEpochMilli()
        val to = minutes.maxOf { it.time }.plusSeconds(REACH_SECONDS).toEpochMilli()
        val stored = rawProvider.getAllSamples(from, to).mapNotNull(::toMinute)
        val firstStored = stored.minOf { it.time }.toEpochMilli()
        val lastStored = stored.maxOf { it.time }.toEpochMilli()
        // Only a minute within a break's length of an edge matters, and a bounded range is cheap:
        // an open-ended "next sample" would scan every later row of every other device.
        val gapBefore = rawProvider.getAllSamples(firstStored - BREAK_MILLIS, firstStored - 1)
            .maxOfOrNull { it.timestamp }?.let { missingSlots(it, firstStored) }
        val gapAfter = rawProvider.getAllSamples(lastStored + 1, lastStored + BREAK_MILLIS)
            .minOfOrNull { it.timestamp }?.let { missingSlots(lastStored, it) }

        val settled = SleepStageEstimator.estimateSettled(stored, gapBefore, gapAfter)
        if (settled == null) {
            LOG.debug("No settled stretch around {} raw minutes from {}", minutes.size, minutes.first().time)
            return
        }
        removeEstimates(device, session, settled.zoneStart, settled.zoneEnd)
        onStretchReplaced(device, session, from, to)
        val stageProvider = GenericEstimatedSleepStageSampleProvider(device, session)
        LOG.debug("Settled {} to {} from {} raw minutes: {} sleep episodes", settled.zoneStart, settled.zoneEnd, stored.size, settled.sessions.size)
        for (estimate in settled.sessions) {
            LOG.debug("Estimated sleep {} to {}, {} min asleep", estimate.onset, estimate.end, estimate.sleepMinutes)
            stageProvider.persistSamples(stageSamples(estimate), context)
            onSessionStored(device, session, context, estimate)
        }
    }

    /** Called once the estimates in a stretch are gone, with the bounds of the minutes fetched for it. */
    protected open fun onStretchReplaced(device: GBDevice, session: DaoSession, fromMillis: Long, toMillis: Long) {
    }

    /** Called for every session after its stage rows are stored. */
    protected open fun onSessionStored(device: GBDevice, session: DaoSession, context: Context?, estimate: EstimatedSleepSession) {
    }

    /**
     * Called with the span `[fromMillis, toMillis]` of whole sessions being deleted, before their
     * stage rows go.
     */
    protected open fun onSessionsRemoved(device: GBDevice, session: DaoSession, fromMillis: Long, toMillis: Long) {
    }

    /**
     * Deletes the stage rows of every estimated session reaching into `[start, end)`. A session is
     * taken whole even when its onset or its end lies outside, so no half session is left behind.
     */
    private fun removeEstimates(device: GBDevice, session: DaoSession, start: Instant, end: Instant) {
        val dbDevice = DBHelper.findDevice(device, session) ?: return
        val dao = session.genericEstimatedSleepStageSampleDao
        val deviceId = GenericEstimatedSleepStageSampleDao.Properties.DeviceId.eq(dbDevice.id)
        val timestamp = GenericEstimatedSleepStageSampleDao.Properties.Timestamp
        val stage = GenericEstimatedSleepStageSampleDao.Properties.Stage
        fun first(vararg conditions: WhereCondition) =
            dao.queryBuilder().where(deviceId, *conditions).orderAsc(timestamp).limit(1).list().firstOrNull()
        fun last(vararg conditions: WhereCondition) =
            dao.queryBuilder().where(deviceId, *conditions).orderDesc(timestamp).limit(1).list().firstOrNull()

        var low = start.toEpochMilli()
        var high = end.toEpochMilli() - 1
        // A session crossing the start began at the last onset before it
        val afterStart = first(timestamp.ge(start.toEpochMilli()))
        if (afterStart != null && afterStart.stage != ONSET) {
            last(timestamp.lt(start.toEpochMilli()), stage.eq(ONSET))?.let { low = it.timestamp }
        }
        // A session crossing the end runs up to the row before the next onset
        val afterEnd = first(timestamp.ge(end.toEpochMilli()))
        if (afterEnd != null && afterEnd.stage != ONSET) {
            val nextOnset = first(timestamp.gt(afterEnd.timestamp), stage.eq(ONSET))
            val sessionEnd = if (nextOnset != null) last(timestamp.lt(nextOnset.timestamp)) else last()
            sessionEnd?.let { high = it.timestamp }
        }
        onSessionsRemoved(device, session, low, high)
        dao.queryBuilder()
            .where(deviceId, timestamp.between(low, high))
            .buildDelete()
            .executeDeleteWithoutDetachingEntities()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(RawSleepNights::class.java)

        private const val REACH_SECONDS = 24 * 3600L

        /** How far beyond the fetched stretch a stored minute still matters: a break's length. */
        private const val BREAK_MILLIS = 45 * 60_000L

        /** Stage code of the row marking a session's onset. */
        @JvmField
        val ONSET = ActivityKind.NOT_MEASURED.code

        /** The onset row, then one row per stage span at the end of the span. */
        @JvmStatic
        fun stageSamples(session: EstimatedSleepSession): List<GenericEstimatedSleepStageSample> =
            listOf(GenericEstimatedSleepStageSample().apply {
                timestamp = session.onset.toEpochMilli()
                stage = ONSET
            }) + session.stages.map { span ->
                GenericEstimatedSleepStageSample().apply {
                    timestamp = span.end.toEpochMilli()
                    stage = activityKindOf(span.stage).code
                }
            }

        /** The activity kind a stored stage code stands for; the onset and unknown spans stand for no sleep. */
        @JvmStatic
        fun activityKindOfStored(stage: Int): ActivityKind = when (val kind = ActivityKind.fromCode(stage)) {
            ActivityKind.AWAKE_SLEEP, ActivityKind.LIGHT_SLEEP, ActivityKind.DEEP_SLEEP, ActivityKind.REM_SLEEP -> kind
            else -> ActivityKind.UNKNOWN
        }

        private fun activityKindOf(stage: EstimatedStage): ActivityKind = when (stage) {
            EstimatedStage.AWAKE -> ActivityKind.AWAKE_SLEEP
            EstimatedStage.LIGHT -> ActivityKind.LIGHT_SLEEP
            EstimatedStage.DEEP -> ActivityKind.DEEP_SLEEP
            EstimatedStage.REM -> ActivityKind.REM_SLEEP
            EstimatedStage.UNKNOWN -> ActivityKind.UNKNOWN
        }

        /** Whole minute slots with no stored minute between two timestamps, as the estimator's grid counts them. */
        private fun missingSlots(earlierMillis: Long, laterMillis: Long): Int =
            maxOf(0L, laterMillis / 60_000L - earlierMillis / 60_000L - 1).toInt()

        private fun toSample(minute: SleepMinute) = GenericRawSleepSample().apply {
            timestamp = minute.time.toEpochMilli()
            kind = minute.kind.code
            if (minute.kind == SleepMinuteKind.RAW) {
                heartRate = minute.heartRate
                movement = minute.movement
            }
        }

        private fun toMinute(sample: GenericRawSleepSample): SleepMinute? {
            val kind = SleepMinuteKind.fromCode(sample.kind) ?: return null
            return SleepMinute(Instant.ofEpochMilli(sample.timestamp), kind, sample.movement ?: 0f, sample.heartRate)
        }
    }
}
