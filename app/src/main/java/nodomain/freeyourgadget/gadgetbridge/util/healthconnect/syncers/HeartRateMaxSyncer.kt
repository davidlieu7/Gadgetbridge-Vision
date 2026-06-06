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

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * Syncs Gadgetbridge HR samples from devices that store HR in a dedicated
 * [nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider]
 * (e.g. [nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider])
 * rather than in the bundled `ActivitySample` table.
 *
 * The existing [HeartRateSync] reads HR out of `ActivitySample` entries.
 * Some devices — ring-form-factor PPG sensors that stream discrete health
 * metrics instead of aggregated activity rows — don't have an
 * `ActivitySample` DAO. For them HR sits in
 * `DeviceCoordinator.getHeartRateMaxSampleProvider`. This syncer picks
 * those samples up and emits HC [HeartRateRecord]s.
 */
internal object HeartRateMaxSyncer : AbstractTimeSampleSyncer<HeartRateSample, HeartRateRecord>() {
    override val logger: Logger = LoggerFactory.getLogger(HeartRateMaxSyncer::class.java)
    override val recordClass: KClass<HeartRateRecord> = HeartRateRecord::class

    override fun getSampleProvider(
        gbDevice: GBDevice,
        daoSession: DaoSession
    ): TimeSampleProvider<out HeartRateSample>? {
        return gbDevice.deviceCoordinator.getHeartRateMaxSampleProvider(gbDevice, daoSession)
    }

    override fun convertSample(
        sample: HeartRateSample,
        offset: ZoneOffset,
        metadata: Metadata,
        deviceName: String
    ): HeartRateRecord? {
        val bpm = sample.heartRate
        if (bpm !in 20..250) return null
        val ts = Instant.ofEpochMilli(sample.timestamp)
        // HeartRateRecord requires non-zero duration; use a 1-second window.
        return HeartRateRecord(
            startTime = ts,
            startZoneOffset = offset,
            endTime = ts.plusSeconds(1),
            endZoneOffset = offset,
            samples = listOf(HeartRateRecord.Sample(ts, bpm.toLong())),
            metadata = metadata
        )
    }
}
