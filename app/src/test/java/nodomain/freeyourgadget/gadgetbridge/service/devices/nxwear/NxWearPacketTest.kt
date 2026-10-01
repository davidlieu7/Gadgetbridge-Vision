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
    fun parsesFirstPageHeaderAndTrimsPadding() {
        val raw = bytes("000b00113103000002000e01000001000903ff00")
        val packet = NxWearPacket.parse(raw)

        assertEquals(NxWearPacket.Kind.FRAMED, packet.kind)
        assertEquals(0x0b, packet.deviceType)
        assertEquals(0, packet.pageCount)
        assertEquals(0x11, packet.sequence)
        assertEquals(0x31, packet.command)
        assertEquals(0x03, packet.dataType)
        assertEquals(2, packet.declaredLength)
        assertEquals(1, packet.pagesPresent)
        assertEquals(true, packet.isComplete)
        assertArrayEquals(bytes("0e01"), packet.payload)
        assertArrayEquals(raw, packet.raw)
    }

    @Test
    fun identifiesContinuationIndexAndData() {
        val raw = bytes("01050003418e005d574205000303010b00800100")
        val packet = NxWearPacket.parse(raw)

        assertEquals(NxWearPacket.Kind.CONTINUATION, packet.kind)
        assertEquals(1, packet.continuationIndex)
        assertNull(packet.sequence)
        assertNull(packet.command)
        assertNull(packet.dataType)
        assertArrayEquals(raw.copyOfRange(1, raw.size), packet.payload)
        assertArrayEquals(raw, packet.raw)
    }

    @Test
    fun recognizesLaterActivityContinuationIndexes() {
        for (index in 0x05..0x06) {
            val raw = ByteArray(20).apply { this[0] = index.toByte() }
            val packet = NxWearPacket.parse(raw)

            assertEquals(NxWearPacket.Kind.CONTINUATION, packet.kind)
            assertEquals(index, packet.continuationIndex)
            assertArrayEquals(raw, packet.raw)
        }
    }

    @Test
    fun parsesCoalescedContinuationPages() {
        val full = NxWearPacket.frame(7, 0x31, 0x09, ByteArray(70) { it.toByte() })
        val raw = full.copyOfRange(20, full.size)
        val packet = NxWearPacket.parse(raw)

        assertEquals(NxWearPacket.Kind.CONTINUATION, packet.kind)
        assertEquals(1, packet.continuationIndex)
        assertEquals(4, packet.pagesPresent)
        assertEquals(76, packet.payload.size)
        assertArrayEquals(full.copyOfRange(21, 40), packet.payload.copyOfRange(0, 19))

        raw[20] = 0x07.toByte()
        assertEquals(NxWearPacket.Kind.RAW, NxWearPacket.parse(raw).kind)
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
    fun readsUnsignedLittleEndianLength() {
        assertEquals(0x1234, bytes("3412").le16(0))
        assertEquals(0xff80, bytes("0080ff").le16(1))
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
        assertArrayEquals(bytes("000b000034800000010001000000000000000000"), NxWearPacket.frame(0, 0x34, 0x80, bytes("01")))
        assertArrayEquals(bytes("000b001104030000010001000000000000000000"), NxWearB8Support.encodeBatteryAck(0x0011))
    }

    @Test
    fun buildsObservedInitializationBootstrapWithDynamicLittleEndianTimestamp() {
        val captured = bytes("00010300016e000043004100070c0066bcb50b00010114aa32000c0068be53eb68201c0000010800027c01ffff00000400670004006d0113006f401f030000881300002c0100007869302a0500780000")
        assertArrayEquals(captured, NxWearB8Support.encodeInitializationBootstrap(0x68eb53beL))
        val parsed = NxWearPacket.parse(captured)
        assertEquals(0x01, parsed.deviceType)
        assertEquals(0x6e, parsed.dataType)
        assertEquals(67, parsed.declaredLength)
        assertEquals(true, parsed.isComplete)
        assertArrayEquals(bytes("be53eb68"), parsed.payload.copyOfRange(18, 22))
        val next = NxWearB8Support.encodeInitializationBootstrap(0x68eb53c0L)
        assertArrayEquals(bytes("68c053eb68"), next.copyOfRange(28, 33))
        assertEquals(0x01, next[20].toInt() and 0xff)
        assertEquals(0x02, next[40].toInt() and 0xff)
        assertEquals(0x03, next[60].toInt() and 0xff)
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
        assertEquals(1, NxWearB8Support.expectedActivityFragmentCount(0))
        assertEquals(1, NxWearB8Support.expectedActivityFragmentCount(10))
        assertEquals(2, NxWearB8Support.expectedActivityFragmentCount(11))
        assertEquals(2, NxWearB8Support.expectedActivityFragmentCount(29))
        assertEquals(3, NxWearB8Support.expectedActivityFragmentCount(30))
        assertEquals(5, NxWearB8Support.expectedActivityFragmentCount(70))
        assertEquals(2, NxWearB8Support.expectedActivityFragmentCount(23))
        assertEquals(4, NxWearB8Support.expectedActivityFragmentCount(59))
        assertEquals(7, NxWearB8Support.expectedActivityFragmentCount(115))
    }

    @Test
    fun genericFrameRoundTripsSequenceAndDeclaredPayload() {
        val raw = NxWearPacket.frame(0x34, 0xab, 0xcd, bytes("010203"))
        assertArrayEquals(bytes("000b0034abcd0000030001020300000000000000"), raw)
        val parsed = NxWearPacket.parse(raw)
        assertEquals(0x34, parsed.sequence)
        assertEquals(0xab, parsed.command)
        assertEquals(0xcd, parsed.dataType)
        assertEquals(3, parsed.declaredLength)
        assertArrayEquals(bytes("010203"), parsed.payload)
    }

    @Test
    fun parsesCoalescedPagesWithoutContinuationIndexes() {
        val payload = ByteArray(67) { it.toByte() }
        val raw = NxWearPacket.frame(0, 0x01, 0x6e, payload, deviceType = 0x01)
        val packet = NxWearPacket.parse(raw)

        assertEquals(80, raw.size)
        assertEquals(3, packet.pageCount)
        assertEquals(4, packet.pagesPresent)
        assertEquals(true, packet.isComplete)
        assertArrayEquals(payload, packet.payload)
        assertEquals(1, raw[20].toInt())
        assertEquals(2, raw[40].toInt())
        assertEquals(3, raw[60].toInt())
    }

    @Test
    fun recognizesIncompleteFirstPageAndRejectsOutOfOrderCoalescedPages() {
        val raw = NxWearPacket.frame(0, 0x31, 0x09, ByteArray(70))
        val firstPage = NxWearPacket.parse(raw.copyOfRange(0, 20))
        assertEquals(NxWearPacket.Kind.FRAMED, firstPage.kind)
        assertEquals(4, firstPage.pageCount)
        assertEquals(70, firstPage.declaredLength)
        assertEquals(10, firstPage.payload.size)
        assertEquals(false, firstPage.isComplete)

        raw[40] = 0x03.toByte()
        assertEquals(NxWearPacket.Kind.RAW, NxWearPacket.parse(raw).kind)
    }

    @Test
    fun rejectsDeclaredLengthBeyondAvailablePageCapacity() {
        val raw = NxWearPacket.frame(0, 0x31, 0x03, bytes("0e01"))
        raw[8] = 0x0b.toByte()

        assertEquals(NxWearPacket.Kind.RAW, NxWearPacket.parse(raw).kind)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfBoundsSequence() {
        NxWearPacket.frame(0x100, 0x01, 0x03, byteArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfBoundsLe16() {
        bytes("00").le16(0)
    }
}
