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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Control messages on {@code CH_FILE_TRANSFER} (0x1e). They don't carry file
 * bytes: they ask the band to accept a file and to bring up its classic
 * Bluetooth RFCOMM server, and the file itself then goes over that socket
 * (see {@code Fit3SppFileSender}).
 *
 * <p>Sequence: {@link #buildOfferFileRequest offer} (what, how big) →
 * {@link #buildOpenSocketRequest open socket} → RFCOMM transfer →
 * {@link #buildCloseSocketRequest close socket}. Each is answered with the same
 * MSG_ID and a {@link Response} status.
 *
 * <p>Requests are fixed format with no param-count byte; replies too.
 * The offer and open requests and their replies are wire-confirmed against a
 * vendor watchface-install capture; the close isn't in that capture (the
 * vendor app sends it lazily, well after the transfer) and is built to the
 * same shape as the open.
 */
public final class Fit3FileTransfer {

    public static final int CHANNEL = SapProtocol.CH_FILE_TRANSFER;

    public static final int MSG_ID_OFFER_FILE = 0x01;
    public static final int MSG_ID_OPEN_SOCKET = 0x02;
    public static final int MSG_ID_CLOSE_SOCKET = 0x03;

    /** What the file is for; the band routes it by this. */
    public static final int PURPOSE_WATCHFACE = 6;
    public static final int PURPOSE_FIRMWARE = 21;

    // Request params.
    private static final int PARAM_PURPOSE = 0x03;
    private static final int PARAM_FILE_NAME = 0x04;
    private static final int PARAM_FILE_SIZE = 0x05;

    // Reply params.
    private static final int PARAM_STATUS = 0x01;
    private static final int PARAM_REASON = 0x02;
    private static final int PARAM_FREE_SPACE = 0x06;

    /** {@link Response#reason} codes for a refused offer. */
    public static final int REASON_LOW_BATTERY = 1;
    public static final int REASON_LOW_MEMORY = 2;

    private Fit3FileTransfer() {
    }

    // ---- requests ----

    /**
     * Ask the band to accept a file: {@code [0x03 feature][0x04 name][0x05 size:4 LE]}.
     * The name is the bare file name; the band files it under its own
     * per-feature directory.
     */
    public static byte[] buildOfferFileRequest(int purpose, String fileName, int fileSize) {
        return SapPayloadBuilder.fixed(MSG_ID_OFFER_FILE)
                .addByte(PARAM_PURPOSE, purpose)
                .addString(PARAM_FILE_NAME, fileName)
                .addInt32(PARAM_FILE_SIZE, fileSize)
                .build();
    }

    /** Ask the band to start its RFCOMM server for the transfer. */
    public static byte[] buildOpenSocketRequest() {
        return SapPayloadBuilder.fixed(MSG_ID_OPEN_SOCKET).build();
    }

    /** Tell the band the transfer is over and it can drop the RFCOMM server. */
    public static byte[] buildCloseSocketRequest() {
        return SapPayloadBuilder.fixed(MSG_ID_CLOSE_SOCKET).build();
    }

    // ---- replies ----

    /** A reply to any of the three requests. */
    public static final class Response {
        public final int msgId;
        public final boolean status;
        public final int reason;
        /** Echo of the offer's purpose; offer replies only, else -1. */
        public final int purpose;
        /** Free space as the band reports it; offer replies only, else -1. Unit unconfirmed. */
        public final int freeSpace;

        Response(int msgId, boolean status, int reason, int purpose, int freeSpace) {
            this.msgId = msgId;
            this.status = status;
            this.reason = reason;
            this.purpose = purpose;
            this.freeSpace = freeSpace;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "Response{msgId=%d, status=%b, reason=%d, feature=%d, memory=%d}",
                    msgId, status, reason, purpose, freeSpace);
        }
    }

    /**
     * Decode a reply: {@code [0x01 status][0x02 reason]}, plus
     * {@code [0x03 purpose][0x06 free space:4 LE]} on an offer reply.
     *
     * @return the reply, or {@code null} if the body isn't a reply on this channel
     */
    @Nullable
    public static Response parse(byte[] body) {
        if (body == null || body.length < 1) {
            return null;
        }
        SapPayloadParser p = new SapPayloadParser(body, false);
        if (!p.response) {
            return null;
        }
        boolean status = false;
        int reason = -1;
        int purpose = -1;
        int freeSpace = -1;
        try {
            while (p.remaining() >= 2) {
                int id = p.u8();
                if (id == PARAM_STATUS) {
                    status = p.u8() != 0;
                } else if (id == PARAM_REASON) {
                    reason = p.u8();
                } else if (id == PARAM_PURPOSE) {
                    purpose = p.u8();
                } else if (id == PARAM_FREE_SPACE) {
                    freeSpace = p.le32();
                } else {
                    // Unknown width - stop rather than desync.
                    break;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            // Truncated: keep what was read.
        }
        return new Response(p.msgId, status, reason, purpose, freeSpace);
    }
}
