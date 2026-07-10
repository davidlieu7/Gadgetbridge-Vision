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

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Request and reply for the OOBE license-agreement step, on {@code CH_CONTROL}
 * (0x01), MSG_ID 4 - the 4th and last step of the setup handshake (after
 * watch-info, device-status and {@link Fit3InitSetting}). Once the band acks
 * this, channel 0x01 is considered fully up and the rest of the app's
 * channels can be brought online.
 *
 * <p>Like {@link Fit3InitSetting}, this is variable-format (header bit7=1)
 * but carries no param-count byte on the wire - see
 * {@link SapPayloadBuilder#variableNoCount}. The response has no payload at
 * all beyond the header: it's a pure acknowledgement.
 *
 * <pre>
 *   request  : 84 01 01     variable, request,  MSG_ID 4, param 1 = accepted (bool)
 *   response : c4           variable, response, MSG_ID 4, no params
 * </pre>
 */
public final class Fit3LicenseAgreement {

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_CONTROL;

    public static final int MSG_ID_LICENSE_AGREEMENT = 0x04;

    private static final int PARAM_ACCEPTED = 0x01;

    private Fit3LicenseAgreement() {
    }

    /**
     * @param accepted whether the license was accepted; normally this should
     *                 only be sent once the user has actually agreed to it in
     *                 the app's own setup flow, not unconditionally.
     */
    public static byte[] buildRequest(boolean accepted) {
        return SapPayloadBuilder.variableNoCount(MSG_ID_LICENSE_AGREEMENT)
                .addByte(PARAM_ACCEPTED, accepted ? 1 : 0)
                .build();
    }

    /** @return {@code true} if this body is a recognised MSG_ID 4 acknowledgement. */
    public static boolean isAck(byte[] body) {
        SapPayloadParser p = new SapPayloadParser(body, false); // no param-count byte, see class note
        return p.msgId == MSG_ID_LICENSE_AGREEMENT && p.variable && p.response;
    }
}
