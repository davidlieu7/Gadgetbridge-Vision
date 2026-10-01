package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

internal data class NxWearPacket(
    val raw: ByteArray,
    val kind: Kind,
    val deviceType: Int? = null,
    val pageCount: Int? = null,
    val sequence: Int? = null,
    val command: Int? = null,
    val dataType: Int? = null,
    val declaredLength: Int? = null,
    val continuationIndex: Int? = null,
    val pagesPresent: Int = 0,
    val payload: ByteArray = byteArrayOf(),
) {
    enum class Kind {
        FRAMED,
        CONTINUATION,
        RAW,
    }

    val isComplete: Boolean
        get() = kind == Kind.FRAMED && payload.size == declaredLength && pagesPresent == pageCount?.plus(1)

    companion object {
        private const val PAGE_SIZE = 20
        private const val FIRST_PAGE_HEADER_SIZE = 10
        private const val CONTINUATION_DATA_SIZE = PAGE_SIZE - 1

        fun frame(sequence: Int, command: Int, dataType: Int, payload: ByteArray, deviceType: Int = 0x0b): ByteArray {
            require(sequence in 0..0xff)
            require(command in 0..0xff)
            require(dataType in 0..0xff)
            require(deviceType in 0..0xff)
            require(payload.size <= 0xffff)

            val pageCount = ((payload.size - (PAGE_SIZE - FIRST_PAGE_HEADER_SIZE)).coerceAtLeast(0) +
                CONTINUATION_DATA_SIZE - 1) / CONTINUATION_DATA_SIZE
            require(pageCount <= 0xff)
            val result = ByteArray(PAGE_SIZE * (pageCount + 1))
            result[1] = deviceType.toByte()
            result[2] = pageCount.toByte()
            result[3] = sequence.toByte()
            result[4] = command.toByte()
            result[5] = dataType.toByte()
            result[8] = payload.size.toByte()
            result[9] = (payload.size shr 8).toByte()

            var copied = minOf(payload.size, PAGE_SIZE - FIRST_PAGE_HEADER_SIZE)
            payload.copyInto(result, FIRST_PAGE_HEADER_SIZE, 0, copied)
            for (pageIndex in 1..pageCount) {
                val pageOffset = pageIndex * PAGE_SIZE
                result[pageOffset] = pageIndex.toByte()
                val next = minOf(payload.size, copied + CONTINUATION_DATA_SIZE)
                payload.copyInto(result, pageOffset + 1, copied, next)
                copied = next
            }
            return result
        }

        fun parse(bytes: ByteArray): NxWearPacket {
            val raw = bytes.copyOf()
            if (bytes.isNotEmpty() && bytes.size % PAGE_SIZE == 0 && bytes[0] != 0.toByte()) {
                val firstIndex = bytes[0].toInt() and 0xff
                val pagesPresent = bytes.size / PAGE_SIZE
                if (firstIndex + pagesPresent - 1 > 0xff) return NxWearPacket(raw, Kind.RAW)
                val payload = ByteArray(pagesPresent * CONTINUATION_DATA_SIZE)
                for (pageOffset in 0 until pagesPresent) {
                    if ((bytes[pageOffset * PAGE_SIZE].toInt() and 0xff) != firstIndex + pageOffset) {
                        return NxWearPacket(raw, Kind.RAW)
                    }
                    bytes.copyInto(
                        payload,
                        pageOffset * CONTINUATION_DATA_SIZE,
                        pageOffset * PAGE_SIZE + 1,
                        (pageOffset + 1) * PAGE_SIZE,
                    )
                }
                return NxWearPacket(
                    raw = raw,
                    kind = Kind.CONTINUATION,
                    continuationIndex = firstIndex,
                    pagesPresent = pagesPresent,
                    payload = payload,
                )
            }
            if (bytes.size < FIRST_PAGE_HEADER_SIZE || bytes[0] != 0.toByte() || bytes.size % PAGE_SIZE != 0) {
                return NxWearPacket(raw, Kind.RAW)
            }

            val pageCount = bytes[2].toInt() and 0xff
            val pagesPresent = bytes.size / PAGE_SIZE
            if (pagesPresent > pageCount + 1) return NxWearPacket(raw, Kind.RAW)
            for (pageIndex in 1 until pagesPresent) {
                if ((bytes[pageIndex * PAGE_SIZE].toInt() and 0xff) != pageIndex) {
                    return NxWearPacket(raw, Kind.RAW)
                }
            }

            val declaredLength = bytes.le16(8)
            val capacity = (PAGE_SIZE - FIRST_PAGE_HEADER_SIZE) + pageCount * CONTINUATION_DATA_SIZE
            if (declaredLength > capacity) return NxWearPacket(raw, Kind.RAW)
            val payload = ByteArray(minOf(declaredLength, PAGE_SIZE - FIRST_PAGE_HEADER_SIZE +
                (pagesPresent - 1) * CONTINUATION_DATA_SIZE))
            var copied = minOf(payload.size, PAGE_SIZE - FIRST_PAGE_HEADER_SIZE)
            bytes.copyInto(payload, 0, FIRST_PAGE_HEADER_SIZE, FIRST_PAGE_HEADER_SIZE + copied)
            for (pageIndex in 1 until pagesPresent) {
                val next = minOf(payload.size, copied + CONTINUATION_DATA_SIZE)
                bytes.copyInto(payload, copied, pageIndex * PAGE_SIZE + 1, pageIndex * PAGE_SIZE + 1 + next - copied)
                copied = next
            }

            return NxWearPacket(
                raw = raw,
                kind = Kind.FRAMED,
                deviceType = bytes[1].toInt() and 0xff,
                pageCount = pageCount,
                sequence = bytes[3].toInt() and 0xff,
                command = bytes[4].toInt() and 0xff,
                dataType = bytes[5].toInt() and 0xff,
                declaredLength = declaredLength,
                pagesPresent = pagesPresent,
                payload = payload,
            )
        }
    }
}

internal fun ByteArray.le16(offset: Int): Int {
    require(offset >= 0 && offset <= size - 2) { "le16 offset $offset is outside byte array of size $size" }
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}
