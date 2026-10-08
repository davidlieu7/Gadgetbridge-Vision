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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Sos;

public class Fit3SosTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    @Test
    public void settingsRequestMatchesTheCapture() {
        // No contacts, permission granted, version 1 as a 4-byte int.
        assertArrayEquals(hex("09 37 00 38 01 34 01000000"), Fit3Sos.buildSettingsRequest(false, true));
        assertArrayEquals(hex("09 37 01 38 01 34 01000000"), Fit3Sos.buildSettingsRequest(true, true));
    }

    @Test
    public void settingsReplyIsDecoded() {
        Fit3Sos reply = Fit3Sos.parse(hex("49 02 01 2c 01 2a 00 01 00 0a 02 34 01"));
        assertNotNull(reply);
        assertEquals(Fit3Sos.MSG_ID_GET_SETTINGS, reply.msgId);
        assertTrue(reply.response);
        assertEquals(Boolean.TRUE, reply.succeeded());
        assertEquals(Boolean.TRUE, reply.getBoolean(Fit3Sos.PARAM_COUNTDOWN));
        assertEquals(Boolean.FALSE, reply.getBoolean(Fit3Sos.PARAM_SEND_SOS));
        assertEquals(Boolean.FALSE, reply.getBoolean(Fit3Sos.PARAM_FALL_DETECTION));
        assertEquals(Integer.valueOf(2), reply.get(Fit3Sos.PARAM_WHEN_TO_DETECT));
    }

    @Test
    public void writesMatchTheCapture() {
        assertArrayEquals(hex("16 37 01"), Fit3Sos.hasContacts(true));
        assertArrayEquals(hex("1a 2a 01"), Fit3Sos.sendSos(true));
        assertArrayEquals(hex("01 01 01"), Fit3Sos.fallDetection(true));
        assertArrayEquals(hex("1b 2c 00"), Fit3Sos.countdown(false));
        // Not captured: the mode goes out as an int.
        assertArrayEquals(hex("05 0a 01000000"), Fit3Sos.whenToDetect(1));
        assertArrayEquals(hex("05 0a 02000000"), Fit3Sos.whenToDetect(9));
    }

    @Test
    public void writeRepliesCarryTheResult() {
        Fit3Sos ok = Fit3Sos.parse(hex("5a 02 01"));
        assertNotNull(ok);
        assertEquals(Fit3Sos.MSG_ID_SEND_SOS, ok.msgId);
        assertEquals(Boolean.TRUE, ok.succeeded());
        Fit3Sos refused = Fit3Sos.parse(hex("41 02 00"));
        assertNotNull(refused);
        assertEquals(Boolean.FALSE, refused.succeeded());
    }

    @Test
    public void bandSideChangeIsARequestAndIsAnsweredWithSuccess() {
        Fit3Sos change = Fit3Sos.parse(hex("01 01 01"));
        assertNotNull(change);
        assertFalse(change.response);
        assertNull(change.succeeded());
        assertEquals(Boolean.TRUE, change.getBoolean(Fit3Sos.PARAM_FALL_DETECTION));
        assertArrayEquals(hex("41 02 01"), Fit3Sos.buildResult(Fit3Sos.MSG_ID_FALL_DETECTION));
    }

    /** A fall triggered from the band's developer menu, then cancelled. */
    @Test
    public void alertRaisedAndCancelled() {
        Fit3Sos raised = Fit3Sos.parse(hex("13 36 01 39 01"));
        assertNotNull(raised);
        assertEquals(Fit3Sos.MSG_ID_ALERT_RAISED, raised.msgId);
        assertFalse(raised.response);
        assertEquals(Integer.valueOf(Fit3Sos.REASON_FALL), raised.get(Fit3Sos.PARAM_REASON));
        assertTrue(raised.getBoolean(Fit3Sos.PARAM_USER_RESPONDED));
        assertArrayEquals(hex("53 02 01"), Fit3Sos.buildResult(Fit3Sos.MSG_ID_ALERT_RAISED));

        Fit3Sos cancelled = Fit3Sos.parse(hex("14"));
        assertNotNull(cancelled);
        assertEquals(Fit3Sos.MSG_ID_ALERT_CANCELLED, cancelled.msgId);
        assertArrayEquals(hex("54 02 01"), Fit3Sos.buildResult(Fit3Sos.MSG_ID_ALERT_CANCELLED));

        // The band's ack of one of our texts.
        Fit3Sos textAck = Fit3Sos.parse(hex("55 02 01"));
        assertNotNull(textAck);
        assertTrue(textAck.succeeded());
    }

    @Test
    public void alertText() {
        // Captured: "Экстренная отправка остановлена", 60 UTF-8 bytes.
        byte[] body = Fit3Sos.buildAlertText("Экстренная отправка остановлена");
        assertArrayEquals(hex("15 35 3c"), java.util.Arrays.copyOf(body, 3));
        assertEquals(63, body.length);
    }

    @Test
    public void alertTextIsCutOnACharacterBoundary() {
        // 200 two-byte characters = 400 bytes; 255 would split one.
        String text = new String(new char[200]).replace('\0', 'ж');
        byte[] body = Fit3Sos.buildAlertText(text);
        assertEquals(254, body[2] & 0xFF);
        assertEquals(3 + 254, body.length);
    }
}
