package nodomain.freeyourgadget.gadgetbridge.service.devices.vring;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.devices.vring.VRingR26Constants;

public class VRingR26PacketTest {
    @Test
    public void encodeAndDecodeRoundTrip() {
        final VRingR26Packet packet = new VRingR26Packet(
                VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_HISTORY_STEPS,
                new byte[]{(byte) 0xff});

        final byte[] encoded = packet.encode();
        assertArrayEquals(new byte[]{
                (byte) 0xfd, (byte) 0xda, 0x10, 0x07,
                0x02, 0x0d, (byte) 0xff
        }, encoded);

        final VRingR26Packet decoded = VRingR26Packet.decode(encoded);
        assertNotNull(decoded);
        assertEquals(VRingR26Constants.CAT_QUERY, decoded.getCategory());
        assertEquals(VRingR26Constants.CMD_QUERY_HISTORY_STEPS, decoded.getCommand());
        assertArrayEquals(new byte[]{(byte) 0xff}, decoded.getPayload());
    }

    @Test
    public void decodeRejectsInvalidOrTruncatedFrames() {
        assertNull(VRingR26Packet.decode(null));
        assertNull(VRingR26Packet.decode(new byte[]{(byte) 0xfd, (byte) 0xda}));
        assertNull(VRingR26Packet.decode(new byte[]{
                0x00, (byte) 0xda, 0x10, 0x06, 0x02, 0x00
        }));
        assertNull(VRingR26Packet.decode(new byte[]{
                (byte) 0xfd, (byte) 0xda, 0x10, 0x08, 0x02, 0x00
        }));
    }

    @Test
    public void setTimeUsesLittleEndianAdjustedEpoch() {
        final TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            final long epochSeconds = 1_700_000_000L;
            final byte[] payload = VRingR26Packet.setTime(epochSeconds).getPayload();

            assertEquals(5, payload.length);
            assertEquals(epochSeconds - 8L * 3600L,
                    Integer.toUnsignedLong(ByteBuffer.wrap(payload, 0, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .getInt()));
            assertEquals(0x08, payload[4] & 0xff);
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
