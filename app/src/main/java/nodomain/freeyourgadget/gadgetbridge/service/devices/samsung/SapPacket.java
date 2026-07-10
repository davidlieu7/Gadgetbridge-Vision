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

/**
 * SAP session layer: the payload carried inside a {@link SapFrame}.
 *
 * <pre>
 *   single / first-fragment:  [TYPE][SEQ][CHAN][CHAN][BODY...]
 *   continuation fragment:    [TYPE][SEQ][BODY...]              (no channel)
 *   ACK:                      [0x88][SEQ][FLAG][0x00]           FLAG 0x80 single / 0xC0 chunk
 *
 *   TYPE bitfield:  bit0 originator (0=watch, 1=phone)
 *                   bit1 first fragment of a multi-part chunk
 *                   bit2 continuation fragment
 * </pre>
 *
 * Continuation SEQ = the chunk's SEQ with bit0 set. Every DATA packet is
 * acknowledged by the other side; the ACK echoes the SEQ.
 */
public final class SapPacket {

    // TYPE values sent by the phone (originator bit set).
    public static final int TYPE_SINGLE = 0x01;   // complete message in one frame
    public static final int TYPE_FIRST  = 0x03;   // first fragment of a chunk
    public static final int TYPE_CONT   = 0x07;   // continuation fragment
    public static final int TYPE_ACK    = 0x88;

    public static final int ACK_FLAG_SINGLE = 0x80;
    public static final int ACK_FLAG_CHUNK  = 0xC0;

    /** Kind of a parsed packet. */
    public enum Kind { SINGLE, FIRST_FRAGMENT, CONTINUATION, ACK, UNKNOWN }

    public final Kind kind;
    public final int type;
    public final int seq;
    public final int channel;   // valid for SINGLE / FIRST_FRAGMENT, else -1
    public final int ackFlag;   // valid for ACK, else -1
    public final byte[] body;   // channel body (SINGLE/FIRST) or raw fragment data (CONT)

    private SapPacket(Kind kind, int type, int seq, int channel, int ackFlag, byte[] body) {
        this.kind = kind;
        this.type = type;
        this.seq = seq;
        this.channel = channel;
        this.ackFlag = ackFlag;
        this.body = body;
    }

    public boolean originatorIsPhone() {
        return (type & 0x01) != 0;
    }

    /** Parse an unwrapped payload into a packet. */
    public static SapPacket parse(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return new SapPacket(Kind.UNKNOWN, -1, -1, -1, -1, new byte[0]);
        }
        int type = payload[0] & 0xFF;
        int seq = payload[1] & 0xFF;

        if (type == TYPE_ACK) {
            int flag = payload.length >= 3 ? (payload[2] & 0xFF) : -1;
            return new SapPacket(Kind.ACK, type, seq, -1, flag, new byte[0]);
        }

        boolean firstFrag = (type & 0x02) != 0;
        boolean continuation = (type & 0x04) != 0;

        if (continuation) {
            byte[] data = slice(payload, 2);
            return new SapPacket(Kind.CONTINUATION, type, seq, -1, -1, data);
        }

        // single or first-fragment: [type][seq][chan][chan][body...]
        if (payload.length < 4) {
            return new SapPacket(Kind.UNKNOWN, type, seq, -1, -1, new byte[0]);
        }
        int channel = payload[2] & 0xFF;
        byte[] body = slice(payload, 4);
        return new SapPacket(firstFrag ? Kind.FIRST_FRAGMENT : Kind.SINGLE,
                type, seq, channel, -1, body);
    }

    /** Encode a complete single-frame message from the phone on a channel. */
    public static byte[] encodeSingle(int channel, int seq, byte[] body) {
        byte[] payload = new byte[4 + body.length];
        payload[0] = (byte) TYPE_SINGLE;
        payload[1] = (byte) (seq & 0xFF);
        payload[2] = (byte) (channel & 0xFF);
        payload[3] = (byte) (channel & 0xFF);
        System.arraycopy(body, 0, payload, 4, body.length);
        return payload;
    }

    /** Encode the first fragment (carries the channel) of a phone-originated multi-frame message. */
    public static byte[] encodeFirstFragment(int channel, int seq, byte[] chunk) {
        byte[] payload = new byte[4 + chunk.length];
        payload[0] = (byte) TYPE_FIRST;
        payload[1] = (byte) (seq & 0xFF);
        payload[2] = (byte) (channel & 0xFF);
        payload[3] = (byte) (channel & 0xFF);
        System.arraycopy(chunk, 0, payload, 4, chunk.length);
        return payload;
    }

    /** Encode a continuation fragment (no channel field) of a phone-originated multi-frame message. */
    public static byte[] encodeContinuation(int seq, byte[] chunk) {
        byte[] payload = new byte[2 + chunk.length];
        payload[0] = (byte) TYPE_CONT;
        payload[1] = (byte) (seq & 0xFF);
        System.arraycopy(chunk, 0, payload, 2, chunk.length);
        return payload;
    }

    /** Encode an ACK for a received packet with the given seq. */
    public static byte[] encodeAck(int seq, boolean chunk) {
        return new byte[]{
                (byte) TYPE_ACK,
                (byte) (seq & 0xFF),
                (byte) (chunk ? ACK_FLAG_CHUNK : ACK_FLAG_SINGLE),
                0x00
        };
    }

    private static byte[] slice(byte[] src, int from) {
        int n = Math.max(0, src.length - from);
        byte[] out = new byte[n];
        System.arraycopy(src, from, out, 0, n);
        return out;
    }
}
