package nodomain.freeyourgadget.gadgetbridge.util

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.GenericWeightSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericWeightSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericWeightSampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

/**
 * Keeps the user's weight in About you, the weight records and the devices in step.
 */
object UserWeight {
    private val LOG = LoggerFactory.getLogger(UserWeight::class.java)

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
     * Whether weight can be entered manually for [device], i.e. it keeps generic weight records.
     */
    @JvmStatic
    fun supportsManualEntry(device: GBDevice, session: DaoSession): Boolean {
        val coordinator = device.deviceCoordinator
        return coordinator.supportsWeightMeasurement(device) &&
            coordinator.getWeightSampleProvider(device, session) is GenericWeightSampleProvider
    }

    @JvmStatic
    fun addRecord(session: DaoSession, device: GBDevice, kg: Double, timestampMillis: Long) {
        val sample = GenericWeightSample().apply {
            timestamp = timestampMillis
            deviceId = DBHelper.getDevice(device, session).id!!
            userId = DBHelper.getUser(session).id!!
            weightKg = kg.toFloat()
        }
        GenericWeightSampleProvider(device, session).addSample(sample)
    }

    @JvmStatic
    fun allRecords(session: DaoSession, device: GBDevice): List<GenericWeightSample> =
        GenericWeightSampleProvider(device, session).getAllSamples(0, System.currentTimeMillis())

    /**
     * Deletes the record of [device] at [timestampMillis] and returns it, so it can be restored.
     */
    @JvmStatic
    fun deleteRecord(session: DaoSession, device: GBDevice, timestampMillis: Long): GenericWeightSample? {
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
    fun restoreRecord(session: DaoSession, sample: GenericWeightSample) {
        session.genericWeightSampleDao.insertOrReplace(sample)
    }

    /**
     * Runs [change] on the records of [device]. When it changes the newest record, that becomes the About you
     * weight, and the result is true so the caller can send it to the devices.
     */
    @JvmStatic
    fun changeRecords(session: DaoSession, device: GBDevice, change: Runnable): Boolean {
        val provider = GenericWeightSampleProvider(device, session)
        val before = provider.latestSample?.let { it.timestamp to it.weightKg }
        change.run()
        val after = provider.latestSample ?: return false
        if (before == (after.timestamp to after.weightKg)) {
            return false
        }
        setProfileWeight(after.weightKg.toDouble())
        return true
    }

    /**
     * Adds a record at [timestampMillis] for every known device that supports manual entry.
     */
    @JvmStatic
    fun addRecordForAllDevices(kg: Double, timestampMillis: Long) {
        try {
            GBApplication.acquireDB().use { db ->
                for (device in GBApplication.app().deviceManager.devices) {
                    if (supportsManualEntry(device, db.daoSession)) {
                        addRecord(db.daoSession, device, kg, timestampMillis)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed to add weight records", e)
        }
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
