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

import java.io.ByteArrayOutputStream;

/**
 * Reassembles fragmented band->phone messages back into a complete
 * (channel, body) pair.
 *
 * Large objects (e.g. app icons, health/workout records) are sent as a first
 * fragment (TYPE bit1 set, carries the channel) followed by continuation
 * fragments (TYPE bit2 set, raw data). Each fragment/chunk is individually
 * ACKed at the {@link SapPacket} layer.
 *
 * NOTE: the exact "end of logical message" marker inside the first-fragment
 * chunk header is not fully reverse-engineered. This implementation uses the
 * pragmatic rule that a logical message is complete when the next SINGLE or
 * FIRST_FRAGMENT arrives (i.e. a new message starts), which matches the observed
 * captures. If you later decode the chunk header's total-length field, switch to
 * length-based completion for robustness.
 */
public final class SapReassembler {

    /** A fully reassembled application message. */
    public static final class Message {
        public final int channel;
        public final byte[] body;

        public Message(int channel, byte[] body) {
            this.channel = channel;
            this.body = body;
        }
    }

    private int pendingChannel = -1;
    private ByteArrayOutputStream pending;

    /**
     * Offer a parsed non-ACK packet. Returns any message that just became
     * complete, or {@code null} if more fragments are expected.
     *
     * Typical usage: whenever this returns non-null, dispatch it; a SINGLE also
     * flushes any dangling fragment buffer first (returned on the SINGLE call is
     * the SINGLE itself; the flushed one should be handled — see offerWithFlush).
     */
    public Message offer(SapPacket packet) {
        switch (packet.kind) {
            case SINGLE:
                // A complete standalone message.
                Message flushed = flush();
                if (flushed != null) {
                    // A previous fragmented message ended without an explicit
                    // terminator; deliver it and buffer this SINGLE for next call.
                    stash(packet);
                    return flushed;
                }
                return new Message(packet.channel, packet.body.clone());

            case FIRST_FRAGMENT: {
                Message done = flush();
                pendingChannel = packet.channel;
                pending = new ByteArrayOutputStream();
                write(pending, packet.body);
                return done; // may be null
            }

            case CONTINUATION:
                if (pending != null) {
                    write(pending, packet.body);
                }
                return null;

            default:
                return null;
        }
    }

    /** Force-complete any buffered fragmented message (e.g. on disconnect). */
    public Message flush() {
        if (pending != null) {
            Message m = new Message(pendingChannel, pending.toByteArray());
            pending = null;
            pendingChannel = -1;
            return m;
        }
        return null;
    }

    private SapPacket stashed;

    private void stash(SapPacket packet) {
        stashed = packet;
    }

    /** Retrieve (and clear) a SINGLE that was stashed while flushing a fragment. */
    public Message takeStashed() {
        if (stashed != null) {
            SapPacket p = stashed;
            stashed = null;
            return new Message(p.channel, p.body.clone());
        }
        return null;
    }

    private static void write(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }
}
