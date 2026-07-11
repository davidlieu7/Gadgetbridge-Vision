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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.BitmapUtil;
import nodomain.freeyourgadget.gadgetbridge.util.NotificationUtils;

/**
 * Answers the band's icon-fetch request on {@code CH_NOTIFICATION} (0x07),
 * {@code NOTI_ADDITIONAL_INFO} (MSG_ID 3). Fixed format, no count byte, both
 * directions - confirmed against a live capture:
 *
 * <pre>
 *   request  (band -&gt; phone): 03 [9 IMAGE_URL][35 REQUIRED_ICON_SIZE:1B]
 *   response (phone -&gt; band): 43 [9 IMAGE_URL][7 IMAGE:4B-LE-len][35 REQUIRED_ICON_SIZE:4B int]
 * </pre>
 *
 * The response is normally too big for one SAP message and needs
 * {@code Fit3BigData}'s chunking layered on top of it.
 *
 * <p>{@code IMAGE_URL} is {@code "APP_ICON_"} plus the same per-package id
 * {@link Fit3Notification#appIdFor} already sends as {@code APP_ID} in the
 * original notification push - the band is asking for the icon of whichever
 * app it has cached under that id, so the caller needs to remember which
 * package each id was for.
 *
 * <p>Pixel format (confirmed from the companion app's own icon-conversion
 * routine, not just from the capture): {@code size x size} pixels, 4 bytes
 * each, in {@code [R][G][B][255-alpha]} order - the alpha byte is inverted
 * (0 = opaque, 255 = fully transparent), not the standard sense.
 */
public final class Fit3NotificationIcon {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_NOTIFICATION;

    public static final int MSG_ID_ADDITIONAL_INFO = 0x03;

    private static final String ICON_URL_PREFIX = "APP_ICON_";

    private static final int PARAM_IMAGE = 0x07;
    private static final int PARAM_IMAGE_URL = 0x09;
    private static final int PARAM_ICON_SIZE = 0x23; // 35

    private Fit3NotificationIcon() {
    }

    /** The {@code IMAGE_URL} the band will ask for when it wants this package's icon. */
    public static String iconUrlFor(String pkg) {
        return ICON_URL_PREFIX + Fit3Notification.appIdFor(pkg);
    }

    /** A parsed band request. */
    public static final class Request {
        public final String iconUrl;
        public final int size;

        private Request(String iconUrl, int size) {
            this.iconUrl = iconUrl;
            this.size = size;
        }
    }

    /** @return the parsed request, or {@code null} if this isn't a NOTI_ADDITIONAL_INFO request. */
    public static Request parseRequest(byte[] body) {
        if (body.length < 1 || (body[0] & 0x40) != 0 || (body[0] & 0x3F) != MSG_ID_ADDITIONAL_INFO) {
            return null;
        }
        int off = 1;
        String iconUrl = null;
        Integer size = null;
        while (off < body.length) {
            int id = body[off] & 0xFF;
            if (id == PARAM_IMAGE_URL && off + 1 < body.length) {
                int len = body[off + 1] & 0xFF;
                if (off + 2 + len > body.length) {
                    break;
                }
                iconUrl = new String(body, off + 2, len, UTF8);
                off += 2 + len;
            } else if (id == PARAM_ICON_SIZE && off + 1 < body.length) {
                size = body[off + 1] & 0xFF;
                off += 2;
            } else {
                break; // unknown/short param - stop clean rather than misreading a value as an id
            }
        }
        if (iconUrl == null || size == null) {
            return null;
        }
        return new Request(iconUrl, size);
    }

    /** Build the full (un-chunked) {@code NOTI_ADDITIONAL_INFO} response body. */
    public static byte[] buildResponse(String iconUrl, int size, byte[] rgbaBytes) {
        byte[] urlBytes = iconUrl.getBytes(UTF8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x40 | MSG_ID_ADDITIONAL_INFO); // fixed, response
        out.write(PARAM_IMAGE_URL);
        out.write(urlBytes.length);
        out.write(urlBytes, 0, urlBytes.length);
        out.write(PARAM_IMAGE);
        out.write(rgbaBytes.length & 0xFF);
        out.write((rgbaBytes.length >> 8) & 0xFF);
        out.write((rgbaBytes.length >> 16) & 0xFF);
        out.write((rgbaBytes.length >> 24) & 0xFF);
        out.write(rgbaBytes, 0, rgbaBytes.length);
        out.write(PARAM_ICON_SIZE);
        out.write(size & 0xFF);
        out.write(0);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    /**
     * Render an installed app's icon to the wire pixel format.
     *
     * @return the raw bytes, or {@code null} if the package/icon can't be resolved.
     */
    public static byte[] renderIcon(Context context, String pkg, int size) {
        Drawable drawable = NotificationUtils.getAppIcon(context, pkg);
        if (drawable == null) {
            return null;
        }
        Bitmap bitmap = BitmapUtil.convert(BitmapUtil.toBitmap(drawable), Bitmap.Config.ARGB_8888, size, size);
        byte[] out = new byte[size * size * 4];
        int i = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int pixel = bitmap.getPixel(x, y);
                out[i] = (byte) ((pixel >> 16) & 0xFF);              // R
                out[i + 1] = (byte) ((pixel >> 8) & 0xFF);           // G
                out[i + 2] = (byte) (pixel & 0xFF);                  // B
                out[i + 3] = (byte) (255 - ((pixel >> 24) & 0xFF));  // inverted alpha
                i += 4;
            }
        }
        return out;
    }
}
