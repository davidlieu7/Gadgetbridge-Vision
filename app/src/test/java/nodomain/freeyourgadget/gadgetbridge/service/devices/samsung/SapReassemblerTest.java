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

import org.junit.Test;

/**
 * {@link SapReassembler}: the "complete on next message" default and the
 * opt-in {@link SapReassembler.LengthEstimator} path used by the log-file
 * pull, where every packet is a first fragment plus one continuation.
 */
public class SapReassemblerTest {

    private static final int CH_A = 0x0a;
    private static final int CH_DEBUG = SapProtocol.CH_DEBUG;

    private static SapPacket single(int channel, byte[] body) {
        return SapPacket.parse(SapPacket.encodeSingle(channel, 0x10, body));
    }

    private static SapPacket first(int channel, byte[] body) {
        return SapPacket.parse(SapPacket.encodeFirstFragment(channel, 0x20, body));
    }

    private static SapPacket cont(byte[] body) {
        return SapPacket.parse(SapPacket.encodeContinuation(0x21, body));
    }

    /** Completes a CH_DEBUG body once 4 bytes have arrived; -1 for anything else. */
    private static SapReassembler.LengthEstimator fourByteDebugBodies() {
        return new SapReassembler.LengthEstimator() {
            @Override
            public int expectedLength(int channel, byte[] soFar) {
                return channel == CH_DEBUG ? 4 : -1;
            }
        };
    }

    @Test
    public void singleIsDeliveredImmediately() {
        SapReassembler r = new SapReassembler();
        SapReassembler.Message m = r.offer(single(CH_A, new byte[]{0x01, 0x02}));
        assertNotNull(m);
        assertEquals(CH_A, m.channel);
        assertArrayEquals(new byte[]{0x01, 0x02}, m.body);
        assertNull(r.takeStashed());
    }

    @Test
    public void withoutEstimatorFragmentsCompleteOnTheNextMessage() {
        SapReassembler r = new SapReassembler();
        assertNull(r.offer(first(CH_A, new byte[]{0x01, 0x02})));
        assertNull(r.offer(cont(new byte[]{0x03})));

        SapReassembler.Message flushed = r.offer(single(CH_A, new byte[]{(byte) 0xAA}));
        assertNotNull(flushed);
        assertArrayEquals("the older fragmented message is delivered first",
                new byte[]{0x01, 0x02, 0x03}, flushed.body);

        SapReassembler.Message stashed = r.takeStashed();
        assertNotNull(stashed);
        assertArrayEquals(new byte[]{(byte) 0xAA}, stashed.body);
        assertNull("stash is cleared once taken", r.takeStashed());
    }

    @Test
    public void withEstimatorFragmentCompletesAsSoonAsItsLengthIsReached() {
        SapReassembler r = new SapReassembler();
        r.setLengthEstimator(fourByteDebugBodies());

        assertNull(r.offer(first(CH_DEBUG, new byte[]{0x01, 0x02})));
        SapReassembler.Message m = r.offer(cont(new byte[]{0x03, 0x04}));
        assertNotNull("no need to wait for the next packet", m);
        assertEquals(CH_DEBUG, m.channel);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, m.body);
        assertNull(r.takeStashed());
        assertNull("nothing left pending", r.flush());
    }

    @Test
    public void aShortMessageCanCompleteInsideItsFirstFragment() {
        SapReassembler r = new SapReassembler();
        r.setLengthEstimator(fourByteDebugBodies());
        SapReassembler.Message m = r.offer(first(CH_DEBUG, new byte[]{0x01, 0x02, 0x03, 0x04}));
        assertNotNull(m);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, m.body);
    }

    @Test
    public void interleavedSingleDoesNotTruncateAKnownIncompleteFragment() {
        SapReassembler r = new SapReassembler();
        r.setLengthEstimator(fourByteDebugBodies());

        assertNull(r.offer(first(CH_DEBUG, new byte[]{0x01, 0x02})));

        // Something on another channel lands between the two fragments.
        SapReassembler.Message m = r.offer(single(CH_A, new byte[]{(byte) 0xBB}));
        assertNotNull(m);
        assertEquals(CH_A, m.channel);
        assertNull("the half-assembled debug packet must not be flushed", r.takeStashed());

        SapReassembler.Message done = r.offer(cont(new byte[]{0x03, 0x04}));
        assertNotNull(done);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, done.body);
    }

    @Test
    public void estimatorThatCannotDecideFallsBackToTheDefaultRule() {
        SapReassembler r = new SapReassembler();
        r.setLengthEstimator(new SapReassembler.LengthEstimator() {
            @Override
            public int expectedLength(int channel, byte[] soFar) {
                return -1;   // never knows
            }
        });

        assertNull(r.offer(first(CH_DEBUG, new byte[]{0x01})));
        assertNull(r.offer(cont(new byte[]{0x02})));
        SapReassembler.Message flushed = r.offer(single(CH_A, new byte[]{(byte) 0xCC}));
        assertNotNull(flushed);
        assertArrayEquals(new byte[]{0x01, 0x02}, flushed.body);
        assertNotNull(r.takeStashed());
    }

    @Test
    public void backToBackFragmentedMessagesEachCompleteOnce() {
        SapReassembler r = new SapReassembler();
        r.setLengthEstimator(fourByteDebugBodies());

        for (int i = 0; i < 3; i++) {
            assertNull(r.offer(first(CH_DEBUG, new byte[]{(byte) i, (byte) i})));
            SapReassembler.Message m = r.offer(cont(new byte[]{(byte) i, (byte) i}));
            assertNotNull("message " + i + " should complete on its continuation", m);
            assertEquals(4, m.body.length);
        }
    }

    @Test
    public void orphanContinuationIsIgnored() {
        SapReassembler r = new SapReassembler();
        assertNull(r.offer(cont(new byte[]{0x01, 0x02})));
        assertNull(r.flush());
    }
}
