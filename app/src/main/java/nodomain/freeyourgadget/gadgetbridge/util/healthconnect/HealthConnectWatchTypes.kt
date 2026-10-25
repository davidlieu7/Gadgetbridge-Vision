/*  Copyright (C) 2026 Gadgetbridge

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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import com.google.gson.JsonObject
import kotlin.reflect.KClass

/**
 * A scalar value extracted from a single Health Connect record.
 *
 * @param value the primary value, in [unit]
 * @param unit machine-readable unit of [value]
 * @param extra optional secondary fields (e.g. diastolic for blood pressure)
 */
data class HealthConnectExtractedValue(
    val value: Double,
    val unit: String,
    val extra: JsonObject? = null
)

/**
 * A Health Connect record type that can be served to connected devices
 * (e.g. Connect IQ watch faces on Garmin watches via
 * [nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.interceptors.HealthConnectInterceptor]).
 *
 * Adding a new type is one entry in [ALL] plus one extractor function. The read
 * permission is derived from the record class, and the same permission is
 * requested in the Health Connect permission flow (see HealthConnectPermissionManager).
 */
class HealthConnectWatchDataType(
    val name: String,
    val recordClass: KClass<out Record>,
    private val extractor: (Record) -> HealthConnectExtractedValue?
) {
    val readPermission: String by lazy {
        HealthPermission.getReadPermission(recordClass)
    }

    // The lambda property must not be named "extract": a member function of the
    // same name shadows it, so "extract(record)" inside the method would resolve
    // to the method itself and recurse until StackOverflowError.
    fun extract(record: Record): HealthConnectExtractedValue? = extractor(record)

    companion object {
        val ALL: List<HealthConnectWatchDataType> = listOf(
            HealthConnectWatchDataType("blood_glucose", BloodGlucoseRecord::class, ::extractBloodGlucose),
            HealthConnectWatchDataType("heart_rate", HeartRateRecord::class, ::extractHeartRate),
            HealthConnectWatchDataType("resting_heart_rate", RestingHeartRateRecord::class, ::extractRestingHeartRate),
            HealthConnectWatchDataType("spo2", OxygenSaturationRecord::class, ::extractSpo2),
            HealthConnectWatchDataType("weight", WeightRecord::class, ::extractWeight),
            HealthConnectWatchDataType("body_fat", BodyFatRecord::class, ::extractBodyFat),
            HealthConnectWatchDataType("body_water_mass", BodyWaterMassRecord::class, ::extractBodyWaterMass),
            HealthConnectWatchDataType("bone_mass", BoneMassRecord::class, ::extractBoneMass),
            HealthConnectWatchDataType("lean_body_mass", LeanBodyMassRecord::class, ::extractLeanBodyMass),
            HealthConnectWatchDataType("basal_metabolic_rate", BasalMetabolicRateRecord::class, ::extractBasalMetabolicRate),
            HealthConnectWatchDataType("body_temperature", BodyTemperatureRecord::class, ::extractBodyTemperature),
            HealthConnectWatchDataType("skin_temperature", SkinTemperatureRecord::class, ::extractSkinTemperature),
            HealthConnectWatchDataType("respiratory_rate", RespiratoryRateRecord::class, ::extractRespiratoryRate),
            HealthConnectWatchDataType("blood_pressure", BloodPressureRecord::class, ::extractBloodPressure),
            HealthConnectWatchDataType("hydration", HydrationRecord::class, ::extractHydration),
            HealthConnectWatchDataType("steps", StepsRecord::class, ::extractSteps),
            HealthConnectWatchDataType("distance", DistanceRecord::class, ::extractDistance),
            HealthConnectWatchDataType("active_calories", ActiveCaloriesBurnedRecord::class, ::extractActiveCalories),
            HealthConnectWatchDataType("total_calories", TotalCaloriesBurnedRecord::class, ::extractTotalCalories),
            HealthConnectWatchDataType("hrv", HeartRateVariabilityRmssdRecord::class, ::extractHrv),
            HealthConnectWatchDataType("vo2_max", Vo2MaxRecord::class, ::extractVo2Max)
        )

        @JvmStatic
        fun byName(name: String?): HealthConnectWatchDataType? = ALL.firstOrNull { it.name == name }

        private fun extractBloodGlucose(r: Record): HealthConnectExtractedValue? {
            val rec = r as? BloodGlucoseRecord ?: return null
            val level = rec.level
            // Juggluco stamps the raw Libre value with
            // BloodGlucose.milligramsPerDeciliter, and the number it writes IS a
            // genuine mg/dL value (116 mg/dL = 6.44 mmol/L, a normal reading).
            // The SDK's unit tag is correct here — do not re-interpret it. Treat
            // the raw value as mg/dL and derive mmol/L, which is the primary
            // display for an mmol/L-region user, keeping mg/dL as the reference.
            val mgdL = level.inMilligramsPerDeciliter
            val mmolL = mgdL / 18.016
            val extra = JsonObject().apply {
                addProperty("mg_dl", mgdL)
            }
            return HealthConnectExtractedValue(mmolL, "mmol/L", extra)
        }

        private fun extractHeartRate(r: Record): HealthConnectExtractedValue? {
            val rec = r as? HeartRateRecord ?: return null
            val sample = rec.samples.lastOrNull() ?: return null
            return HealthConnectExtractedValue(sample.beatsPerMinute.toDouble(), "bpm")
        }

        private fun extractRestingHeartRate(r: Record): HealthConnectExtractedValue? {
            val rec = r as? RestingHeartRateRecord ?: return null
            return HealthConnectExtractedValue(rec.beatsPerMinute.toDouble(), "bpm")
        }

        private fun extractSpo2(r: Record): HealthConnectExtractedValue? {
            val rec = r as? OxygenSaturationRecord ?: return null
            return HealthConnectExtractedValue(rec.percentage.value, "percent")
        }

        private fun extractWeight(r: Record): HealthConnectExtractedValue? {
            val rec = r as? WeightRecord ?: return null
            return HealthConnectExtractedValue(rec.weight.inKilograms, "kg")
        }

        private fun extractBodyFat(r: Record): HealthConnectExtractedValue? {
            val rec = r as? BodyFatRecord ?: return null
            return HealthConnectExtractedValue(rec.percentage.value, "percent")
        }

        private fun extractBodyWaterMass(r: Record): HealthConnectExtractedValue? {
            val rec = r as? BodyWaterMassRecord ?: return null
            return HealthConnectExtractedValue(rec.mass.inKilograms, "kg")
        }

        private fun extractBoneMass(r: Record): HealthConnectExtractedValue? {
            val rec = r as? BoneMassRecord ?: return null
            return HealthConnectExtractedValue(rec.mass.inKilograms, "kg")
        }

        private fun extractLeanBodyMass(r: Record): HealthConnectExtractedValue? {
            val rec = r as? LeanBodyMassRecord ?: return null
            return HealthConnectExtractedValue(rec.mass.inKilograms, "kg")
        }

        private fun extractBasalMetabolicRate(r: Record): HealthConnectExtractedValue? {
            val rec = r as? BasalMetabolicRateRecord ?: return null
            return HealthConnectExtractedValue(rec.basalMetabolicRate.inKilocaloriesPerDay, "kcal/day")
        }

        private fun extractBodyTemperature(r: Record): HealthConnectExtractedValue? {
            val rec = r as? BodyTemperatureRecord ?: return null
            return HealthConnectExtractedValue(rec.temperature.inCelsius, "C")
        }

        private fun extractSkinTemperature(r: Record): HealthConnectExtractedValue? {
            val rec = r as? SkinTemperatureRecord ?: return null
            return HealthConnectExtractedValue(rec.baseline?.inCelsius ?: return null, "C")
        }

        private fun extractRespiratoryRate(r: Record): HealthConnectExtractedValue? {
            val rec = r as? RespiratoryRateRecord ?: return null
            return HealthConnectExtractedValue(rec.rate, "breaths/min")
        }

        private fun extractBloodPressure(r: Record): HealthConnectExtractedValue? {
            val rec = r as? BloodPressureRecord ?: return null
            val extra = JsonObject().apply {
                addProperty("diastolic", rec.diastolic.inMillimetersOfMercury)
            }
            return HealthConnectExtractedValue(rec.systolic.inMillimetersOfMercury, "mmHg", extra)
        }

        private fun extractHydration(r: Record): HealthConnectExtractedValue? {
            val rec = r as? HydrationRecord ?: return null
            return HealthConnectExtractedValue(rec.volume.inMilliliters, "ml")
        }

        private fun extractSteps(r: Record): HealthConnectExtractedValue? {
            val rec = r as? StepsRecord ?: return null
            return HealthConnectExtractedValue(rec.count.toDouble(), "steps")
        }

        private fun extractDistance(r: Record): HealthConnectExtractedValue? {
            val rec = r as? DistanceRecord ?: return null
            return HealthConnectExtractedValue(rec.distance.inMeters, "m")
        }

        private fun extractActiveCalories(r: Record): HealthConnectExtractedValue? {
            val rec = r as? ActiveCaloriesBurnedRecord ?: return null
            return HealthConnectExtractedValue(rec.energy.inKilocalories, "kcal")
        }

        private fun extractTotalCalories(r: Record): HealthConnectExtractedValue? {
            val rec = r as? TotalCaloriesBurnedRecord ?: return null
            return HealthConnectExtractedValue(rec.energy.inKilocalories, "kcal")
        }

        private fun extractHrv(r: Record): HealthConnectExtractedValue? {
            val rec = r as? HeartRateVariabilityRmssdRecord ?: return null
            return HealthConnectExtractedValue(rec.heartRateVariabilityMillis, "ms")
        }

        private fun extractVo2Max(r: Record): HealthConnectExtractedValue? {
            val rec = r as? Vo2MaxRecord ?: return null
            return HealthConnectExtractedValue(rec.vo2MillilitersPerMinuteKilogram, "ml/kg/min")
        }
    }
}
