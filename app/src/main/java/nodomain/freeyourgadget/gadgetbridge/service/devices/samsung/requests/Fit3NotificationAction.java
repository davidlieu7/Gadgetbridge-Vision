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

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * What the user does with a notification on the band, and the phone's
 * answers - plus the phone telling the band a notification is gone. All on
 * {@code CH_NOTIFICATION} (0x07).
 *
 * <pre>
 *   dismissed on band  B-&gt;P: 02 01 [seq:4 LE]                 P-&gt;B: 42 01 [seq] 0b [result]
 *   cleared all        B-&gt;P: 09                               P-&gt;B: 49 0b [result]
 *   quick reply        B-&gt;P: 8a [n] 01 [seq] 0a [len][utf8]   P-&gt;B: 4a 01 [seq] 0b [result]
 *   open on phone      B-&gt;P: 04 01 [seq]
 *   launch the app     B-&gt;P: 87 [n] 06 [len][app id]
 *   gone from phone    P-&gt;B: 01 01 [seq]
 * </pre>
 *
 * {@code seq} is the id the notification was pushed with (its {@code
 * SEQUENCE_ID}); the app id is the stand-in {@code APP_ID} it carried. The
 * dismiss, clear-all and reply exchanges are confirmed against vendor
 * captures, including the reply text arriving as literal UTF-8 and the band
 * following a reply with a dismiss of the same notification. Open and launch
 * are source-derived and get no answer.
 */
public final class Fit3NotificationAction {

    public static final int CHANNEL = SapProtocol.CH_NOTIFICATION;

    public static final int MSG_ID_DELETED_ON_PHONE = 0x01;
    public static final int MSG_ID_DISMISSED_ON_BAND = 0x02;
    public static final int MSG_ID_OPEN_ON_PHONE = 0x04;
    public static final int MSG_ID_LAUNCH_APP = 0x07;
    public static final int MSG_ID_CLEARED_ALL = 0x09;
    public static final int MSG_ID_ACTION = 0x0a;

    private static final int PARAM_SEQUENCE_ID = 0x01;
    private static final int PARAM_APP_ID = 0x06;
    private static final int PARAM_REPLY = 0x0a;
    private static final int PARAM_RESULT = 0x0b;

    private Fit3NotificationAction() {
    }

    public enum Kind { DISMISS, CLEAR_ALL, REPLY, OPEN, LAUNCH_APP }

    /** One band-side action. */
    public static final class Action {
        public final Kind kind;
        /** The notification's id; -1 for {@link Kind#CLEAR_ALL} and {@link Kind#LAUNCH_APP}. */
        public final int sequenceId;
        @Nullable public final String reply;
        @Nullable public final String appId;

        Action(Kind kind, int sequenceId, @Nullable String reply, @Nullable String appId) {
            this.kind = kind;
            this.sequenceId = sequenceId;
            this.reply = reply;
            this.appId = appId;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "Action{%s, seq=%d, reply=%s, app=%s}", kind, sequenceId, reply, appId);
        }
    }

    /** @return the band's action, or {@code null} if this body isn't one. */
    @Nullable
    public static Action parse(byte[] body) {
        if (body == null || body.length < 1 || (body[0] & 0x40) != 0) {
            return null;
        }
        try {
            switch (body[0] & 0x3f) {
                case MSG_ID_DISMISSED_ON_BAND:
                    return seqAction(Kind.DISMISS, body, 1);
                case MSG_ID_OPEN_ON_PHONE:
                    return seqAction(Kind.OPEN, body, 1);
                case MSG_ID_CLEARED_ALL:
                    return new Action(Kind.CLEAR_ALL, -1, null, null);
                case MSG_ID_ACTION:
                    return parseReply(body);
                case MSG_ID_LAUNCH_APP:
                    return parseLaunch(body);
                default:
                    return null;
            }
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static Action seqAction(Kind kind, byte[] body, int off) {
        if ((body[off] & 0xff) != PARAM_SEQUENCE_ID) {
            return null;
        }
        return new Action(kind, le32(body, off + 1), null, null);
    }

    private static Action parseReply(byte[] body) {
        // [hdr][count] then [01 seq:4][0a len utf8], in either order.
        int count = body[1] & 0xff;
        int off = 2;
        int seq = -1;
        String reply = null;
        for (int i = 0; i < count; i++) {
            int id = body[off] & 0xff;
            if (id == PARAM_SEQUENCE_ID) {
                seq = le32(body, off + 1);
                off += 5;
            } else if (id == PARAM_REPLY) {
                int len = body[off + 1] & 0xff;
                reply = new String(body, off + 2, len, StandardCharsets.UTF_8);
                off += 2 + len;
            } else {
                break;
            }
        }
        return seq < 0 && reply == null ? null : new Action(Kind.REPLY, seq, reply, null);
    }

    private static Action parseLaunch(byte[] body) {
        // [hdr][count=1][06 len app-id]
        if ((body[2] & 0xff) != PARAM_APP_ID) {
            return null;
        }
        int len = body[3] & 0xff;
        return new Action(Kind.LAUNCH_APP, -1, null, new String(body, 4, len, StandardCharsets.UTF_8));
    }

    /**
     * The phone's answer to an action, or {@code null} for actions that get
     * none (open, launch).
     */
    @Nullable
    public static byte[] buildResponse(Action action, boolean ok) {
        int result = ok ? 1 : 0;
        switch (action.kind) {
            case DISMISS:
                return withSeq(0x40 | MSG_ID_DISMISSED_ON_BAND, action.sequenceId, result);
            case REPLY:
                return withSeq(0x40 | MSG_ID_ACTION, action.sequenceId, result);
            case CLEAR_ALL:
                return new byte[]{(byte) (0x40 | MSG_ID_CLEARED_ALL), PARAM_RESULT, (byte) result};
            default:
                return null;
        }
    }

    /** Tell the band a notification was removed on the phone. */
    public static byte[] buildDeletedOnPhone(int sequenceId) {
        byte[] out = new byte[6];
        out[0] = MSG_ID_DELETED_ON_PHONE;
        out[1] = PARAM_SEQUENCE_ID;
        putLe32(out, 2, sequenceId);
        return out;
    }

    private static byte[] withSeq(int header, int seq, int result) {
        byte[] out = new byte[8];
        out[0] = (byte) header;
        out[1] = PARAM_SEQUENCE_ID;
        putLe32(out, 2, seq);
        out[6] = PARAM_RESULT;
        out[7] = (byte) result;
        return out;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }
}
