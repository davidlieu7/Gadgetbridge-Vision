package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

internal data class NxWearSportReading(
    val timestampMillis: Long,
    val steps: Int,
    val distanceMeters: Int,
    val caloriesMilliKcal: Int,
    val durationSeconds: Int,
) {
    companion object {
        fun parse(packet: NxWearPacket): List<NxWearSportReading>? {
            if (!packet.isComplete || packet.command != 0x31 || packet.dataType !in setOf(NxWearDataType.ACTIVITY_04, NxWearDataType.ACTIVITY_05)) return null
            val payload = packet.payload
            if (payload.size < 3 || payload.le16(0) < (payload[2].toInt() and 0xff) || payload.size != 3 + (payload[2].toInt() and 0xff) * 20) return null
            return (3 until payload.size step 20).mapNotNull { offset ->
                val timestamp = payload.le32(offset)
                val steps = payload.le32(offset + 4)
                val distance = payload.le32(offset + 8)
                val calories = payload.le32(offset + 12)
                val duration = payload.le32(offset + 16)
                if (timestamp == 0L || steps > Int.MAX_VALUE || distance > Int.MAX_VALUE ||
                    calories > Int.MAX_VALUE || duration > Int.MAX_VALUE) null
                else NxWearSportReading(timestamp * 1000L, steps.toInt(), distance.toInt(), calories.toInt(), duration.toInt())
            }
        }
    }
}
