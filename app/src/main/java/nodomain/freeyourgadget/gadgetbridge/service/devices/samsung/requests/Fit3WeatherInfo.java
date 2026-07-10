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

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureUnit;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Weather push on {@code CH_WEATHER} (0x05), {@code WEATHER_INFO} (MSG_ID 1).
 *
 * <p>Unlike every other channel, weather does NOT use the shared
 * {@code SapPayloadBuilder} TLV grammar: the header is followed by a
 * <b>2-byte</b> little-endian param count (the shared grammar uses one
 * byte), and fixed-width numeric params carry no inline length
 * byte - only strings do ({@code [id][len:1][utf8]}).
 *
 * <pre>
 *   c1 CCCC                    variable, response, MSG_ID 1, paramCount (2 LE)
 *      settings block (5 params, the phone's own weather-app settings):
 *        08 xx                    cp type (byte, 0-4; see {@link #CP_TYPE_ACC})
 *        06 xx                    temp scale (byte, inverted: celsius=0, fahrenheit=1)
 *        09 xx                    show "use location" popup (byte)
 *        0a xx                    location on (byte)
 *        07 xx                    auto-refresh duration (byte)
 *      0b xx                      has-current-location flag (byte bool)
 *      -- if a location follows --
 *      0c xx                        location index, always 0 (byte)
 *      12 xx                        is-current-location, always 1 here (byte bool)
 *      0d len utf8...               city name (string)
 *      0e len utf8...               region/state (string)
 *      0f len utf8...               country (string)
 *      10 xx                        current condition/icon code (byte, 0-19, see {@link #owmToSamsungIcon})
 *      13 xx*4                      last-update time (u32 LE, epoch s)
 *      14 xx*2                      current temp (u16 LE, celsius)
 *      15 xx*2                      today's high (u16 LE, celsius)
 *      16 xx*2                      today's low (u16 LE, celsius)
 *      17 xx                        is-day flag for the current icon (byte bool)
 *      18 xx*2                      yesterday's high (u16 LE) - no GB equivalent, sent = today's high
 *      19 xx*2                      yesterday's low (u16 LE) - no GB equivalent, sent = today's low
 *      1a xx*4                      sunrise (u32 LE, epoch s)
 *      1b xx*4                      sunset (u32 LE, epoch s)
 *      1c len utf8...               timezone name (string) - GB has no tz name, sent empty
 *      23 xx                        feels-like temp (u16 LE, celsius) [see field-order note below]
 *      -- hourly forecast --
 *      1d xx                        total hourly-entry count (byte)
 *      per hour:
 *        1e xx                        hour index, 0-based (byte)
 *        22 xx                        is-day flag (byte bool)
 *        1f xx*4                      hour timestamp (u32 LE, epoch s)
 *        20 xx                        condition/icon code (byte)
 *        21 xx*2                      temperature (u16 LE, celsius)
 *        23 xx                        precipitation probability % (byte)
 *      -- daily forecast --
 *      24 xx                        total daily-entry count (byte)
 *      per day:
 *        25 xx                        day index, 0-based (byte)
 *        26 xx*4                      day timestamp (u32 LE, epoch s)
 *        27 xx                        condition/icon code, generic (byte)
 *        28 xx                        condition/icon code, day (byte, same as generic here)
 *        29 xx                        condition/icon code, night (byte, same as generic here)
 *        2a xx*2                      max temp (u16 LE, celsius)
 *        2b xx*2                      min temp (u16 LE, celsius)
 *      -- air quality / UV / wind / humidity, once per message --
 *      2c xx*2                      PM10 value (u16 LE)
 *      2d xx*2                      PM10 index/level (u16 LE)
 *      2e xx*2                      PM2.5 value (u16 LE)
 *      2f xx*2                      PM2.5 index/level (u16 LE)
 *      30 xx*2                      AQI value (u16 LE)
 *      31 xx*2                      AQI index/level (u16 LE) - GB has one AQI value, sent = AQI value
 *      32 xx*2                      UV value (u16 LE)
 *      33 len utf8...               UV index text (string; only numeric for cp type KOR/JPN, see doc)
 *      34 xx*2                      wind speed (u16 LE, km/h)
 *      35 len utf8...               wind direction text (string, e.g. "NW")
 *      36 xx*2                      humidity % (u16 LE)
 * </pre>
 *
 * Wire order doesn't matter for a TLV walk, so this builder writes the
 * current-conditions ids in the readable field order above; only the
 * byte-for-byte id/width mapping is load-bearing.
 */
public final class Fit3WeatherInfo {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_WEATHER;

    public static final int MSG_ID_WEATHER_INFO = 0x01;
    /** Band request: "add current location" (uses the band's own GPS fix). */
    public static final int MSG_ID_ADD_CURRENT_LOCATION = 0x00;

    private static final int PARAM_SETTING_CP_TYPE = 0x08;
    private static final int PARAM_SETTING_TEMP_SCALE = 0x06;
    private static final int PARAM_SETTING_SHOW_LOCATION_POPUP = 0x09;
    private static final int PARAM_SETTING_LOCATION_ON = 0x0a;
    private static final int PARAM_SETTING_AUTO_REFRESH = 0x07;

    private static final int PARAM_HAS_CURRENT_LOCATION = 0x0b;
    private static final int PARAM_LOCATION_INDEX = 0x0c;
    private static final int PARAM_IS_CURRENT_LOCATION = 0x12;
    private static final int PARAM_CITY_NAME = 0x0d;
    private static final int PARAM_CITY_STATE = 0x0e;
    private static final int PARAM_CITY_COUNTRY = 0x0f;
    private static final int PARAM_ICON = 0x10;
    private static final int PARAM_IS_DAY = 0x11;
    private static final int PARAM_UPDATE_TIME = 0x13;
    private static final int PARAM_TEMP = 0x14;
    private static final int PARAM_HIGH_TEMP = 0x15;
    private static final int PARAM_LOW_TEMP = 0x16;
    private static final int PARAM_FEELS_LIKE_TEMP = 0x17;
    private static final int PARAM_YESTERDAY_HIGH = 0x18;
    private static final int PARAM_YESTERDAY_LOW = 0x19;
    private static final int PARAM_SUNRISE = 0x1a;
    private static final int PARAM_SUNSET = 0x1b;
    private static final int PARAM_TIMEZONE = 0x1c;

    private static final int PARAM_HOURLY_TOTAL_COUNT = 0x1d;
    private static final int PARAM_HOURLY_INDEX = 0x1e;
    private static final int PARAM_HOURLY_TIME = 0x1f;
    private static final int PARAM_HOURLY_ICON = 0x20;
    private static final int PARAM_HOURLY_TEMP = 0x21;
    private static final int PARAM_HOURLY_IS_DAY = 0x22;
    private static final int PARAM_HOURLY_PRECIP_PROB = 0x23;

    private static final int PARAM_DAILY_TOTAL_COUNT = 0x24;
    private static final int PARAM_DAILY_INDEX = 0x25;
    private static final int PARAM_DAILY_TIME = 0x26;
    private static final int PARAM_DAILY_ICON = 0x27;
    private static final int PARAM_DAILY_ICON_DAY = 0x28;
    private static final int PARAM_DAILY_ICON_NIGHT = 0x29;
    private static final int PARAM_DAILY_HIGH_TEMP = 0x2a;
    private static final int PARAM_DAILY_LOW_TEMP = 0x2b;

    private static final int PARAM_PM10_VALUE = 0x2c;
    private static final int PARAM_PM10_INDEX = 0x2d;
    private static final int PARAM_PM25_VALUE = 0x2e;
    private static final int PARAM_PM25_INDEX = 0x2f;
    private static final int PARAM_AQI_VALUE = 0x30;
    private static final int PARAM_AQI_INDEX = 0x31;
    private static final int PARAM_UV_VALUE = 0x32;
    private static final int PARAM_UV_INDEX = 0x33;
    private static final int PARAM_WIND_VALUE = 0x34;
    private static final int PARAM_WIND_INDEX = 0x35;
    private static final int PARAM_HUMIDITY = 0x36;

    /** Content-provider type for the settings echo. TWC=0, ACC=1, KOR=2, JPN=3, CMA/HUA/WCN=4. */
    private static final int CP_TYPE_ACC = 1;

    /**
     * The vendor app builds its whole weather message into a fixed 980-byte
     * buffer, and real captures showed roughly a day of hourly data and
     * about a week of daily data - GB's weather providers can return far
     * more of both (100+ hourly entries), which produced a multi-KB message
     * the band silently ignored in testing. Cap to what that budget implies
     * fits comfortably, rather than sending everything the provider has.
     */
    private static final int HOURLY_LIMIT = 24;
    private static final int DAILY_LIMIT = 8;

    private Fit3WeatherInfo() {
    }

    /**
     * The message body, plus a running param count - kept together so the
     * header's count can never drift from what was actually written.
     */
    private static final class Body {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int paramCount = 0;
    }

    public static byte[] buildRequest(WeatherSpec spec) {
        Body body = new Body();

        writeSettings(body);

        boolean haveCurrent = spec != null;
        writeByte(body, PARAM_HAS_CURRENT_LOCATION, haveCurrent ? 1 : 0);
        if (haveCurrent) {
            writeCurrentConditions(body, spec);
            writeHourly(body, spec);
            writeDaily(body, spec);
            writeIndices(body, spec);
        }

        byte[] payload = body.out.toByteArray();
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        msg.write(0xC1); // format=1 (variable), type=1 (response), MSG_ID=1 (WEATHER_INFO)
        msg.write(body.paramCount & 0xFF); // param count is 2 bytes here, little-endian (see class doc)
        msg.write((body.paramCount >> 8) & 0xFF);
        msg.write(payload, 0, payload.length);
        return msg.toByteArray();
    }

    private static void writeSettings(Body body) {
        boolean fahrenheit = GBApplication.getPrefs().getTemperatureUnit() == TemperatureUnit.FAHRENHEIT;
        writeByte(body, PARAM_SETTING_CP_TYPE, CP_TYPE_ACC);
        writeByte(body, PARAM_SETTING_TEMP_SCALE, fahrenheit ? 1 : 0);
        writeByte(body, PARAM_SETTING_SHOW_LOCATION_POPUP, 0);
        writeByte(body, PARAM_SETTING_LOCATION_ON, 1);
        writeByte(body, PARAM_SETTING_AUTO_REFRESH, 0);
    }

    private static void writeCurrentConditions(Body body, WeatherSpec spec) {
        boolean isDay = !spec.isNight();
        int highC = kelvinToCelsius(spec.getTodayMaxTemp());
        int lowC = kelvinToCelsius(spec.getTodayMinTemp());

        writeByte(body, PARAM_LOCATION_INDEX, 0);
        writeByte(body, PARAM_IS_CURRENT_LOCATION, 1);
        writeString(body, PARAM_CITY_NAME, spec.getLocation() != null ? spec.getLocation() : "");
        writeString(body, PARAM_CITY_STATE, "");
        writeString(body, PARAM_CITY_COUNTRY, "");
        writeByte(body, PARAM_ICON, owmToSamsungIcon(spec.getCurrentConditionCode()));
        writeU32(body, PARAM_UPDATE_TIME, toBandEpoch(spec.getTimestamp()));
        writeU16(body, PARAM_TEMP, kelvinToCelsius(spec.getCurrentTemp()));
        writeU16(body, PARAM_HIGH_TEMP, highC);
        writeU16(body, PARAM_LOW_TEMP, lowC);
        writeByte(body, PARAM_IS_DAY, isDay ? 1 : 0);
        // No "yesterday" data in WeatherSpec; echo today's range rather than send zeroes.
        writeU16(body, PARAM_YESTERDAY_HIGH, highC);
        writeU16(body, PARAM_YESTERDAY_LOW, lowC);
        writeU32(body, PARAM_SUNRISE, toBandEpoch(spec.getSunRise()));
        writeU32(body, PARAM_SUNSET, toBandEpoch(spec.getSunSet()));
        writeString(body, PARAM_TIMEZONE, "");
        writeU16(body, PARAM_FEELS_LIKE_TEMP, kelvinToCelsius(spec.getFeelsLikeTemp()));
    }

    private static void writeHourly(Body body, WeatherSpec spec) {
        int count = Math.min(spec.getHourly().size(), HOURLY_LIMIT);
        writeByte(body, PARAM_HOURLY_TOTAL_COUNT, count);
        for (int i = 0; i < count; i++) {
            WeatherSpec.Hourly h = spec.getHourly().get(i);
            writeByte(body, PARAM_HOURLY_INDEX, i);
            writeByte(body, PARAM_HOURLY_IS_DAY, isDayAt(spec, h.getTimestamp()) ? 1 : 0);
            writeU32(body, PARAM_HOURLY_TIME, toBandEpoch(h.getTimestamp()));
            writeByte(body, PARAM_HOURLY_ICON, owmToSamsungIcon(h.getConditionCode()));
            writeU16(body, PARAM_HOURLY_TEMP, kelvinToCelsius(h.getTemp()));
            writeByte(body, PARAM_HOURLY_PRECIP_PROB, h.getPrecipProbability());
        }
    }

    private static void writeDaily(Body body, WeatherSpec spec) {
        int count = Math.min(spec.getForecasts().size(), DAILY_LIMIT);
        writeByte(body, PARAM_DAILY_TOTAL_COUNT, count);
        for (int i = 0; i < count; i++) {
            WeatherSpec.Daily d = spec.getForecasts().get(i);
            int icon = owmToSamsungIcon(d.getConditionCode());
            writeByte(body, PARAM_DAILY_INDEX, i);
            // Daily.sunRise/sunSet aren't populated by most GB weather providers; fall
            // back to today's timestamp offset by the day index rather than sending 0.
            writeU32(body, PARAM_DAILY_TIME, toBandEpoch(spec.getTimestamp() + i * 86400));
            writeByte(body, PARAM_DAILY_ICON, icon);
            writeByte(body, PARAM_DAILY_ICON_DAY, icon);
            writeByte(body, PARAM_DAILY_ICON_NIGHT, icon);
            writeU16(body, PARAM_DAILY_HIGH_TEMP, kelvinToCelsius(d.getMaxTemp()));
            writeU16(body, PARAM_DAILY_LOW_TEMP, kelvinToCelsius(d.getMinTemp()));
        }
    }

    private static void writeIndices(Body body, WeatherSpec spec) {
        WeatherSpec.AirQuality aq = spec.getAirQuality();
        writeU16(body, PARAM_PM10_VALUE, aq != null ? Math.round(Math.max(aq.getPm10(), 0)) : 0);
        writeU16(body, PARAM_PM10_INDEX, aq != null ? Math.max(aq.getPm10Aqi(), 0) : 0);
        writeU16(body, PARAM_PM25_VALUE, aq != null ? Math.round(Math.max(aq.getPm25(), 0)) : 0);
        writeU16(body, PARAM_PM25_INDEX, aq != null ? Math.max(aq.getPm25Aqi(), 0) : 0);
        writeU16(body, PARAM_AQI_VALUE, aq != null ? Math.max(aq.getAqi(), 0) : 0);
        writeU16(body, PARAM_AQI_INDEX, aq != null ? Math.max(aq.getAqi(), 0) : 0);
        writeU16(body, PARAM_UV_VALUE, Math.round(spec.getUvIndex()));
        // CP_TYPE_ACC takes the string branch for the UV field (see class doc).
        writeString(body, PARAM_UV_INDEX, uvIndexLabel(spec.getUvIndex()));
        writeU16(body, PARAM_WIND_VALUE, Math.round(spec.getWindSpeed()));
        writeString(body, PARAM_WIND_INDEX, compassDirection(spec.getWindDirection()));
        writeU16(body, PARAM_HUMIDITY, spec.getCurrentHumidity());
    }

    /**
     * Maps OpenWeatherMap condition codes (used by {@link WeatherSpec}) to
     * Samsung's internal 0-19 icon code. This is a best-effort category
     * mapping, not a verified 1:1 table. The day/night
     * distinction is a separate {@code is-day} flag, not part of this code.
     */
    static int owmToSamsungIcon(int owm) {
        if (owm >= 200 && owm <= 232) return 8;  // thunderstorm
        if (owm >= 300 && owm <= 321) return 5;  // drizzle -> showers
        if (owm == 500) return 5;                // light rain -> showers
        if (owm >= 501 && owm <= 504) return 4;  // rain
        if (owm == 511) return 16;               // freezing rain -> ice
        if (owm >= 520 && owm <= 531) return 5;  // shower rain
        if (owm == 600) return 10;               // light snow -> flurries
        if (owm >= 601 && owm <= 602) return 13; // snow
        if (owm == 611) return 16;               // sleet -> ice
        if (owm >= 612 && owm <= 613) return 16; // shower sleet -> ice
        if (owm >= 615 && owm <= 616) return 15; // rain and snow
        if (owm == 620) return 10;               // light shower snow -> flurries
        if (owm >= 621 && owm <= 622) return 13; // shower snow / heavy snow
        if (owm >= 701 && owm <= 781) return 3;  // fog/mist/haze/dust/smoke/sand/ash/squalls/tornado
        if (owm == 800) return 0;                // clear
        if (owm == 801 || owm == 802) return 1;  // few/scattered clouds -> partly cloudy
        if (owm == 803 || owm == 804) return 2;  // broken/overcast clouds -> cloudy
        return 1; // unknown code: default to partly cloudy rather than claim certainty
    }

    private static boolean isDayAt(WeatherSpec spec, int timestamp) {
        return !spec.isTimeNight(timestamp * 1000L);
    }

    private static int kelvinToCelsius(int kelvin) {
        return kelvin - 273;
    }

    /**
     * The band renders every epoch field here (update time, sunrise/sunset,
     * hourly/daily time) as if it were already local wall-clock time - it
     * does not apply any conversion itself (confirmed on-device: sending the
     * real UTC epoch made sunrise/sunset show up shifted by exactly the
     * local UTC offset). {@link #PARAM_TIMEZONE} is sent empty rather than a
     * real tz name/offset since there's no capture confirming what format
     * the band expects there; pre-shifting every timestamp we send sidesteps
     * that gap entirely.
     */
    private static int toBandEpoch(int utcEpochSeconds) {
        int offsetSeconds = TimeZone.getDefault().getOffset(utcEpochSeconds * 1000L) / 1000;
        return utcEpochSeconds + offsetSeconds;
    }

    private static String uvIndexLabel(float uv) {
        if (uv < 3) return "Low";
        if (uv < 6) return "Moderate";
        if (uv < 8) return "High";
        if (uv < 11) return "Very High";
        return "Extreme";
    }

    private static String compassDirection(int degrees) {
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int idx = Math.floorMod(Math.round(degrees / 45f), 8);
        return dirs[idx];
    }

    // ---- wire primitives (2-byte param count at the message level; it and
    // all multi-byte param VALUES are little-endian - see class doc). Each of these
    // is one wire param, so each increments body.paramCount exactly once. ----

    private static void writeByte(Body body, int id, int value) {
        body.out.write(id & 0xFF);
        body.out.write(value & 0xFF);
        body.paramCount++;
    }

    private static void writeU16(Body body, int id, int value) {
        body.out.write(id & 0xFF);
        body.out.write(value & 0xFF);
        body.out.write((value >> 8) & 0xFF);
        body.paramCount++;
    }

    private static void writeU32(Body body, int id, int value) {
        body.out.write(id & 0xFF);
        body.out.write(value & 0xFF);
        body.out.write((value >> 8) & 0xFF);
        body.out.write((value >> 16) & 0xFF);
        body.out.write((value >> 24) & 0xFF);
        body.paramCount++;
    }

    private static void writeString(Body body, int id, String value) {
        byte[] utf8 = value.getBytes(UTF8);
        body.out.write(id & 0xFF);
        body.out.write(utf8.length & 0xFF);
        body.out.write(utf8, 0, utf8.length);
        body.paramCount++;
    }
}
