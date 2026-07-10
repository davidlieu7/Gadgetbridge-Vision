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
 * Volume-control command from the band, on {@code CH_MEDIA} (0x09),
 * MSG_ID 7. Variable format WITH a leading param-count byte; both params
 * are always present on the wire, even for commands (up/down/mute) where
 * the value byte carries no meaning.
 *
 * <pre>
 *   xx 02                   variable, MSG_ID 7, 2 params
 *      01 xx                    param 0x01 = command (see CMD_* constants)
 *      02 xx                    param 0x02 = value (0-100; only meaningful for CMD_SET)
 * </pre>
 */
public final class Fit3MediaVolume {

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_MEDIA;

    public static final int MSG_ID_VOLUME_CONTROL = 0x07;

    private static final int PARAM_COMMAND = 0x01;
    private static final int PARAM_VALUE   = 0x02;

    public static final int CMD_SET      = 1;
    public static final int CMD_UP       = 2;
    public static final int CMD_DOWN     = 3;
    public static final int CMD_MUTE_ON  = 4;
    public static final int CMD_MUTE_OFF = 5;

    public final int command;
    public final int value;

    private Fit3MediaVolume(int command, int value) {
        this.command = command;
        this.value = value;
    }

    /** @return the decoded volume command, or {@code null} if this body is not a MSG_ID 7 event. */
    public static Fit3MediaVolume parse(byte[] body) {
        SapPayloadParser p = new SapPayloadParser(body);
        if (p.msgId != MSG_ID_VOLUME_CONTROL) {
            return null;
        }

        int command = 0;
        int value = 0;
        while (p.remaining() >= 2) {
            int id = p.u8();
            int paramValue = p.u8();
            if (id == PARAM_COMMAND) {
                command = paramValue;
            } else if (id == PARAM_VALUE) {
                value = paramValue;
            } else {
                break; // unknown param id - stop clean
            }
        }
        return new Fit3MediaVolume(command, value);
    }

    @Override
    public String toString() {
        return "MediaVolume{command=" + command + ", value=" + value + "}";
    }
}
