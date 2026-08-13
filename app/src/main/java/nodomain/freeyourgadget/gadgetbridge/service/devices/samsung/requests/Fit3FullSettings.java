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

import java.util.LinkedHashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * The whole settings state in one message, on {@code CH_SETTINGS} (0x0b),
 * MSG_ID 32. Asking for this once per
 * connection is how the host learns what the band is actually set to, instead
 * of assuming its own defaults; {@link Fit3Settings} then writes single params
 * back as the user changes them.
 *
 * <pre>
 *   request  (phone -&gt; band): 20                    fixed, request, MSG_ID 32, no params
 *   response (band -&gt; phone): 60 [count:1] { [id:1][value:1 or 2 LE] }*
 * </pre>
 *
 * <p>Note the response header: {@code 0x60} is <b>fixed</b> format (bit7 clear)
 * and yet a param-count byte follows anyway - the mirror image of the channels
 * that set the variable bit and then omit the count. Neither the format bit nor
 * this class's own header check can tell you whether the count is there; for
 * MSG_ID 32 it always is, so the parser is told so explicitly.
 *
 * <p>Value widths are schema-defined per param id, not tagged on the wire:
 * {@link #PARAM_WIDTHS} is the band's own table, transcribed from the vendor
 * app's {@code FIT3_PACKET_FULL_SETTING_INFO_RESPONSE} descriptor - note it is
 * model-specific there, so these widths are the Fit3's, not a family-wide rule.
 * A live band's 22-param reply consumed its body exactly, which is a good check
 * on the whole table at once.
 *
 * <p>The param ids are the same ones the single-setting writes use, but the
 * groups they belong to are flattened here: 0-4 vibration (MSG_ID 3), 5-6
 * vibration patterns (4), 7 brightness (5), 8-13 display (6), 14-15 timeouts
 * (7), 16-18 advanced (8), 19 sync-modes (9), 20-21 general (10).
 *
 * <p>Confirmed against a live band: the {@code 20} request draws a 22-param
 * reply covering every id from 0 to 21.
 */
public final class Fit3FullSettings {

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_SETTINGS;

    public static final int MSG_ID = 32;

    // Param ids worth reading back - the rest are parsed but have no
    // Gadgetbridge preference to prime (yet).
    public static final int PARAM_MUTE = 0;
    public static final int PARAM_LONG_VIBRATION = 1;
    public static final int PARAM_VIBRATION_INTENSITY = 2;
    public static final int PARAM_TOUCH_VIBRATION = 3;
    public static final int PARAM_CHARGING_VIBRATION = 4;
    public static final int PARAM_CALL_VIBRATION_PATTERN = 5;
    public static final int PARAM_NOTIFICATION_VIBRATION_PATTERN = 6;
    public static final int PARAM_BRIGHTNESS = 7;
    public static final int PARAM_AUTO_BRIGHTNESS = 8;
    public static final int PARAM_ALWAYS_ON_DISPLAY = 9;
    public static final int PARAM_RAISE_WRIST_WAKE = 10;
    public static final int PARAM_TOUCH_WAKE_UP = 11;
    public static final int PARAM_MEDIA_CONTROL = 12;
    public static final int PARAM_CHARGING_INFO = 13;
    public static final int PARAM_SCREEN_TIMEOUT = 14;
    public static final int PARAM_SHOW_LAST_APP_TIMEOUT = 15;
    public static final int PARAM_DISCONNECTION_ALERT = 16;
    public static final int PARAM_DOUBLE_PRESS = 17;
    public static final int PARAM_STATUS_INDICATOR = 18;
    public static final int PARAM_SYNC_MODES_WITH_PHONE = 19;
    public static final int PARAM_WRIST_ORIENTATION = 20;
    public static final int PARAM_BUTTON_POSITION = 21;

    /**
     * Value width in bytes for params 0..21, indexed by param id. Transcribed
     * from the vendor app's descriptor table; a param id outside this range has
     * an unknown width, which is why the walk stops rather than guessing.
     */
    private static final int[] PARAM_WIDTHS = {
            1, 1, 1, 1, 1,   //  0-4  vibration toggles
            2, 2,            //  5-6  vibration pattern indices
            1,               //  7    brightness level
            1, 1, 1, 1, 1, 1,//  8-13 display toggles
            2, 2,            // 14-15 screen / last-app timeout, seconds
            2, 2, 2,         // 16-18 disconnection alert, double press, status indicator
            1, 1, 1,         // 19-21 sync modes, wrist orientation, button position
    };

    private final Map<Integer, Integer> values;

    private Fit3FullSettings(Map<Integer, Integer> values) {
        this.values = values;
    }

    /** Ask the band for its full settings state. Bare header, no params. */
    public static byte[] buildRequest() {
        return SapPayloadBuilder.fixed(MSG_ID).build();
    }

    /**
     * @return the decoded snapshot, or {@code null} if this body is not a
     *     MSG_ID 32 response. A response that is only partly understood still
     *     decodes - whatever was read before the first unknown or truncated
     *     param is kept, since every param stands on its own.
     */
    public static Fit3FullSettings parse(byte[] body) {
        // Always a count byte here, whatever the header's format bit says.
        SapPayloadParser p = new SapPayloadParser(body, true);
        if (p.msgId != MSG_ID || !p.response) {
            return null;
        }

        Map<Integer, Integer> values = new LinkedHashMap<>();
        for (int i = 0; i < p.paramCount && p.remaining() >= 2; i++) {
            int id = p.u8();
            if (id < 0 || id >= PARAM_WIDTHS.length) {
                break;  // unknown id: its width is unknown too, so stop cleanly
            }
            int width = PARAM_WIDTHS[id];
            if (p.remaining() < width) {
                break;  // truncated
            }
            values.put(id, width == 2 ? p.le16() : p.u8());
        }
        return new Fit3FullSettings(values);
    }

    /** @return the raw value of a param, or {@code null} if the band did not send it. */
    public Integer get(int param) {
        return values.get(param);
    }

    /** @return the param as a boolean, or {@code null} if absent. */
    public Boolean getBoolean(int param) {
        Integer value = values.get(param);
        return value == null ? null : value != 0;
    }

    /** How many params the band actually reported. */
    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        return "Fit3FullSettings" + values;
    }
}
