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

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Outgoing user-preference writes on {@code CH_SETTINGS} (0x0b). The band
 * exposes a large settings surface keyed on <b>(MSG_ID, param_id)</b> -
 * param ids are scoped per MSG_ID, not global.
 *
 * <p>Wire form (verified against the settings captures): single-setting writes
 * are <b>fixed format</b> - {@code [HDR][paramID][value]} with the header's
 * format bit clear (e.g. {@code 03 00 01}, {@code 07 0e 04 00}), built with
 * {@link SapPayloadBuilder#fixed}. A handful of MSG_IDs (12, 14, 19, 20, 32, 52)
 * instead carry a leading param-count byte. {@code [value]} is 1 byte for a
 * bool/level or 2 bytes little-endian, per param - this differs by param, not
 * by a MSG-wide rule, so each builder below encodes the width its param needs.
 *
 * <p>This class covers the single-param writes that have a preference (see the
 * key constants below); add more as they are wired into
 * {@code onSendConfiguration}. Every builder names its MSG_ID
 * and param id explicitly so the mapping stays checkable.
 *
 * <p>Every write here is confirmed against a live band: the band acks each one
 * and the setting visibly changes on the device. Mute and display timeout were
 * additionally verified byte-for-byte in a capture ({@code 03 00 01},
 * {@code 07 0e 1e 00}).
 */
public final class Fit3Settings {

    /** Channel these messages ride on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_SETTINGS;

    /**
     * Preference keys - the generic Gadgetbridge ones, matching the
     * {@code android:key}s in devicesettings_samsung_fit3.xml. They have to be
     * generic: {@code onSendConfiguration} is only called for keys
     * {@code DeviceSpecificSettingsFragment} attaches a change handler to, and
     * that is a fixed list of known keys.
     *
     * <p>Note the polarity flip on vibration: Gadgetbridge's switch is
     * "vibrations enabled", the band's param is "mute".
     */
    public static final String PREF_VIBRATION_ENABLE = DeviceSettingsPreferenceConst.PREF_VIBRATION_ENABLE;
    public static final String PREF_VIBRATION_INTENSITY = DeviceSettingsPreferenceConst.PREF_VIBRATION_INTENSITY;
    public static final String PREF_KEY_VIBRATION = DeviceSettingsPreferenceConst.PREF_KEY_VIBRATION;
    public static final String PREF_SCREEN_BRIGHTNESS = DeviceSettingsPreferenceConst.PREF_SCREEN_BRIGHTNESS;
    public static final String PREF_SCREEN_AUTO_BRIGHTNESS = DeviceSettingsPreferenceConst.PREF_SCREEN_AUTO_BRIGHTNESS;
    public static final String PREF_ALWAYS_ON_DISPLAY = DeviceSettingsPreferenceConst.PREF_ALWAYS_ON_DISPLAY;
    public static final String PREF_LIFTWRIST_NOSHED = DeviceSettingsPreferenceConst.PREF_LIFTWRIST_NOSHED;
    public static final String PREF_SCREEN_TIMEOUT = DeviceSettingsPreferenceConst.PREF_SCREEN_TIMEOUT;
    public static final String PREF_WEARLOCATION = DeviceSettingsPreferenceConst.PREF_WEARLOCATION;
    public static final String PREF_WEARDIRECTION = DeviceSettingsPreferenceConst.PREF_WEARDIRECTION;

    /**
     * Device-specific keys, for settings no generic preference fits. These
     * only reach {@code onSendConfiguration} because
     * {@code SamsungFit3SettingsCustomizer} registers a handler for each.
     * Their values are the band's list indices, as strings.
     */
    public static final String PREF_DOUBLE_PRESS = "samsung_fit3_double_press";
    public static final String PREF_DISCONNECTION_ALERT = "samsung_fit3_disconnection_alert";
    public static final String PREF_STATUS_INDICATOR = "samsung_fit3_status_indicator";

    /** Values of the generic wear-location and wear-direction lists. */
    public static final String WEARLOCATION_RIGHT = "right";
    public static final String WEARLOCATION_LEFT = "left";
    public static final String WEARDIRECTION_BUTTONS_RIGHT = "buttons_on_right";
    public static final String WEARDIRECTION_BUTTONS_LEFT = "buttons_on_left";

    /**
     * The two values of {@code @array/vibration_intensity_values}. The band's
     * param is a plain bool (light / strong), so only these two exist - there
     * is no shared constant for them in Gadgetbridge.
     */
    public static final String VIBRATION_INTENSITY_NORMAL = "normal";
    public static final String VIBRATION_INTENSITY_ENHANCED = "enhanced";

    private Fit3Settings() {
    }

    // ---- Vibration (MSG_ID 3, one param per message, no count byte) ----

    private static final int MSG_ID_VIBRATION = 3;
    private static final int PARAM_MUTE = 0;       // byte(bool): mute all vibration on/off
    private static final int PARAM_INTENSITY = 2;  // byte(bool): light (0) / strong (1)
    private static final int PARAM_TOUCH_VIBRATION = 3;  // byte(bool): vibrate on touch

    /** MSG_ID 3 / param 0: mute all vibration on or off. Wire: {@code 03 00 xx}. */
    public static byte[] mute(final boolean on) {
        return SapPayloadBuilder.fixed(MSG_ID_VIBRATION)
                .raw(PARAM_MUTE)
                .raw(on ? 1 : 0)
                .build();
    }

    /**
     * MSG_ID 3 / param 2: vibration intensity. The band only knows two levels,
     * light and strong. Wire: {@code 03 02 xx}.
     */
    public static byte[] vibrationIntensityStrong(final boolean strong) {
        return SapPayloadBuilder.fixed(MSG_ID_VIBRATION)
                .raw(PARAM_INTENSITY)
                .raw(strong ? 1 : 0)
                .build();
    }

    /** MSG_ID 3 / param 3: vibrate on touch. Wire: {@code 03 03 xx}. */
    public static byte[] touchVibration(final boolean on) {
        return SapPayloadBuilder.fixed(MSG_ID_VIBRATION)
                .raw(PARAM_TOUCH_VIBRATION)
                .raw(on ? 1 : 0)
                .build();
    }

    // ---- Brightness (MSG_ID 5 level, MSG_ID 6 auto; no count byte) ----

    private static final int MSG_ID_BRIGHTNESS = 5;
    private static final int PARAM_BRIGHTNESS = 7;   // byte: level, see BRIGHTNESS_MIN/MAX

    /** Lowest brightness step the band accepts. */
    public static final int BRIGHTNESS_MIN = 1;
    /** Highest brightness step the band accepts. */
    public static final int BRIGHTNESS_MAX = 10;

    /**
     * MSG_ID 5 / param 7: display brightness, as a step from
     * {@link #BRIGHTNESS_MIN} to {@link #BRIGHTNESS_MAX} - not a percentage.
     * The band's own slider runs 0..90 and converts with {@code progress / 10 + 1}
     * (and back with {@code (level - 1) * 10}), which is where the ten steps come
     * from. Out-of-range values are clamped rather than sent as-is.
     * Wire: {@code 05 07 xx}.
     */
    public static byte[] brightness(final int level) {
        int clamped = Math.max(BRIGHTNESS_MIN, Math.min(BRIGHTNESS_MAX, level));
        return SapPayloadBuilder.fixed(MSG_ID_BRIGHTNESS)
                .raw(PARAM_BRIGHTNESS)
                .raw(clamped)
                .build();
    }

    // ---- Display (MSG_ID 6, one param per message, no count byte) ----

    private static final int MSG_ID_DISPLAY = 6;
    private static final int PARAM_AUTO_BRIGHTNESS = 8;     // byte(bool)
    private static final int PARAM_ALWAYS_ON_DISPLAY = 9;   // byte(bool)
    private static final int PARAM_RAISE_WRIST_WAKE = 10;   // byte(bool)

    /**
     * MSG_ID 6 / param 8: adaptive brightness. While it is on, the band picks
     * the level itself and the value from {@link #brightness} is ignored.
     * Wire: {@code 06 08 xx}.
     */
    public static byte[] autoBrightness(final boolean on) {
        return SapPayloadBuilder.fixed(MSG_ID_DISPLAY)
                .raw(PARAM_AUTO_BRIGHTNESS)
                .raw(on ? 1 : 0)
                .build();
    }

    /** MSG_ID 6 / param 9: keep the watch face on. Wire: {@code 06 09 xx}. */
    public static byte[] alwaysOnDisplay(final boolean on) {
        return SapPayloadBuilder.fixed(MSG_ID_DISPLAY)
                .raw(PARAM_ALWAYS_ON_DISPLAY)
                .raw(on ? 1 : 0)
                .build();
    }

    /** MSG_ID 6 / param 10: wake the screen on a raise-wrist gesture. Wire: {@code 06 0a xx}. */
    public static byte[] raiseWristWake(final boolean on) {
        return SapPayloadBuilder.fixed(MSG_ID_DISPLAY)
                .raw(PARAM_RAISE_WRIST_WAKE)
                .raw(on ? 1 : 0)
                .build();
    }

    // ---- Display timeout (MSG_ID 7, one param per message, no count byte) ----

    private static final int MSG_ID_DISPLAY_TIMEOUT = 7;
    private static final int PARAM_SCREEN_TIMEOUT = 14;  // short: screen-on timeout, seconds

    /**
     * MSG_ID 7 / param 14: display (screen-on) timeout in seconds. The band's
     * own UI offers a discrete set - {@code {4,7,10,15,30}} - so callers
     * should pass one of those; the value is sent as a little-endian short.
     * Wire: {@code 07 0e xx xx} (e.g. {@code 07 0e 1e 00} = 30 s).
     */
    public static byte[] screenTimeout(final int seconds) {
        return SapPayloadBuilder.fixed(MSG_ID_DISPLAY_TIMEOUT)
                .raw(PARAM_SCREEN_TIMEOUT)
                .rawLe16(seconds)
                .build();
    }

    // ---- Advanced (MSG_ID 8, one param per message, no count byte) ----

    private static final int MSG_ID_ADVANCED = 8;
    private static final int PARAM_DISCONNECTION_ALERT = 16;  // short: list index
    private static final int PARAM_DOUBLE_PRESS = 17;         // short: list index
    private static final int PARAM_STATUS_INDICATOR = 18;     // short: list index

    /** Disconnection alert values: none, vibrate, full screen + vibrate. */
    public static final int DISCONNECTION_ALERT_MAX = 2;
    /**
     * Highest double-press action. The band uses its full table for a Samsung
     * phone, which is what the HELLO announces: 12 is the camera remote and
     * 13 the calculator. (A non-Samsung phone gets the table without the
     * camera remote, 12 = calculator.)
     */
    public static final int DOUBLE_PRESS_MAX = 13;
    /** Status indicator values: show always, hide after 2 seconds. */
    public static final int STATUS_INDICATOR_MAX = 1;

    /**
     * MSG_ID 8 / param 16: what the band does when the phone disconnects -
     * 0 nothing, 1 vibrate, 2 full screen + vibrate. Wire: {@code 08 10 xx 00}.
     */
    public static byte[] disconnectionAlert(final int mode) {
        return SapPayloadBuilder.fixed(MSG_ID_ADVANCED)
                .raw(PARAM_DISCONNECTION_ALERT)
                .rawLe16(clamp(mode, DISCONNECTION_ALERT_MAX))
                .build();
    }

    /**
     * MSG_ID 8 / param 17: the app a double press of the button opens, as an
     * index into the band's own table (0 recent apps ... 13 calculator, see
     * {@code @array/samsung_fit3_double_press_values}). Wire: {@code 08 11 xx 00}.
     */
    public static byte[] doublePress(final int action) {
        return SapPayloadBuilder.fixed(MSG_ID_ADVANCED)
                .raw(PARAM_DOUBLE_PRESS)
                .rawLe16(clamp(action, DOUBLE_PRESS_MAX))
                .build();
    }

    /**
     * MSG_ID 8 / param 18: the notification dot - 0 show always, 1 hide after
     * two seconds. Wire: {@code 08 12 xx 00}.
     */
    public static byte[] statusIndicator(final int mode) {
        return SapPayloadBuilder.fixed(MSG_ID_ADVANCED)
                .raw(PARAM_STATUS_INDICATOR)
                .rawLe16(clamp(mode, STATUS_INDICATOR_MAX))
                .build();
    }

    // ---- General (MSG_ID 10, one param per message, no count byte) ----

    private static final int MSG_ID_GENERAL = 10;
    private static final int PARAM_WRIST = 20;        // byte(bool): 1 = right wrist
    private static final int PARAM_BUTTON_SIDE = 21;  // byte(bool): 1 = button right of the screen

    /** MSG_ID 10 / param 20: the wrist the band is worn on. Wire: {@code 0a 14 xx}. */
    public static byte[] wrist(final boolean right) {
        return SapPayloadBuilder.fixed(MSG_ID_GENERAL)
                .raw(PARAM_WRIST)
                .raw(right ? 1 : 0)
                .build();
    }

    /**
     * MSG_ID 10 / param 21: which side of the screen the button is on, which
     * flips the display. Independent of {@link #wrist}. Wire: {@code 0a 15 xx}.
     */
    public static byte[] buttonSide(final boolean right) {
        return SapPayloadBuilder.fixed(MSG_ID_GENERAL)
                .raw(PARAM_BUTTON_SIDE)
                .raw(right ? 1 : 0)
                .build();
    }

    private static int clamp(final int value, final int max) {
        return Math.max(0, Math.min(max, value));
    }
}
