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

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Random;

/**
 * The SAP HELLO handshake — the first thing exchanged after the notify
 * subscription, and a prerequisite for the band to leave its "Continue on the
 * phone" setup screen.
 *
 * These frames are NOT CRC-wrapped like normal SAP frames: they are a fixed
 * 105-byte layout starting with 0x68, written/received as a raw ATT value.
 *
 * <pre>
 *   band -> phone:  68 14 04 01 04 01 03  "SAMSUNG_ACCESSARY__" <bin>  <params>
 *                   "Galaxy Fit3 (..)\0"  "SAMSUNG\0"  ...  <trailer>
 *   phone -> band:  68 15 04 01 00 ff 03  "SAP_"<32 hex token>         <params>
 *                   <model@50>  <vendor@66>  <vendor@82>  <6-byte addr@98>  02
 *
 *   offsets (phone frame): 0    header (7 bytes)
 *                          7    "SAP_" + 32 upper-hex chars   (36 bytes)
 *                          43   param block 01 01 f4 00 00 03 d4 (echoed from band)
 *                          50   model string  (16-byte field, NUL padded)
 *                          66   vendor string (16-byte field)
 *                          82   vendor string (16-byte field, repeated)
 *                          98   6-byte address field (observed 00.. or ff..)
 *                          104  trailer 0x02
 * </pre>
 *
 * The {@code SAP_} token is a 32-hex session id the phone generates; it differs
 * between sessions and does not appear to be derived from the band's frame, so a
 * random value works for a first connection. The 7-byte param block was constant
 * across captures (0x01f4 = 500 looks like a max-message hint); we echo it.
 */
public final class SapHello {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final byte[] PHONE_HEADER = {0x68, 0x15, 0x04, 0x01, 0x00, (byte) 0xFF, 0x03};
    private static final byte[] PARAM_BLOCK = {0x01, 0x01, (byte) 0xF4, 0x00, 0x00, 0x03, (byte) 0xD4};
    private static final int FRAME_LEN = 105;

    private SapHello() {
    }

    /** True if a received raw ATT value is a SAP HELLO frame. */
    public static boolean isHello(byte[] frame) {
        return frame != null && frame.length >= 1 && (frame[0] & 0xFF) == 0x68;
    }

    /** True specifically for the band's opening HELLO (role byte 0x14). */
    public static boolean isBandHello(byte[] frame) {
        return isHello(frame) && frame.length >= 2 && (frame[1] & 0xFF) == 0x14;
    }

    /** Build the phone's HELLO reply. Send it raw (do NOT wrap in a SapFrame). */
    public static byte[] buildPhoneHello(String token32Hex, String phoneModel, String phoneVendor) {
        byte[] f = new byte[FRAME_LEN];
        System.arraycopy(PHONE_HEADER, 0, f, 0, PHONE_HEADER.length);

        byte[] sap = ("SAP_" + token32Hex).getBytes(ASCII);
        System.arraycopy(sap, 0, f, 7, Math.min(sap.length, 36));

        System.arraycopy(PARAM_BLOCK, 0, f, 43, PARAM_BLOCK.length);

        putField(f, 50, phoneModel);
        putField(f, 66, phoneVendor);
        putField(f, 82, phoneVendor);

        // f[98..103] address field left zero; f[104] trailer.
        f[104] = 0x02;
        return f;
    }

    /** Convenience: reply with a freshly generated session token. */
    public static byte[] buildPhoneHello(String phoneModel, String phoneVendor) {
        return buildPhoneHello(randomToken(), phoneModel, phoneVendor);
    }

    /** 32 upper-case hex characters (16 random bytes). */
    public static String randomToken() {
        byte[] r = new byte[16];
        new Random().nextBytes(r);   // a session id, not a security key
        StringBuilder sb = new StringBuilder(32);
        for (byte b : r) {
            sb.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static void putField(byte[] f, int off, String value) {
        byte[] b = value.getBytes(ASCII);
        int n = Math.min(b.length, 15);   // keep a NUL terminator inside the 16-byte field
        System.arraycopy(b, 0, f, off, n);
    }
}
