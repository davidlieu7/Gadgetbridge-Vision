/*  Copyright (C) 2025 Gideon Zenz

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
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.devices.ManualWeightSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.ManualWeightSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample
import nodomain.freeyourgadget.gadgetbridge.util.UserWeight
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectUtils
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.SyncException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

internal object WeightSyncer : AbstractTimeSampleSyncer<WeightSample, WeightRecord>() {
    override val logger: Logger = LoggerFactory.getLogger(WeightSyncer::class.java)
    override val recordClass: KClass<WeightRecord> = WeightRecord::class

    override fun getSampleProvider(
        gbDevice: GBDevice,
        daoSession: DaoSession
    ): TimeSampleProvider<out WeightSample>? {
        return UserWeight.provider(gbDevice, daoSession)
    }

    /**
     * Syncs the manual weights dated up to [syncedUntil] that were added or edited after it.
     */
    suspend fun syncEditedManualSamples(
        healthConnectClient: HealthConnectClient,
        gbDevice: GBDevice,
        metadata: Metadata,
        offset: ZoneId,
        syncedUntil: Instant,
        grantedPermissions: Set<String>
    ): SyncerStatistics {
        val recordTypeName = recordClass.simpleName ?: "Unknown"
        if (HealthPermission.getWritePermission(recordClass) !in grantedPermissions) {
            return SyncerStatistics(recordType = recordTypeName)
        }
        val samples = try {
            GBApplication.acquireDbReadOnly().use { db ->
                if (UserWeight.provider(gbDevice, db.daoSession) == null) {
                    emptyList()
                } else {
                    ManualWeightSampleProvider(db.daoSession).getModifiedSince(syncedUntil.toEpochMilli(), syncedUntil.toEpochMilli())
                }
            }
        } catch (e: Exception) {
            throw SyncException("Error fetching edited manual weights for device '${gbDevice.aliasOrName}'.", e)
        }
        val records = samples.mapNotNull {
            convertSample(it, offset.rules.getOffset(Instant.ofEpochMilli(it.timestamp)), metadata, gbDevice.aliasOrName)
        }
        if (records.isEmpty()) {
            return SyncerStatistics(recordsSkipped = samples.size, recordType = recordTypeName)
        }
        logger.info("Inserting ${records.size} manual weight(s) edited since $syncedUntil for device '${gbDevice.aliasOrName}'.")
        HealthConnectUtils.insertRecords(records, healthConnectClient)
        return SyncerStatistics(
            recordsSynced = records.size,
            recordsSkipped = samples.size - records.size,
            recordType = recordTypeName
        )
    }

    override fun convertSample(
        sample: WeightSample,
        offset: ZoneOffset,
        metadata: Metadata,
        deviceName: String
    ): WeightRecord? {
        val weightInKg = sample.getWeightKg().toDouble()

        if (weightInKg !in 0.0..1000.0 || !weightInKg.isFinite()) {
            logger.skipOutOfRange(deviceName, "Weight", weightInKg, "0..1000 kg")
            return null
        }

        return WeightRecord(
            time = Instant.ofEpochMilli(sample.timestamp),
            zoneOffset = offset,
            weight = Mass.kilograms(weightInKg),
            // A manual weight is synced with every device that supports weight. Its fixed id makes
            // Health Connect keep one record of it rather than one per device.
            metadata = if (sample is ManualWeightSample) {
                Metadata.manualEntry(
                    clientRecordId = "gadgetbridge_manual_weight_${sample.createdTimestamp}",
                    clientRecordVersion = sample.modifiedTimestamp
                )
            } else {
                metadata
            }
        )
    }
}