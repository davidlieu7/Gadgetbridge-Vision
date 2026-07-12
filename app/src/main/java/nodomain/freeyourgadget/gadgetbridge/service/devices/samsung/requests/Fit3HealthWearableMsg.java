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
 * {@code WEARABLE_MSG} on {@code CH_HEALTH} (0x0a) - request header 0x04,
 * response header 0x44, same MSG_ID with the type bit flipped like {@link
 * Fit3HealthRequestData}. Unlike that one, the reply here isn't a
 * contiguous echoed span - two independent live-capture samples of this
 * exact device both showed the reply picking specific byte offsets out of
 * the 22-byte request and dropping the rest, not a generic re-encoding:
 *
 * <pre>
 *   request  (band -&gt; phone, 22B): 04 e0 SEQ 27 xx xx xx xx xx xx xx xx xx A xx B C xx D E F G H
 *   response (phone -&gt; band, 12B): 44 e0 SEQ 27 A    B  C  H  G  D  E  F
 * </pre>
 *
 * (offsets: request bytes 13,15,16,17,18,19,20,21 map to response bytes
 * 4,5,6,9,10,11,7,8 respectively). Reconstructed positionally from live
 * capture samples - this is a "reproduce what was
 * observed" implementation, not a semantically understood one.
 *
 * <p>A longer (46-byte) variant of the request also occurs, carrying a
 * workout's {@code datauuid} as ASCII plus trailing params. It uses the
 * <em>same</em> reply mapping (all the referenced offsets are &le; 21, i.e.
 * before the extra payload), and - crucially - replying to it is what makes
 * the band release the corresponding workout ({@code EXERCISE}) bulk object;
 * leaving it unanswered stalls workout sync. So any request of at least the
 * base length is answered with the same offset mapping.
 */
public final class Fit3HealthWearableMsg {

    public static final int CHANNEL = SapProtocol.CH_HEALTH;
    public static final int MSG_ID = 0x04;
    private static final int RES_HEADER = 0x44;
    private static final int MIN_REQUEST_LEN = 22;

    private Fit3HealthWearableMsg() {
    }

    /** @return the reply to send back, or {@code null} if {@code request} is too short to map. */
    public static byte[] buildResponse(byte[] request) {
        if (request.length < MIN_REQUEST_LEN) {
            return null;
        }
        return new byte[]{
                (byte) RES_HEADER,
                request[1],
                request[2],
                request[3],
                request[13],
                request[15],
                request[16],
                request[20],
                request[21],
                request[17],
                request[18],
                request[19],
        };
    }
}
