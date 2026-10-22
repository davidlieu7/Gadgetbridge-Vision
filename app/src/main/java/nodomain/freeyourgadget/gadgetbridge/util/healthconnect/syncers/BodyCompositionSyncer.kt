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

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionCalculator
import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionEstimates
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectUtils
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.SyncException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Synchronizes body composition estimates to Health Connect.
 *
 * Gadgetbridge stores the raw bio-impedance of weight measurements but derives the body
 * composition values on the fly whenever they are displayed; nothing is persisted. This
 * syncer recomputes the same estimates (see [BodyCompositionEstimates]) for every weight
 * sample carrying an impedance and writes them to the Health Connect record types that
 * exist for the individual values:
 *
 * - [BodyFatRecord] from the body fat percentage
 * - [BodyWaterMassRecord] from the body water percentage and the sample's weight
 * - [BoneMassRecord] from the estimated bone mass
 * - [LeanBodyMassRecord] from the fat-free mass implied by the body fat percentage
 * - [BasalMetabolicRateRecord] from the estimated basal metabolic rate
 *
 * Samples without an impedance (plain weight scales) or with an implausible impedance
 * produce no records and are counted as skipped. The estimates are only as good as the
 * single-frequency BIA reading, so consumers should treat them as a trend, not a
 * medical measurement.
 */
internal object BodyCompositionSyncer : HealthConnectSyncer {
    private val logger: Logger = LoggerFactory.getLogger(BodyCompositionSyncer::class.java)

    private const val RECORD_TYPE = "BodyComposition"

    /**
     * A derivable Health Connect record type: the write permission guarding it and the
     * closure turning an estimate into a record. [weightKg] is the sample's weight in
     * kilograms, needed by the mass-based types. A closure returning null skips that
     * type for the sample (e.g. a value outside its plausible range).
     */
    private data class RecordType(
        val name: String,
        val permission: String,
        val build: (Instant, ZoneOffset, Metadata, BodyCompositionCalculator.BodyComposition, Float) -> Record?
    )

    private val recordTypes: List<RecordType> = listOf(
        RecordType("BodyFatRecord", HealthPermission.getWritePermission(BodyFatRecord::class)) { time, offset, metadata, composition, _ ->
            BodyFatRecord(time, offset, Percentage(composition.bodyFatPercent.toDouble()), metadata)
        },
        RecordType("BodyWaterMassRecord", HealthPermission.getWritePermission(BodyWaterMassRecord::class)) { time, offset, metadata, composition, weightKg ->
            val waterKg = weightKg * composition.bodyWaterPercent / 100f
            if (waterKg in 0f..weightKg) BodyWaterMassRecord(time, offset, Mass.kilograms(waterKg.toDouble()), metadata) else null
        },
        RecordType("BoneMassRecord", HealthPermission.getWritePermission(BoneMassRecord::class)) { time, offset, metadata, composition, _ ->
            BoneMassRecord(time, offset, Mass.kilograms(composition.boneMassKg.toDouble()), metadata)
        },
        RecordType("LeanBodyMassRecord", HealthPermission.getWritePermission(LeanBodyMassRecord::class)) { time, offset, metadata, composition, weightKg ->
            val leanKg = weightKg * (100f - composition.bodyFatPercent) / 100f
            if (leanKg in 0f..weightKg) LeanBodyMassRecord(time, offset, Mass.kilograms(leanKg.toDouble()), metadata) else null
        },
        RecordType("BasalMetabolicRateRecord", HealthPermission.getWritePermission(BasalMetabolicRateRecord::class)) { time, offset, metadata, composition, _ ->
            if (composition.basalMetabolicRate > 0) {
                BasalMetabolicRateRecord(time, offset, Power.kilocaloriesPerDay(composition.basalMetabolicRate.toDouble()), metadata)
            } else {
                null
            }
        }
    )

    /**
     * Builds the Health Connect records derivable from a single impedance measurement.
     * Only the record types guarded by a permission in [grantedPermissions] are built,
     * and a record type is skipped for the sample when its value is not usable.
     */
    internal fun buildRecords(
        timestamp: Instant,
        offset: ZoneOffset,
        metadata: Metadata,
        composition: BodyCompositionCalculator.BodyComposition,
        weightKg: Float,
        grantedPermissions: Set<String>
    ): List<Record> = recordTypes
        .filter { it.permission in grantedPermissions }
        .mapNotNull { it.build(timestamp, offset, metadata, composition, weightKg) }

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

        // Only the record types the user has granted write permission for
        val enabledTypes = recordTypes.filter { it.permission in grantedPermissions }
        if (enabledTypes.isEmpty()) {
            logger.info("Skipping $RECORD_TYPE sync for device '$deviceName'; no body composition permissions granted.")
            return SyncerStatistics(recordType = RECORD_TYPE)
        }

        // Fetch weight samples (the only source of impedance data)
        val samples = try {
            GBApplication.acquireDbReadOnly().use { dbInstance ->
                val provider = gbDevice.deviceCoordinator.getWeightSampleProvider(gbDevice, dbInstance.daoSession)
                if (provider == null) {
                    logger.info("$RECORD_TYPE sample provider not available for device '$deviceName'.")
                    return SyncerStatistics(recordType = RECORD_TYPE)
                }
                provider.getAllSamples(sliceStartBoundary.toEpochMilli(), sliceEndBoundary.toEpochMilli())
            }
        } catch (e: Exception) {
            throw SyncException(
                "Error fetching weight samples for device '$deviceName' for slice $sliceStartBoundary to $sliceEndBoundary.",
                e
            )
        }

        if (samples.isEmpty()) {
            logger.info("No weight samples found by provider for device '$deviceName' in slice $sliceStartBoundary to $sliceEndBoundary.")
            return SyncerStatistics(recordType = RECORD_TYPE)
        }

        logger.info("Processing ${samples.size} weight samples for $RECORD_TYPE for device '$deviceName'.")

        var skippedCount = 0
        var latestSyncedTimestamp: Instant? = null
        val recordsToInsert = mutableListOf<Record>()

        GBApplication.acquireDbReadOnly().use { dbInstance ->
            samples.forEach { sample ->
                val timestamp = Instant.ofEpochMilli(sample.timestamp)
                if (timestamp.isBefore(sliceStartBoundary) || timestamp.isAfter(sliceEndBoundary)) {
                    logger.trace(
                        "Skipping weight sample at {} as it's outside the slice {} - {}.",
                        timestamp, sliceStartBoundary, sliceEndBoundary
                    )
                    return@forEach
                }

                val composition = BodyCompositionEstimates.composition(dbInstance.daoSession, sample)
                if (composition == null) {
                    // No impedance, or the estimate is implausible
                    skippedCount++
                    return@forEach
                }

                val sampleOffset = offset.rules.getOffset(timestamp)
                val records = buildRecords(timestamp, sampleOffset, metadata, composition, sample.getWeightKg(), grantedPermissions)
                if (records.isEmpty()) {
                    skippedCount++
                } else {
                    recordsToInsert.addAll(records)
                    if (latestSyncedTimestamp == null || timestamp.isAfter(latestSyncedTimestamp)) {
                        latestSyncedTimestamp = timestamp
                    }
                }
            }
        }

        if (recordsToInsert.isEmpty()) {
            logger.info("No valid $RECORD_TYPE records to insert for device '$deviceName' in slice $sliceStartBoundary to $sliceEndBoundary after processing samples.")
            return SyncerStatistics(recordsSkipped = skippedCount, recordType = RECORD_TYPE)
        }

        // Insert records
        logger.info("Attempting to insert ${recordsToInsert.size} $RECORD_TYPE record(s) for device '$deviceName' for slice $sliceStartBoundary to $sliceEndBoundary.")
        HealthConnectUtils.insertRecords(recordsToInsert, healthConnectClient)

        logger.info("Successfully inserted ${recordsToInsert.size} $RECORD_TYPE record(s) for device '$deviceName' for slice $sliceStartBoundary to $sliceEndBoundary.")
        return SyncerStatistics(
            recordsSynced = recordsToInsert.size,
            recordsSkipped = skippedCount,
            recordType = RECORD_TYPE,
            latestRecordTimestamp = latestSyncedTimestamp
        )
    }
}
