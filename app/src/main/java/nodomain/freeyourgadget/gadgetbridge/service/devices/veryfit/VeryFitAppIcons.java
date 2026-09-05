/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.veryfit;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Queue;

import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitPacker;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitPicture;
import nodomain.freeyourgadget.gadgetbridge.util.BitmapUtil;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.NotificationUtils;

/**
 * The icon the watch draws beside a notification, one per app it holds a switch for.
 * <p>
 * The watch is the one to say what it wants: an app is named, the answer gives the size and the
 * format to draw it at, and the picture then goes over the file channel under a name that is the
 * same for every app. Nothing in the file says which app it belongs to — what settles that is the
 * question asked a moment earlier, so they can only be sent one at a time.
 */
class VeryFitAppIcons {
    private static final Logger LOG = LoggerFactory.getLogger(VeryFitAppIcons.class);

    private final VeryFitSupport support;
    private final Queue<String> queue = new ArrayDeque<>();
    private String current;

    VeryFitAppIcons(final VeryFitSupport support) {
        this.support = support;
    }

    boolean isRunning() {
        return current != null;
    }

    void reset() {
        queue.clear();
        current = null;
    }

    /** Starts over on the apps the settings screen has ticked, taking them one at a time. */
    void upload(final Collection<String> packages) {
        reset();
        queue.addAll(packages);
        LOG.info("Sending the icons of {} apps", queue.size());
        next();
    }

    private void next() {
        current = queue.poll();
        if (current == null) {
            support.onAppIconsFinished();
            return;
        }
        support.queryAppIcon(VeryFitSupport.appId(current));
    }

    /** What the watch wants drawn, and for which app; it names one only after being asked. */
    void onParameters(final byte[] payload) {
        if (current == null || payload.length < VeryFitConstants.ICON_PARAMS_LEN) {
            return;
        }
        if (payload[0] != VeryFitConstants.ICON_OK) {
            LOG.warn("Watch will take no icon for {}: {}", current, GB.hexdump(payload));
            next();
            return;
        }
        if (readShort(payload, VeryFitConstants.ICON_APP_OFFSET) != VeryFitSupport.appId(current)) {
            LOG.warn("Icon parameters for another app than {}", current);
            return;
        }

        final byte[] picture = draw(readShort(payload, VeryFitConstants.ICON_WIDTH_OFFSET),
                readShort(payload, VeryFitConstants.ICON_HEIGHT_OFFSET),
                payload[VeryFitConstants.ICON_FORMAT_OFFSET]);
        if (picture == null) {
            next();
            return;
        }
        support.sendAppIcon(VeryFitPacker.pack(picture, VeryFitConstants.ICON_BLOCK_LEN));
    }

    void onUploaded(final boolean success) {
        LOG.info("Icon for {} {}", current, success ? "taken" : "refused");
        next();
    }

    private byte[] draw(final int width, final int height, final byte format) {
        final Drawable icon = NotificationUtils.getAppIcon(support.getContext(), current);
        if (icon == null || width <= 0 || height <= 0) {
            LOG.warn("Nothing to draw an icon for {} from", current);
            return null;
        }
        try {
            final Bitmap scaled = BitmapUtil.convert(BitmapUtil.toBitmap(icon),
                    Bitmap.Config.ARGB_8888, width, height);
            return VeryFitPicture.encode(scaled, format);
        } catch (final Exception e) {
            LOG.error("Failed to draw the icon of {}", current, e);
            return null;
        }
    }

    private static int readShort(final byte[] payload, final int offset) {
        return (payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8);
    }

}
