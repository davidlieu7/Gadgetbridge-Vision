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
 * Request and reply for the band battery state, on {@code CH_SETTINGS} (0x0B),
 * {@code BATTERY_INFO} (MSG_ID 2).
 *
 * <p>Request and response share the MSG_ID and differ only in the header Type
 * bit (bit6): request {@code 0x02}, response {@code 0x42}.
 *
 * <pre>
 *   request  : 02                fixed, request,  MSG_ID 2   (bare header, no params)
 *   response : 42 06 00 05 29    fixed, response, MSG_ID 2
 *                 06 00              param 0x06 = 0x00  charging (0 = no, 1 = yes)
 *                 05 29              param 0x05 = 0x29  level 41 %
 * </pre>
 *
 * The response body is a fixed-format flat list of {@code [param_id:1][value:1]}
 * pairs. Both battery params are 1 byte here; do NOT borrow widths from the
 * settings table (there param 0x05 is 2 bytes) — param ids are keyed on
 * (MSG_ID, param_id), not global.
 */
public final class Fit3BatteryInfo {

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_SETTINGS;

    public static final int MSG_ID_BATTERY = 0x02;
    private static final int PARAM_LEVEL    = 0x05;
    private static final int PARAM_CHARGING = 0x06;

    // ---- request ----

    /**
     * Body for a battery read. Equivalent to the raw byte {@code {0x02}}, but
     * built through {@link SapPayloadBuilder} so the header semantics
     * (fixed / request / MSG_ID 2) stay explicit and symmetric with {@link #parse}.
     *
     * <p>Send with e.g.
     * {@code sendRawMessage(Fit3BatteryInfo.CHANNEL, Fit3BatteryInfo.buildRequest(), "battery-request");}
     */
    public static byte[] buildRequest() {
        return SapPayloadBuilder.fixed(MSG_ID_BATTERY).build();
    }

    // ---- response ----

    /** Battery percentage, or -1 if the reply carried no level param. */
    public final int level;
    public final boolean charging;

    private Fit3BatteryInfo(int level, boolean charging) {
        this.level = level;
        this.charging = charging;
    }

    /** @return decoded info, or {@code null} if this body is not a BATTERY_INFO reply. */
    public static Fit3BatteryInfo parse(byte[] body) {
        SapPayloadParser p = new SapPayloadParser(body);
        if (p.msgId != MSG_ID_BATTERY || p.variable || !p.response) {
            return null; // not the fixed BATTERY_INFO reply we expect
        }

        int level = -1;
        boolean charging = false;
        // Fixed body: walk [param_id][value:1] pairs to the end. Param widths are
        // schema-defined, not tagged on the wire, so an id we don't know is an id
        // whose width we don't know either - keep what was decoded so far and stop
        // rather than guessing 1 byte and desyncing the rest of the walk (same rule
        // as Fit3InitSetting.parse).
        while (p.remaining() >= 2) {
            int id = p.u8();
            if (id != PARAM_LEVEL && id != PARAM_CHARGING) {
                break;
            }
            int value = p.u8();
            if (id == PARAM_LEVEL) {
                level = value;
            } else {
                charging = value != 0;
            }
        }
        return new Fit3BatteryInfo(level, charging);
    }

    @Override
    public String toString() {
        return "Battery{" + level + "%" + (charging ? ", charging" : "") + "}";
    }
}
