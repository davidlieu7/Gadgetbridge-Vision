package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NxWearPacketTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun parsesFramedPacketAndPreservesOpaquePayload() {
        val raw = bytes("000b00113103000002000e01000001000903ff00")
        val packet = NxWearPacket.parse(raw)

        assertEquals(NxWearPacket.Kind.FRAMED, packet.kind)
        assertEquals(0x0011, packet.notificationId)
        assertEquals(0x31, packet.command)
        assertEquals(0x03, packet.subtype)
        assertArrayEquals(raw.copyOfRange(6, raw.size), packet.payload)
        assertArrayEquals(raw, packet.raw)
    }

    @Test
    fun keepsContinuationsOpaque() {
        val raw = bytes("01050003418e005d574205000303010b00800100")
        val packet = NxWearPacket.parse(raw)

        assertEquals(NxWearPacket.Kind.CONTINUATION, packet.kind)
        assertNull(packet.notificationId)
        assertNull(packet.command)
        assertNull(packet.subtype)
        assertEquals(0, packet.payload.size)
        assertArrayEquals(raw, packet.raw)
    }

    @Test
    fun preservesMalformedAndUnrecognizedPackets() {
        val malformed = bytes("000b0011")
        val packet = NxWearPacket.parse(malformed)
        assertEquals(NxWearPacket.Kind.RAW, packet.kind)
        assertArrayEquals(malformed, packet.raw)

        val unknown = bytes("ff010203040506")
        assertArrayEquals(unknown, NxWearPacket.parse(unknown).raw)
    }

    @Test
    fun readsUnsignedBigEndianValues() {
        val raw = bytes("80ffabcdef01")
        assertEquals(0x80ff, raw.be16(0))
        assertEquals(0xabcdef01L, raw.be32(2))
        assertArrayEquals(bytes("80ff"), 0x80ff.be16())
    }

    @Test
    fun buildsObservedHealthDetectionPackets() {
        assertArrayEquals(bytes("000b000001800000080000003c00000000000000"),
            NxWearB8Support.encodeDetectionSettings(false, 60, false))
        assertArrayEquals(bytes("000b000001800000080001003c00000000000000"),
            NxWearB8Support.encodeDetectionSettings(true, 60, false))
        assertArrayEquals(bytes("000b000001800000080001003c01000000000000"),
            NxWearB8Support.encodeDetectionSettings(true, 60, true))
        assertArrayEquals(bytes("000b000001800000080000003c01000000000000"),
            NxWearB8Support.encodeDetectionSettings(false, 60, true))
        assertArrayEquals(bytes("000b000001800000080001001e01000000000000"),
            NxWearB8Support.encodeDetectionSettings(true, 30, true))
    }

    @Test
    fun buildsObservedClockPacketsWithLittleEndianTimeFields() {
        val epochMillis = 0x6abe37ecL * 1000L
        assertArrayEquals(bytes("000b0000016800000900ec37be6a201c00000000"),
            NxWearB8Support.encodeClockSettings(epochMillis, 7200, false))
        assertArrayEquals(bytes("000b0000016800000900ec37be6a201c00000100"),
            NxWearB8Support.encodeClockSettings(epochMillis, 7200, true))
    }

    @Test
    fun buildsObservedWristWakePackets() {
        assertArrayEquals(bytes("000b0000017f00000600000001173b0500000000"),
            NxWearB8Support.encodeWristWakeSettings(false, "00:01", "23:59", 5))
        assertArrayEquals(bytes("000b0000017f00000600010c2215370800000000"),
            NxWearB8Support.encodeWristWakeSettings(true, "12:34", "21:55", 8))
    }

    @Test
    fun buildsObservedRefreshAndAcknowledgementPackets() {
        assertArrayEquals(bytes("000b000003090000000000000000000000000000"), NxWearB8Support.encodeRefreshActivity())
        assertArrayEquals(bytes("000b000034800000010001000000000000000000"), NxWearPacket.frame(0, 0x34, 0x80, bytes("0000010001000000000000000000")))
        assertArrayEquals(bytes("000b001104030000010001000000000000000000"), NxWearB8Support.encodeBatteryAck(0x0011))
    }

    @Test
    fun genericFrameRoundTripsBigEndianIdAndOpaquePayload() {
        val raw = NxWearPacket.frame(0x1234, 0xab, 0xcd, bytes("010203"))
        assertArrayEquals(bytes("000b1234abcd010203"), raw)
        val parsed = NxWearPacket.parse(raw)
        assertEquals(0x1234, parsed.notificationId)
        assertEquals(0xab, parsed.command)
        assertEquals(0xcd, parsed.subtype)
        assertArrayEquals(bytes("010203"), parsed.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfBoundsBe16() {
        bytes("00").be16(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfBoundsBe32() {
        bytes("000000").be32(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfBoundsBe16Serialization() {
        0x10000.be16()
    }
}
