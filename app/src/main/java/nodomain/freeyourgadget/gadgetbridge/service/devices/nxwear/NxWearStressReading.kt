package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

internal data class NxWearStressReading(val timestampMillis: Long, val stress: Int) {
    companion object {
        fun parse(packet: NxWearPacket): List<NxWearStressReading>? {
            return packet.timedReadings(NxWearDataType.STRESS)
                ?.map { NxWearStressReading(it.timestampMillis, it.value) }
        }
    }
}
