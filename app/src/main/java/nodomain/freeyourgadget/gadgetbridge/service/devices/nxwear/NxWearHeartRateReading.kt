package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample
import java.io.Serializable

internal data class NxWearHeartRateReading(val timestampMillis: Long, val bpm: Int) : HeartRateSample, Serializable {
    override fun getTimestamp(): Long = timestampMillis

    override fun getHeartRate(): Int = bpm

    companion object {
        fun parse(packet: NxWearPacket): List<NxWearHeartRateReading>? {
            if (packet.dataType !in setOf(NxWearDataType.HEART_RATE, NxWearDataType.HISTORY_HEART_RATE,
                    NxWearDataType.EXERCISE_HEART_RATE)) return null
            return packet.timedReadings(requireNotNull(packet.dataType))
                ?.map { NxWearHeartRateReading(it.timestampMillis, it.value) }
        }
    }
}
