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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitConstants;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;

/**
 * The replies the watch offers under a notification, and what it takes to send the one picked.
 * <p>
 * The watch holds the texts but reports a pick by slot number and notification id only, so the
 * list is read back from the same settings it was sent from, and the notifications sent lately
 * are kept around to find the one being answered. A pick names no notification at all when it
 * answers a call, and the number then comes from the call the phone last reported.
 */
class VeryFitCannedReplies {
    private static final Logger LOG = LoggerFactory.getLogger(VeryFitCannedReplies.class);

    private static final String PREF_REPLY = "canned_reply_";
    private static final int PREF_REPLY_SLOTS = 16;
    private static final int REMEMBERED = 10;
    /** The watch answers the outcome with the pick again; that is not a second pick. */
    private static final long ECHO_WINDOW_MS = 3000;

    private final VeryFitSupport support;
    private final Map<Integer, NotificationSpec> notifications = new LinkedHashMap<Integer, NotificationSpec>() {
        @Override
        protected boolean removeEldestEntry(final Entry<Integer, NotificationSpec> eldest) {
            return size() > REMEMBERED;
        }
    };
    private String callNumber;
    private byte[] lastPick;
    private long lastPickTime;

    VeryFitCannedReplies(final VeryFitSupport support) {
        this.support = support;
    }

    /** The list as the settings screen holds it, empty slots left out, which is how it was sent. */
    static List<String> fromPrefs(final DevicePrefs prefs) {
        final List<String> replies = new ArrayList<>();
        for (int i = 1; i <= PREF_REPLY_SLOTS && replies.size() < VeryFitConstants.REPLY_SLOTS; i++) {
            final String reply = prefs.getString(PREF_REPLY + i, null);
            if (!StringUtils.isNullOrEmpty(reply)) {
                replies.add(reply);
            }
        }
        return replies;
    }

    void remember(final NotificationSpec spec) {
        notifications.put(spec.getId(), spec);
    }

    void onCallState(final CallSpec spec) {
        if (spec.command == CallSpec.CALL_INCOMING) {
            callNumber = spec.number;
        } else if (spec.command == CallSpec.CALL_END || spec.command == CallSpec.CALL_REJECT) {
            callNumber = null;
        }
    }

    void onPick(final byte[] payload) {
        if (payload.length < VeryFitConstants.LINK_REPLY_LEN) {
            LOG.debug("Reply outcome acknowledged: {}", GB.hexdump(payload));
            return;
        }
        final byte[] pick = Arrays.copyOf(payload, VeryFitConstants.LINK_REPLY_LEN);
        final long now = System.currentTimeMillis();
        if (Arrays.equals(pick, lastPick) && now - lastPickTime < ECHO_WINDOW_MS) {
            return;
        }
        lastPick = pick;
        lastPickTime = now;

        final int id = (pick[2] & 0xff) | ((pick[3] & 0xff) << 8) | ((pick[4] & 0xff) << 16) | ((pick[5] & 0xff) << 24);
        final int slot = pick[6] & 0xff;
        final List<String> replies = fromPrefs(support.getDevicePrefs());
        if (slot < 1 || slot > replies.size()) {
            LOG.warn("Watch picked reply {} of {}", slot, replies.size());
            support.sendReplyOutcome(false, pick);
            return;
        }
        final String reply = replies.get(slot - 1);
        LOG.info("Watch picked reply {} for notification {}", slot, id);

        final GBDeviceEventNotificationControl event = new GBDeviceEventNotificationControl();
        event.event = GBDeviceEventNotificationControl.Event.REPLY;
        event.reply = reply;
        if (id == 0) {
            if (callNumber == null) {
                LOG.warn("No call to answer with a reply");
                support.sendReplyOutcome(false, pick);
                return;
            }
            event.handle = -1;
            event.phoneNumber = callNumber;
        } else if (!address(event, notifications.get(id))) {
            LOG.warn("Notification {} is not one that can be replied to", id);
            support.sendReplyOutcome(false, pick);
            return;
        }
        support.evaluateGBDeviceEvent(event);
        support.sendReplyOutcome(true, pick);
    }

    /** A message from a number is answered by number, anything else through its reply action. */
    private static boolean address(final GBDeviceEventNotificationControl event, final NotificationSpec spec) {
        if (spec == null) {
            return false;
        }
        event.handle = spec.getId();
        if ((spec.type == NotificationType.GENERIC_SMS || spec.type == NotificationType.GENERIC_PHONE)
                && spec.phoneNumber != null) {
            event.phoneNumber = spec.phoneNumber;
            return true;
        }
        if (spec.attachedActions != null) {
            for (final NotificationSpec.Action action : spec.attachedActions) {
                if (action.isReply()) {
                    event.handle = action.handle;
                    return true;
                }
            }
        }
        return false;
    }
}
