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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Notification;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3NotificationAction;

/**
 * Notification actions from the band and the phone's answers, against
 * vendor captures, and how a reply is delivered.
 */
public class Fit3NotificationActionTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static String hexOf(String utf8) {
        StringBuilder sb = new StringBuilder();
        for (byte b : utf8.getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ---- notification actions ----

    @Test
    public void dismissOnBand() {
        Fit3NotificationAction.Action a = Fit3NotificationAction.parse(hex("02 01 01000000"));
        assertNotNull(a);
        assertEquals(Fit3NotificationAction.Kind.DISMISS, a.kind);
        assertEquals(1, a.sequenceId);
        assertArrayEquals(hex("42 01 01000000 0b 01"), Fit3NotificationAction.buildResponse(a, true));
    }

    @Test
    public void clearAllOnBand() {
        Fit3NotificationAction.Action a = Fit3NotificationAction.parse(hex("09"));
        assertNotNull(a);
        assertEquals(Fit3NotificationAction.Kind.CLEAR_ALL, a.kind);
        assertArrayEquals("captured answer", hex("49 0b 01"), Fit3NotificationAction.buildResponse(a, true));
    }

    @Test
    public void quickReplyFromBand() {
        String reply = "Да, пожалуйста";
        Fit3NotificationAction.Action a = Fit3NotificationAction.parse(
                hex("8a 02 01 03000000 0a 1a " + hexOf(reply)));
        assertNotNull(a);
        assertEquals(Fit3NotificationAction.Kind.REPLY, a.kind);
        assertEquals(3, a.sequenceId);
        assertEquals(reply, a.reply);
        assertArrayEquals("captured answer", hex("4a 01 03000000 0b 01"),
                Fit3NotificationAction.buildResponse(a, true));
    }

    @Test
    public void openAndLaunchGetNoAnswer() {
        Fit3NotificationAction.Action open = Fit3NotificationAction.parse(hex("04 01 2a000000"));
        assertNotNull(open);
        assertEquals(Fit3NotificationAction.Kind.OPEN, open.kind);
        assertEquals(42, open.sequenceId);
        assertNull(Fit3NotificationAction.buildResponse(open, true));

        Fit3NotificationAction.Action launch = Fit3NotificationAction.parse(hex("87 01 06 03 323037"));
        assertNotNull(launch);
        assertEquals(Fit3NotificationAction.Kind.LAUNCH_APP, launch.kind);
        assertEquals("207", launch.appId);
    }

    @Test
    public void deletedOnPhoneAndNonActions() {
        assertArrayEquals(hex("01 01 2a000000"), Fit3NotificationAction.buildDeletedOnPhone(42));
        assertNull("a phone-side answer is not a band action", Fit3NotificationAction.parse(hex("42 01 01000000 0b 01")));
        assertNull("icon fetch is handled elsewhere", Fit3NotificationAction.parse(hex("03 09 00 23 34")));
    }

    @Test
    public void smsRepliesGoToTheNumberNotTheSyntheticHandle() {
        NotificationSpec sms =
                new NotificationSpec(7);
        sms.setPhoneNumber("+10000000000");
        ArrayList<NotificationSpec.Action> actions = new ArrayList<>();
        NotificationSpec.Action synthetic =
                new NotificationSpec.Action();
        synthetic.setType(NotificationSpec.Action.TYPE_SYNTHETIC_REPLY_PHONENR);
        actions.add(synthetic);
        sms.setAttachedActions(actions);
        assertEquals("+10000000000", Fit3Notification.replyPhoneNumber(sms));
        assertNull("handle 0 is no handle", Fit3Notification.replyActionHandle(sms));
        assertTrue(Fit3Notification.canReply(sms));

        NotificationSpec chat =
                new NotificationSpec(8);
        ArrayList<NotificationSpec.Action> chatActions = new ArrayList<>();
        NotificationSpec.Action wearable =
                new NotificationSpec.Action();
        wearable.setType(NotificationSpec.Action.TYPE_WEARABLE_REPLY);
        wearable.setHandle((8L << 4) + 2);
        chatActions.add(wearable);
        chat.setAttachedActions(chatActions);
        assertNull(Fit3Notification.replyPhoneNumber(chat));
        assertEquals(Long.valueOf((8L << 4) + 2),
                Fit3Notification.replyActionHandle(chat));
    }

}
