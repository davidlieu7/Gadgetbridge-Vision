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
    public static final int CH_CAPABILITY_0F = 0x0F;
    public static final int CH_QUICK_REPLY  = 0x12;
    public static final int CH_CAP2_06      = 0x06;   // capability handshake tail
    public static final int CH_IDENTITY     = 0x1A;   // identity + binding token


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
     * Only single-frame messages are produced here (they cover all normal client
     * commands: settings, replies, find-band, media, etc.). If {@code body} is
     * too large for one ATT write, split it into a first-fragment + continuation
     * chunk sequence — see the note in {@link SapReassembler}. For that you also
     * need the outgoing chunk-header format, which is not yet fully decoded.
     *
     * @param mtu the negotiated ATT MTU (e.g. 512); max frame = mtu - 3.
     */
    public byte[] buildSingle(int channel, byte[] body, int mtu) {
        byte[] payload = SapPacket.encodeSingle(channel, nextSeq(), body);
        byte[] frame = SapFrame.wrap(payload);
        int maxFrame = mtu - 3;
        if (frame.length > maxFrame) {
            throw new IllegalArgumentException(
                    "message needs fragmentation (" + frame.length + " > " + maxFrame + "); not implemented");
        }
        return frame;
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
                if (m != null) {
                    listener.onMessage(m.channel, m.body);
                }
                // Each chunk is ACKed with the chunk flag.
                return SapFrame.wrap(SapPacket.encodeAck(packet.seq, true));
            }

            default:
                return null;
        }
    }
}
