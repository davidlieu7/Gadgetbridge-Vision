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
 * A capability-check exchange on {@code CH_HEALTH} (0x0a), MSG_ID 0x22 (34) -
 * the band asks the phone to confirm a sequence number and the health
 * protocol version it should use. The response header is 0xe2 and the
 * sequence number param is 0xe0, followed by the health-protocol-version
 * param (0xe1) and the "S Health installed" tail (0xd0/0xd2). There is no
 * meaningful "supported version" concept on the Gadgetbridge side, so
 * whatever protocol version the band offers is echoed back unchanged.
 *
 * <p>In the real app, Galaxy Wearable (the band's general connection/sync
 * app) and Samsung Health (a separate app that actually consumes and
 * decodes the health data it receives) are independent - this capability
 * check is how the former tells the band whether the latter is installed
 * at all. Answering "not installed" here would tell the band nobody wants
 * its health data, which is the opposite of what Gadgetbridge needs - it
 * has to claim a (consuming) Samsung Health app is present, or the band may
 * skip sending the detailed sync entirely. The version code sent is one
 * observed from a real device's installed Samsung Health, reused here only
 * as a plausible-looking value - the band has no way to verify it and
 * Gadgetbridge has no real Samsung Health version of its own to report.
 *
 * <pre>
 *   request  (band -&gt; phone): a2 [count:1] { [e0 SEQUENCE_NUMBER:2 LE] | [e1 HEALTH_PROTOCOL_VERSION:2 LE] }*
 *   response (phone -&gt; band): e2 04 e0 [SEQUENCE_NUMBER, echoed] e1 [HEALTH_PROTOCOL_VERSION, echoed] d0 02 d2 [version:4 LE]
 * </pre>
 */
public final class Fit3HealthCapabilityCheck {

    public static final int CHANNEL = SapProtocol.CH_HEALTH;
    public static final int MSG_ID = 0x22;
    private static final int RES_HEADER = 0xe2;
    private static final int PARAM_SEQUENCE_NUMBER = 0xe0;
    private static final int PARAM_HEALTH_PROTOCOL_VERSION = 0xe1;
    private static final int PARAM_SHEALTH_INSTALLED = 0xd0;
    private static final int PARAM_SHEALTH_VERSION = 0xd2;
    // Observed from a real Samsung Health install - see the class doc.
    private static final int SHEALTH_VERSION_CODE = 6996523;

    private Fit3HealthCapabilityCheck() {
    }

    /** @return the reply to send back, or {@code null} if the expected params weren't found. */
    public static byte[] buildResponse(byte[] request) {
        if (request.length < 2) {
            return null;
        }
        int count = request[1] & 0xFF;
        int pos = 2;
        Integer sequenceNumber = null;
        Integer healthProtocolVersion = null;
        for (int i = 0; i < count && pos < request.length; i++) {
            int paramId = request[pos] & 0xFF;
            if (paramId == PARAM_SEQUENCE_NUMBER && pos + 3 <= request.length) {
                sequenceNumber = (request[pos + 1] & 0xFF) | ((request[pos + 2] & 0xFF) << 8);
                pos += 3;
            } else if (paramId == PARAM_HEALTH_PROTOCOL_VERSION && pos + 3 <= request.length) {
                healthProtocolVersion = (request[pos + 1] & 0xFF) | ((request[pos + 2] & 0xFF) << 8);
                pos += 3;
            } else {
                pos += 1;
            }
        }
        if (sequenceNumber == null || healthProtocolVersion == null) {
            return null;
        }
        return new byte[]{
                (byte) RES_HEADER,
                4, // param count
                (byte) PARAM_SEQUENCE_NUMBER,
                (byte) (int) sequenceNumber,
                (byte) (sequenceNumber >> 8),
                (byte) PARAM_HEALTH_PROTOCOL_VERSION,
                (byte) (int) healthProtocolVersion,
                (byte) (healthProtocolVersion >> 8),
                (byte) PARAM_SHEALTH_INSTALLED,
                2, // installed
                (byte) PARAM_SHEALTH_VERSION,
                (byte) SHEALTH_VERSION_CODE,
                (byte) (SHEALTH_VERSION_CODE >> 8),
                (byte) (SHEALTH_VERSION_CODE >> 16),
                (byte) (SHEALTH_VERSION_CODE >> 24),
        };
    }
}
