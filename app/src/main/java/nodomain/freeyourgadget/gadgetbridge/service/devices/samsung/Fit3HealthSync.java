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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

/**
 * The health/fitness channel's ({@code CH_HEALTH}, 0x0a) bulk-transfer
 * chunking, layered above ordinary SAP messages and distinct from generic SAP
 * fragmentation ({@link SapProtocol#buildFrames}). It is the same big-data
 * scheme the notification channel uses ({@link Fit3BigData}), just with the
 * version fixed at 1. Only used band-to-phone
 * here - Gadgetbridge doesn't build outgoing health payloads yet, so there's
 * no corresponding chunk-building side.
 *
 * <pre>
 *   [ 0x3f ][ op:1 ][ idx:4 LE ][ payload ]
 *              |        |
 *              |        +-- chunk index, 0,1,2,... (resets to 0 per object)
 *              +----------- (version &lt;&lt; 3) | flag: flag 0 first, 1 middle, 2 last
 *                           (0x08 / 0x09 / 0x0a with version 1)
 * </pre>
 *
 * <p>The whole object is acked once, after its last chunk, not chunk by
 * chunk: SAP header {@code 0x3e} (MSG_ID 62), body
 * {@code [0x3e][(version << 3) | result][n:4 LE]}, where result 1 = ok and
 * 0 = failed. As receiver the phone puts the chunk count in {@code n}; the
 * band, receiving, puts the last chunk index there instead. A direction may
 * carry several objects back-to-back, each restarting {@code idx} at 0 on its
 * own first chunk.
 */
public final class Fit3HealthSync {

    private static final Logger LOG = LoggerFactory.getLogger(Fit3HealthSync.class);

    private static final int MARKER = 0x3f;
    private static final int CHUNK_HEADER_LEN = 6;
    public static final int OP_FIRST = 0x08;
    public static final int OP_MIDDLE = 0x09;
    public static final int OP_LAST = 0x0a;
    private static final int FLAG_FIRST = 0;
    private static final int FLAG_LAST = 2;
    private static final int ACK_HEADER = 0x3e;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /** Index the next chunk must carry, or -1 when no object is in progress. */
    private int expectedIdx = -1;

    /** The object-level ack to send, set when an object ends; see {@link #takeAck()}. */
    private byte[] pendingAck;

    /** A parsed chunk header - see the class docs for the wire layout. */
    public static final class Chunk {
        public final int op;
        public final int idx;
        public final byte[] payload;

        private Chunk(int op, int idx, byte[] payload) {
            this.op = op;
            this.idx = idx;
            this.payload = payload;
        }

        public int version() {
            return op >> 3;
        }

        /** 0 first, 1 middle, 2 last. */
        public int flag() {
            return op & 0x07;
        }
    }

    /** @return the parsed chunk, or {@code null} if this isn't a bulk-transfer chunk. */
    public static Chunk parseChunk(byte[] body) {
        if (body.length < CHUNK_HEADER_LEN || (body[0] & 0xFF) != MARKER) {
            return null;
        }
        int op = body[1] & 0xFF;
        int idx = (body[2] & 0xFF) | ((body[3] & 0xFF) << 8) | ((body[4] & 0xFF) << 16) | ((body[5] & 0xFF) << 24);
        byte[] payload = new byte[body.length - CHUNK_HEADER_LEN];
        System.arraycopy(body, CHUNK_HEADER_LEN, payload, 0, payload.length);
        return new Chunk(op, idx, payload);
    }

    /**
     * Feed one chunk into the reassembly buffer.
     *
     * <p>Chunk indices are checked against the sequence: a first chunk starts
     * a new object at its own index, and every following chunk must carry the
     * next index. A middle/last chunk that arrives out of order (or with no
     * object in progress at all) can only produce a silently corrupt blob, so
     * the partial object is dropped instead, and its last chunk is answered
     * with a failure ack so the band can re-send it.
     *
     * @return the fully reassembled object once the last chunk arrives,
     *     otherwise {@code null} (more chunks still expected, or the object
     *     was discarded).
     */
    public byte[] onChunk(Chunk chunk) {
        if (chunk.flag() == FLAG_FIRST) {
            buffer.reset();
        } else if (expectedIdx < 0 || chunk.idx != expectedIdx) {
            LOG.warn("Samsung Fit 3: out-of-order health chunk (op 0x{}, idx {}, expected {}), dropping partial object",
                    Integer.toHexString(chunk.op), chunk.idx, expectedIdx);
            buffer.reset();
            expectedIdx = -1;
            if (chunk.flag() == FLAG_LAST) {
                pendingAck = buildAck(chunk.version(), false, chunk.idx + 1);
            }
            return null;
        }
        buffer.write(chunk.payload, 0, chunk.payload.length);
        expectedIdx = chunk.idx + 1;
        if (chunk.flag() == FLAG_LAST) {
            byte[] result = buffer.toByteArray();
            buffer.reset();
            expectedIdx = -1;
            pendingAck = buildAck(chunk.version(), true, chunk.idx + 1);
            return result;
        }
        return null;
    }

    /**
     * @return the object-level ack owed for the chunk just fed to
     *     {@link #onChunk}, or {@code null} while the object is still incomplete.
     *     Each ack is returned once.
     */
    public byte[] takeAck() {
        byte[] ack = pendingAck;
        pendingAck = null;
        return ack;
    }

    /** Build the object-level ack: {@code [0x3e][(version << 3) | result][n:4 LE]}. */
    public static byte[] buildAck(int version, boolean ok, int n) {
        return new byte[]{
                (byte) ACK_HEADER,
                (byte) ((version << 3) | (ok ? 1 : 0)),
                (byte) n,
                (byte) (n >> 8),
                (byte) (n >> 16),
                (byte) (n >> 24),
        };
    }
}
