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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepScoreSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepScoreSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.SoundcoreSleepSessionSample
import nodomain.freeyourgadget.gadgetbridge.entities.SoundcoreSleepSessionSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.SoundcoreSleepStageSample
import nodomain.freeyourgadget.gadgetbridge.entities.SoundcoreSleepStageSampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice

object SoundcoreSleepHistoryStorage {
    @JvmStatic fun persist(device: GBDevice, report: SoundcoreSleepHistory.Report) {
        GBApplication.acquireDB().use { db ->
            val session = db.daoSession
            val deviceId = requireNotNull(DBHelper.getDevice(device, session).id)
            val userId = requireNotNull(DBHelper.getUser(session).id)
            session.runInTx {
                val sessionDao = session.soundcoreSleepSessionSampleDao
                val scoreDao = session.genericSleepScoreSampleDao
                val staleRanges = mutableListOf<LongRange>()
                for (night in report.sessions) {
                    val old = sessionDao.queryBuilder().where(
                        SoundcoreSleepSessionSampleDao.Properties.DeviceId.eq(deviceId),
                        SoundcoreSleepSessionSampleDao.Properties.Timestamp.eq(night.start * 1000L)
                    ).build().unique()
                    // Never regress a longer version already stored by an earlier fetch.
                    if (old != null && old.endTimestamp > night.end * 1000L) {
                        staleRanges.add(night.start..night.end)
                        continue
                    }
                    if (old != null && old.endTimestamp != night.end * 1000L) {
                        scoreDao.queryBuilder().where(GenericSleepScoreSampleDao.Properties.DeviceId.eq(deviceId),
                            GenericSleepScoreSampleDao.Properties.Timestamp.eq(old.endTimestamp))
                            .buildDelete().executeDeleteWithoutDetachingEntities()
                    }
                    sessionDao.insertOrReplace(SoundcoreSleepSessionSample(night.start * 1000L, deviceId, userId,
                        night.end * 1000L, night.type, night.score, night.unknownCount, night.durationMinutes, night.unknown))
                    if (night.score > 0) {
                        scoreDao.insertOrReplace(GenericSleepScoreSample(night.end * 1000L, deviceId, userId, night.score))
                    }
                }
                val ends = report.intervals.associate { it.start to it.end }
                // Preserve all raw records, including unknown values and endpoint markers. Only
                // fully bounded intervals are exposed to charts; raw timestamps stay millisecond-precise.
                val samples = report.records.filter { record ->
                    staleRanges.none { record.timestamp in it }
                }.map { record ->
                    val old = session.soundcoreSleepStageSampleDao.queryBuilder().where(
                        SoundcoreSleepStageSampleDao.Properties.DeviceId.eq(deviceId),
                        SoundcoreSleepStageSampleDao.Properties.Timestamp.eq(record.timestamp * 1000L)
                    ).build().unique()
                    val end = ends[record.timestamp]?.times(1000L)
                        ?: old?.endTimestamp ?: record.timestamp * 1000L
                    SoundcoreSleepStageSample(record.timestamp * 1000L, deviceId, userId,
                        end, record.packed, record.rollovers, record.flags)
                }
                session.soundcoreSleepStageSampleDao.insertOrReplaceInTx(samples)
            }
        }
    }
}
