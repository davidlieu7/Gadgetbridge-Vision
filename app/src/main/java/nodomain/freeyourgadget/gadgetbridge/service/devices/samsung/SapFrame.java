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

import nodomain.freeyourgadget.gadgetbridge.util.CheckSums;

/**
 * SAP transport wrapper, as carried in a single ATT value on the Galaxy Fit3.
 *
 * <pre>
 *   +--------+---------+----------------+----------+
 *   |  LEN   | HDR_CRC |    PAYLOAD     | DATA_CRC |
 *   | 2 (BE) |  2 (BE) |   LEN bytes    |  2 (BE)  |
 *   +--------+---------+----------------+----------+
 *
 *   LEN      = payload length, big-endian. Total frame = LEN + 6.
 *   HDR_CRC  = CRC-16/ARC over the 2 LEN bytes,   stored big-endian.
 *   DATA_CRC = CRC-16/ARC over the PAYLOAD bytes, stored big-endian.
 * </pre>
 *
 * The HELLO handshake frames (first byte 0x68) are NOT wrapped this way and are
 * handled separately.
 */
public final class SapFrame {

    private SapFrame() {
    }

    /** Wrap a session-layer payload into a complete on-wire frame. */
    public static byte[] wrap(byte[] payload) {
        int len = payload.length;
        byte[] lenBytes = new byte[]{(byte) ((len >> 8) & 0xFF), (byte) (len & 0xFF)};
        int hdrCrc = Crc16.arc(lenBytes);
        int dataCrc = Crc16.arc(payload); //FIXME: try to use generic crc16

        byte[] frame = new byte[len + 6];
        frame[0] = lenBytes[0];
        frame[1] = lenBytes[1];
        frame[2] = (byte) ((hdrCrc >> 8) & 0xFF);
        frame[3] = (byte) (hdrCrc & 0xFF);
        System.arraycopy(payload, 0, frame, 4, len);
        frame[4 + len] = (byte) ((dataCrc >> 8) & 0xFF);
        frame[5 + len] = (byte) (dataCrc & 0xFF);
        return frame;
    }

    /** True if this looks like a plaintext SAP HELLO handshake frame. */
    public static boolean isHello(byte[] frame) {
        return frame.length >= 1 && (frame[0] & 0xFF) == 0x68;
    }

    /**
     * Validate the framing and return the payload, or {@code null} if the frame
     * is malformed / fails a CRC check. Never throws.
     */
    public static byte[] unwrap(byte[] frame) {
        if (frame == null || frame.length < 6) {
            return null;
        }
        int len = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
        if (frame.length != len + 6) {
            return null;
        }
        int hdrCrc = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        if (Crc16.arc(frame, 0, 2) != hdrCrc) {
            return null;
        }
        int dataCrc = ((frame[4 + len] & 0xFF) << 8) | (frame[5 + len] & 0xFF);
        if (Crc16.arc(frame, 4, len) != dataCrc) {
            return null;
        }
        byte[] payload = new byte[len];
        System.arraycopy(frame, 4, payload, 0, len);
        return payload;
    }
}
