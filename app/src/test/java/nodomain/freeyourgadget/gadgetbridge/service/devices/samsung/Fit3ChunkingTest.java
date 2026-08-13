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

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * The two channel-specific chunking schemes: {@link Fit3BigData}
 * (CH_NOTIFICATION) and {@link Fit3HealthSync} (CH_HEALTH). Both ack a
 * transfer once, after its last chunk.
 */
public class Fit3ChunkingTest {

    private static byte[] ramp(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    // ---- Fit3BigData ----

    @Test
    public void bigDataLeavesSmallPayloadsAlone() {
        Fit3BigData bigData = new Fit3BigData();
        byte[] data = ramp(100);
        List<byte[]> chunks = bigData.chunk(data, 980);
        assertEquals(1, chunks.size());
        assertArrayEquals("under the cap the payload is sent as-is", data, chunks.get(0));
    }

    @Test
    public void bigDataSplitsAndTagsEachChunk() {
        Fit3BigData bigData = new Fit3BigData();
        final int cap = 26;                 // 20 data bytes per chunk
        byte[] data = ramp(50);
        List<byte[]> chunks = bigData.chunk(data, cap);
        assertEquals(3, chunks.size());

        ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
        int[] expectedFlags = {0, 1, 2};    // start, middle, end
        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunk = chunks.get(i);
            assertTrue(chunk.length <= cap);
            assertEquals(0x3f, chunk[0] & 0xFF);
            assertEquals("flag of chunk " + i, expectedFlags[i], chunk[1] & 0x07);
            assertEquals("version of chunk " + i, 0, (chunk[1] & 0xFF) >> 3);
            assertEquals("index of chunk " + i, i,
                    (chunk[2] & 0xFF) | ((chunk[3] & 0xFF) << 8)
                            | ((chunk[4] & 0xFF) << 16) | ((chunk[5] & 0xFF) << 24));
            reassembled.write(chunk, 6, chunk.length - 6);
        }
        assertArrayEquals(data, reassembled.toByteArray());
    }

    @Test
    public void bigDataVersionAdvancesPerTransferAndWrapsAt32() {
        Fit3BigData bigData = new Fit3BigData();
        byte[] data = ramp(50);
        for (int i = 0; i < 33; i++) {
            int version = (bigData.chunk(data, 26).get(0)[1] & 0xFF) >> 3;
            assertEquals("transfer " + i, i % 32, version);
        }
    }

    @Test
    public void bigDataAckDecodesVersionAndResult() {
        Fit3BigData.Ack ok = Fit3BigData.parseAck(new byte[]{0x3e, (byte) ((5 << 3) | 0x01)});
        assertNotNull(ok);
        assertEquals(5, ok.version);
        assertTrue(ok.success);

        Fit3BigData.Ack nack = Fit3BigData.parseAck(new byte[]{0x3e, (byte) (5 << 3)});
        assertNotNull(nack);
        assertEquals(5, nack.version);
        assertTrue(!nack.success);

        assertNull(Fit3BigData.parseAck(new byte[]{0x3e}));
    }

    // ---- Fit3HealthSync ----

    private static byte[] healthChunk(int op, int idx, byte[] payload) {
        byte[] body = new byte[6 + payload.length];
        body[0] = 0x3f;
        body[1] = (byte) op;
        body[2] = (byte) idx;
        body[3] = (byte) (idx >> 8);
        body[4] = (byte) (idx >> 16);
        body[5] = (byte) (idx >> 24);
        System.arraycopy(payload, 0, body, 6, payload.length);
        return body;
    }

    @Test
    public void healthChunkHeaderIsParsed() {
        Fit3HealthSync.Chunk chunk = Fit3HealthSync.parseChunk(
                healthChunk(Fit3HealthSync.OP_MIDDLE, 0x0102, new byte[]{0x07, 0x00}));
        assertNotNull(chunk);
        assertEquals(Fit3HealthSync.OP_MIDDLE, chunk.op);
        assertEquals(0x0102, chunk.idx);
        assertArrayEquals(new byte[]{0x07, 0x00}, chunk.payload);
    }

    @Test
    public void nonChunkBodiesAreRejected() {
        assertNull(Fit3HealthSync.parseChunk(new byte[]{0x02, 0x08, 0x00, 0x00, 0x00, 0x00}));
        assertNull("too short to hold a header", Fit3HealthSync.parseChunk(new byte[]{0x3f, 0x08}));
    }

    @Test
    public void healthAckCarriesVersionResultAndCount() {
        assertArrayEquals(new byte[]{0x3e, 0x09, 0x02, 0x01, 0x00, 0x00},
                Fit3HealthSync.buildAck(1, true, 0x0102));
        assertArrayEquals(new byte[]{0x3e, 0x08, 0x05, 0x00, 0x00, 0x00},
                Fit3HealthSync.buildAck(1, false, 5));
    }

    @Test
    public void healthChunksReassembleInOrder() {
        Fit3HealthSync sync = new Fit3HealthSync();
        assertNull(sync.onChunk(chunk(Fit3HealthSync.OP_FIRST, 0, new byte[]{0x01})));
        assertNull(sync.onChunk(chunk(Fit3HealthSync.OP_MIDDLE, 1, new byte[]{0x02})));
        byte[] object = sync.onChunk(chunk(Fit3HealthSync.OP_LAST, 2, new byte[]{0x03}));
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, object);
    }

    @Test
    public void healthObjectIsAckedOnceWithItsChunkCount() {
        Fit3HealthSync sync = new Fit3HealthSync();
        sync.onChunk(chunk(Fit3HealthSync.OP_FIRST, 0, new byte[]{0x01}));
        assertNull("no ack before the last chunk", sync.takeAck());
        sync.onChunk(chunk(Fit3HealthSync.OP_MIDDLE, 1, new byte[]{0x02}));
        assertNull(sync.takeAck());
        sync.onChunk(chunk(Fit3HealthSync.OP_LAST, 2, new byte[]{0x03}));
        // Same shape as the vendor phone's ack in captures: 3e 09 <count>.
        assertArrayEquals(new byte[]{0x3e, 0x09, 0x03, 0x00, 0x00, 0x00}, sync.takeAck());
        assertNull("handed out once", sync.takeAck());
    }

    @Test
    public void flagBitsDecideFirstAndLastWhateverTheVersion() {
        Fit3HealthSync sync = new Fit3HealthSync();
        sync.onChunk(chunk((2 << 3) | 0, 0, new byte[]{0x01}));
        byte[] object = sync.onChunk(chunk((2 << 3) | 2, 1, new byte[]{0x02}));
        assertArrayEquals(new byte[]{0x01, 0x02}, object);
        assertArrayEquals(new byte[]{0x3e, 0x11, 0x02, 0x00, 0x00, 0x00}, sync.takeAck());
    }

    @Test
    public void aNewFirstChunkStartsAFreshObject() {
        Fit3HealthSync sync = new Fit3HealthSync();
        sync.onChunk(chunk(Fit3HealthSync.OP_FIRST, 0, new byte[]{(byte) 0xAA}));
        // Second object begins before the first one ever ended.
        sync.onChunk(chunk(Fit3HealthSync.OP_FIRST, 0, new byte[]{0x01}));
        byte[] object = sync.onChunk(chunk(Fit3HealthSync.OP_LAST, 1, new byte[]{0x02}));
        assertArrayEquals("the abandoned object's bytes must not leak in",
                new byte[]{0x01, 0x02}, object);
    }

    @Test
    public void outOfOrderChunkDiscardsThePartialObject() {
        Fit3HealthSync sync = new Fit3HealthSync();
        sync.onChunk(chunk(Fit3HealthSync.OP_FIRST, 0, new byte[]{0x01}));
        assertNull(sync.onChunk(chunk(Fit3HealthSync.OP_MIDDLE, 5, new byte[]{(byte) 0xFF})));
        // The gap makes the object unrecoverable, so its last chunk yields nothing
        // and is answered with a failure ack.
        assertNull(sync.onChunk(chunk(Fit3HealthSync.OP_LAST, 6, new byte[]{(byte) 0xFF})));
        assertArrayEquals(new byte[]{0x3e, 0x08, 0x07, 0x00, 0x00, 0x00}, sync.takeAck());

        // ...and the next object still parses cleanly.
        sync.onChunk(chunk(Fit3HealthSync.OP_FIRST, 0, new byte[]{0x0a}));
        assertArrayEquals(new byte[]{0x0a, 0x0b},
                sync.onChunk(chunk(Fit3HealthSync.OP_LAST, 1, new byte[]{0x0b})));
    }

    @Test
    public void orphanChunkWithoutAFirstIsIgnored() {
        Fit3HealthSync sync = new Fit3HealthSync();
        assertNull(sync.onChunk(chunk(Fit3HealthSync.OP_MIDDLE, 1, new byte[]{(byte) 0xFF})));
        assertNull(sync.onChunk(chunk(Fit3HealthSync.OP_LAST, 2, new byte[]{(byte) 0xFF})));
    }

    private static Fit3HealthSync.Chunk chunk(int op, int idx, byte[] payload) {
        return Fit3HealthSync.parseChunk(healthChunk(op, idx, payload));
    }
}
