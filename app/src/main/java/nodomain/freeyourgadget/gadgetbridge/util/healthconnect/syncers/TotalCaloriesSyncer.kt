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

import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

internal object TotalCaloriesSyncer : AbstractActivitySampleSyncer<TotalCaloriesBurnedRecord>() {
    override val logger: Logger = LoggerFactory.getLogger(TotalCaloriesSyncer::class.java)
    override val recordClass: KClass<TotalCaloriesBurnedRecord> = TotalCaloriesBurnedRecord::class

    override fun convertSample(
        sample: ActivitySample,
        offset: ZoneOffset,
        metadata: Metadata,
        deviceName: String,
        version: Long
    ): TotalCaloriesBurnedRecord? {
        // Devices with supportsTotalCalories expose their single reported kcal figure through
        // getActiveCalories (the band/vendor app shows one "calories" number, no active/total
        // split), so the same value feeds both record types.
        val calories = sample.activeCalories
        if (calories <= 0) {
            return null
        }
        // HC's TotalCaloriesBurnedRecord caps energy at 1_000_000 kcal (= 1e9 cal).
        if (calories > 1_000_000_000) {
            logger.skipOutOfRange(deviceName, "TotalCalories", "$calories cal", "<= 1000000 kcal per record")
            return null
        }

        val endTs = Instant.ofEpochSecond(sample.timestamp.toLong())
        val startTs = endTs.minus(1, ChronoUnit.MINUTES)

        return TotalCaloriesBurnedRecord(
            startTs,
            offset,
            endTs,
            offset,
            Energy.calories(calories.toDouble()),
            clientRecordMetadata(metadata, "total-calories", endTs.epochSecond, version)
        )
    }
}
