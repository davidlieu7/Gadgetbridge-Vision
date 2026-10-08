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
 * Stateful glue between the transport ({@link SapFrame}), session
 * ({@link SapPacket}) and reassembly ({@link SapReassembler}) layers.
 *
 * It is deliberately transport-agnostic: it turns application messages into
 * frames to write, and turns received frames into application messages, plus
 * the ACK frames that must be written back. The BLE-specific class
 * ({@code Fit3DeviceSupport}) owns the GATT characteristics and just moves
 * bytes.
 */
public final class SapProtocol {

    /** Channel ids. */
    public static final int CH_CONTROL      = 0x01;
    public static final int CH_FIND_BAND    = 0x02;
    public static final int CH_CALL         = 0x03;
    public static final int CH_WEATHER      = 0x05;
    public static final int CH_NOTIFICATION = 0x07;
    public static final int CH_TIME_CAL     = 0x08;
    public static final int CH_MEDIA        = 0x09;
    public static final int CH_HEALTH       = 0x0A;
    public static final int CH_SETTINGS     = 0x0B;
    public static final int CH_DEBUG        = 0x0C;   // log-file pull
    public static final int CH_TILES        = 0x0F;   // tile list + carousel order
    public static final int CH_QUICK_REPLY  = 0x12;
    public static final int CH_QUICK_PANEL  = 0x13;   // quick-panel button list + order
    public static final int CH_APPS         = 0x18;   // app list order
    public static final int CH_SOS          = 0x19;   // SOS + fall-detection settings
    public static final int CH_WORLD_CLOCK  = 0x1B;   // world clock cities
    public static final int CH_WATCHFACE    = 0x06;   // watchface list/select/install
    public static final int CH_IDENTITY     = 0x1A;   // identity + binding token
    public static final int CH_FILE_TRANSFER = 0x1E;  // bulk file upload control; bytes go over RFCOMM


    public static final String PREF_SAMSUNG_TOKEN = "samsung_token";

    /** Callback for fully-decoded, reassembled application messages. */
    public interface Listener {
        void onMessage(int channel, byte[] body);

        void onHello(byte[] frame);
    }

    private final Listener listener;
    private final SapReassembler reassembler = new SapReassembler();

    /**
     * First SEQ the phone sends. The vendor app's captures always start the
     * session in the 0x40..0xF0 band and never use 0x00, so this starts there
     * too; the band echoes SEQ back in its ACK and does not otherwise act on
     * it, so the exact value is not load-bearing.
     */
    private static final int SEQ_START = 0x40;

    /** Step between successive SEQs, as observed in the captures. */
    private static final int SEQ_STEP = 0x10;

    // Outgoing sequence. The exact wire algorithm the phone uses is not fully
    // reverse-engineered; the band echoes SEQ in its ACK, so an incrementing
    // value is sufficient in practice. Adjust if the device rejects it.
    private int seq = SEQ_START;

    public SapProtocol(Listener listener) {
        this.listener = listener;
    }

    /**
     * Install a {@link SapReassembler.LengthEstimator} so fragmented messages on
     * length-delimited channels (e.g. {@link #CH_DEBUG} log transfers) complete
     * as soon as their declared length is reached rather than when the next
     * message starts. Channels the estimator does not recognise are unaffected.
     */
    public void setReassemblyLengthEstimator(SapReassembler.LengthEstimator estimator) {
        reassembler.setLengthEstimator(estimator);
    }

    private int nextSeq() {
        int s = seq;
        seq = (seq + SEQ_STEP) & 0xFF;
        if (seq == 0x00) {
            seq = SEQ_START;
        }
        return s;
    }

    /**
     * Build the frame(s) to send an application message on a channel.
     *
     * If {@code body} fits in one ATT write, this is a single {@code TYPE_SINGLE}
     * frame, same as before. Otherwise it's split into one first-fragment frame
     * (carries the channel, {@code TYPE_FIRST}) followed by as many continuation
     * frames ({@code TYPE_CONT}, no channel field) as needed to carry the rest.
     *
     * This is deliberately the simple case: one
     * first-fragment plus N continuations for a single logical message, with no
     * chunk-index header. {@link SapReassembler} (the receive side) concatenates
     * exactly that shape - first-frag body + continuation bodies - until the next
     * single/first-frag. It is NOT the separate, still only partly decoded,
     * multi-CHUNK scheme used for objects that span several first-frag+
     * continuation groups (e.g. app icons); that needs a chunk-index header this
     * class does not (yet) produce.
     *
     * A continuation's SEQ is its chunk's SEQ with bit 0 set (e.g.
     * {@code 0x50 -> 0x51}); every continuation in this batch shares that same
     * value, matching the observed captures.
     *
     * @param mtu the negotiated ATT MTU (e.g. 512); max frame = mtu - 3.
     */
    public List<byte[]> buildFrames(int channel, byte[] body, int mtu) {
        int maxFrame = mtu - 3;
        int seq = nextSeq();

        byte[] singlePayload = SapPacket.encodeSingle(channel, seq, body);
        byte[] singleFrame = SapFrame.wrap(singlePayload);
        if (singleFrame.length <= maxFrame) {
            return Collections.singletonList(singleFrame);
        }

        // SapFrame.wrap adds 6 bytes of framing (LEN 2 + HDRCRC 2 + DATACRC 2);
        // first-frag payload has a 4-byte header ([TYPE][SEQ][CHAN][CHAN]),
        // continuation payload has a 2-byte header ([TYPE][SEQ]).
        int firstCap = maxFrame - 6 - 4;
        int contCap = maxFrame - 6 - 2;
        if (firstCap <= 0 || contCap <= 0) {
            throw new IllegalArgumentException("MTU too small to fragment (" + mtu + ")");
        }

        List<byte[]> frames = new ArrayList<>();
        int offset = Math.min(firstCap, body.length);
        frames.add(SapFrame.wrap(SapPacket.encodeFirstFragment(channel, seq, Arrays.copyOfRange(body, 0, offset))));

        int contSeq = seq | 0x01;
        while (offset < body.length) {
            int end = Math.min(offset + contCap, body.length);
            frames.add(SapFrame.wrap(SapPacket.encodeContinuation(contSeq, Arrays.copyOfRange(body, offset, end))));
            offset = end;
        }
        return frames;
    }

    /**
     * Handle one received ATT value (a complete frame). Returns a complete,
     * CRC-wrapped ACK frame to write back verbatim if one is required, otherwise
     * {@code null}. Decoded application messages are delivered via the
     * {@link Listener}. NOTE: the returned ACK is already framed - write it with
     * the raw writer, do not wrap it again.
     */
    public byte[] onFrameReceived(byte[] frame) {
        if (SapFrame.isHello(frame)) {
            listener.onHello(frame);
            return null;
        }
        byte[] payload = SapFrame.unwrap(frame);
        if (payload == null) {
            // CRC failure or malformed; drop. (Consider logging.)
            return null;
        }
        SapPacket packet = SapPacket.parse(payload);

        switch (packet.kind) {
            case ACK:
                // Our previous write was acknowledged; nothing to deliver.
                return null;

            case SINGLE: {
                SapReassembler.Message m = reassembler.offer(packet);
                SapReassembler.Message stashed = reassembler.takeStashed();
                if (m != null) {
                    listener.onMessage(m.channel, m.body);
                }
                if (stashed != null) {
                    listener.onMessage(stashed.channel, stashed.body);
                }
                return SapFrame.wrap(SapPacket.encodeAck(packet.seq, false));
            }

            case FIRST_FRAGMENT:
            case CONTINUATION: {
                SapReassembler.Message m = reassembler.offer(packet);
                SapReassembler.Message stashed = reassembler.takeStashed();
                if (m != null) {
                    listener.onMessage(m.channel, m.body);
                }
                if (stashed != null) {
                    listener.onMessage(stashed.channel, stashed.body);
                }
                // Each chunk is ACKed with the chunk flag.
                return SapFrame.wrap(SapPacket.encodeAck(packet.seq, true));
            }

            default:
                return null;
        }
    }
}
