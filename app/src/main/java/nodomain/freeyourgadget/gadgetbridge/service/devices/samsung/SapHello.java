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
 *   band -> phone:  68 14 04 01 04 01 03  "SAMSUNG_ACCESSARY__" (19 bytes @7)
 *                   <band addr, ':'-spliced, 16 bytes @26>  00 (@42)
 *                   <param block, 7 bytes @43>
 *                   "Galaxy Fit3 (.."@50  "SAMSUNG\0"@66  <zero>@82
 *                   <band addr, 6 raw bytes @98>  00 (@104)
 *
 *   phone -> band:  68 15 04 01 00 ff 03  "SAP_"<32 hex token>  (36 bytes @7)
 *                   <param block, 7 bytes @43, ECHOED from the band frame above>
 *                   <model@50>  <vendor@66>  <vendor@82>  <6-byte addr@98>  02
 * </pre>
 *
 * Confirmed against a real band-HELLO / phone-HELLO capture pair: the phone's
 * 7-byte param block at offset 43 is a byte-for-byte copy of the band's own
 * param block at the same offset in its opening frame — not a fixed value, so
 * it must be parsed out of the band's HELLO and echoed back verbatim (see
 * {@link #parseBandHello}).
 *
 * The band's identity block also carries its own BLE address twice: as 6 raw
 * bytes at offset 98, and — filling the gap its shorter 19-byte peer-id
 * string leaves before the fixed offset-43 param block — the same 6 bytes
 * with a literal ':' (0x3a) spliced between each one (i.e. built like
 * "AA:BB:CC:DD:EE:FF" but with raw address bytes instead of hex digits in
 * each slot). By symmetry the phone's own offset-98 field is this device's
 * identity; real captures show it filled with both all-zero and all-0xFF
 * values across different sessions, so the band evidently doesn't validate
 * it strictly. Android hides the true local adapter MAC from apps (API 23+
 * returns a dummy constant), so Gadgetbridge fills it with the paired
 * device's known BT address instead of a placeholder.
 *
 * The {@code SAP_} token is a 32-hex session id the phone generates; it differs
 * between sessions and does not appear to be derived from the band's frame, so a
 * random value works for a first connection.
 */
public final class SapHello {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final byte[] PHONE_HEADER = {0x68, 0x15, 0x04, 0x01, 0x00, (byte) 0xFF, 0x03};
    private static final int FRAME_LEN = 105;

    private static final int PARAM_OFF = 43;
    private static final int PARAM_LEN = 7;
    private static final int MODEL_OFF = 50;
    private static final int VENDOR_OFF = 66;
    private static final int VENDOR2_OFF = 82;
    private static final int FIELD_LEN = 16;
    private static final int ADDR_OFF = 98;
    private static final int ADDR_LEN = 6;
    private static final int TRAILER_OFF = 104;

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

    /** The fields pulled out of the band's opening HELLO, needed to build a correct reply. */
    public static final class BandHello {
        /** 7 bytes @43; echo this back verbatim in the phone's reply. */
        public final byte[] paramBlock;
        /** 6 bytes @98; the band's own BLE address. */
        public final byte[] address;
        /** e.g. "Galaxy Fit3 (97" — truncated by the fixed 16-byte field. */
        public final String modelName;
        /** e.g. "SAMSUNG". */
        public final String vendorName;

        private BandHello(byte[] paramBlock, byte[] address, String modelName, String vendorName) {
            this.paramBlock = paramBlock;
            this.address = address;
            this.modelName = modelName;
            this.vendorName = vendorName;
        }
    }

    /** Parse the band's opening HELLO frame. Returns null if it's too short to hold the fixed fields. */
    public static BandHello parseBandHello(byte[] frame) {
        if (!isBandHello(frame) || frame.length < FRAME_LEN) {
            return null;
        }
        byte[] paramBlock = new byte[PARAM_LEN];
        System.arraycopy(frame, PARAM_OFF, paramBlock, 0, PARAM_LEN);
        byte[] address = new byte[ADDR_LEN];
        System.arraycopy(frame, ADDR_OFF, address, 0, ADDR_LEN);
        return new BandHello(paramBlock, address, getField(frame, MODEL_OFF), getField(frame, VENDOR_OFF));
    }

    /**
     * Build the phone's HELLO reply. Send it raw (do NOT wrap in a SapFrame).
     *
     * @param bandHello     the band's opening HELLO, already parsed with {@link #parseBandHello}
     * @param token32Hex    32 upper-case hex chars, this session's id
     * @param phoneModel    this device's model string, announced to the band
     * @param phoneVendor   this device's vendor string, announced to the band
     * @param deviceAddress 6-byte BT address for the offset-98 identity field, or null to leave it zero
     */
    public static byte[] buildPhoneHello(BandHello bandHello, String token32Hex, String phoneModel,
                                          String phoneVendor, byte[] deviceAddress) {
        byte[] f = new byte[FRAME_LEN];
        System.arraycopy(PHONE_HEADER, 0, f, 0, PHONE_HEADER.length);

        byte[] sap = ("SAP_" + token32Hex).getBytes(ASCII);
        System.arraycopy(sap, 0, f, 7, Math.min(sap.length, 36));

        System.arraycopy(bandHello.paramBlock, 0, f, PARAM_OFF, PARAM_LEN);

        putField(f, MODEL_OFF, phoneModel);
        putField(f, VENDOR_OFF, phoneVendor);
        putField(f, VENDOR2_OFF, phoneVendor);

        if (deviceAddress != null && deviceAddress.length == ADDR_LEN) {
            System.arraycopy(deviceAddress, 0, f, ADDR_OFF, ADDR_LEN);
        }
        f[TRAILER_OFF] = 0x02;
        return f;
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

    /** Parse "AA:BB:CC:DD:EE:FF" into 6 raw bytes; null if malformed. */
    public static byte[] macStringToBytes(String mac) {
        if (mac == null) {
            return null;
        }
        String[] parts = mac.split(":");
        if (parts.length != ADDR_LEN) {
            return null;
        }
        byte[] out = new byte[ADDR_LEN];
        try {
            for (int i = 0; i < ADDR_LEN; i++) {
                out[i] = (byte) Integer.parseInt(parts[i], 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static void putField(byte[] f, int off, String value) {
        byte[] b = value.getBytes(ASCII);
        int n = Math.min(b.length, FIELD_LEN - 1);   // keep a NUL terminator inside the field
        System.arraycopy(b, 0, f, off, n);
    }

    private static String getField(byte[] f, int off) {
        int end = off;
        int limit = off + FIELD_LEN;
        while (end < limit && f[end] != 0) {
            end++;
        }
        return new String(f, off, end - off, ASCII);
    }
}
