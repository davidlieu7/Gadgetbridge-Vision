/*  Copyright (C) 2026 Vitaliy Tomin

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Transport ({@link SapFrame}) and session ({@link SapPacket},
 * {@link SapProtocol}) layer tests.
 */
public class SapTransportTest {

    /** Band-originated packet types: same bits as the phone's, originator bit clear. */
    private static final int BAND_SINGLE = 0x00;
    private static final int BAND_FIRST = 0x02;
    private static final int BAND_CONT = 0x04;

    private static byte[] bandSingle(int seq, int channel, byte[] body) {
        byte[] p = new byte[4 + body.length];
        p[0] = (byte) BAND_SINGLE;
        p[1] = (byte) seq;
        p[2] = (byte) channel;
        p[3] = (byte) channel;
        System.arraycopy(body, 0, p, 4, body.length);
        return p;
    }

    // ---- SapFrame ----

    @Test
    public void frameRoundTrip() {
        byte[] payload = new byte[]{0x01, 0x40, 0x0b, 0x0b, 0x02};
        byte[] frame = SapFrame.wrap(payload);
        assertEquals(payload.length + 6, frame.length);
        assertEquals(payload.length, ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF));
        assertArrayEquals(payload, SapFrame.unwrap(frame));
    }

    @Test
    public void frameRoundTripEmptyPayload() {
        byte[] frame = SapFrame.wrap(new byte[0]);
        assertEquals(6, frame.length);
        assertArrayEquals(new byte[0], SapFrame.unwrap(frame));
    }

    @Test
    public void unwrapRejectsCorruptPayload() {
        byte[] frame = SapFrame.wrap(new byte[]{0x01, 0x40, 0x0b, 0x0b, 0x02});
        frame[5] ^= 0x40;   // flip a payload bit: DATA_CRC no longer matches
        assertNull(SapFrame.unwrap(frame));
    }

    @Test
    public void unwrapRejectsCorruptHeader() {
        byte[] frame = SapFrame.wrap(new byte[]{0x01, 0x40, 0x0b, 0x0b, 0x02});
        frame[2] ^= 0x01;   // HDR_CRC no longer matches LEN
        assertNull(SapFrame.unwrap(frame));
    }

    @Test
    public void unwrapRejectsLengthMismatchAndRunts() {
        byte[] frame = SapFrame.wrap(new byte[]{0x01, 0x40});
        assertNull(SapFrame.unwrap(Arrays.copyOf(frame, frame.length - 1)));
        assertNull(SapFrame.unwrap(new byte[]{0x00, 0x02, 0x00}));
        assertNull(SapFrame.unwrap(new byte[0]));
        assertNull(SapFrame.unwrap(null));
    }

    @Test
    public void helloFramesAreNotSapFrames() {
        assertTrue(SapFrame.isHello(new byte[]{0x68, 0x00, 0x01}));
        assertTrue(!SapFrame.isHello(SapFrame.wrap(new byte[]{0x01, 0x40, 0x0b, 0x0b})));
    }

    // ---- SapPacket ----

    @Test
    public void packetSingleRoundTrip() {
        byte[] body = new byte[]{0x42, 0x06, 0x00, 0x05, 0x29};
        SapPacket p = SapPacket.parse(SapPacket.encodeSingle(0x0b, 0x40, body));
        assertEquals(SapPacket.Kind.SINGLE, p.kind);
        assertEquals(0x40, p.seq);
        assertEquals(0x0b, p.channel);
        assertTrue(p.originatorIsPhone());
        assertArrayEquals(body, p.body);
    }

    @Test
    public void packetFragmentRoundTrip() {
        byte[] head = new byte[]{0x01, 0x02, 0x03};
        byte[] tail = new byte[]{0x04, 0x05};

        SapPacket first = SapPacket.parse(SapPacket.encodeFirstFragment(0x07, 0x50, head));
        assertEquals(SapPacket.Kind.FIRST_FRAGMENT, first.kind);
        assertEquals(0x07, first.channel);
        assertArrayEquals(head, first.body);

        SapPacket cont = SapPacket.parse(SapPacket.encodeContinuation(0x51, tail));
        assertEquals(SapPacket.Kind.CONTINUATION, cont.kind);
        assertEquals(0x51, cont.seq);
        assertEquals(-1, cont.channel);
        assertArrayEquals(tail, cont.body);
    }

    @Test
    public void packetAckRoundTrip() {
        SapPacket single = SapPacket.parse(SapPacket.encodeAck(0x40, false));
        assertEquals(SapPacket.Kind.ACK, single.kind);
        assertEquals(0x40, single.seq);
        assertEquals(SapPacket.ACK_FLAG_SINGLE, single.ackFlag);

        SapPacket chunk = SapPacket.parse(SapPacket.encodeAck(0x51, true));
        assertEquals(SapPacket.ACK_FLAG_CHUNK, chunk.ackFlag);
    }

    @Test
    public void packetParseNeverThrowsOnRunts() {
        assertEquals(SapPacket.Kind.UNKNOWN, SapPacket.parse(null).kind);
        assertEquals(SapPacket.Kind.UNKNOWN, SapPacket.parse(new byte[]{0x01}).kind);
        // Single/first-fragment without the channel bytes.
        assertEquals(SapPacket.Kind.UNKNOWN, SapPacket.parse(new byte[]{0x01, 0x40, 0x0b}).kind);
    }

    // ---- SapProtocol: outgoing ----

    @Test
    public void smallMessageIsSentAsOneFrame() {
        SapProtocol sap = new SapProtocol(new CollectingListener());
        List<byte[]> frames = sap.buildFrames(0x0b, new byte[]{0x02}, 512);
        assertEquals(1, frames.size());
        SapPacket p = SapPacket.parse(SapFrame.unwrap(frames.get(0)));
        assertEquals(SapPacket.Kind.SINGLE, p.kind);
        assertEquals(0x0b, p.channel);
    }

    @Test
    public void largeMessageIsFragmentedAndReassemblesToTheOriginal() {
        final int mtu = 64;
        final int maxFrame = mtu - 3;
        byte[] body = new byte[300];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }

        SapProtocol sap = new SapProtocol(new CollectingListener());
        List<byte[]> frames = sap.buildFrames(0x07, body, mtu);
        assertTrue("expected several frames, got " + frames.size(), frames.size() > 2);

        SapReassembler reassembler = new SapReassembler();
        int firstSeq = -1;
        for (int i = 0; i < frames.size(); i++) {
            byte[] frame = frames.get(i);
            assertTrue("frame " + i + " is " + frame.length + " > " + maxFrame, frame.length <= maxFrame);
            SapPacket p = SapPacket.parse(SapFrame.unwrap(frame));
            if (i == 0) {
                assertEquals(SapPacket.Kind.FIRST_FRAGMENT, p.kind);
                assertEquals(0x07, p.channel);
                firstSeq = p.seq;
            } else {
                assertEquals(SapPacket.Kind.CONTINUATION, p.kind);
                // Every continuation of a batch shares the chunk's SEQ | 0x01.
                assertEquals(firstSeq | 0x01, p.seq);
            }
            reassembler.offer(p);
        }
        SapReassembler.Message m = reassembler.flush();
        assertNotNull(m);
        assertEquals(0x07, m.channel);
        assertArrayEquals(body, m.body);
    }

    @Test
    public void sequenceNumberAdvancesAndSkipsZero() {
        SapProtocol sap = new SapProtocol(new CollectingListener());
        List<Integer> seqs = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            seqs.add(SapPacket.parse(SapFrame.unwrap(sap.buildFrames(0x0b, new byte[]{0x02}, 512).get(0))).seq);
        }
        assertEquals(Integer.valueOf(0x40), seqs.get(0));
        assertEquals(Integer.valueOf(0x50), seqs.get(1));
        for (int seq : seqs) {
            assertTrue("SEQ 0x00 must never be used", seq != 0x00);
        }
    }

    @Test
    public void tooSmallMtuIsRejectedRatherThanProducingGarbage() {
        SapProtocol sap = new SapProtocol(new CollectingListener());
        try {
            sap.buildFrames(0x07, new byte[64], 12);
            fail("expected an exception for an MTU that cannot carry a fragment");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- SapProtocol: incoming ----

    @Test
    public void receivedSingleIsDispatchedAndAcked() {
        CollectingListener listener = new CollectingListener();
        SapProtocol sap = new SapProtocol(listener);

        byte[] body = new byte[]{0x42, 0x05, 0x29};
        byte[] ack = sap.onFrameReceived(SapFrame.wrap(bandSingle(0x31, 0x0b, body)));

        assertEquals(1, listener.channels.size());
        assertEquals(Integer.valueOf(0x0b), listener.channels.get(0));
        assertArrayEquals(body, listener.bodies.get(0));

        SapPacket ackPacket = SapPacket.parse(SapFrame.unwrap(ack));
        assertEquals(SapPacket.Kind.ACK, ackPacket.kind);
        assertEquals(0x31, ackPacket.seq);
        assertEquals(SapPacket.ACK_FLAG_SINGLE, ackPacket.ackFlag);
    }

    @Test
    public void receivedFragmentsAreAckedPerChunkAndDeliveredOnce() {
        CollectingListener listener = new CollectingListener();
        SapProtocol sap = new SapProtocol(listener);

        byte[] firstPayload = new byte[]{(byte) BAND_FIRST, 0x30, 0x0a, 0x0a, 0x01, 0x02};
        byte[] contPayload = new byte[]{(byte) BAND_CONT, 0x31, 0x03, 0x04};

        SapPacket ack1 = SapPacket.parse(SapFrame.unwrap(sap.onFrameReceived(SapFrame.wrap(firstPayload))));
        assertEquals(SapPacket.ACK_FLAG_CHUNK, ack1.ackFlag);
        sap.onFrameReceived(SapFrame.wrap(contPayload));
        assertTrue("nothing is delivered until the message is known complete", listener.bodies.isEmpty());

        // The next message starting is what completes the fragmented one.
        sap.onFrameReceived(SapFrame.wrap(bandSingle(0x32, 0x0b, new byte[]{0x42})));
        assertEquals(2, listener.bodies.size());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, listener.bodies.get(0));
        assertArrayEquals(new byte[]{0x42}, listener.bodies.get(1));
    }

    @Test
    public void receivedAckIsSwallowed() {
        CollectingListener listener = new CollectingListener();
        SapProtocol sap = new SapProtocol(listener);
        assertNull(sap.onFrameReceived(SapFrame.wrap(SapPacket.encodeAck(0x40, false))));
        assertTrue(listener.bodies.isEmpty());
    }

    @Test
    public void corruptFrameIsDroppedWithoutAck() {
        CollectingListener listener = new CollectingListener();
        SapProtocol sap = new SapProtocol(listener);
        byte[] frame = SapFrame.wrap(bandSingle(0x31, 0x0b, new byte[]{0x42}));
        frame[frame.length - 1] ^= 0xFF;
        assertNull(sap.onFrameReceived(frame));
        assertTrue(listener.bodies.isEmpty());
    }

    @Test
    public void helloFrameGoesToTheHelloCallback() {
        CollectingListener listener = new CollectingListener();
        SapProtocol sap = new SapProtocol(listener);
        byte[] hello = new byte[]{0x68, 0x00, 0x01, 0x02};
        assertNull(sap.onFrameReceived(hello));
        assertEquals(1, listener.hellos.size());
        assertTrue(listener.bodies.isEmpty());
    }

    private static final class CollectingListener implements SapProtocol.Listener {
        final List<Integer> channels = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final List<byte[]> hellos = new ArrayList<>();

        @Override
        public void onMessage(int channel, byte[] body) {
            channels.add(channel);
            bodies.add(body);
        }

        @Override
        public void onHello(byte[] frame) {
            hellos.add(frame);
        }
    }
}
