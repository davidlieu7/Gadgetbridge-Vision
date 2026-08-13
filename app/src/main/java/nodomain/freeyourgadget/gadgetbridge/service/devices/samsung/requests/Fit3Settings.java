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
 * <p>This class currently covers the two simplest single-param writes; add
 * more as they are wired into {@code onSendConfiguration}. Every builder names
 * its MSG_ID and param id explicitly so the mapping stays checkable.
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
    public static final String PREF_SCREEN_TIMEOUT = DeviceSettingsPreferenceConst.PREF_SCREEN_TIMEOUT;

    private Fit3Settings() {
    }

    // ---- Vibration (MSG_ID 3, single param, no count byte) ----

    private static final int MSG_ID_VIBRATION = 3;
    private static final int PARAM_MUTE = 0;   // byte(bool): mute all vibration on/off

    /** MSG_ID 3 / param 0: mute all vibration on or off. Wire: {@code 03 00 xx}. */
    public static byte[] mute(final boolean on) {
        return SapPayloadBuilder.fixed(MSG_ID_VIBRATION)
                .raw(PARAM_MUTE)
                .raw(on ? 1 : 0)
                .build();
    }

    // ---- Display (MSG_ID 7, single param, no count byte) ----

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
}
