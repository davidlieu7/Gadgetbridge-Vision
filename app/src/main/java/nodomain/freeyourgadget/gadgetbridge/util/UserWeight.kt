package nodomain.freeyourgadget.gadgetbridge.util

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.GenericWeightSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.ManualWeightSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.WeightWithManualSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
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

    @JvmStatic
    @JvmOverloads
    fun addRecord(session: DaoSession, kg: Double, timestampMillis: Long, createdMillis: Long? = null) {
        val now = System.currentTimeMillis()
        val sample = ManualWeightSample().apply {
            timestamp = timestampMillis
            userId = DBHelper.getUser(session).id!!
            weightKg = kg.toFloat()
            createdTimestamp = createdMillis ?: now
            modifiedTimestamp = now
        }
        ManualWeightSampleProvider(session).addSample(sample)
    }

    /**
     * Moves the manual weight at [originalTimestampMillis] to [kg] at [timestampMillis], keeping its creation time.
     */
    @JvmStatic
    fun replaceRecord(session: DaoSession, originalTimestampMillis: Long, kg: Double, timestampMillis: Long) {
        val original = deleteRecord(session, originalTimestampMillis)
        addRecord(session, kg, timestampMillis, original?.createdTimestamp)
    }

    /**
     * Deletes the manual weight at [timestampMillis] and returns it so it can be restored.
     */
    @JvmStatic
    fun deleteRecord(session: DaoSession, timestampMillis: Long): ManualWeightSample? {
        val manual = ManualWeightSampleProvider(session)
        return manual.get(timestampMillis)?.also { manual.delete(timestampMillis) }
    }

    @JvmStatic
    fun restoreRecord(session: DaoSession, sample: ManualWeightSample) {
        sample.modifiedTimestamp = System.currentTimeMillis()
        session.manualWeightSampleDao.insertOrReplace(sample)
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
