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

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3QuickReplies;

/**
 * The canned-reply lists, against a vendor capture.
 */
public class Fit3QuickRepliesTest {

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

    // ---- canned replies ----

    @Test
    public void callReplyListMatchesCapture() {
        byte[] captured = hex("0100030118d09dd0b0d0bfd0b8d188d0b8d182d0b520d0bcd0bdd0b52e0134d092d18b20d0bcd0bed0b6d0b5d182"
                + "d0b520d0bfd0b5d180d0b5d0b7d0b2d0bed0bdd0b8d182d18c20d0bfd0bed0b7d0b6d0b53f011ed09fd0b5d180d0b5"
                + "d0b7d0b2d0bed0bdd18e20d0bfd0bed0b7d0b6d0b52e");
        assertArrayEquals(captured, Fit3QuickReplies.buildList(Fit3QuickReplies.MSG_ID_CALL_REPLIES, Arrays.asList(
                "Напишите мне.", "Вы можете перезвонить позже?", "Перезвоню позже.")));
        assertArrayEquals("captured switch", hex("02 03 01"), Fit3QuickReplies.buildEnabled(true));
        assertArrayEquals(hex("00 00 01 01 02 4f4b"),
                Fit3QuickReplies.buildList(Fit3QuickReplies.MSG_ID_NOTIFICATION_REPLIES, Collections.singletonList("OK")));
    }

}
