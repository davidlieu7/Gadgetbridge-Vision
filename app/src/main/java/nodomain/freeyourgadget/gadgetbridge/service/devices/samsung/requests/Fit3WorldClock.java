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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * World clock cities pushed to the band on {@code CH_WORLD_CLOCK} (0x1b). All
 * phone-to-band requests, fixed format,
 * no param-count byte. Strings are {@code [id][len:4 LE][utf8]}, an int is
 * four bytes little-endian.
 *
 * <pre>
 *   04 07 [installed:4]                                   the phone has a clock app
 *   00 01 [total:1] 09 [chunk:1] { 02 name 03 sunrise 04 sunset 05 zone id 08 offset }*
 *   01 06 [zone name]                                     the phone's own time zone
 * </pre>
 *
 * <p>The city list goes out in chunks of {@link #CITIES_PER_CHUNK}, numbered
 * from 1; an empty list is one message with chunk 0. The offset is the zone's
 * current UTC offset as {@code +8}, {@code +5:30} or {@code -3:30}. Sunrise
 * and sunset are display strings ({@code 06:12} or {@code 6:12 AM}); a city
 * here has no coordinates, so they are sent empty.
 *
 * <p>The band also sends MSG_ID 2 and 3 (edit / add a city on the phone),
 * bare headers, which have nothing to act on here.
 *
 * <p>Confidence: confirmed working against a live band from Gadgetbridge's
 * own logs.
 */
public final class Fit3WorldClock {

    public static final int CHANNEL = SapProtocol.CH_WORLD_CLOCK;

    public static final int MSG_ID_CITY_LIST = 0;
    public static final int MSG_ID_CURRENT_ZONE = 1;
    public static final int MSG_ID_EDIT_ON_PHONE = 2;
    public static final int MSG_ID_ADD_ON_PHONE = 3;
    public static final int MSG_ID_CLOCK_APP = 4;

    private static final int PARAM_LIST_LENGTH = 1;
    private static final int PARAM_CITY_NAME = 2;
    private static final int PARAM_SUNRISE = 3;
    private static final int PARAM_SUNSET = 4;
    private static final int PARAM_ZONE_ID = 5;
    private static final int PARAM_CURRENT_ZONE_NAME = 6;
    private static final int PARAM_INSTALLED = 7;
    private static final int PARAM_UTC_OFFSET = 8;
    private static final int PARAM_CHUNK = 9;

    public static final int CITIES_PER_CHUNK = 10;

    private Fit3WorldClock() {
    }

    public static final class City {
        public final String name;
        public final String zoneId;
        public final String sunrise;
        public final String sunset;
        public final String utcOffset;

        public City(String name, String zoneId, String sunrise, String sunset, String utcOffset) {
            this.name = name;
            this.zoneId = zoneId;
            this.sunrise = sunrise;
            this.sunset = sunset;
            this.utcOffset = utcOffset;
        }

        public static City of(String name, ZoneId zone, Instant now) {
            return new City(name, zone.getId(), "", "", utcOffset(zone, now));
        }
    }

    /** Whether the phone has a clock app to edit cities in. Wire: {@code 04 07 xx 00 00 00}. */
    public static byte[] buildClockApp(boolean installed) {
        ByteBuffer b = le(6);
        b.put((byte) MSG_ID_CLOCK_APP).put((byte) PARAM_INSTALLED).putInt(installed ? 1 : 0);
        return b.array();
    }

    /** The whole city list, as one message per chunk. */
    public static List<byte[]> buildCityList(List<City> cities) {
        List<byte[]> out = new ArrayList<>();
        if (cities.isEmpty()) {
            out.add(buildChunk(cities, 0, 0));
            return out;
        }
        for (int i = 0; i < cities.size(); i += CITIES_PER_CHUNK) {
            List<City> chunk = cities.subList(i, Math.min(i + CITIES_PER_CHUNK, cities.size()));
            out.add(buildChunk(chunk, i / CITIES_PER_CHUNK + 1, cities.size()));
        }
        return out;
    }

    private static byte[] buildChunk(List<City> cities, int chunk, int total) {
        List<byte[]> params = new ArrayList<>();
        for (City c : cities) {
            params.add(string(PARAM_CITY_NAME, c.name));
            params.add(string(PARAM_SUNRISE, c.sunrise));
            params.add(string(PARAM_SUNSET, c.sunset));
            params.add(string(PARAM_ZONE_ID, c.zoneId));
            params.add(string(PARAM_UTC_OFFSET, c.utcOffset));
        }
        int len = 5;
        for (byte[] p : params) {
            len += p.length;
        }
        ByteBuffer b = le(len);
        b.put((byte) MSG_ID_CITY_LIST);
        b.put((byte) PARAM_LIST_LENGTH).put((byte) total);
        b.put((byte) PARAM_CHUNK).put((byte) chunk);
        for (byte[] p : params) {
            b.put(p);
        }
        return b.array();
    }

    /** The phone's own time zone, by display name. Wire: {@code 01 06 [len:4] name}. */
    public static byte[] buildCurrentZone(String displayName) {
        byte[] p = string(PARAM_CURRENT_ZONE_NAME, displayName);
        return ByteBuffer.allocate(1 + p.length).put((byte) MSG_ID_CURRENT_ZONE).put(p).array();
    }

    /** A zone's UTC offset at {@code now}: {@code +8}, {@code +5:30}, {@code -3:30}, {@code +0}. */
    public static String utcOffset(ZoneId zone, Instant now) {
        int seconds = zone.getRules().getOffset(now).getTotalSeconds();
        int minutes = Math.abs(seconds) / 60;
        String s = (seconds < 0 ? "-" : "+") + (minutes / 60);
        if (minutes % 60 != 0) {
            s += ":" + (minutes % 60);
        }
        return s;
    }

    private static byte[] string(int id, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = le(5 + utf8.length);
        b.put((byte) id).putInt(utf8.length).put(utf8);
        return b.array();
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
