package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class EarFunPacketTest {
    @Test
    public void testEncode() {
        EarFunPacket.Command command = EarFunPacket.Command.SET_AMBIENT_SOUND;
        byte[] payload = {0x01, 0x02, 0x03};
        EarFunPacket packet = new EarFunPacket(command, payload);
        byte[] encoded = packet.encode();
        assertEquals((byte) 0xff, encoded[0]);
        assertEquals(EarFunPacket.DEFAULT_VERSION, encoded[1]);
        assertEquals(0x00, encoded[2]);
        assertEquals(payload.length, encoded[3]);
        assertEquals(EarFunPacket.VENDOR_ID_V1_V2, ByteBuffer.wrap(encoded, 4, 2).getShort());
        assertEquals(command.commandId, ByteBuffer.wrap(encoded, 6, 2).getShort());
        byte[] decodedPayload = Arrays.copyOfRange(encoded, 8, encoded.length);
        assertArrayEquals(payload, decodedPayload);
    }

    @Test
    public void testDecode() {
        byte[] payload = {0x01, 0x02, 0x03};
        ByteBuffer buffer = ByteBuffer.allocate(11)
                .put((byte) 0xff)
                .put(EarFunPacket.DEFAULT_VERSION)
                .put((byte) 0x00)
                .put((byte) payload.length)
                .putShort(EarFunPacket.VENDOR_ID_V3)
                .putShort(EarFunPacket.Command.BASIC_GET_SUPPORTED_FEATURES.commandId)
                .put(payload);
        buffer.flip();

        EarFunPacket packet = EarFunPacket.decode(buffer);

        assertNotNull(packet);
        assertEquals(EarFunPacket.Command.BASIC_GET_SUPPORTED_FEATURES, packet.getCommand());
        assertArrayEquals(payload, packet.getPayload());
    }

    @Test
    public void testDecodeInvalidPacket() {
        ByteBuffer buffer = ByteBuffer.allocate(2)
                .put((byte) 0xff)
                .put(EarFunPacket.DEFAULT_VERSION);
        buffer.flip();

        EarFunPacket packet = EarFunPacket.decode(buffer);
        assertNull(packet);
    }

    @Test
    public void testGetCommandById() {
        EarFunPacket.Command command = EarFunPacket.Command.getCommandById((short) 0x0001);

        assertNotNull(command);
        assertEquals(EarFunPacket.Command.BASIC_GET_SUPPORTED_FEATURES, command);
    }

    @Test
    public void testInvalidCommandId() {
        EarFunPacket.Command command = EarFunPacket.Command.getCommandById((short) 0x1234);
        assertNull(command);
    }

    @Test
    public void testEncodeExtendedLength() {
        // payloads above 254 bytes carry the length on two bytes, which makes the header 9 bytes long
        byte[] payload = new byte[300];
        Arrays.fill(payload, (byte) 0x42);

        byte[] encoded = new EarFunPacket(EarFunPacket.Command.SET_EQUALIZER_BAND, payload).encode();

        assertEquals(9 + payload.length, encoded.length);
        assertEquals((byte) 0xff, encoded[0]);
        assertEquals((byte) 0x02, encoded[2]);
        assertEquals(payload.length, ByteBuffer.wrap(encoded, 3, 2).getShort());
        assertEquals(EarFunPacket.VENDOR_ID_V3, ByteBuffer.wrap(encoded, 5, 2).getShort());
        assertEquals(EarFunPacket.Command.SET_EQUALIZER_BAND.commandId, ByteBuffer.wrap(encoded, 7, 2).getShort());
    }

    @Test
    public void testDecodeExtendedLength() {
        byte[] payload = new byte[300];
        Arrays.fill(payload, (byte) 0x42);
        byte[] encoded = new EarFunPacket(EarFunPacket.Command.SET_EQUALIZER_BAND, payload).encode();

        EarFunPacket packet = EarFunPacket.decode(ByteBuffer.wrap(encoded));

        assertNotNull(packet);
        assertEquals(EarFunPacket.Command.SET_EQUALIZER_BAND, packet.getCommand());
        assertArrayEquals(payload, packet.getPayload());
    }

    @Test
    public void testExtendedLengthRoundTripAtBoundary() {
        // 254 bytes still fit in a one byte length, 255 does not
        for (int size : new int[]{254, 255}) {
            byte[] payload = new byte[size];
            byte[] encoded = new EarFunPacket(EarFunPacket.Command.SET_DEVICENAME, payload).encode();
            assertEquals(size > 254 ? 9 + size : 8 + size, encoded.length);

            EarFunPacket packet = EarFunPacket.decode(ByteBuffer.wrap(encoded));
            assertNotNull("sizing " + size, packet);
            assertEquals(size, packet.getPayload().length);
        }
    }

    @Test
    public void testDecodeTruncatedPayload() {
        // the header announces 10 payload bytes but only 3 were received: this used to throw a
        // BufferUnderflowException, which lost every packet following it as well
        byte[] frame = {(byte) 0xff, EarFunPacket.DEFAULT_VERSION, 0x00, 0x0A,
                (byte) 0x00, (byte) 0x0A, 0x03, 0x06, 0x00, 0x64, 0x11};

        EarFunPacket packet = EarFunPacket.decode(ByteBuffer.wrap(frame));

        assertNull(packet);
    }

    @Test
    public void testDecodeResynchronisesOnNextPacket() {
        // one garbage byte, then a valid frame: the valid frame must still be found
        byte[] garbage = {0x12};
        byte[] valid = new EarFunPacket(EarFunPacket.Command.REQUEST_RESPONSE_BATTERY_STATE_LEFT,
                new byte[]{0x00, 0x64}).encode();
        byte[] data = new byte[garbage.length + valid.length];
        System.arraycopy(garbage, 0, data, 0, garbage.length);
        System.arraycopy(valid, 0, data, garbage.length, valid.length);

        ByteBuffer buf = ByteBuffer.wrap(data);
        EarFunPacket packet = null;
        while (buf.hasRemaining() && packet == null) {
            packet = EarFunPacket.decode(buf);
        }

        assertNotNull("the packet after the garbage byte was lost", packet);
        assertEquals(EarFunPacket.Command.REQUEST_RESPONSE_BATTERY_STATE_LEFT, packet.getCommand());
        assertArrayEquals(new byte[]{0x00, 0x64}, packet.getPayload());
    }

    @Test
    public void testDecodeUnknownCommandDoesNotSwallowFollowingPacket() {
        // an unknown command is not decodable, but it must not hide the packet coming after it
        byte[] unknown = {(byte) 0xff, EarFunPacket.DEFAULT_VERSION, 0x00, 0x02,
                (byte) 0x00, (byte) 0x0A, 0x12, 0x34, (byte) 0xab, (byte) 0xcd};
        byte[] valid = new EarFunPacket(EarFunPacket.Command.REQUEST_RESPONSE_BATTERY_STATE_RIGHT,
                new byte[]{0x00, 0x64}).encode();
        byte[] data = new byte[unknown.length + valid.length];
        System.arraycopy(unknown, 0, data, 0, unknown.length);
        System.arraycopy(valid, 0, data, unknown.length, valid.length);

        ByteBuffer buf = ByteBuffer.wrap(data);
        EarFunPacket packet = null;
        while (buf.hasRemaining() && packet == null) {
            packet = EarFunPacket.decode(buf);
        }

        assertNotNull(packet);
        assertEquals(EarFunPacket.Command.REQUEST_RESPONSE_BATTERY_STATE_RIGHT, packet.getCommand());
    }
}