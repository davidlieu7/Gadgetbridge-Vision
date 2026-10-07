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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * The canned replies the band offers, on {@code CH_QUICK_REPLY} (0x12). Two
 * lists - one for replying to notifications, one for rejecting a call with a
 * message - plus a switch for the feature. The band sends the chosen text
 * back literally (see {@link Fit3NotificationAction}), so the lists only
 * shape what it offers.
 *
 * <pre>
 *   notification replies: 00 00 [n] n x (01 [len][utf8])
 *   call-reject replies:  01 00 [n] n x (01 [len][utf8])
 *   feature on/off:       02 03 [0/1]
 * </pre>
 *
 * Confirmed against vendor captures (16 notification replies, 3 call
 * replies, the switch sent on before them).
 */
public final class Fit3QuickReplies {

    public static final int CHANNEL = SapProtocol.CH_QUICK_REPLY;

    public static final int MSG_ID_NOTIFICATION_REPLIES = 0x00;
    public static final int MSG_ID_CALL_REPLIES = 0x01;
    public static final int MSG_ID_ENABLED = 0x02;

    private static final int PARAM_COUNT = 0x00;
    private static final int PARAM_TEXT = 0x01;
    private static final int PARAM_ENABLED = 0x03;
    /** The vendor caps the list at what fits one byte of count; keep strings within a 1-byte length. */
    private static final int MAX_TEXT_BYTES = 255;

    private Fit3QuickReplies() {
    }

    /** @param msgId {@link #MSG_ID_NOTIFICATION_REPLIES} or {@link #MSG_ID_CALL_REPLIES} */
    public static byte[] buildList(int msgId, List<String> replies) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(msgId);
        out.write(PARAM_COUNT);
        int n = Math.min(replies.size(), 255);
        out.write(n);
        for (int i = 0; i < n; i++) {
            byte[] text = truncate(replies.get(i).getBytes(StandardCharsets.UTF_8));
            out.write(PARAM_TEXT);
            out.write(text.length);
            out.write(text, 0, text.length);
        }
        return out.toByteArray();
    }

    public static byte[] buildEnabled(boolean enabled) {
        return new byte[]{MSG_ID_ENABLED, PARAM_ENABLED, (byte) (enabled ? 1 : 0)};
    }

    /** Cut to at most {@link #MAX_TEXT_BYTES} without splitting a UTF-8 sequence. */
    private static byte[] truncate(byte[] text) {
        if (text.length <= MAX_TEXT_BYTES) {
            return text;
        }
        int end = MAX_TEXT_BYTES;
        while (end > 0 && (text[end] & 0xc0) == 0x80) {
            end--;
        }
        byte[] out = new byte[end];
        System.arraycopy(text, 0, out, 0, end);
        return out;
    }
}
