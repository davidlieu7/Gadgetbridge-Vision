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
 * captures.
 *
 * <p>That "wait for the next message" rule is fine for occasional large objects,
 * but it is fragile for a rapid back-to-back stream where every message is
 * fragmented (the band's log-file pull on {@link SapProtocol#CH_DEBUG}, which
 * sends every packet as first-fragment + one continuation): it defers delivery
 * by a whole message and, if a first/continuation pairing is disturbed, a
 * following first-fragment flushes a half-assembled body. For channels where the
 * total body length can be derived from the bytes seen so far, install a
 * {@link LengthEstimator}; a fragmented message is then completed the instant its
 * declared length is reached, with no dependence on the next message arriving.
 * Channels without an estimator (the estimator returns -1) keep the exact
 * "complete on next message" behaviour.
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

    /**
     * Optional per-channel hook that reports the expected total body length of a
     * fragmented message from the bytes accumulated so far, or -1 if it cannot be
     * determined yet (or the channel is not length-delimited). When it returns a
     * value that the pending buffer has reached, the message is delivered
     * immediately instead of waiting for the next message to start.
     */
    public interface LengthEstimator {
        int expectedLength(int channel, byte[] soFar);
    }

    private int pendingChannel = -1;
    private ByteArrayOutputStream pending;
    private LengthEstimator lengthEstimator;

    /** Install (or clear, with {@code null}) the length estimator. */
    public void setLengthEstimator(LengthEstimator estimator) {
        this.lengthEstimator = estimator;
    }

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
                // A complete standalone message. If a length-delimited fragment is
                // still mid-assembly (e.g. a debug log packet whose continuation
                // has not arrived), a SINGLE - typically on another channel - must
                // not truncate it: keep it pending and deliver just the SINGLE.
                if (isPendingKnownIncomplete()) {
                    return new Message(packet.channel, packet.body.clone());
                }
                Message flushed = flush();
                if (flushed != null) {
                    // A previous fragmented message ended without an explicit
                    // terminator; deliver it and buffer this SINGLE for next call.
                    stashMessage(new Message(packet.channel, packet.body.clone()));
                    return flushed;
                }
                return new Message(packet.channel, packet.body.clone());

            case FIRST_FRAGMENT: {
                Message done = flush();
                pendingChannel = packet.channel;
                pending = new ByteArrayOutputStream();
                write(pending, packet.body);
                // A tiny message can arrive complete in its first fragment.
                Message complete = tryCompleteByLength();
                if (complete != null && done != null) {
                    // Deliver the older leftover first, the just-completed one next.
                    stashMessage(complete);
                    return done;
                }
                return complete != null ? complete : done; // either may be null
            }

            case CONTINUATION:
                if (pending != null) {
                    write(pending, packet.body);
                    return tryCompleteByLength(); // non-null once the body is whole
                }
                return null;

            default:
                return null;
        }
    }

    /**
     * If a {@link LengthEstimator} is installed and the pending buffer has
     * reached the message's declared total length, deliver it and reset. Returns
     * {@code null} (buffer untouched) when no estimator is set, the length is not
     * yet known, or the message is still incomplete - so channels without an
     * estimator behave exactly as before.
     */
    private Message tryCompleteByLength() {
        if (pending == null || lengthEstimator == null) {
            return null;
        }
        byte[] soFar = pending.toByteArray();
        int expected = lengthEstimator.expectedLength(pendingChannel, soFar);
        if (expected < 0 || soFar.length < expected) {
            return null;
        }
        Message m = new Message(pendingChannel, soFar);
        pending = null;
        pendingChannel = -1;
        return m;
    }

    /**
     * True when a fragment is buffered and the estimator knows its total length
     * but it has not all arrived yet - i.e. tearing it down now would truncate it.
     */
    private boolean isPendingKnownIncomplete() {
        if (pending == null || lengthEstimator == null) {
            return false;
        }
        byte[] soFar = pending.toByteArray();
        int expected = lengthEstimator.expectedLength(pendingChannel, soFar);
        return expected >= 0 && soFar.length < expected;
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

    private Message stashed;

    private void stashMessage(Message m) {
        stashed = m;
    }

    /**
     * Retrieve (and clear) a message that was set aside while another was
     * delivered in the same {@link #offer} call (a SINGLE that arrived mid-
     * fragment, or a length-completed first fragment behind a flushed leftover).
     */
    public Message takeStashed() {
        Message m = stashed;
        stashed = null;
        return m;
    }

    private static void write(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }
}
