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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The notification channel's ({@code CH_NOTIFICATION}, 0x07) own big-payload
 * chunking, layered above ordinary SAP messages - distinct from both generic
 * SAP fragmentation ({@link SapProtocol#buildFrames}) and the channel-0x0a
 * bulk-transfer sub-protocol, despite all three sharing the {@code 0x3f} SAP
 * header byte. Only messages that don't fit
 * under the cap go through this at all; smaller ones are sent as a single
 * plain message.
 *
 * <pre>
 *   [ 0x3f ][ (version&lt;&lt;3)|flag ][ idx:4 LE ][ up to (cap-6) B of data ]
 *                 |
 *                 +-- top 5 bits: rolling version id (0-31, wraps);
 *                     bottom 3 bits: chunk flag - 0 start, 1 middle, 2 end
 * </pre>
 *
 * The whole transfer is acked once, after the last chunk, on
 * {@code LARGE_DATA_ACK_NACK} (SAP header {@code 0x3e}): {@code [hdr][byte]}
 * where the byte's top 5 bits echo {@code version} and the bottom 2 bits are
 * a success code.
 */
public final class Fit3BigData {

    private static final int MARKER = 0x3f;
    private static final int CHUNK_HEADER_LEN = 6;
    private static final int FLAG_START = 0;
    private static final int FLAG_MIDDLE = 1;
    private static final int FLAG_END = 2;

    /**
     * The real cap comes from the SA Accessory Framework's own per-peer max
     * message size (there's no Gadgetbridge equivalent to query it), which a
     * live capture showed to be 980 bytes - the same figure the weather
     * channel hardcodes as its own message buffer size, suggesting it's a
     * shared framework constant rather than something either channel picked
     * independently. Used as a fixed value here.
     */
    public static final int DEFAULT_CHUNK_CAP = 980;

    private int version = 0;
    private int lastVersion = -1;

    private int nextVersion() {
        int v = version;
        version = (version + 1) % 32;
        return v;
    }

    /**
     * Split {@code data} into one or more big-data chunks, or return it
     * unwrapped as the single element of a one-item list if it already fits
     * under {@code cap} - matching the source's own behaviour of only
     * engaging this scheme for oversized messages.
     */
    public List<byte[]> chunk(byte[] data, int cap) {
        if (data.length <= cap) {
            lastVersion = -1;
            return Collections.singletonList(data);
        }
        int ver = nextVersion();
        lastVersion = ver;
        int dataCap = cap - CHUNK_HEADER_LEN;
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        int idx = 0;
        while (offset < data.length) {
            int end = Math.min(offset + dataCap, data.length);
            boolean first = idx == 0;
            boolean last = end == data.length;
            int flag = first ? FLAG_START : (last ? FLAG_END : FLAG_MIDDLE);
            byte[] slice = Arrays.copyOfRange(data, offset, end);
            byte[] chunk = new byte[CHUNK_HEADER_LEN + slice.length];
            chunk[0] = (byte) MARKER;
            chunk[1] = (byte) ((ver << 3) | flag);
            chunk[2] = (byte) idx;
            chunk[3] = (byte) (idx >> 8);
            chunk[4] = (byte) (idx >> 16);
            chunk[5] = (byte) (idx >> 24);
            System.arraycopy(slice, 0, chunk, CHUNK_HEADER_LEN, slice.length);
            chunks.add(chunk);
            offset = end;
            idx++;
        }
        return chunks;
    }

    /**
     * The version the last {@link #chunk} call tagged its chunks with - what
     * the band's {@link Ack} for that transfer will echo - or -1 if that
     * payload was small enough to go out unchunked (and gets no such ack).
     */
    public int lastVersion() {
        return lastVersion;
    }

    /** Parsed {@code LARGE_DATA_ACK_NACK} (MSG_ID 0x3e) body. */
    public static final class Ack {
        public final int version;
        public final boolean success;

        private Ack(int version, boolean success) {
            this.version = version;
            this.success = success;
        }

        @Override
        public String toString() {
            return "BigDataAck{version=" + version + ", success=" + success + "}";
        }
    }

    /** @return the parsed ack, or {@code null} if the body is too short to hold one. */
    public static Ack parseAck(byte[] body) {
        if (body.length < 2) {
            return null;
        }
        int b = body[1] & 0xFF;
        int version = (b & 0xF8) >> 3;
        boolean success = (b & 0x3) != 0;
        return new Ack(version, success);
    }
}
