/*  Copyright (C) 2026 Gadgetbridge contributors

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

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BloodPressureSample
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

internal object BloodPressureSyncer : AbstractTimeSampleSyncer<BloodPressureSample, BloodPressureRecord>() {
    override val logger: Logger = LoggerFactory.getLogger(BloodPressureSyncer::class.java)
    override val recordClass: KClass<BloodPressureRecord> = BloodPressureRecord::class

    override fun getSampleProvider(
        gbDevice: GBDevice,
        daoSession: DaoSession
    ): TimeSampleProvider<out BloodPressureSample>? {
        return gbDevice.deviceCoordinator.getBloodPressureSampleProvider(gbDevice, daoSession)
    }

    override fun convertSample(
        sample: BloodPressureSample,
        offset: ZoneOffset,
        metadata: Metadata,
        deviceName: String
    ): BloodPressureRecord? {
        val systolic = sample.bpSystolic
        val diastolic = sample.bpDiastolic
        if (systolic !in 20..200) {
            logger.skipOutOfRange(deviceName, "BloodPressure systolic", systolic, "20..200 mmHg")
            return null
        }
        if (diastolic !in 10..180) {
            logger.skipOutOfRange(deviceName, "BloodPressure diastolic", diastolic, "10..180 mmHg")
            return null
        }
        if (systolic <= diastolic) {
            logger.skipOutOfRange(
                deviceName,
                "BloodPressure",
                "$systolic/$diastolic mmHg",
                "systolic > diastolic"
            )
            return null
        }

        // Deterministic id so a re-synced overlapping window upserts instead of duplicating.
        // Version stays fixed: BP readings are immutable once taken.
        return BloodPressureRecord(
            time = Instant.ofEpochMilli(sample.timestamp),
            zoneOffset = offset,
            metadata = clientRecordMetadata(metadata, "blood-pressure", sample.timestamp, 0L),
            systolic = Pressure.millimetersOfMercury(systolic.toDouble()),
            diastolic = Pressure.millimetersOfMercury(diastolic.toDouble())
        )
    }
}
