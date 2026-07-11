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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * A device-info exchange on {@code CH_HEALTH} (0x0a), MSG_ID 0x20 (32) - the
 * band announces itself (device/model/manufacturer name as plain
 * length-prefixed ASCII params, e.g. {@code "Galaxy Fit3"}, {@code
 * "SM-R390"}, {@code "Samsung Electronics"}) and the phone answers with a
 * capability table. Live-capture evidence across two separate sessions
 * shows the phone's reply is byte-for-byte identical regardless of the
 * band's specific request content, so this is reproduced as a fixed
 * template rather than actually parsed and rebuilt - nothing in it appears
 * to depend on anything Gadgetbridge would need to compute (no user
 * profile, no per-device fields), just a fixed capability/settings
 * advertisement. Precedes {@link Fit3HealthRequestData}'s exchange and
 * that, in turn, precedes the band starting a bulk-transfer sync (see
 * {@code Fit3HealthSync}) - not replying to this at all may be why the band
 * never got that far.
 */
public final class Fit3HealthDeviceInfo {

    public static final int CHANNEL = SapProtocol.CH_HEALTH;
    public static final int MSG_ID = 0x20;

    // Captured verbatim from a live phone reply (2026-07-12); not decoded
    // field-by-field - see the class doc.
    private static final int[] RESPONSE = {
            0xe0, 0x0b, 0xa1, 0x04, 0x33, 0xc0, 0x01, 0xc1, 0x00, 0xc2, 0x02, 0xa2, 0x2b, 0xd0, 0x6a,
            0x00, 0xb1, 0x01, 0xae, 0x01, 0x04, 0x03, 0x0a, 0xaf, 0x07, 0x00, 0xb1, 0x01, 0xb2, 0x97,
            0x32, 0x15, 0x8e, 0x08, 0x16, 0x8e, 0x08, 0x14, 0x8e, 0x08, 0x2d, 0x8e, 0x08, 0x2e, 0x01,
            0x00, 0x2f, 0x01, 0x00, 0x31, 0x8e, 0x08, 0x32, 0x01, 0x00, 0x33, 0x01, 0x00, 0x30, 0x8e,
            0x08, 0x45, 0x07, 0x00, 0x46, 0x8e, 0x08, 0x47, 0x07, 0x00, 0x51, 0x07, 0x00, 0x52, 0x07,
            0x00, 0x3a, 0x8e, 0x08, 0x3b, 0x8e, 0x08, 0x1d, 0x8e, 0x08, 0x4c, 0x8e, 0x08, 0x4d, 0x8e,
            0x08, 0x4e, 0x8e, 0x08, 0x4f, 0x8e, 0x08, 0x50, 0x8e, 0x08, 0x3c, 0x07, 0x00, 0x3d, 0x01,
            0x00, 0x0e, 0x07, 0x00, 0x0f, 0x01, 0x00, 0x4b, 0x0e, 0x00, 0x53, 0x0e, 0x00, 0x20, 0x01,
            0x00, 0x1f, 0x01, 0x00, 0x2c, 0x01, 0x00, 0x07, 0x01, 0x00, 0x06, 0x01, 0x00, 0x37, 0x01,
            0x00, 0x09, 0x8e, 0x08, 0x39, 0x01, 0x00, 0x48, 0x23, 0x00, 0x49, 0x23, 0x00, 0x1c, 0x07,
            0x00, 0x03, 0x8e, 0x08, 0x23, 0x8e, 0x08, 0x40, 0x8e, 0x08, 0x41, 0x8e, 0x08, 0x18, 0x8e,
            0x08, 0x19, 0x8e, 0x08, 0x42, 0x8e, 0x08, 0x12, 0x01, 0x00, 0x28, 0x8e, 0x08, 0x3f, 0x10,
            0x27, 0xb3, 0x0d, 0x12, 0x06, 0xd0, 0x07, 0x01, 0x00,
    };

    private Fit3HealthDeviceInfo() {
    }

    /** @return the fixed reply - see the class doc for why this ignores {@code request}. */
    public static byte[] buildResponse(byte[] request) {
        byte[] response = new byte[RESPONSE.length];
        for (int i = 0; i < RESPONSE.length; i++) {
            response[i] = (byte) RESPONSE[i];
        }
        return response;
    }
}
