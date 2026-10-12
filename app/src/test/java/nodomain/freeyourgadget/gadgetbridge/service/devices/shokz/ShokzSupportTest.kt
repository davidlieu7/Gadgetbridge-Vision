package nodomain.freeyourgadget.gadgetbridge.service.devices.shokz

import android.bluetooth.BluetoothAdapter
import nodomain.freeyourgadget.gadgetbridge.service.ResponseWait
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType
import nodomain.freeyourgadget.gadgetbridge.service.btbr.BtBRQueue
import nodomain.freeyourgadget.gadgetbridge.service.btbr.Transaction
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.service.btbr.actions.WriteAction
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import nodomain.freeyourgadget.gadgetbridge.util.CheckSums
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.util.ReflectionHelpers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.BiPredicate

class ShokzSupportTest : TestBase() {
    private lateinit var support: ShokzSupport
    private lateinit var matcher: BiPredicate<ByteArray, ByteArray>

    @Before
    fun setUpSupport() {
        support = ShokzSupport()
        support.setContext(createDummyGDevice("00:00:00:00:00:32"), BluetoothAdapter.getDefaultAdapter(), context)
        val transaction = initializeTransaction()
        matcher = requireNotNull(transaction.responseMatcher)
    }

    @After
    fun disposeSupport() {
        support.dispose()
    }

    @Test
    fun testEncodeCommand() {
        assertEquals(
            "a55a2c000100000028005ab701000000200000000200000004000000020000000300000004000000020000000400000000000000",
            ShokzSupport.encodeCommand(ShokzCommand.FIRMWARE_GET).toHexString()
        )
    }

    @Test
    fun initializationUsesPacedWritesInOriginalOrder() {
        val transaction = initializeTransaction()
        assertEquals(2000L, transaction.messageTimeout)
        val expected = listOf(
            ShokzCommand.FIRMWARE_GET,
            ShokzCommand.MEDIA_SOURCE_GET,
            ShokzCommand.BATTERY_GET,
            ShokzCommand.EQUALIZER_GET,
            ShokzCommand.PLAYBACK_STATUS_GET,
            ShokzCommand.VOLUME_GET,
            ShokzCommand.MP3_PLAYBACK_MODE_GET,
            ShokzCommand.CONTROLS_GET,
            ShokzCommand.LANGUAGE_GET
        )
        val writes = transaction.actions.filterIsInstance<WriteAction>()
        assertEquals(expected.size, writes.size)
        expected.zip(writes).forEach { (command, write) ->
            assertArrayEquals(ShokzSupport.encodeCommand(command), write.value)
        }
    }

    @Test
    fun initializationRetainsOpenRunCapabilityGates() {
        support.dispose()
        support = ShokzSupport()
        val device = GBDevice("00:00:00:00:00:33", "OpenRun Pro 2", null, null, DeviceType.SHOKZ_OPENRUN_PRO_2)
        support.setContext(device, BluetoothAdapter.getDefaultAdapter(), context)
        val transaction = initializeTransaction()
        val expected = listOf(
            ShokzCommand.FIRMWARE_GET, ShokzCommand.BATTERY_GET, ShokzCommand.EQUALIZER_GET,
            ShokzCommand.PLAYBACK_STATUS_GET, ShokzCommand.VOLUME_GET, ShokzCommand.LANGUAGE_GET
        )
        val writes = transaction.actions.filterIsInstance<WriteAction>()
        assertEquals(expected.size, writes.size)
        expected.zip(writes).forEach { (command, write) ->
            assertArrayEquals(ShokzSupport.encodeCommand(command), write.value)
        }
    }

    @Test
    fun regularCommandUsesSharedPacingAndKeepsArguments() {
        val args = byteArrayOf(1, 0, 0, 0)
        val transaction = queuedCommand(ShokzCommand.LANGUAGE_SET, args)
        assertEquals(2000L, transaction.messageTimeout)
        assertNotNull(transaction.responseMatcher)
        assertArrayEquals(
            ShokzSupport.encodeCommand(ShokzCommand.LANGUAGE_SET, args),
            (transaction.actions.single() as WriteAction).value
        )
    }

    @Test
    fun startPairingDoesNotWaitForAck() {
        assertEquals(0L, queuedCommand(ShokzCommand.MULTIPOINT_START_PAIR_REQ).messageTimeout)
    }

    @Test
    fun finishPairingDoesNotWaitForAck() {
        assertEquals(0L, queuedCommand(ShokzCommand.MULTIPOINT_PAIR_SECOND_FINISH).messageTimeout)
    }

    @Test
    fun matchingAckReleasesWriteAfterSupportCallback() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY)
        val response = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY_ACK)
        val wait = ResponseWait()
        val pending = wait.arm(request, matcher)
        support.onSocketRead(response)
        wait.onResponse(pending, response)
        assertTrue(wait.await(pending, 0))
    }

    @Test
    fun fragmentedAckWaitsForCompleteFrame() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY)
        val response = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY_ACK)
        for (fragment in response.dropLast(1)) {
            val read = byteArrayOf(fragment)
            support.onSocketRead(read)
            assertFalse(matcher.test(request, read))
        }
        val last = response.takeLast(1).toByteArray()
        support.onSocketRead(last)
        assertTrue(matcher.test(request, last))
    }

    @Test
    fun coalescedUnrelatedFrameDoesNotHideMatchingAck() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY)
        val response = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY_ACK) +
            ShokzSupport.encodeCommand(ShokzCommand.LANGUAGE_ACK)
        support.onSocketRead(response)
        assertTrue(matcher.test(request, response))
    }

    @Test
    fun sameResponseCodeInAnotherGroupDoesNotReleaseWrite() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY)
        val response = ShokzSupport.encodeCommand(ShokzCommand.BATTERY_RET)
        support.onSocketRead(response)
        assertFalse(matcher.test(request, response))
    }

    @Test
    fun unrelatedNotificationDoesNotReleaseWrite() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.VOLUME_GET)
        val response = ShokzSupport.encodeCommand(ShokzCommand.VOLUME_NOTIFY, byteArrayOf(8))
        support.onSocketRead(response)
        assertFalse(matcher.test(request, response))
    }

    @Test
    fun corruptAckDoesNotReleaseWrite() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY)
        val response = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY_ACK)
        response[10] = (response[10].toInt() xor 1).toByte()
        support.onSocketRead(response)
        assertFalse(matcher.test(request, response))
    }

    @Test
    fun invalidPayloadHeaderDoesNotReleaseWrite() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY)
        val response = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY_ACK)
        response[12] = 2
        updateCrc(response)
        support.onSocketRead(response)
        assertFalse(matcher.test(request, response))
    }

    @Test
    fun invalidArgsLengthDoesNotReleaseWrite() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY)
        val response = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY_ACK)
        ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).putInt(48, 1)
        updateCrc(response)
        support.onSocketRead(response)
        assertFalse(matcher.test(request, response))
    }

    @Test
    fun previousReadAckDoesNotMatchNewRead() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY)
        val response = ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY_ACK)
        support.onSocketRead(response)
        assertTrue(matcher.test(request, response))
        val partial = response.copyOfRange(0, 10)
        support.onSocketRead(partial)
        assertFalse(matcher.test(request, partial))
    }

    @Test
    fun knownMultipointCrcQuirkStillReleasesWrite() {
        val request = ShokzSupport.encodeCommand(ShokzCommand.MULTIPOINT_DEVICES_GET)
        val response = ShokzSupport.encodeCommand(ShokzCommand.MULTIPOINT_DEVICES_RET, byteArrayOf(0, 0, 0, 0))
        response[10] = (response[10].toInt() xor 1).toByte()
        support.onSocketRead(response)
        assertTrue(matcher.test(request, response))
    }

    @Test
    fun missingAckTimesOutAndNextRequestCanProceed() {
        val wait = ResponseWait()
        val first = wait.arm(ShokzSupport.encodeCommand(ShokzCommand.BUTTON_PLAY), matcher)
        assertFalse(wait.await(first, 1))
        assertNull(wait.pending)
        val second = wait.arm(ShokzSupport.encodeCommand(ShokzCommand.LANGUAGE_SET), matcher)
        val response = ShokzSupport.encodeCommand(ShokzCommand.LANGUAGE_ACK)
        support.onSocketRead(response)
        wait.onResponse(second, response)
        assertTrue(wait.await(second, 0))
    }

    @Test
    fun unconfiguredTransactionRetainsZeroTimeout() {
        val transaction = support.createTransactionBuilder("unpaced").transaction
        assertEquals(0L, transaction.messageTimeout)
        assertNull(transaction.responseMatcher)
    }

    private fun initializeTransaction(): Transaction {
        val builder = support.createTransactionBuilder("initialize")
        val method = ShokzSupport::class.java.getDeclaredMethod("initializeDevice", TransactionBuilder::class.java)
        method.isAccessible = true
        return (method.invoke(support, builder) as TransactionBuilder).transaction
    }

    private fun queuedCommand(command: ShokzCommand, args: ByteArray = byteArrayOf()): Transaction {
        val queue = mock(BtBRQueue::class.java)
        ReflectionHelpers.setField(support, "mQueue", queue)
        val method = ShokzSupport::class.java.getDeclaredMethod("queueCommand", ShokzCommand::class.java, ByteArray::class.java)
        method.isAccessible = true
        method.invoke(support, command, args)
        val captor = ArgumentCaptor.forClass(Transaction::class.java)
        verify(queue).add(captor.capture())
        return captor.value
    }

    private fun updateCrc(packet: ByteArray) {
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(
            10, CheckSums.crc16_maxim(packet, 12, packet.size - 12).toShort()
        )
    }
}
