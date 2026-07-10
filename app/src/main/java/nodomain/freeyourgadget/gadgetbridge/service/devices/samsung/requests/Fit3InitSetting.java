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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Request and reply for OOBE INIT_SETTING, on {@code CH_CONTROL} (0x01),
 * MSG_ID 3: pushes locale/roaming/time/timezone/24h-format to the band, and
 * reads back its battery+locale echo. This is the third step of the OOBE
 * handshake, after the watch-info and device-status exchanges.
 *
 * <p>Both directions are variable-format (header bit7=1) but neither carries
 * a param-count byte on the wire, despite the format bit - see
 * {@link SapPayloadBuilder#variableNoCount}. Build with that helper, parse
 * with {@code new SapPayloadParser(body, false)}.
 *
 * <pre>
 *   request  : 83                          variable, request,  MSG_ID 3 (no count byte)
 *                 01 39 00                     param 1 = locale id (short LE)                e.g. 0x0039 = ru
 *                 02 00                        param 2 = roaming (bool)
 *                 03 0a 31 37 38 33 34 ...      param 3 = epoch seconds (string, 1-byte len)
 *                 04 00 80 70                   param 4 = tz sign (byte) + abs offset seconds
 *                                                          (short LE, bare value - no id of its own)
 *                 05 01                         param 5 = 24-hour format (bool)
 *   response : c3 01 xx 02 xx 03 xx 04 xx xx    variable, response, MSG_ID 3 (no count byte)
 *                 01 xx                          param 1 = unconfirmed (byte)
 *                 02 xx                          param 2 = battery level code, 1-6
 *                                                           (very-low/very-low/low/moderate/high/full)
 *                 03 xx                          param 3 = locale available (bool)
 *                 04 xx xx                       param 4 = locale value (short LE), default 90 if absent
 * </pre>
 *
 * <p>Locale ids are a Fit3-specific enumeration, not a system constant.
 * Ported as a base-language table (one id per two-letter code) plus a short
 * list of region-specific overrides for the handful of locales that need
 * more than the language alone (e.g. simplified vs. traditional Chinese,
 * Brazilian vs. European Portuguese). Only overrides that actually change
 * the outcome are kept; entries that agreed with the plain base-language
 * lookup anyway were left out.
 */
public final class Fit3InitSetting {

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_CONTROL;

    public static final int MSG_ID_INIT_SETTING = 0x03;

    private static final int PARAM_LOCALE  = 0x01;
    private static final int PARAM_ROAMING = 0x02;
    private static final int PARAM_EPOCH   = 0x03;
    private static final int PARAM_TZ_SIGN = 0x04;
    private static final int PARAM_24H     = 0x05;

    private static final int RESP_PARAM_UNKNOWN_1      = 0x01;
    private static final int RESP_PARAM_BATTERY_LEVEL  = 0x02;
    private static final int RESP_PARAM_LOCALE_AVAIL   = 0x03;
    private static final int RESP_PARAM_LOCALE_VALUE   = 0x04;
    private static final int RESP_DEFAULT_LOCALE_VALUE = 90; // used when the band omits param 4

    // ---- request ----

    /**
     * Build the request with epoch/timezone taken from the current system
     * clock and default timezone (plain JDK calls, no Android Context
     * needed for those two - unlike roaming and 24h-format, which come from
     * TelephonyManager / DateFormat and must be supplied by the caller).
     *
     * @param locale         user's locale, e.g. {@link Locale#getDefault()}
     * @param roaming        current network-roaming state
     * @param is24HourFormat user's clock-format preference
     */
    public static byte[] buildRequest(Locale locale, boolean roaming, boolean is24HourFormat) {
        long epochSeconds = System.currentTimeMillis() / 1000L;
        int tzOffsetSeconds = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
        return buildRequest(locale, roaming, epochSeconds, tzOffsetSeconds, is24HourFormat);
    }

    /** Same as {@link #buildRequest(Locale, boolean, boolean)} but with an explicit epoch/offset (for tests). */
    public static byte[] buildRequest(Locale locale, boolean roaming, long epochSeconds,
                                       int tzOffsetSeconds, boolean is24HourFormat) {
        int localeId = lookupLocaleId(locale.toString());
        boolean negative = tzOffsetSeconds < 0;
        int absOffsetSeconds = Math.abs(tzOffsetSeconds);

        return SapPayloadBuilder.variableNoCount(MSG_ID_INIT_SETTING)
                .addShort(PARAM_LOCALE, localeId)
                .addByte(PARAM_ROAMING, roaming ? 1 : 0)
                .addString(PARAM_EPOCH, Long.toString(epochSeconds))
                .addByte(PARAM_TZ_SIGN, negative ? 1 : 0)
                .rawLe16(absOffsetSeconds)       // bare value, no id - see class note
                .addByte(PARAM_24H, is24HourFormat ? 1 : 0)
                .build();
    }

    // ---- response ----

    public final int unknownParam1;
    public final int batteryLevelCode;
    public final boolean localeAvailable;
    public final int localeValue;

    private Fit3InitSetting(int unknownParam1, int batteryLevelCode, boolean localeAvailable, int localeValue) {
        this.unknownParam1 = unknownParam1;
        this.batteryLevelCode = batteryLevelCode;
        this.localeAvailable = localeAvailable;
        this.localeValue = localeValue;
    }

    /** @return decoded info, or {@code null} if this body is not an INIT_SETTING reply. */
    public static Fit3InitSetting parse(byte[] body) {
        SapPayloadParser p = new SapPayloadParser(body, false); // no param-count byte, see class note
        if (p.msgId != MSG_ID_INIT_SETTING || !p.variable || !p.response) {
            return null;
        }

        int unknownParam1 = 0;
        int batteryLevelCode = 0;
        boolean localeAvailable = false;
        int localeValue = RESP_DEFAULT_LOCALE_VALUE;

        while (p.remaining() >= 2) {
            int id = p.u8();
            switch (id) {
                case RESP_PARAM_UNKNOWN_1:
                    unknownParam1 = p.u8();
                    break;
                case RESP_PARAM_BATTERY_LEVEL:
                    batteryLevelCode = p.u8();
                    break;
                case RESP_PARAM_LOCALE_AVAIL:
                    localeAvailable = p.u8() != 0;
                    break;
                case RESP_PARAM_LOCALE_VALUE:
                    if (p.remaining() < 2) {
                        return new Fit3InitSetting(unknownParam1, batteryLevelCode, localeAvailable, localeValue);
                    }
                    localeValue = p.le16();
                    break;
                default:
                    // unknown param id - stop clean rather than misreading a value as an id
                    return new Fit3InitSetting(unknownParam1, batteryLevelCode, localeAvailable, localeValue);
            }
        }
        return new Fit3InitSetting(unknownParam1, batteryLevelCode, localeAvailable, localeValue);
    }

    @Override
    public String toString() {
        return "InitSettingResponse{batteryLevelCode=" + batteryLevelCode
                + ", localeAvailable=" + localeAvailable
                + ", localeValue=" + localeValue
                + ", unknownParam1=" + unknownParam1 + "}";
    }

    // ---- locale id lookup (see class javadoc for the porting note) ----

    /** Base two-letter/three-letter language code -> Fit3 locale id. */
    private static final Map<String, Integer> LANGUAGE_TO_ID = new HashMap<>();
    static {
        LANGUAGE_TO_ID.put("ar", 0);  LANGUAGE_TO_ID.put("as", 1);  LANGUAGE_TO_ID.put("az", 2);
        LANGUAGE_TO_ID.put("be", 3);  LANGUAGE_TO_ID.put("bg", 4);  LANGUAGE_TO_ID.put("bn", 5);
        LANGUAGE_TO_ID.put("bo", 6);  LANGUAGE_TO_ID.put("bs", 7);  LANGUAGE_TO_ID.put("ca", 8);
        LANGUAGE_TO_ID.put("cs", 9);  LANGUAGE_TO_ID.put("da", 10); LANGUAGE_TO_ID.put("de", 11);
        LANGUAGE_TO_ID.put("el", 12); LANGUAGE_TO_ID.put("en", 13); LANGUAGE_TO_ID.put("es", 14);
        LANGUAGE_TO_ID.put("et", 15); LANGUAGE_TO_ID.put("eu", 16); LANGUAGE_TO_ID.put("fa", 17);
        LANGUAGE_TO_ID.put("fi", 18); LANGUAGE_TO_ID.put("fil", 19); LANGUAGE_TO_ID.put("fr", 20);
        LANGUAGE_TO_ID.put("ga", 21); LANGUAGE_TO_ID.put("gl", 22); LANGUAGE_TO_ID.put("gu", 23);
        LANGUAGE_TO_ID.put("hi", 24); LANGUAGE_TO_ID.put("hr", 25); LANGUAGE_TO_ID.put("hu", 26);
        LANGUAGE_TO_ID.put("hy", 27); LANGUAGE_TO_ID.put("in", 28); LANGUAGE_TO_ID.put("is", 29);
        LANGUAGE_TO_ID.put("it", 30); LANGUAGE_TO_ID.put("iw", 31); LANGUAGE_TO_ID.put("ja", 32);
        LANGUAGE_TO_ID.put("ka", 33); LANGUAGE_TO_ID.put("kk", 34); LANGUAGE_TO_ID.put("km", 35);
        LANGUAGE_TO_ID.put("kn", 36); LANGUAGE_TO_ID.put("ko", 37); LANGUAGE_TO_ID.put("ky", 38);
        LANGUAGE_TO_ID.put("lo", 39); LANGUAGE_TO_ID.put("lt", 40); LANGUAGE_TO_ID.put("lv", 41);
        LANGUAGE_TO_ID.put("mg", 42); LANGUAGE_TO_ID.put("mk", 43); LANGUAGE_TO_ID.put("ml", 44);
        LANGUAGE_TO_ID.put("mn", 45); LANGUAGE_TO_ID.put("mr", 46); LANGUAGE_TO_ID.put("ms", 47);
        LANGUAGE_TO_ID.put("my", 48); LANGUAGE_TO_ID.put("nb", 49); LANGUAGE_TO_ID.put("ne", 50);
        LANGUAGE_TO_ID.put("nl", 51); LANGUAGE_TO_ID.put("nn", 52); LANGUAGE_TO_ID.put("pa", 53);
        LANGUAGE_TO_ID.put("pl", 54); LANGUAGE_TO_ID.put("pt", 55); LANGUAGE_TO_ID.put("ro", 56);
        LANGUAGE_TO_ID.put("ru", 57); LANGUAGE_TO_ID.put("si", 58); LANGUAGE_TO_ID.put("sk", 59);
        LANGUAGE_TO_ID.put("sl", 60); LANGUAGE_TO_ID.put("sq", 61); LANGUAGE_TO_ID.put("sr", 62);
        LANGUAGE_TO_ID.put("sv", 63); LANGUAGE_TO_ID.put("sw", 64); LANGUAGE_TO_ID.put("swc", 65);
        LANGUAGE_TO_ID.put("ta", 66); LANGUAGE_TO_ID.put("te", 67); LANGUAGE_TO_ID.put("th", 68);
        LANGUAGE_TO_ID.put("tr", 69); LANGUAGE_TO_ID.put("ug", 70); LANGUAGE_TO_ID.put("uk", 71);
        LANGUAGE_TO_ID.put("ur", 72); LANGUAGE_TO_ID.put("vi", 73); LANGUAGE_TO_ID.put("zh", 74);
        LANGUAGE_TO_ID.put("zu", 75); LANGUAGE_TO_ID.put("or", 85); LANGUAGE_TO_ID.put("szl", 86);
        LANGUAGE_TO_ID.put("tg", 87); LANGUAGE_TO_ID.put("tk", 88); LANGUAGE_TO_ID.put("uz", 89);
    }

    /**
     * Region-specific overrides, checked in this order (first match wins) via
     * substring containment against {@code Locale.toString()}. Only pairs
     * whose id differs from (or is missing from) the base-language lookup are
     * listed - see the class javadoc.
     */
    private static final String[] LOCALE_OVERRIDE_TAGS = {
            "en_GB", "en_CA", "en_PH", "bn_BD", "zh_HK", "zh_TW", "pt_BR", "es_MX", "es_US", "fr_CA",
            "hy_AM", "eu_ES", "be_BY", "bn_IN", "bs_BA", "ca_AD", "zh_CN", "cs_CZ", "nl_AW", "en_US",
            "et_EE", "gl_ES", "ka_GE", "he_IL", "id_ID", "ga_IE", "es_419", "bo_CN",
    };
    private static final int[] LOCALE_OVERRIDE_IDS = {
            76, 77, 78, 79, 80, 81, 82, 83, 83, 84,
            90, 91, 92, 93, 94, 95, 96, 98, 99, 100,
            101, 104, 105, 106, 107, 108, 110, 111,
    };

    /** @return the Fit3 locale id for {@code localeString} (e.g. "en_US"), or -1 if unmapped. */
    public static int lookupLocaleId(String localeString) {
        if (localeString.startsWith("fil")) {
            return 19;
        }
        if (localeString.startsWith("swc")) {
            return 65;
        }
        for (int i = 0; i < LOCALE_OVERRIDE_TAGS.length; i++) {
            if (localeString.contains(LOCALE_OVERRIDE_TAGS[i])) {
                return LOCALE_OVERRIDE_IDS[i];
            }
        }
        Integer id = LANGUAGE_TO_ID.get(localeString.split("_")[0]);
        return id != null ? id : -1;
    }
}
