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

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class BodyCompositionSyncerTest {

    private val timestamp = Instant.ofEpochMilli(1_700_000_000_000L)
    private val offset = ZoneOffset.UTC
    private val weightKg = 80f

    private fun metadata(): Metadata =
        Metadata.autoRecorded(
            Device(
                manufacturer = "Test",
                model = "TestModel",
                type = Device.TYPE_SCALE,
            )
        )

    private val allPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class)
    )

    /**
     * A real estimate via the public calculator: 35-year-old male, 180 cm, 80 kg, 500 ohm.
     */
    private fun composition(): BodyCompositionCalculator.BodyComposition {
        val c = BodyCompositionCalculator.compute(ActivityUser.GENDER_MALE, 35, 180, weightKg, 500f)
        assertNotNull(c)
        return c!!
    }

    @Test
    fun compute_implausibleImpedance_returnsNull() {
        assertNull(BodyCompositionCalculator.compute(ActivityUser.GENDER_MALE, 35, 180, weightKg, 100f))
        assertNull(BodyCompositionCalculator.compute(ActivityUser.GENDER_MALE, 35, 180, weightKg, 1500f))
    }

    @Test
    fun compute_missingProfile_returnsNull() {
        assertNull(BodyCompositionCalculator.compute(ActivityUser.GENDER_MALE, 35, 0, weightKg, 500f))
    }

    @Test
    fun buildRecords_allPermissions_returnsAllFiveRecords() {
        val records = BodyCompositionSyncer.buildRecords(timestamp, offset, metadata(), composition(), weightKg, allPermissions)

        assertEquals(5, records.size)
        val types = records.map { it::class.java }.toSet()
        assertEquals(
            setOf(
                BodyFatRecord::class.java,
                BodyWaterMassRecord::class.java,
                BoneMassRecord::class.java,
                LeanBodyMassRecord::class.java,
                BasalMetabolicRateRecord::class.java
            ),
            types
        )
    }

    @Test
    fun buildRecords_noPermissions_returnsEmpty() {
        val records = BodyCompositionSyncer.buildRecords(timestamp, offset, metadata(), composition(), weightKg, emptySet())
        assertTrue(records.isEmpty())
    }

    @Test
    fun buildRecords_singlePermission_returnsOnlyThatRecord() {
        val permissions = setOf(HealthPermission.getWritePermission(BodyFatRecord::class))
        val records = BodyCompositionSyncer.buildRecords(timestamp, offset, metadata(), composition(), weightKg, permissions)

        assertEquals(1, records.size)
        assertTrue(records[0] is BodyFatRecord)
    }

    @Test
    fun buildRecords_valuesMatchComposition() {
        val c = composition()
        val records = BodyCompositionSyncer.buildRecords(timestamp, offset, metadata(), c, weightKg, allPermissions)

        val bodyFat = records.filterIsInstance<BodyFatRecord>().single()
        assertEquals(timestamp, bodyFat.time)
        assertEquals(offset, bodyFat.zoneOffset)
        assertEquals(c.bodyFatPercent.toDouble(), bodyFat.percentage.value, 1e-9)

        val boneMass = records.filterIsInstance<BoneMassRecord>().single()
        assertEquals(c.boneMassKg.toDouble(), boneMass.mass.inKilograms, 1e-9)

        val waterMass = records.filterIsInstance<BodyWaterMassRecord>().single()
        // mirror the syncer's float arithmetic exactly, so the comparison is bit-precise
        val expectedWaterKg = (weightKg * c.bodyWaterPercent / 100f).toDouble()
        assertEquals(expectedWaterKg, waterMass.mass.inKilograms, 1e-9)

        val leanMass = records.filterIsInstance<LeanBodyMassRecord>().single()
        val expectedLeanKg = (weightKg * (100f - c.bodyFatPercent) / 100f).toDouble()
        assertEquals(expectedLeanKg, leanMass.mass.inKilograms, 1e-9)

        val bmr = records.filterIsInstance<BasalMetabolicRateRecord>().single()
        assertEquals(c.basalMetabolicRate.toDouble(), bmr.basalMetabolicRate.inKilocaloriesPerDay, 1e-9)
    }

    @Test
    fun buildRecords_zeroBmr_skipsBmrRecord() {
        // BodyComposition's constructor is package-private; reach it via reflection so the
        // test does not require widening visibility of production code.
        val constructor = BodyCompositionCalculator.BodyComposition::class.java
            .getDeclaredConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        val c = constructor.newInstance(20f, 55f, 60f, 2.8f, 0)

        val records = BodyCompositionSyncer.buildRecords(timestamp, offset, metadata(), c, weightKg, allPermissions)

        assertEquals(4, records.size)
        assertTrue(records.none { it is BasalMetabolicRateRecord })
    }
}
