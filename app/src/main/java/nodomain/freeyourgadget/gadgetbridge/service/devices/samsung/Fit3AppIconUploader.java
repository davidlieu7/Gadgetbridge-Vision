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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3NotificationCapability;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3NotificationIcon;

/**
 * Pushes notification icons for the apps the user picked
 * ({@link DeviceSettingsPreferenceConst#PREF_UPLOAD_NOTIFICATIONS_APP_ICON})
 * to the band ahead of time, one at a time, each waiting for the band's ack
 * before the next. Only those apps get icons at all; notifications from
 * other apps go out without one.
 *
 * <p>Which icons the band holds is remembered across connections, keyed by
 * package and a checksum of the rendered pixels, so a reconnect only pushes
 * what's new or changed. An icon only counts as on the band once the band
 * has acked it - in practice with the chunked transfer's own ack (the
 * per-icon ack the protocol defines never arrives from the Fit3), or with
 * that per-icon ack where a band does send it - until then notifications for that app still ask
 * the band to fetch it (see {@link #isOnBand}), so nothing depends on the
 * push being honoured.
 */
public class Fit3AppIconUploader {
    private static final Logger LOG = LoggerFactory.getLogger(Fit3AppIconUploader.class);

    /** Device pref: {@code "<package>\n<crc>"} per icon the band has acked. */
    private static final String PREF_ICONS_ON_BAND = "samsung_fit3_notification_icons_on_band";
    /** Give up on one icon's ack after this long and move to the next. */
    private static final long ACK_TIMEOUT_MS = 15_000;

    public interface Listener {
        /**
         * Send a full notification-channel message, chunking it as needed.
         *
         * @return the chunked transfer's version (see {@link Fit3BigData#lastVersion}),
         *         or -1 if it went out unchunked
         */
        int sendNotificationPayload(byte[] body, String reason);

        Context getContext();

        String getDeviceAddress();
    }

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutTask = this::onTimeout;
    private final ArrayDeque<String> queue = new ArrayDeque<>();

    private int iconSize = Fit3NotificationCapability.DEFAULT_ICON_SIZE;
    private String currentPackage;
    private long currentCrc;
    private int currentVersion = -1;

    public Fit3AppIconUploader(Listener listener) {
        this.listener = listener;
    }

    /** The edge length icons are rendered at, from the band's capability reply. */
    public synchronized void setIconSize(int size) {
        if (size > 0) {
            iconSize = size;
        }
    }

    public synchronized int getIconSize() {
        return iconSize;
    }

    /** Whether notifications from {@code pkg} should carry an icon at all. */
    public boolean isSelected(String pkg) {
        return getSelected().contains(pkg);
    }

    /** Whether the band has acked {@code pkg}'s icon as stored. */
    public boolean isOnBand(String pkg) {
        String prefix = pkg + "\n";
        for (String entry : getOnBand()) {
            if (entry.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The selected package the band means by {@code iconUrl}, or {@code null}. */
    public String packageForIconUrl(String iconUrl) {
        for (String pkg : getSelected()) {
            if (Fit3NotificationIcon.iconUrlFor(pkg).equals(iconUrl)) {
                return pkg;
            }
        }
        return null;
    }

    /**
     * Queue every selected icon the band doesn't already hold in its current
     * form, and start pushing if idle.
     */
    public synchronized void sync() {
        Set<String> onBand = getOnBand();
        queue.clear();
        for (String pkg : getSelected()) {
            byte[] rgba = Fit3NotificationIcon.renderIcon(listener.getContext(), pkg, iconSize);
            if (rgba == null) {
                LOG.debug("Samsung Fit 3: no icon to push for {}", pkg);
                continue;
            }
            if (!onBand.contains(entry(pkg, crc(rgba)))) {
                queue.add(pkg);
            }
        }
        LOG.info("Samsung Fit 3: {} notification icon(s) to push at {}px", queue.size(), iconSize);
        if (currentPackage == null) {
            sendNext();
        }
    }

    /** The per-icon ack, for a band that sends one. */
    public synchronized void onAck(Fit3NotificationIcon.PushAck ack) {
        if (currentPackage == null || !Fit3NotificationIcon.iconUrlFor(currentPackage).equals(ack.iconUrl)) {
            LOG.debug("Samsung Fit 3: unexpected icon ack {}", ack);
            return;
        }
        finishCurrent(ack.stored);
    }

    /**
     * The chunked transfer's ack. Acks for other transfers on the channel
     * (icon fetch replies) carry other versions and are ignored.
     */
    public synchronized void onTransferAck(Fit3BigData.Ack ack) {
        if (currentPackage == null || currentVersion < 0 || ack.version != currentVersion) {
            return;
        }
        finishCurrent(ack.success);
    }

    private void finishCurrent(boolean stored) {
        handler.removeCallbacks(timeoutTask);
        if (stored) {
            markOnBand(currentPackage, currentCrc);
            LOG.debug("Samsung Fit 3: band took the icon for {}", currentPackage);
        } else {
            LOG.warn("Samsung Fit 3: band refused the icon for {}", currentPackage);
        }
        currentPackage = null;
        currentVersion = -1;
        sendNext();
    }

    /** Stop pushing, e.g. on disconnect. What was acked stays remembered. */
    public synchronized void cancel() {
        handler.removeCallbacks(timeoutTask);
        queue.clear();
        currentPackage = null;
        currentVersion = -1;
    }

    private void sendNext() {
        while (!queue.isEmpty()) {
            String pkg = queue.poll();
            byte[] rgba = Fit3NotificationIcon.renderIcon(listener.getContext(), pkg, iconSize);
            if (rgba == null) {
                continue;
            }
            currentPackage = pkg;
            currentCrc = crc(rgba);
            handler.postDelayed(timeoutTask, ACK_TIMEOUT_MS);
            currentVersion = listener.sendNotificationPayload(
                    Fit3NotificationIcon.buildPush(Fit3NotificationIcon.iconUrlFor(pkg), rgba),
                    "notification-icon-push");
            return;
        }
    }

    private synchronized void onTimeout() {
        if (currentPackage == null) {
            return;
        }
        LOG.warn("Samsung Fit 3: no ack for the icon of {}, moving on", currentPackage);
        currentPackage = null;
        currentVersion = -1;
        sendNext();
    }

    private Set<String> getSelected() {
        Set<String> selected = prefs().getStringSet(
                DeviceSettingsPreferenceConst.PREF_UPLOAD_NOTIFICATIONS_APP_ICON, null);
        return selected != null ? selected : Collections.emptySet();
    }

    private Set<String> getOnBand() {
        Set<String> onBand = prefs().getStringSet(PREF_ICONS_ON_BAND, null);
        return onBand != null ? onBand : Collections.emptySet();
    }

    private void markOnBand(String pkg, long crc) {
        // A copy: the set a SharedPreferences hands out must not be modified.
        Set<String> updated = new HashSet<>();
        String prefix = pkg + "\n";
        for (String e : getOnBand()) {
            if (!e.startsWith(prefix)) {
                updated.add(e);
            }
        }
        updated.add(entry(pkg, crc));
        prefs().edit().putStringSet(PREF_ICONS_ON_BAND, updated).apply();
    }

    private SharedPreferences prefs() {
        return GBApplication.getDeviceSpecificSharedPrefs(listener.getDeviceAddress());
    }

    private static String entry(String pkg, long crc) {
        return pkg + "\n" + Long.toHexString(crc);
    }

    private static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
