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

import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Request for pushing a new notification to the band, on {@code CH_NOTIFICATION}
 * (0x07). One wire shape serves three MSG_IDs, picked by how the notification
 * should be presented:
 *
 * <ul>
 *   <li>{@link #MSG_ID_NEW_WITH_POPUP} - normal popup alert</li>
 *   <li>{@link #MSG_ID_NEW_WITHOUT_POPUP} - silent, added to the list only</li>
 *   <li>{@link #MSG_ID_WITH_DND} - surfaced despite Do Not Disturb (an
 *       interruption-filter exception, e.g. a starred contact); picked
 *       automatically by {@link #buildRequest(NotificationSpec)} from
 *       {@code spec.dndSuppressed}</li>
 * </ul>
 *
 * <p>Variable format, WITH a leading param-count byte - unlike the OOBE
 * messages, build with {@link SapPayloadBuilder#variable}, not
 * {@code variableNoCount}.
 *
 * <pre>
 *   xx (count)                        variable, request, MSG_ID 0 / 5 / 0x12
 *      00 01                             param 0x00 = NOTI_TYPE = 1 (byte)
 *      01 xx xx xx xx                    param 0x01 = SEQUENCE_ID (int32 LE)
 *      06 len ascii...                   param 0x06 = APP_ID (string, decimal digits)
 *      02 xx*8                           param 0x02 = TIME (int64 LE, epoch ms)
 *      03 len utf8...                    param 0x03 = NOTI_HEADING (string, 1-byte len)
 *      04 len:2 utf8...    [if body]         param 0x04 = NOTI_BODY (string, 2-byte len)
 *      0c len utf8...      [if category]     param 0x0c = NOTI_CATEGORY (string, 1-byte len)
 *      0d 01               [if quick replies available] param 0x0d = NEED_QUICK_RESPONSE (byte)
 *      0e len utf8...      [if app name]     param 0x0e = APP_NAME (string, 1-byte len)
 *      0f 01                             param 0x0f = ALERT_TYPE = 1 (byte)
 *      10 01                             param 0x10 = IS_URGENT = 1 (byte)
 *      12 len utf8...      [if package]      param 0x12 = APP_PACKAGE_NAME (string, 1-byte len)
 * </pre>
 *
 * <p>Field order matters: these params are read positionally against a
 * per-MSG_ID shape on the band's side, not looked up by id out of a bag, so
 * this builds them in the confirmed order rather than an arbitrary one.
 *
 * <p>{@code APP_ID} has no Gadgetbridge equivalent - on the source app it is
 * a stable id assigned per installed app (reused later to target
 * block/unblock-app messages), not something derived from a single
 * notification. A hash of the package name is sent in its place, so at
 * least repeated notifications from the same app carry a consistent id.
 * Icon transfer (params 0x07/0x09/0x13) and {@code WINDOW_ID} (0x11) are not
 * implemented.
 */
public final class Fit3Notification {

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_NOTIFICATION;

    public static final int MSG_ID_NEW_WITH_POPUP    = 0x00;
    public static final int MSG_ID_NEW_WITHOUT_POPUP = 0x05;
    public static final int MSG_ID_WITH_DND          = 0x12;

    private static final int PARAM_TYPE             = 0x00;
    private static final int PARAM_SEQUENCE_ID      = 0x01;
    private static final int PARAM_APP_ID           = 0x06;
    private static final int PARAM_TIME             = 0x02;
    private static final int PARAM_HEADING          = 0x03;
    private static final int PARAM_BODY             = 0x04;
    private static final int PARAM_CATEGORY         = 0x0c;
    private static final int PARAM_NEED_QUICK_REPLY = 0x0d;
    private static final int PARAM_APP_NAME         = 0x0e;
    private static final int PARAM_ALERT_TYPE       = 0x0f;
    private static final int PARAM_IS_URGENT        = 0x10;
    private static final int PARAM_APP_PACKAGE      = 0x12;

    private Fit3Notification() {
    }

    /** Build with the MSG_ID picked automatically from {@code spec.dndSuppressed}. */
    public static byte[] buildRequest(NotificationSpec spec) {
        int msgId = spec.getDndSuppressed() != 0 ? MSG_ID_WITH_DND : MSG_ID_NEW_WITH_POPUP;
        return buildRequest(msgId, spec);
    }

    public static byte[] buildRequest(int msgId, NotificationSpec spec) {
        String heading = firstNonEmpty(spec.getTitle(), spec.getSender(), spec.getSourceName(), "Notification");
        String body    = firstNonEmpty(spec.getBody(), spec.getSubject(), "");
        String appName = spec.getSourceName();
        String pkg     = spec.getSourceAppId() != null ? spec.getSourceAppId() : "";
        long whenMillis = spec.getWhen() > 0 ? spec.getWhen() : System.currentTimeMillis();
        boolean hasQuickReplies = spec.getCannedReplies() != null && spec.getCannedReplies().length > 0;

        SapPayloadBuilder b = SapPayloadBuilder.variable(msgId)
                .addByte(PARAM_TYPE, 1)
                .addInt32(PARAM_SEQUENCE_ID, spec.getId())
                .addString(PARAM_APP_ID, Integer.toString(Math.abs(pkg.hashCode())))
                .addLong(PARAM_TIME, whenMillis)
                .addString(PARAM_HEADING, heading);

        if (!body.isEmpty()) {
            b.addString16(PARAM_BODY, body);
        }
        if (spec.getCategory() != null && !spec.getCategory().isEmpty()) {
            b.addString(PARAM_CATEGORY, spec.getCategory());
        }
        if (hasQuickReplies) {
            b.addByte(PARAM_NEED_QUICK_REPLY, 1);
        }
        if (appName != null && !appName.isEmpty()) {
            b.addString(PARAM_APP_NAME, appName);
        }

        b.addByte(PARAM_ALERT_TYPE, 1)
         .addByte(PARAM_IS_URGENT, 1);

        if (!pkg.isEmpty()) {
            b.addString(PARAM_APP_PACKAGE, pkg);
        }

        return b.build();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
