package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState

class NxWearPacketTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun mapsObservedBatteryStatusValues() {
        assertEquals(BatteryState.BATTERY_NORMAL, NxWearB8Support.batteryStateForStatus(0x00))
        assertEquals(BatteryState.BATTERY_CHARGING, NxWearB8Support.batteryStateForStatus(0x01))
        assertEquals(BatteryState.BATTERY_CHARGING_FULL, NxWearB8Support.batteryStateForStatus(0x02))
        assertEquals(BatteryState.UNKNOWN, NxWearB8Support.batteryStateForStatus(0xff))
    }

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
    fun recognizesLaterActivityContinuationIndexes() {
        for (index in 0x05..0x06) {
            val raw = ByteArray(20).apply { this[0] = index.toByte() }
            val packet = NxWearPacket.parse(raw)

            assertEquals(NxWearPacket.Kind.CONTINUATION, packet.kind)
            assertArrayEquals(raw, packet.raw)
        }
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
    fun buildsObservedInitializationBootstrapWithDynamicBigEndianTimestamp() {
        val captured = bytes("00010300016e000043004100070c0066bcb50b00010114aa32000c0068eb53be6a201c0000010800027c01ffff00000400670004006d0113006f401f030000881300002c0100007869302a0500780000")
        assertArrayEquals(captured, NxWearB8Support.encodeInitializationBootstrap(0x68eb53beL))
        assertArrayEquals(bytes("68eb53c0"), NxWearB8Support.encodeInitializationBootstrap(0x68eb53c0L).copyOfRange(28, 32))
    }

    @Test
    fun buildsObservedInitializationReadSequencePackets() {
        assertArrayEquals(bytes("000b000104090000010001000000000000000000"),
            NxWearB8Support.encodeInitializationRead(1, 0x09))
        assertArrayEquals(bytes("000b000204040000010001000000000000000000"),
            NxWearB8Support.encodeInitializationRead(2, 0x04))
        assertArrayEquals(bytes("000b000304fa0000010001000000000000000000"),
            NxWearB8Support.encodeInitializationRead(3, 0xfa))
    }

    @Test
    fun calculatesActivityFragmentCountsFromDeclaredDataLength() {
        assertEquals(5, NxWearB8Support.expectedActivityFragmentCount(70))
        assertEquals(2, NxWearB8Support.expectedActivityFragmentCount(23))
        assertEquals(4, NxWearB8Support.expectedActivityFragmentCount(59))
        assertEquals(7, NxWearB8Support.expectedActivityFragmentCount(115))
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
