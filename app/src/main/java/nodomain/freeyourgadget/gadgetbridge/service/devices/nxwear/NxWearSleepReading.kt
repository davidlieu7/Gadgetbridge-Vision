package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind

/** Sleep transition records from the B8's paged 0x06 history stream. */
internal data class NxWearSleepReading(val timestampMillis: Long, val durationMinutes: Int, val kind: ActivityKind) {
    companion object {
        fun parse(packet: NxWearPacket): List<NxWearSleepReading>? {
            if (!packet.isComplete || packet.command != 0x31 || packet.dataType != NxWearDataType.SLEEP) return null
            val payload = packet.payload
            if (payload.size < 4) return null
            val count = payload[3].toInt() and 0xff
            if (count < 2 || count > (payload.size - 4) / 5) return null
            val records = (0 until count).map { index ->
                val offset = 4 + index * 5
                (payload[offset].toInt() and 0xff) to payload.le32(offset + 1)
            }
            if (records.first().first != 1 || records.last().first != 4 ||
                records.any { it.first !in 1..5 } ||
                records.zipWithNext().any { (start, end) -> end.second < start.second }) return null
            return records.zipWithNext().mapNotNull { (start, end) ->
                val duration = end.second - start.second
                if (duration == 0L || start.first == 4) return@mapNotNull null
                if (duration % 60L != 0L || duration / 60L > Int.MAX_VALUE) return null
                val kind = when (start.first) {
                    // The captured initial interval is light sleep (including it reproduces 192 min).
                    1, 3 -> ActivityKind.LIGHT_SLEEP
                    2 -> ActivityKind.DEEP_SLEEP
                    5 -> ActivityKind.REM_SLEEP
                    else -> return null
                }
                NxWearSleepReading(start.second * 1000L, (duration / 60L).toInt(), kind)
            }
        }
    }
}
