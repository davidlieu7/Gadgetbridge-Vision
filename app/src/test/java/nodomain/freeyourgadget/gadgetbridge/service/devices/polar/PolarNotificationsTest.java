/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventCallControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;

public class PolarNotificationsTest {
    private static PolarProto.PbPftpPnsDHNotificationResponse pressed(final int id, final PolarProto.PbPftpPnsDHAttributeType type) {
        return PolarProto.PbPftpPnsDHNotificationResponse.newBuilder()
                .setNotificationId(id)
                .addAttributes(PolarProto.PbPftpPnsDHAttribute.newBuilder().setType(type))
                .build();
    }

    private static NotificationSpec chatNotification() {
        final NotificationSpec spec = new NotificationSpec(42);
        spec.setType(NotificationType.SIGNAL);
        spec.setSourceName("Signal");
        spec.setTitle("Alice");
        spec.setBody("Hi");
        final ArrayList<NotificationSpec.Action> actions = new ArrayList<>();
        actions.add(new NotificationSpec.Action(NotificationSpec.Action.TYPE_WEARABLE_SIMPLE, 100, "Mark as read"));
        actions.add(new NotificationSpec.Action(NotificationSpec.Action.TYPE_WEARABLE_REPLY, 200, "Reply"));
        spec.setAttachedActions(actions);
        return spec;
    }

    @Test
    public void actionsThenCannedReplies() {
        final PolarNotifications notifications = new PolarNotifications();
        final PolarProto.PbPftpPnsHDNotification message = notifications.create(
                chatNotification(), Arrays.asList("Yes", "No", "Later", "Too many"), "Delete");

        assertEquals(42, message.getNotificationId());
        assertEquals(PolarProto.Action.CREATED, message.getAction());
        assertEquals(PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_SOCIAL, message.getCategoryId());

        final List<String> labels = new ArrayList<>();
        for (final PolarProto.PbPftpPnsHDAttribute attribute : message.getAttributesList()) {
            labels.add(attribute.getType() + "=" + attribute.getData());
        }
        // only four action slots: the plain action plus the first three canned replies
        assertEquals(Arrays.asList(
                "APPLICATION_NAME=Signal", "TITLE=Alice", "MESSAGE=Hi", "CLEAR_ACTION_LABEL=Delete",
                "A_ACTION_LABEL=Mark as read", "B_ACTION_LABEL=Yes", "C_ACTION_LABEL=No", "D_ACTION_LABEL=Later"
        ), labels);

        final GBDeviceEventNotificationControl plain = (GBDeviceEventNotificationControl) notifications.onResponse(
                pressed(42, PolarProto.PbPftpPnsDHAttributeType.A_ACTION));
        assertNotNull(plain);
        assertEquals(GBDeviceEventNotificationControl.Event.REPLY, plain.event);
        assertEquals(100, plain.handle);
        assertNull(plain.reply);

        final GBDeviceEventNotificationControl reply = (GBDeviceEventNotificationControl) notifications.onResponse(
                pressed(42, PolarProto.PbPftpPnsDHAttributeType.C_ACTION));
        assertNotNull(reply);
        assertEquals(GBDeviceEventNotificationControl.Event.REPLY, reply.event);
        assertEquals(200, reply.handle);
        assertEquals("No", reply.reply);

        final GBDeviceEventNotificationControl dismiss = (GBDeviceEventNotificationControl) notifications.onResponse(
                pressed(42, PolarProto.PbPftpPnsDHAttributeType.CLEAR_ACTION));
        assertNotNull(dismiss);
        assertEquals(GBDeviceEventNotificationControl.Event.DISMISS, dismiss.event);
        assertEquals(42, dismiss.handle);
    }

    @Test
    public void removeOnlyShownNotifications() {
        final PolarNotifications notifications = new PolarNotifications();
        notifications.create(chatNotification(), Collections.emptyList(), "Delete");

        final PolarProto.PbPftpPnsHDNotification removal = notifications.remove(42, false);
        assertNotNull(removal);
        assertEquals(PolarProto.Action.REMOVED, removal.getAction());
        assertTrue(removal.hasIssueTime());
        assertNull(notifications.remove(42, false));
        // e.g. left on the watch by Polar Flow
        assertNotNull(notifications.remove(1020471511, true));
        assertNull(notifications.onResponse(pressed(42, PolarProto.PbPftpPnsDHAttributeType.A_ACTION)));
    }

    @Test
    public void incomingCall() {
        final PolarNotifications notifications = new PolarNotifications();
        final CallSpec call = new CallSpec();
        call.setCommand(CallSpec.CALL_INCOMING);
        call.setName("Bob");
        call.setNumber("+49123");
        final PolarProto.PbPftpPnsHDNotification message = notifications.incomingCall(call, "Accept", "Reject");
        assertEquals(PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_INCOMINGCALL, message.getCategoryId());

        final GBDeviceEvent accept = notifications.onResponse(
                pressed(PolarNotifications.CALL_NOTIFICATION_ID, PolarProto.PbPftpPnsDHAttributeType.POSITIVE_ACTION));
        assertEquals(GBDeviceEventCallControl.Event.ACCEPT, ((GBDeviceEventCallControl) accept).event);
        final GBDeviceEvent reject = notifications.onResponse(
                pressed(PolarNotifications.CALL_NOTIFICATION_ID, PolarProto.PbPftpPnsDHAttributeType.NEGATIVE_ACTION));
        assertEquals(GBDeviceEventCallControl.Event.REJECT, ((GBDeviceEventCallControl) reject).event);
    }
}
