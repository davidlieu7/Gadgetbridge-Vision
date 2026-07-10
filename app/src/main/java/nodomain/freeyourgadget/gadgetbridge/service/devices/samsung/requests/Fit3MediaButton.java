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

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Media transport-button events from the band, on {@code CH_MEDIA} (0x09),
 * MSG_ID 4. Every physical transport button (play/pause, next, previous,
 * stop) shares this one MSG_ID; which button and whether it's a press or a
 * release come in as two separate byte params, variable format WITH a
 * leading param-count byte.
 *
 * <pre>
 *   xx 02                   variable, MSG_ID 4, 2 params
 *      01 xx                    param 0x01 = key code (see KEY_* constants)
 *      02 xx                    param 0x02 = key action (see ACTION_* constants)
 * </pre>
 *
 * <p>A tap sends a press and a release in quick succession, so callers
 * should act on one of them only (typically {@link #ACTION_PRESSED}) to
 * avoid double-triggering next/previous.
 */
public final class Fit3MediaButton {

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_MEDIA;

    public static final int MSG_ID_KEY_EVENT = 0x04;

    private static final int PARAM_KEY_CODE   = 0x01;
    private static final int PARAM_KEY_ACTION = 0x02;

    public static final int KEY_STOP      = 1;
    public static final int KEY_PLAYPAUSE = 2;
    public static final int KEY_PREVIOUS  = 3;
    public static final int KEY_NEXT      = 4;

    public static final int ACTION_RELEASED = 1;
    public static final int ACTION_PRESSED  = 2;

    public final int keyCode;
    public final int keyAction;

    private Fit3MediaButton(int keyCode, int keyAction) {
        this.keyCode = keyCode;
        this.keyAction = keyAction;
    }

    /** @return the decoded key event, or {@code null} if this body is not a MSG_ID 4 key event. */
    public static Fit3MediaButton parse(byte[] body) {
        SapPayloadParser p = new SapPayloadParser(body);
        if (p.msgId != MSG_ID_KEY_EVENT) {
            return null;
        }

        int keyCode = 0;
        int keyAction = 0;
        while (p.remaining() >= 2) {
            int id = p.u8();
            int value = p.u8();
            if (id == PARAM_KEY_CODE) {
                keyCode = value;
            } else if (id == PARAM_KEY_ACTION) {
                keyAction = value;
            } else {
                break; // unknown param id - stop clean
            }
        }
        return new Fit3MediaButton(keyCode, keyAction);
    }

    @Override
    public String toString() {
        return "MediaButton{keyCode=" + keyCode + ", keyAction=" + keyAction + "}";
    }
}
