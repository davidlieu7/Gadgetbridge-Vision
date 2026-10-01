package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample
import java.io.Serializable

internal data class NxWearHeartRateReading(val timestampMillis: Long, val bpm: Int) : HeartRateSample, Serializable {
    override fun getTimestamp(): Long = timestampMillis

    override fun getHeartRate(): Int = bpm

    companion object {
        fun parse(packet: NxWearPacket): List<NxWearHeartRateReading>? {
            return packet.timedReadings(NxWearDataType.HEART_RATE)
                ?.map { NxWearHeartRateReading(it.timestampMillis, it.value) }
        }
    }
}
