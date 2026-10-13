package nodomain.freeyourgadget.gadgetbridge.util

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.GenericWeightSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.ManualWeightSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.WeightWithManualSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericWeightSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericWeightSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.ManualWeightSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit
import kotlin.math.roundToInt

/**
 * Keeps the user's weight in About you, the weight records and the devices in step.
 */
object UserWeight {
    const val MIN_KG = 20.0
    const val MAX_KG = 500.0

    /**
     * The weight in kg of [text] in [unit], or null if it is not a plausible weight.
     */
    @JvmStatic
    fun parseKg(text: String, unit: WeightUnit): Double? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        val kg = value / WeightUnit.convertWeight(1.0, unit)
        return kg.takeIf { it in MIN_KG..MAX_KG }
    }

    /**
     * The readings of [device] together with the manual weights, or null if [device] does not support weight.
     */
    @JvmStatic
    fun provider(device: GBDevice, session: DaoSession): TimeSampleProvider<WeightSample>? {
        val coordinator = device.deviceCoordinator
        if (!coordinator.supportsWeightMeasurement(device)) {
            return null
        }
        return WeightWithManualSampleProvider(coordinator.getWeightSampleProvider(device, session), ManualWeightSampleProvider(session))
    }

    /**
     * Whether weight can be entered manually for [device]. Manual weights belong to no device, and are
     * shown for every device that supports weight.
     */
    @JvmStatic
    fun supportsManualEntry(device: GBDevice): Boolean = device.deviceCoordinator.supportsWeightMeasurement(device)

    /**
     * Whether the readings of [device] itself can be edited and deleted, i.e. it keeps generic weight records.
     */
    @JvmStatic
    fun supportsDeviceRecordEdits(device: GBDevice, session: DaoSession): Boolean {
        val coordinator = device.deviceCoordinator
        return coordinator.supportsWeightMeasurement(device) &&
            coordinator.getWeightSampleProvider(device, session) is GenericWeightSampleProvider
    }

    @JvmStatic
    fun addRecord(session: DaoSession, kg: Double, timestampMillis: Long) {
        val sample = ManualWeightSample().apply {
            timestamp = timestampMillis
            userId = DBHelper.getUser(session).id!!
            weightKg = kg.toFloat()
        }
        ManualWeightSampleProvider(session).addSample(sample)
    }

    private fun addDeviceRecord(session: DaoSession, device: GBDevice, kg: Double, timestampMillis: Long) {
        val sample = GenericWeightSample().apply {
            timestamp = timestampMillis
            deviceId = DBHelper.getDevice(device, session).id!!
            userId = DBHelper.getUser(session).id!!
            weightKg = kg.toFloat()
        }
        GenericWeightSampleProvider(device, session).addSample(sample)
    }

    /**
     * Replaces the record at [originalTimestampMillis] with [kg] at [timestampMillis]. A manual weight stays
     * manual and a reading of [device] stays with [device].
     */
    @JvmStatic
    fun replaceRecord(session: DaoSession, device: GBDevice, originalTimestampMillis: Long, kg: Double, timestampMillis: Long) {
        when (deleteRecord(session, device, originalTimestampMillis)) {
            is GenericWeightSample -> addDeviceRecord(session, device, kg, timestampMillis)
            else -> addRecord(session, kg, timestampMillis)
        }
    }

    @JvmStatic
    fun allRecords(session: DaoSession, device: GBDevice): List<GenericWeightSample> =
        GenericWeightSampleProvider(device, session).getAllSamples(0, System.currentTimeMillis())

    /**
     * Deletes the manual weight at [timestampMillis], or else the record of [device] there, and returns it
     * so it can be restored.
     */
    @JvmStatic
    fun deleteRecord(session: DaoSession, device: GBDevice, timestampMillis: Long): WeightSample? {
        val manual = ManualWeightSampleProvider(session)
        manual.get(timestampMillis)?.let {
            manual.delete(timestampMillis)
            return it
        }
        val sample = GenericWeightSampleProvider(device, session).getAllSamples(timestampMillis, timestampMillis).firstOrNull()
            ?: return null
        session.genericWeightSampleDao.queryBuilder()
            .where(
                GenericWeightSampleDao.Properties.DeviceId.eq(sample.deviceId),
                GenericWeightSampleDao.Properties.Timestamp.eq(timestampMillis),
            )
            .buildDelete()
            .executeDeleteWithoutDetachingEntities()
        return sample
    }

    @JvmStatic
    fun restoreRecord(session: DaoSession, sample: WeightSample) {
        when (sample) {
            is ManualWeightSample -> session.manualWeightSampleDao.insertOrReplace(sample)
            is GenericWeightSample -> session.genericWeightSampleDao.insertOrReplace(sample)
        }
    }

    /**
     * Runs [change] on the records of [device]. When it changes the newest record, that becomes the About you
     * weight, and the result is true so the caller can send it to the devices.
     */
    @JvmStatic
    fun changeRecords(session: DaoSession, device: GBDevice, change: Runnable): Boolean {
        val provider = provider(device, session)
        val before = provider?.latestSample?.let { it.timestamp to it.weightKg }
        change.run()
        val after = provider?.latestSample ?: return false
        if (before == (after.timestamp to after.weightKg)) {
            return false
        }
        setProfileWeight(after.weightKg.toDouble())
        return true
    }

    /**
     * Sets the About you weight, without sending it to the devices.
     */
    @JvmStatic
    fun setProfileWeight(kg: Double) {
        GBApplication.getPrefs().preferences.edit()
            .putString(ActivityUser.PREF_USER_WEIGHT_KG, kg.roundToInt().toString())
            .apply()
    }
}
