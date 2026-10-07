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

import java.util.Arrays;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * The notification channel's capability exchange ({@code CH_NOTIFICATION},
 * MSG_ID 0x0d): the phone asks with a bare header, and the band answers with
 * what its notification UI supports - most usefully the icon size(s) it
 * wants.
 *
 * <pre>
 *   request:  0d
 *   reply:    4d [count] count x [id][value]
 *             ids 0-4, 7-9: one byte, a flag
 *             id 5:         one byte, image support level
 *             id 6:         [n][n bytes] - the icon sizes, in pixels
 * </pre>
 *
 * A real band answered {@code 4d 08 00 01 01 01 02 01 03 01 04 01 05 03 07 01
 * 06 02 34 70}: every flag it lists set, image level 3, icon sizes 52 and
 * 112, and no icon-sync-on-connect flag (id 8) at all.
 */
public final class Fit3NotificationCapability {

    public static final int CHANNEL = SapProtocol.CH_NOTIFICATION;
    public static final int MSG_ID = 0x0d;

    private static final int CAP_INDICATOR = 0;
    private static final int CAP_SCREEN_ON = 1;
    private static final int CAP_PERSISTENT = 2;
    private static final int CAP_QUICK_REPLY = 3;
    private static final int CAP_BODY = 4;
    private static final int CAP_IMAGE_LEVEL = 5;
    private static final int CAP_ICON_SIZES = 6;
    private static final int CAP_WHILE_WORN = 7;
    private static final int CAP_ICON_SYNC = 8;
    private static final int CAP_MUTE_SYNC = 9;

    /** Icon edge used when the band hasn't said otherwise - what it asks for in practice. */
    public static final int DEFAULT_ICON_SIZE = 52;

    public boolean indicator;
    public boolean screenOn;
    public boolean persistent;
    public boolean quickReply;
    public boolean body;
    public int imageLevel;
    public int[] iconSizes = new int[0];
    public boolean whileWorn;
    /** The band offers to take icons pushed on connect. Absent on the Fit3 tested. */
    public boolean iconSync;
    public boolean muteSync;

    public static byte[] buildRequest() {
        return SapPayloadBuilder.fixed(MSG_ID).build();
    }

    /** The edge length notification icons should be rendered at. */
    public int iconSize() {
        return iconSizes.length > 0 ? iconSizes[0] : DEFAULT_ICON_SIZE;
    }

    /**
     * @return the decoded capabilities, or {@code null} if this isn't the
     *         handshake reply. An id whose width isn't known ends the walk,
     *         keeping what was read before it.
     */
    @Nullable
    public static Fit3NotificationCapability parse(byte[] body) {
        if (body == null || body.length < 2 || (body[0] & 0x40) == 0 || (body[0] & 0x3f) != MSG_ID) {
            return null;
        }
        Fit3NotificationCapability cap = new Fit3NotificationCapability();
        int count = body[1] & 0xff;
        int off = 2;
        for (int i = 0; i < count && off + 1 < body.length; i++) {
            int id = body[off] & 0xff;
            int value = body[off + 1] & 0xff;
            if (id == CAP_ICON_SIZES) {
                if (off + 2 + value > body.length) {
                    break;
                }
                cap.iconSizes = new int[value];
                for (int k = 0; k < value; k++) {
                    cap.iconSizes[k] = body[off + 2 + k] & 0xff;
                }
                off += 2 + value;
                continue;
            }
            boolean flag = value != 0;
            switch (id) {
                case CAP_INDICATOR: cap.indicator = flag; break;
                case CAP_SCREEN_ON: cap.screenOn = flag; break;
                case CAP_PERSISTENT: cap.persistent = flag; break;
                case CAP_QUICK_REPLY: cap.quickReply = flag; break;
                case CAP_BODY: cap.body = flag; break;
                case CAP_IMAGE_LEVEL: cap.imageLevel = value; break;
                case CAP_WHILE_WORN: cap.whileWorn = flag; break;
                case CAP_ICON_SYNC: cap.iconSync = flag; break;
                case CAP_MUTE_SYNC: cap.muteSync = flag; break;
                default:
                    return cap;
            }
            off += 2;
        }
        return cap;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "NotificationCapability{indicator=%b, screenOn=%b, persistent=%b, quickReply=%b, body=%b, "
                        + "imageLevel=%d, iconSizes=%s, whileWorn=%b, iconSync=%b, muteSync=%b}",
                indicator, screenOn, persistent, quickReply, body, imageLevel, Arrays.toString(iconSizes),
                whileWorn, iconSync, muteSync);
    }
}
