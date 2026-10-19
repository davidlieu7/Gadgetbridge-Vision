/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar.pftp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;

/**
 * PFTP message encoding and the "RFC76" frame layer below it.
 *
 * <p>Every frame starts with one header byte:
 * <pre>
 *   bit 0     next     0 = first frame of a message, 1 = continuation
 *   bits 1-2  status   1 = last frame, 3 = more frames follow,
 *                      0 = status frame: the next two bytes are a little-endian PbPFtpError
 *   bits 4-7  sequence number, starts at 0 per message and wraps after 15
 * </pre>
 * A status frame with error 0 is the plain "ok" answer to a request or query
 * that returns no payload.
 *
 * <p>Message payloads (before framing):
 * <ul>
 *   <li>request: 2-byte little-endian length of the PbPFtpOperation, the operation, then the file data (PUT)</li>
 *   <li>query: 2-byte little-endian query id with bit 15 set, then the protobuf parameters</li>
 *   <li>notification: 1-byte notification id, then the protobuf parameters</li>
 * </ul>
 */
public final class PftpFraming {
    private static final int STATUS_ERROR_OR_RESPONSE = 0;
    private static final int STATUS_LAST = 1;
    private static final int STATUS_MORE = 3;

    private PftpFraming() {
    }

    public static byte[] request(final PolarProto.PbPFtpOperation.Command command,
                                 final String path,
                                 @Nullable final byte[] data) {
        final byte[] operation = PolarProto.PbPFtpOperation.newBuilder()
                .setCommand(command)
                .setPath(path)
                .build()
                .toByteArray();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(operation.length & 0xFF);
        out.write((operation.length >> 8) & 0x7F);
        out.write(operation, 0, operation.length);
        if (data != null) {
            out.write(data, 0, data.length);
        }
        return out.toByteArray();
    }

    public static byte[] query(final int queryId, @Nullable final byte[] params) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(queryId & 0xFF);
        out.write(((queryId >> 8) & 0x7F) | 0x80);
        if (params != null) {
            out.write(params, 0, params.length);
        }
        return out.toByteArray();
    }

    public static byte[] notification(final int notificationId, @Nullable final byte[] params) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(notificationId & 0xFF);
        if (params != null) {
            out.write(params, 0, params.length);
        }
        return out.toByteArray();
    }

    /**
     * Split a message payload into frames of at most {@code frameSize} bytes
     * (the ATT MTU minus 3).
     */
    public static List<byte[]> frames(@NonNull final byte[] message, final int frameSize) {
        final int chunk = frameSize - 1;
        final List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        int sequence = 0;
        do {
            final int length = Math.min(chunk, message.length - offset);
            final boolean last = offset + length >= message.length;
            final byte[] frame = new byte[length + 1];
            frame[0] = (byte) ((offset == 0 ? 0 : 1)
                    | ((last ? STATUS_LAST : STATUS_MORE) << 1)
                    | (sequence << 4));
            System.arraycopy(message, offset, frame, 1, length);
            frames.add(frame);
            offset += length;
            sequence = (sequence + 1) & 0x0F;
        } while (offset < message.length);
        return frames;
    }

    /** Result of a complete incoming message: either a payload, or a status frame's error code. */
    public static final class Message {
        @Nullable
        public final byte[] payload;
        public final int error;

        private Message(@Nullable final byte[] payload, final int error) {
            this.payload = payload;
            this.error = error;
        }

        public boolean isStatus() {
            return payload == null;
        }
    }

    /** Reassembles the frames received on one characteristic into messages. */
    public static final class Reassembler {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int expectedSequence = -1;

        /**
         * @return the completed message, or null if more frames are needed
         * @throws IllegalStateException on a frame that does not continue the current message
         */
        @Nullable
        public Message add(@NonNull final byte[] frame) {
            if (frame.length == 0) {
                throw new IllegalStateException("empty frame");
            }
            final int header = frame[0] & 0xFF;
            final boolean continuation = (header & 0x01) != 0;
            final int status = (header >> 1) & 0x03;
            final int sequence = (header >> 4) & 0x0F;

            if (!continuation) {
                reset();
                if (status == STATUS_ERROR_OR_RESPONSE) {
                    if (frame.length < 3) {
                        throw new IllegalStateException("short status frame");
                    }
                    return new Message(null, (frame[1] & 0xFF) | ((frame[2] & 0xFF) << 8));
                }
            } else if (sequence != expectedSequence) {
                reset();
                throw new IllegalStateException("sequence " + sequence + ", expected " + expectedSequence);
            }

            buffer.write(frame, 1, frame.length - 1);
            if (status == STATUS_LAST) {
                final byte[] payload = buffer.toByteArray();
                reset();
                return new Message(payload, 0);
            }
            if (status != STATUS_MORE) {
                reset();
                throw new IllegalStateException("unexpected status " + status);
            }
            expectedSequence = (sequence + 1) & 0x0F;
            return null;
        }

        public void reset() {
            buffer.reset();
            expectedSequence = -1;
        }
    }
}
