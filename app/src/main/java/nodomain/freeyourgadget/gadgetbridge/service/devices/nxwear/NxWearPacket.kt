package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import java.nio.ByteBuffer

/** Parsed view of an Nx Wear packet. Unrecognized bytes are retained verbatim. */
internal data class NxWearPacket(
    val raw: ByteArray,
    val kind: Kind,
    val notificationId: Int? = null,
    val command: Int? = null,
    val subtype: Int? = null,
    val payload: ByteArray = byteArrayOf(),
) {
    enum class Kind {
        FRAMED,
        CONTINUATION,
        RAW,
    }

    companion object {
        private val MAGIC = byteArrayOf(0x00, 0x0b)

        fun frame(notificationId: Int, command: Int, subtype: Int, payload: ByteArray): ByteArray {
            require(notificationId in 0..0xffff)
            require(command in 0..0xff)
            require(subtype in 0..0xff)
            return MAGIC + notificationId.be16() + byteArrayOf(command.toByte(), subtype.toByte()) + payload
        }

        fun parse(bytes: ByteArray): NxWearPacket {
            if (bytes.size >= 6 && bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1]) {
                return NxWearPacket(
                    raw = bytes.copyOf(),
                    kind = Kind.FRAMED,
                    notificationId = bytes.be16(2),
                    command = bytes[4].toInt() and 0xff,
                    subtype = bytes[5].toInt() and 0xff,
                    payload = bytes.copyOfRange(6, bytes.size),
                )
            }

            // Activity continuations were observed with leading bytes 01..04; their layout is unknown.
            if (bytes.size == 20 && (bytes[0].toInt() and 0xff) in 0x01..0x04) {
                return NxWearPacket(bytes.copyOf(), Kind.CONTINUATION)
            }
            return NxWearPacket(bytes.copyOf(), Kind.RAW)
        }
    }
}

/** Serialize an unsigned big-endian 16-bit value. */
internal fun Int.be16(): ByteArray {
    require(this in 0..0xffff) { "be16 value $this is outside unsigned 16-bit range" }
    return ByteBuffer.allocate(2).putShort(toShort()).array()
}

/** Read an unsigned big-endian 16-bit value. */
internal fun ByteArray.be16(offset: Int): Int {
    require(offset >= 0 && offset <= size - 2) { "be16 offset $offset is outside byte array of size $size" }
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

/** Read an unsigned big-endian 32-bit value, represented in a Long. */
internal fun ByteArray.be32(offset: Int): Long {
    require(offset >= 0 && offset <= size - 4) { "be32 offset $offset is outside byte array of size $size" }
    return ((this[offset].toLong() and 0xff) shl 24) or
        ((this[offset + 1].toLong() and 0xff) shl 16) or
        ((this[offset + 2].toLong() and 0xff) shl 8) or
        (this[offset + 3].toLong() and 0xff)
}
