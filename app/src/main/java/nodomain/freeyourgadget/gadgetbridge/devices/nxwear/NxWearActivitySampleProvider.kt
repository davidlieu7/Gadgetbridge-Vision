package nodomain.freeyourgadget.gadgetbridge.devices.nxwear

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.generic_hr.GenericHeartRateActivitySampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample
import nodomain.freeyourgadget.gadgetbridge.entities.NxWearSportSample
import nodomain.freeyourgadget.gadgetbridge.entities.NxWearSportSampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import java.util.Calendar

class NxWearActivitySampleProvider(private val device: GBDevice, private val session: DaoSession) :
    GenericHeartRateActivitySampleProvider(device, session) {

    override fun getAllActivitySamples(timestampFrom: Int, timestampTo: Int): MutableList<GenericActivitySample> {
        val samples = super.getAllActivitySamples(timestampFrom, timestampTo)
        val dbDevice = DBHelper.findDevice(device, session) ?: return samples
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = timestampFrom.toLong() * 1000L
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val records = session.nxWearSportSampleDao.queryBuilder()
            .where(NxWearSportSampleDao.Properties.DeviceId.eq(dbDevice.id),
                NxWearSportSampleDao.Properties.Timestamp.ge(dayStart),
                NxWearSportSampleDao.Properties.Timestamp.le(timestampTo.toLong() * 1000L))
            .orderAsc(NxWearSportSampleDao.Properties.Timestamp)
            .list()
        var previous: NxWearSportSample? = null
        var previousDay = Long.MIN_VALUE
        for (record in records) {
            val calendar = Calendar.getInstance().apply { timeInMillis = record.timestamp }
            val day = calendar.get(Calendar.YEAR).toLong() * 1000L + calendar.get(Calendar.DAY_OF_YEAR)
            val baseline = previous?.takeIf { previousDay == day &&
                record.cumulativeSteps >= it.cumulativeSteps &&
                record.cumulativeDistanceMeters >= it.cumulativeDistanceMeters &&
                record.cumulativeCaloriesMilliKcal >= it.cumulativeCaloriesMilliKcal }
            val stepDelta = (record.cumulativeSteps - (baseline?.cumulativeSteps ?: 0)).coerceAtLeast(0)
            val distanceDelta = (record.cumulativeDistanceMeters - (baseline?.cumulativeDistanceMeters ?: 0)).coerceAtLeast(0)
            val calorieDelta = (record.cumulativeCaloriesMilliKcal - (baseline?.cumulativeCaloriesMilliKcal ?: 0)).coerceAtLeast(0)
            previous = record
            previousDay = day
            val timestamp = (record.timestamp / 1000L).toInt()
            if (timestamp !in timestampFrom..timestampTo) continue
            val sample = samples.firstOrNull { it.timestamp == timestamp } ?: GenericActivitySample().also {
                it.provider = this
                it.timestamp = timestamp
                samples.add(it)
            }
            sample.rawKind = ActivityKind.ACTIVITY.code
            sample.steps = stepDelta
            sample.distanceCm = (distanceDelta.toLong() * 100L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            sample.activeCalories = calorieDelta / 1000
        }
        val sleepProvider = GenericSleepStageSampleProvider(device, session)
        val stages = sleepProvider.getAllSamples(timestampFrom.toLong() * 1000L, timestampTo.toLong() * 1000L).toMutableList()
        sleepProvider.getLastSampleBefore(timestampFrom.toLong() * 1000L)?.let { stages.add(0, it) }
        val byTimestamp = samples.associateBy { it.timestamp }.toMutableMap()
        for (stage in stages) {
            val start = (stage.timestamp / 1000L).toInt()
            val end = start.toLong() + stage.duration * 60L
            var timestamp = maxOf(start.toLong(), timestampFrom.toLong())
            while (timestamp < end && timestamp <= timestampTo) {
                val sample = byTimestamp.getOrPut(timestamp.toInt()) {
                    GenericActivitySample().also {
                        it.provider = this
                        it.timestamp = timestamp.toInt()
                    }
                }
                sample.rawKind = stage.stage
                timestamp += 60
            }
            // Preserve heart-rate points with seconds offsets inside each sleep interval.
            for (sample in samples) {
                if (sample.timestamp.toLong() >= start && sample.timestamp.toLong() < end) sample.rawKind = stage.stage
            }
        }
        return byTimestamp.values.sortedBy { it.timestamp }.toMutableList()
    }

    override fun getLatestActivitySample(): GenericActivitySample? =
        getAllActivitySamples(0, (System.currentTimeMillis() / 1000L).toInt()).lastOrNull()

    override fun getLatestActivitySample(until: Int): GenericActivitySample? =
        getAllActivitySamples(0, until).lastOrNull()

    override fun getFirstActivitySample(): GenericActivitySample? =
        getAllActivitySamples(0, (System.currentTimeMillis() / 1000L).toInt()).firstOrNull()

    override fun getFirstActivitySample(after: Int): GenericActivitySample? =
        getAllActivitySamples(after, (System.currentTimeMillis() / 1000L).toInt()).firstOrNull()
}
