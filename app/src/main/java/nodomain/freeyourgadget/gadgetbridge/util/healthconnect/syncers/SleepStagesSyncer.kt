/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectUtils
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId

private val LOG = LoggerFactory.getLogger("SleepStagesSyncer")

/**
 * Syncs Gadgetbridge [GenericSleepStageSample] rows into Health Connect
 * as [SleepSessionRecord]s with embedded [SleepSessionRecord.Stage] entries.
 *
 * The existing [SleepSyncer] reconstructs sleep from `ActivitySample`
 * entries. Devices that store sleep stages in the dedicated
 * [GenericSleepStageSample] DAO (ring-form-factor PPG sensors) don't
 * have ActivitySample rows — this syncer fills that gap.
 *
 * Sleep sessions are reconstructed by grouping consecutive stage rows
 * within a 30-minute gap window: any break larger than 30 min starts a
 * new session, matching the AASM convention for distinguishing nightly
 * sleep from naps.
 */
internal object SleepStagesSyncer : HealthConnectSyncer {

    override suspend fun sync(
        healthConnectClient: HealthConnectClient,
        gbDevice: GBDevice,
        metadata: Metadata,
        offset: ZoneId,
        sliceStartBoundary: Instant,
        sliceEndBoundary: Instant,
        grantedPermissions: Set<String>
    ): SyncerStatistics {
        val deviceName = gbDevice.aliasOrName
        val coordinator = gbDevice.deviceCoordinator

        if (!coordinator.supportsDedicatedSleepStageSync(gbDevice)) {
            LOG.debug("Skipping SleepStages sync for '$deviceName' — coordinator does not opt in")
            return SyncerStatistics(recordType = "SleepStages")
        }

        if (HealthPermission.getWritePermission(SleepSessionRecord::class) !in grantedPermissions) {
            LOG.info("Skipping SleepStages sync for '$deviceName' — permission not granted")
            return SyncerStatistics(recordType = "SleepStages")
        }

        val stages = GBApplication.acquireDbReadOnly().use { db ->
            GenericSleepStageSampleProvider(gbDevice, db.daoSession)
                .getAllSamples(sliceStartBoundary.toEpochMilli(), sliceEndBoundary.toEpochMilli())
                .sortedBy { it.timestamp }
        }
        if (stages.isEmpty()) {
            LOG.debug("No sleep-stage samples for '$deviceName' in slice $sliceStartBoundary–$sliceEndBoundary")
            return SyncerStatistics(recordType = "SleepStages")
        }

        // Group into sessions (gaps > 30 min start a new session)
        val sessions = mutableListOf<MutableList<GenericSleepStageSample>>()
        var current = mutableListOf<GenericSleepStageSample>()
        for (s in stages) {
            if (current.isEmpty()) { current.add(s); continue }
            val prev = current.last()
            val gapMs = s.timestamp - (prev.timestamp + prev.duration * 1000L)
            if (gapMs > 30 * 60 * 1000) {
                sessions.add(current); current = mutableListOf()
            }
            current.add(s)
        }
        if (current.isNotEmpty()) sessions.add(current)

        val records = mutableListOf<Record>()
        for (sess in sessions) {
            val first = sess.first()
            val last = sess.last()
            val startTs = Instant.ofEpochMilli(first.timestamp)
            val endTs = Instant.ofEpochMilli(last.timestamp + last.duration * 1000L)
            if (!endTs.isAfter(startTs)) continue
            val hcStages = sess.map { stage ->
                val sStart = Instant.ofEpochMilli(stage.timestamp)
                val sEnd   = Instant.ofEpochMilli(stage.timestamp + stage.duration * 1000L)
                val hcType = mapStage(stage.stage)
                SleepSessionRecord.Stage(sStart, sEnd, hcType)
            }
            records.add(
                SleepSessionRecord(
                    startTime = startTs,
                    startZoneOffset = offset.rules.getOffset(startTs),
                    endTime = endTs,
                    endZoneOffset = offset.rules.getOffset(endTs),
                    stages = hcStages,
                    metadata = metadata
                )
            )
        }

        if (records.isEmpty()) return SyncerStatistics(recordType = "SleepStages")

        LOG.info("Inserting ${records.size} SleepSessionRecord(s) for '$deviceName'")
        for (chunk in records.chunked(HealthConnectUtils.CHUNK_SIZE)) {
            HealthConnectUtils.insertRecords(chunk, healthConnectClient)
        }
        return SyncerStatistics(
            recordsSynced = records.size,
            recordType = "SleepStages",
            latestRecordTimestamp = Instant.ofEpochMilli(stages.last().timestamp)
        )
    }

    private fun mapStage(rawKind: Int): Int {
        return when (ActivityKind.fromCode(rawKind)) {
            ActivityKind.DEEP_SLEEP  -> SleepSessionRecord.STAGE_TYPE_DEEP
            ActivityKind.LIGHT_SLEEP -> SleepSessionRecord.STAGE_TYPE_LIGHT
            ActivityKind.REM_SLEEP   -> SleepSessionRecord.STAGE_TYPE_REM
            ActivityKind.AWAKE_SLEEP -> SleepSessionRecord.STAGE_TYPE_AWAKE
            else                     -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
        }
    }
}
