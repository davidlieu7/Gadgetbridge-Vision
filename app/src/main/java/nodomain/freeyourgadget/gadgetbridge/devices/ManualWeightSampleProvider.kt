package nodomain.freeyourgadget.gadgetbridge.devices

import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.ManualWeightSample
import nodomain.freeyourgadget.gadgetbridge.entities.ManualWeightSampleDao
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample

/**
 * The weights the user entered by hand. They belong to no device, so every device that supports
 * weight shows them, and removing a device keeps them.
 */
class ManualWeightSampleProvider(private val session: DaoSession) {
    private val dao: ManualWeightSampleDao get() = session.manualWeightSampleDao

    fun getAllSamples(timestampFrom: Long, timestampTo: Long): List<ManualWeightSample> =
        dao.queryBuilder()
            .where(ManualWeightSampleDao.Properties.Timestamp.between(timestampFrom, timestampTo))
            .orderAsc(ManualWeightSampleDao.Properties.Timestamp)
            .list()

    fun getLatestSample(until: Long): ManualWeightSample? =
        dao.queryBuilder()
            .where(ManualWeightSampleDao.Properties.Timestamp.le(until))
            .orderDesc(ManualWeightSampleDao.Properties.Timestamp)
            .limit(1)
            .list()
            .firstOrNull()

    fun getFirstSample(): ManualWeightSample? =
        dao.queryBuilder()
            .orderAsc(ManualWeightSampleDao.Properties.Timestamp)
            .limit(1)
            .list()
            .firstOrNull()

    fun getModifiedSince(modifiedAfter: Long, timestampTo: Long): List<ManualWeightSample> =
        dao.queryBuilder()
            .where(
                ManualWeightSampleDao.Properties.ModifiedTimestamp.gt(modifiedAfter),
                ManualWeightSampleDao.Properties.Timestamp.le(timestampTo),
            )
            .orderAsc(ManualWeightSampleDao.Properties.Timestamp)
            .list()

    fun get(timestamp: Long): ManualWeightSample? = dao.load(timestamp)

    fun addSample(sample: ManualWeightSample) {
        dao.insertOrReplace(sample)
    }

    fun delete(timestamp: Long) {
        dao.deleteByKey(timestamp)
    }
}

/**
 * The readings of a device together with the manual weights, ordered by time. [deviceProvider] is null
 * for a device that keeps no readings of its own.
 */
class WeightWithManualSampleProvider(
    private val deviceProvider: TimeSampleProvider<out WeightSample>?,
    private val manual: ManualWeightSampleProvider,
) : TimeSampleProvider<WeightSample> {
    override fun getAllSamples(timestampFrom: Long, timestampTo: Long): List<WeightSample> =
        (deviceProvider?.getAllSamples(timestampFrom, timestampTo).orEmpty() + manual.getAllSamples(timestampFrom, timestampTo))
            .sortedBy { it.timestamp }

    override fun getLatestSample(): WeightSample? =
        listOfNotNull(deviceProvider?.latestSample, manual.getLatestSample(Long.MAX_VALUE)).maxByOrNull { it.timestamp }

    override fun getLatestSample(until: Long): WeightSample? =
        listOfNotNull(deviceProvider?.getLatestSample(until), manual.getLatestSample(until)).maxByOrNull { it.timestamp }

    override fun getFirstSample(): WeightSample? =
        listOfNotNull(deviceProvider?.firstSample, manual.getFirstSample()).minByOrNull { it.timestamp }

    override fun addSample(timeSample: WeightSample) = throw UnsupportedOperationException("add to the device or manual provider")

    override fun addSamples(timeSamples: List<WeightSample>) = throw UnsupportedOperationException("add to the device or manual provider")

    override fun createSample(): WeightSample = throw UnsupportedOperationException("create with the device or manual provider")
}
