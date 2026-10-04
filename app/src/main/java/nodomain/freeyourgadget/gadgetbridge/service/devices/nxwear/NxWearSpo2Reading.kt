package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

internal object NxWearSpo2Reading {
    fun parse(packet: NxWearPacket): List<NxWearPacket.TimedReading>? {
        if (packet.dataType !in setOf(NxWearDataType.SPO2, NxWearDataType.HISTORY_SPO2)) return null
        val readings = packet.timedReadings(requireNotNull(packet.dataType)) ?: return null
        if (packet.dataType == NxWearDataType.HISTORY_SPO2 &&
            (packet.payload.le16(0) != readings.size || (packet.payload[2].toInt() and 0xff) != readings.size)) return null
        return readings
    }
}
