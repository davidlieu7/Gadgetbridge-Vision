/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.devices.soundcore

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.generic_hr.GenericHeartRateActivitySampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample
import nodomain.freeyourgadget.gadgetbridge.entities.SoundcoreSleepStageSample
import nodomain.freeyourgadget.gadgetbridge.entities.SoundcoreSleepStageSampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind

/** Projects exact sleep intervals onto the activity chart's minute grid. */
class SoundcoreSleepSampleProvider(private val device: GBDevice, private val session: DaoSession) :
    GenericHeartRateActivitySampleProvider(device, session) {

    override fun getAllActivitySamples(timestampFrom: Int, timestampTo: Int): MutableList<GenericActivitySample> {
        if (timestampFrom > timestampTo) return mutableListOf()
        val deviceId = DBHelper.findDevice(device, session)?.id ?: return mutableListOf()
        val records = session.soundcoreSleepStageSampleDao.queryBuilder().where(
            SoundcoreSleepStageSampleDao.Properties.DeviceId.eq(deviceId),
            SoundcoreSleepStageSampleDao.Properties.Timestamp.le(timestampTo.toLong() * 1000L),
            SoundcoreSleepStageSampleDao.Properties.EndTimestamp.gt(timestampFrom.toLong() * 1000L)
        ).orderAsc(SoundcoreSleepStageSampleDao.Properties.Timestamp).build().list()
        return projectSamples(records, timestampFrom, timestampTo)
    }

    internal fun projectSamples(records: List<SoundcoreSleepStageSample>, timestampFrom: Int,
                                timestampTo: Int): MutableList<GenericActivitySample> {
        val samples = sortedMapOf<Int, GenericActivitySample>()
        val intervals = records.filter { it.endTimestamp > it.timestamp }.sortedBy { it.timestamp }
        fun addUnknown(timestamp: Long) {
            if (timestamp !in timestampFrom.toLong()..timestampTo.toLong()) return
            samples.getOrPut(timestamp.toInt()) {
                GenericActivitySample().also {
                    it.provider = this
                    it.timestamp = timestamp.toInt()
                    it.rawKind = ActivityKind.UNKNOWN.code
                }
            }
        }
        for ((index, record) in intervals.withIndex()) {
            val start = maxOf(record.timestamp / 1000L, timestampFrom.toLong())
            val end = record.endTimestamp / 1000L
            var timestamp = ((start + 59L) / 60L) * 60L
            while (timestamp < end && timestamp <= timestampTo) {
                samples[timestamp.toInt()] = sample(timestamp.toInt(), record)
                timestamp += 60L
            }
            val next = intervals.getOrNull(index + 1)
            if (next == null || next.timestamp > record.endTimestamp) {
                // Explicit boundaries prevent activity analysis from attributing an unmeasured
                // gap to the next sleep sample. Unknown does not invent an awake-stage reading.
                addUnknown(end)
                if (next != null) addUnknown(next.timestamp / 1000L - 1L)
            }
        }
        return samples.values.toMutableList()
    }

    private fun sample(timestamp: Int, record: SoundcoreSleepStageSample): GenericActivitySample =
        GenericActivitySample().also {
            it.provider = this
            it.timestamp = timestamp
            it.rawKind = when (record.rawKind and 15) {
                2 -> ActivityKind.LIGHT_SLEEP.code
                3 -> ActivityKind.DEEP_SLEEP.code
                else -> ActivityKind.UNKNOWN.code
            }
        }

    override fun getLatestActivitySample(): GenericActivitySample? = getLatestActivitySample(Int.MAX_VALUE)
    override fun getFirstActivitySample(): GenericActivitySample? = getFirstActivitySample(0)
    override fun getLatestActivitySample(until: Int): GenericActivitySample? = boundary(until, true)
    override fun getFirstActivitySample(after: Int): GenericActivitySample? = boundary(after, false)

    private fun boundary(limit: Int, latest: Boolean): GenericActivitySample? {
        val deviceId = DBHelper.findDevice(device, session)?.id ?: return null
        val query = session.soundcoreSleepStageSampleDao.queryBuilder().where(
            SoundcoreSleepStageSampleDao.Properties.DeviceId.eq(deviceId),
            de.greenrobot.dao.query.WhereCondition.StringCondition("T.\"END_TIMESTAMP\" > T.\"TIMESTAMP\"")
        )
        if (latest) {
            query.where(SoundcoreSleepStageSampleDao.Properties.Timestamp.le(limit.toLong() * 1000L))
                .orderDesc(SoundcoreSleepStageSampleDao.Properties.EndTimestamp)
        } else {
            query.where(SoundcoreSleepStageSampleDao.Properties.EndTimestamp.gt(limit.toLong() * 1000L))
                .orderAsc(SoundcoreSleepStageSampleDao.Properties.Timestamp)
        }
        val record = query.limit(1).build().unique() ?: return null
        val start = record.timestamp / 1000L
        val end = record.endTimestamp / 1000L
        val timestamp = if (latest) minOf(limit.toLong(), end - 1) / 60L * 60L
            else ((maxOf(limit.toLong(), start) + 59L) / 60L) * 60L
        if (timestamp < start || timestamp >= end) return null
        return sample(timestamp.toInt(), record)
    }
}
