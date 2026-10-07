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

import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Notification;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3NotificationCapability;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3NotificationIcon;

/**
 * Notification capability handshake and the icon push, plus the
 * notification push's optional icon reference.
 */
public class Fit3NotificationIconTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (haystack[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    public void capabilityRequestIsBareHeader() {
        assertArrayEquals(hex("0d"), Fit3NotificationCapability.buildRequest());
    }

    @Test
    public void parsesCapturedCapabilityReply() {
        Fit3NotificationCapability cap = Fit3NotificationCapability.parse(
                hex("4d 08 00 01 01 01 02 01 03 01 04 01 05 03 07 01 06 02 34 70"));
        assertNotNull(cap);
        assertTrue(cap.indicator);
        assertTrue(cap.quickReply);
        assertTrue(cap.body);
        assertEquals(3, cap.imageLevel);
        assertTrue(cap.whileWorn);
        assertArrayEquals(new int[]{52, 112}, cap.iconSizes);
        assertEquals(52, cap.iconSize());
        assertFalse("not advertised by this band", cap.iconSync);
    }

    @Test
    public void capabilityDefaultsWithoutSizes() {
        Fit3NotificationCapability cap = Fit3NotificationCapability.parse(hex("4d 01 08 01"));
        assertNotNull(cap);
        assertTrue(cap.iconSync);
        assertEquals(Fit3NotificationCapability.DEFAULT_ICON_SIZE, cap.iconSize());
        assertNull("a request is not the reply", Fit3NotificationCapability.parse(hex("0d")));
    }

    @Test
    public void pushFraming() {
        byte[] rgba = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] push = Fit3NotificationIcon.buildPush("APP_ICON_7", rgba);
        byte[] url = "APP_ICON_7".getBytes(StandardCharsets.US_ASCII);
        byte[] expected = new byte[1 + 2 + url.length + 5 + rgba.length];
        int i = 0;
        expected[i++] = 0x13;
        expected[i++] = 0x09;
        expected[i++] = (byte) url.length;
        System.arraycopy(url, 0, expected, i, url.length);
        i += url.length;
        expected[i++] = 0x07;
        expected[i++] = 8;
        expected[i++] = 0;
        expected[i++] = 0;
        expected[i++] = 0;
        System.arraycopy(rgba, 0, expected, i, rgba.length);
        assertArrayEquals(expected, push);
    }

    @Test
    public void parsesPushAck() {
        // 53 [09 "APP_ICON_7"] [0b 01]
        Fit3NotificationIcon.PushAck ack = Fit3NotificationIcon.parsePushAck(
                hex("53 09 0a 4150505f49434f4e5f37 0b 01"));
        assertNotNull(ack);
        assertEquals("APP_ICON_7", ack.iconUrl);
        assertTrue(ack.stored);

        Fit3NotificationIcon.PushAck refused = Fit3NotificationIcon.parsePushAck(
                hex("53 09 0a 4150505f49434f4e5f37 0b 00"));
        assertNotNull(refused);
        assertFalse(refused.stored);

        assertNull(Fit3NotificationIcon.parsePushAck(hex("13 09 0a 4150505f49434f4e5f37")));
    }

    @Test
    public void bigDataReportsTheVersionItsAckWillEcho() {
        Fit3BigData bigData = new Fit3BigData();
        bigData.chunk(new byte[10], 980);
        assertEquals("unchunked: no transfer ack to wait for", -1, bigData.lastVersion());
        bigData.chunk(new byte[2000], 980);
        assertEquals(0, bigData.lastVersion());
        bigData.chunk(new byte[2000], 980);
        assertEquals(1, bigData.lastVersion());
        // The band's ack for that transfer: 3e [(version << 3) | 1] 0b 00 00 00, as captured.
        Fit3BigData.Ack ack = Fit3BigData.parseAck(hex("3e 09 0b 00 00 00"));
        assertEquals(1, ack.version);
        assertTrue(ack.success);
    }

    @Test
    public void notificationIconReferenceIsOptional() {
        NotificationSpec spec = new NotificationSpec(42);
        spec.setTitle("Hi");
        String pkg = "org.example.app";
        byte[] urlParam = ("\u0009" + (char) Fit3NotificationIcon.iconUrlFor(pkg).length()
                + Fit3NotificationIcon.iconUrlFor(pkg)).getBytes(StandardCharsets.US_ASCII);

        byte[] without = Fit3Notification.buildRequest(spec, pkg, false, false);
        assertFalse("unpicked app: no IMAGE_URL", contains(without, urlParam));
        assertTrue("the package is still named", contains(without, pkg.getBytes(StandardCharsets.US_ASCII)));

        byte[] with = Fit3Notification.buildRequest(spec, pkg, true, false);
        assertTrue(contains(with, urlParam));
        assertFalse("no fetch flag", contains(with, hex("13 01 12")));

        byte[] fetch = Fit3Notification.buildRequest(spec, pkg, true, true);
        assertTrue("IMAGE_NEWLY_ADDED right before the package", contains(fetch, hex("13 01 12")));
    }
}
