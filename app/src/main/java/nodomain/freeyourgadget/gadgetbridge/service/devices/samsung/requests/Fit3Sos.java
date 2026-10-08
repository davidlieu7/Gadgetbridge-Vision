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

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * SOS and fall-detection settings on {@code CH_SOS} (0x19). Fixed format
 * throughout, no param-count byte;
 * booleans and bytes are one byte, an int is four bytes little-endian.
 *
 * <pre>
 *   settings request (phone -&gt; band): 09 37 [hasContacts] 38 [permission] 34 [version:4]
 *   settings reply   (band -&gt; phone): 49 02 [result] 2c [countdown] 2a [sendSos] 01 [fall] 0a [when] 34 [version:1]
 *   writes           (phone -&gt; band): 01 01 v | 05 0a [v:4] | 1a 2a v | 1b 2c v | 16 37 v
 *   write reply      (band -&gt; phone): [0x40|msgId] 02 [result]
 * </pre>
 *
 * <p>The band sends the same setting messages the other way, as requests, when
 * the user changes them on the band; those are answered with
 * {@link #buildResult}.
 *
 * <p>Confidence: the settings request/reply and the fall-detection, send-SOS
 * and has-contacts writes match a capture byte for byte. The countdown and
 * when-to-detect writes follow the same rules but were never captured; the
 * when-to-detect value is an int on the way out and a byte on the way back.
 *
 * <p>An alert raised on the band (button presses or a fall) goes the same way:
 *
 * <pre>
 *   band -&gt; phone                          phone -&gt; band
 *   13 36 [reason] 39 [responded]   raised    53 02 01
 *   14                              cancelled 54 02 01
 *   18 03 [battery %]               battery   58 02 01
 *                                             15 35 [len:1] [utf8]   text shown on the band
 * </pre>
 *
 * <p>Reason is 0 for the button and 1 for a fall; "responded" says whether the
 * user reacted on the band. The phone is expected to do the alerting and
 * report each step back as a text the band shows.
 *
 * <p>Confidence: raised (reason 1), cancelled and the texts match a capture
 * of a fall triggered from the band's developer menu. The button reason and
 * the battery message were not captured.
 */
public final class Fit3Sos {

    public static final int CHANNEL = SapProtocol.CH_SOS;

    /** Device-specific preference keys, registered by the settings customizer. */
    public static final String PREF_FALL_DETECTION = "samsung_fit3_fall_detection";
    public static final String PREF_WHEN_TO_DETECT = "samsung_fit3_fall_detection_when";
    public static final String PREF_SEND_SOS = "samsung_fit3_send_sos";
    public static final String PREF_COUNTDOWN = "samsung_fit3_sos_countdown";

    public static final int MSG_ID_FALL_DETECTION = 1;
    public static final int MSG_ID_WHEN_TO_DETECT = 5;
    public static final int MSG_ID_GET_SETTINGS = 9;
    public static final int MSG_ID_HAS_CONTACTS = 22;
    public static final int MSG_ID_SEND_SOS = 26;
    public static final int MSG_ID_COUNTDOWN = 27;
    public static final int MSG_ID_ALERT_RAISED = 19;
    public static final int MSG_ID_ALERT_CANCELLED = 20;
    public static final int MSG_ID_ALERT_TEXT = 21;
    public static final int MSG_ID_ALERT_BATTERY = 24;

    public static final int PARAM_FALL_DETECTION = 0x01;
    public static final int PARAM_RESULT = 0x02;
    public static final int PARAM_WHEN_TO_DETECT = 0x0a;
    public static final int PARAM_SEND_SOS = 0x2a;
    public static final int PARAM_COUNTDOWN = 0x2c;
    public static final int PARAM_VERSION = 0x34;
    /** True when the phone holds at least one emergency contact. */
    public static final int PARAM_HAS_CONTACTS = 0x37;
    public static final int PARAM_PERMISSION = 0x38;
    public static final int PARAM_BATTERY = 0x03;
    public static final int PARAM_ALERT_TEXT = 0x35;
    public static final int PARAM_REASON = 0x36;
    public static final int PARAM_USER_RESPONDED = 0x39;

    /** Alert reasons. */
    public static final int REASON_BUTTON = 0;
    public static final int REASON_FALL = 1;

    /** The text's length field is one byte. */
    private static final int MAX_TEXT_BYTES = 255;

    /** When-to-detect values: always, during physical activity, only during workouts. */
    public static final int WHEN_TO_DETECT_MAX = 2;

    /** 1 is what a phone without the newest vendor software sends. */
    private static final int VERSION = 1;
    private static final int RESULT_SUCCESS = 1;

    /** Ask for the band's SOS settings, telling it whether contacts exist. */
    public static byte[] buildSettingsRequest(final boolean hasContacts, final boolean permissionGranted) {
        return SapPayloadBuilder.fixed(MSG_ID_GET_SETTINGS)
                .addByte(PARAM_HAS_CONTACTS, hasContacts ? 1 : 0)
                .addByte(PARAM_PERMISSION, permissionGranted ? 1 : 0)
                .addInt32(PARAM_VERSION, VERSION)
                .build();
    }

    /** Fall detection on or off. Wire: {@code 01 01 xx}. */
    public static byte[] fallDetection(final boolean on) {
        return SapPayloadBuilder.fixed(MSG_ID_FALL_DETECTION)
                .addByte(PARAM_FALL_DETECTION, on ? 1 : 0)
                .build();
    }

    /** When to look for falls, 0..{@link #WHEN_TO_DETECT_MAX}. Wire: {@code 05 0a xx 00 00 00}. */
    public static byte[] whenToDetect(final int mode) {
        return SapPayloadBuilder.fixed(MSG_ID_WHEN_TO_DETECT)
                .addInt32(PARAM_WHEN_TO_DETECT, Math.max(0, Math.min(WHEN_TO_DETECT_MAX, mode)))
                .build();
    }

    /** Raise an SOS with repeated button presses, on or off. Wire: {@code 1a 2a xx}. */
    public static byte[] sendSos(final boolean on) {
        return SapPayloadBuilder.fixed(MSG_ID_SEND_SOS)
                .addByte(PARAM_SEND_SOS, on ? 1 : 0)
                .build();
    }

    /** Count down before sending an SOS, on or off. Wire: {@code 1b 2c xx}. */
    public static byte[] countdown(final boolean on) {
        return SapPayloadBuilder.fixed(MSG_ID_COUNTDOWN)
                .addByte(PARAM_COUNTDOWN, on ? 1 : 0)
                .build();
    }

    /** Whether the phone holds an emergency contact. Wire: {@code 16 37 xx}. */
    public static byte[] hasContacts(final boolean any) {
        return SapPayloadBuilder.fixed(MSG_ID_HAS_CONTACTS)
                .addByte(PARAM_HAS_CONTACTS, any ? 1 : 0)
                .build();
    }

    /** Success reply to one of the band's own requests: {@code [0x40|msgId] 02 01}. */
    public static byte[] buildResult(final int msgId) {
        return SapPayloadBuilder.fixedResponse(msgId)
                .addByte(PARAM_RESULT, RESULT_SUCCESS)
                .build();
    }

    /** A text the band shows during an alert. Wire: {@code 15 35 [len:1] [utf8]}. */
    public static byte[] buildAlertText(final String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_TEXT_BYTES) {
            int end = MAX_TEXT_BYTES;
            // Don't cut a character in half: back off continuation bytes.
            while (end > 0 && (utf8[end] & 0xC0) == 0x80) {
                end--;
            }
            utf8 = Arrays.copyOf(utf8, end);
        }
        return SapPayloadBuilder.fixed(MSG_ID_ALERT_TEXT)
                .addRawParam(PARAM_ALERT_TEXT, utf8)
                .build();
    }

    // ---- incoming ----

    public final int msgId;
    public final boolean response;
    private final Map<Integer, Integer> values;

    private Fit3Sos(final int msgId, final boolean response, final Map<Integer, Integer> values) {
        this.msgId = msgId;
        this.response = response;
        this.values = values;
    }

    /**
     * Decode a settings reply, a write reply, a band-side setting change or
     * an alert message: all of them are {@code [id][value:1]} pairs on the way
     * in.
     *
     * @return the message, or {@code null} for anything else on the channel
     */
    @Nullable
    public static Fit3Sos parse(final byte[] body) {
        if (body == null || body.length < 1) {
            return null;
        }
        SapPayloadParser p = new SapPayloadParser(body, false);
        switch (p.msgId) {
            case MSG_ID_FALL_DETECTION:
            case MSG_ID_WHEN_TO_DETECT:
            case MSG_ID_GET_SETTINGS:
            case MSG_ID_HAS_CONTACTS:
            case MSG_ID_SEND_SOS:
            case MSG_ID_COUNTDOWN:
            case MSG_ID_ALERT_RAISED:
            case MSG_ID_ALERT_CANCELLED:
            case MSG_ID_ALERT_TEXT:
            case MSG_ID_ALERT_BATTERY:
                break;
            default:
                return null;
        }
        Map<Integer, Integer> values = new LinkedHashMap<>();
        while (p.remaining() >= 2) {
            values.put(p.u8(), p.u8());
        }
        return new Fit3Sos(p.msgId, p.response, values);
    }

    @Nullable
    public Integer get(final int param) {
        return values.get(param);
    }

    @Nullable
    public Boolean getBoolean(final int param) {
        Integer value = values.get(param);
        return value == null ? null : value != 0;
    }

    /** A reply's result: {@code true} on success, {@code null} if there was none. */
    @Nullable
    public Boolean succeeded() {
        Integer result = values.get(PARAM_RESULT);
        return result == null ? null : result == RESULT_SUCCESS;
    }

    @Override
    public String toString() {
        return "Fit3Sos{msgId=" + msgId + (response ? " reply" : " request") + ", " + values + "}";
    }
}
