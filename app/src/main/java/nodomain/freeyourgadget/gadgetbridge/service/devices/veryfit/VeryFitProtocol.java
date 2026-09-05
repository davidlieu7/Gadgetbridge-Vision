/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.veryfit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitConstants;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.WeatherMapper;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

/**
 * Builds and parses both packet types. Short packets carry the settings and query commands and are
 * understood by every device in the family; framed packets are only used by the newer ones.
 */
public class VeryFitProtocol {
    private int sequence = 0;

    public static int crc16(final byte[] data, final int offset, final int length) {
        int crc = 0xffff;
        for (int i = offset; i < offset + length; i++) {
            crc = ((crc >>> 8) | (crc << 8)) & 0xffff;
            crc ^= (data[i] & 0xff);
            crc ^= (crc & 0xff) >>> 4;
            crc ^= (crc << 12) & 0xffff;
            crc ^= (crc & 0xff) << 5;
            crc &= 0xffff;
        }
        return crc & 0xffff;
    }

    public byte[] framed(final int cmd, final byte[] payload) {
        final byte[] body = payload != null ? payload : new byte[0];
        final int seq = nextSequence();
        final int len = VeryFitConstants.FRAME_HEADER_LEN + body.length;

        final ByteArrayOutputStream covered = new ByteArrayOutputStream();
        covered.write(VeryFitConstants.FRAME_MAGIC, 0, VeryFitConstants.FRAME_MAGIC.length);
        covered.write(VeryFitConstants.FRAME_VERSION);
        covered.write(len & 0xff);
        covered.write((len >> 8) & 0xff);
        covered.write(cmd & 0xff);
        covered.write((cmd >> 8) & 0xff);
        covered.write(seq & 0xff);
        covered.write((seq >> 8) & 0xff);
        covered.write(body, 0, body.length);
        final byte[] coveredBytes = covered.toByteArray();
        final int crc = crc16(coveredBytes, 0, coveredBytes.length);

        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(VeryFitConstants.FRAME_MARKER);
        frame.write(coveredBytes, 0, coveredBytes.length);
        frame.write(crc & 0xff);
        frame.write((crc >> 8) & 0xff);
        return frame.toByteArray();
    }

    public static byte[] command(final byte group, final byte key, final byte... payload) {
        final byte[] packet = new byte[2 + (payload != null ? payload.length : 0)];
        packet[0] = group;
        packet[1] = key;
        if (payload != null) {
            System.arraycopy(payload, 0, packet, 2, payload.length);
        }
        return packet;
    }

    public static byte[] query(final byte key) {
        return command(VeryFitConstants.GROUP_QUERY, key);
    }

    public static byte[] setting(final byte key, final byte... payload) {
        return command(VeryFitConstants.GROUP_SETTING, key, payload);
    }

    /**
     * Asks the watch what a notification icon for one of its registered apps has to look like.
     * The slot behind the app is the sport a workout picture would be for, and stays empty.
     */
    public static byte[] appIcon(final int appId) {
        return command(VeryFitConstants.GROUP_QUERY, VeryFitConstants.QUERY_APP_ICON,
                VeryFitConstants.ICON_NOTIFICATION, (byte) appId, (byte) (appId >> 8),
                (byte) 0, (byte) 0);
    }

    /**
     * Body for {@link VeryFitConstants#FRAMED_WEATHER}: a 32-byte header, then the runs it counts
     * off — sun times, hours, days — and the city name at the end of them. The header carries how
     * long that name is, so it is the one field the watch reads the place from.
     */
    public static byte[] weather(final WeatherSpec spec) {
        final byte[] city = StringUtils.truncateToBytes(spec.getLocation(), VeryFitConstants.WEATHER_CITY_LEN);
        final byte[] name = city != null ? city : new byte[0];
        final byte[] body = new byte[VeryFitConstants.WEATHER_FIXED_LEN + name.length];

        final Calendar now = GregorianCalendar.getInstance();
        body[0] = VeryFitConstants.WEATHER_VERSION;
        body[1] = (byte) (now.get(Calendar.MONTH) + 1);
        body[2] = (byte) now.get(Calendar.DAY_OF_MONTH);
        body[3] = (byte) now.get(Calendar.HOUR_OF_DAY);
        body[4] = (byte) now.get(Calendar.MINUTE);
        body[5] = (byte) now.get(Calendar.SECOND);
        body[6] = (byte) (now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                ? 7 : now.get(Calendar.DAY_OF_WEEK) - 1);

        final byte condition = WeatherMapper.mapToVeryFitCondition(spec.getCurrentConditionCode());
        final long seconds = now.getTimeInMillis() / 1000L;
        body[7] = seconds >= spec.getSunRise() && seconds < spec.getSunSet()
                ? condition : WeatherMapper.veryFitConditionToNight(condition);
        body[8] = temperature(spec.getCurrentTemp());
        body[9] = temperature(spec.getTodayMaxTemp());
        body[10] = temperature(spec.getTodayMinTemp());
        body[15] = (byte) spec.getCurrentHumidity();
        body[16] = (byte) Math.round(spec.getUvIndex());
        body[18] = VeryFitConstants.WEATHER_SUN_TIMES;
        body[19] = VeryFitConstants.WEATHER_HOURS;
        body[20] = VeryFitConstants.WEATHER_DAYS;
        body[21] = (byte) name.length;
        body[22] = 0x01;

        sunTimes(body, 32, spec);
        forecast(body, 180, spec);

        // The hourly run has never been filled, and the vendor leaves the tail of its own record
        // in it — humidity, the sun, the days and the name over again. Sent the same way.
        body[112] = body[15];
        body[113] = body[16];
        body[114] = 0x01;
        sunTimes(body, 115, spec);
        forecast(body, 119, spec);
        body[150] = (byte) name.length;
        System.arraycopy(name, 0, body, 151, name.length);

        System.arraycopy(name, 0, body, VeryFitConstants.WEATHER_FIXED_LEN, name.length);
        return body;
    }

    private static void sunTimes(final byte[] body, final int offset, final WeatherSpec spec) {
        final Calendar sunrise = GregorianCalendar.getInstance();
        sunrise.setTimeInMillis(spec.getSunRise() * 1000L);
        final Calendar sunset = GregorianCalendar.getInstance();
        sunset.setTimeInMillis(spec.getSunSet() * 1000L);

        body[offset] = (byte) sunrise.get(Calendar.HOUR_OF_DAY);
        body[offset + 1] = (byte) sunrise.get(Calendar.MINUTE);
        body[offset + 2] = (byte) sunset.get(Calendar.HOUR_OF_DAY);
        body[offset + 3] = (byte) sunset.get(Calendar.MINUTE);
    }

    /** Five days of {@code [condition][high][low]}, as many as the forecast reaches. */
    private static void forecast(final byte[] body, final int offset, final WeatherSpec spec) {
        final List<WeatherSpec.Daily> days = spec.getForecasts();
        for (int i = 0; i < VeryFitConstants.WEATHER_FORECAST_DAYS && i < days.size(); i++) {
            final WeatherSpec.Daily day = days.get(i);
            body[offset + i * 3] = WeatherMapper.mapToVeryFitCondition(day.getConditionCode());
            body[offset + i * 3 + 1] = temperature(day.getMaxTemp());
            body[offset + i * 3 + 2] = temperature(day.getMinTemp());
        }
    }

    private static byte temperature(final int kelvin) {
        return (byte) (kelvin - 273 + VeryFitConstants.WEATHER_TEMPERATURE_OFFSET);
    }

    /**
     * Body for {@link VeryFitConstants#FRAMED_APP_REGISTRY}: the apps the watch should keep a
     * notification switch for, or an empty list to read back the ones it already has.
     */
    public static byte[] appRegistry(final byte operation, final int... appIds) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(operation);
        out.write(0x00);
        out.write(appIds.length);
        for (final int appId : appIds) {
            writeIntLE(out, appId, 2);
            out.write(0x01);
            out.write(VeryFitConstants.APP_READY);
        }
        return out.toByteArray();
    }

    /**
     * Body for {@link VeryFitConstants#FRAMED_WATCHFACE}: what to do, then the name of the file
     * the watch keeps that face in, padded out to the slot it is read from.
     */
    public static byte[] watchface(final byte operate, final String name) {
        final byte[] body = new byte[1 + VeryFitConstants.WATCHFACE_NAME_LEN];
        body[0] = operate;
        final byte[] file = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(file, 0, body, 1,
                Math.min(file.length, VeryFitConstants.WATCHFACE_NAME_LEN));
        return body;
    }

    /** Fetch one data type, or close it again so the watch moves on to the next. */
    public static byte[] healthRequest(final byte operate, final byte type, final boolean today) {
        return new byte[]{operate, type, (byte) (today ? 1 : 0), 0x00, 0x00};
    }

    /** The round opens by announcing the wanted types, one fixed-size slot each. */
    public static byte[] healthTypes(final byte[] types) {
        final byte[] payload =
                new byte[VeryFitConstants.HEALTH_TYPE_SLOTS * VeryFitConstants.HEALTH_REQUEST_LEN];
        for (int i = 0; i < types.length; i++) {
            payload[i * VeryFitConstants.HEALTH_REQUEST_LEN] = types[i];
        }
        return payload;
    }

    public byte[] bindRequest() {
        return command(VeryFitConstants.GROUP_BIND, VeryFitConstants.BIND_START,
                new byte[]{0x02, 0x02, 0x01, 0x03});
    }

    /** The response needs its own little-endian length in front of it or the watch rejects it. */
    public byte[] bindAuth(final byte[] response) {
        final byte[] payload = new byte[2 + response.length];
        payload[0] = (byte) (response.length & 0xff);
        payload[1] = (byte) ((response.length >> 8) & 0xff);
        System.arraycopy(response, 0, payload, 2, response.length);
        return command(VeryFitConstants.GROUP_BIND, VeryFitConstants.BIND_AUTH, payload);
    }

    public static class Packet {
        public enum Kind {FRAMED, SHORT, UNKNOWN}

        public Kind kind = Kind.UNKNOWN;
        public boolean crcValid;
        public boolean incomplete;
        public int group = -1;
        public int key = -1;
        public int cmd = -1;
        public int sequence = -1;
        public byte[] payload = new byte[0];
    }

    public Packet parse(final byte[] data) {
        final Packet packet = new Packet();
        if (data == null || data.length < 2) {
            return packet;
        }

        if (isFramed(data)) {
            packet.kind = Packet.Kind.FRAMED;
            final int declaredLen = (data[6] & 0xff) | ((data[7] & 0xff) << 8);
            packet.cmd = (data[8] & 0xff) | ((data[9] & 0xff) << 8);
            packet.sequence = (data[10] & 0xff) | ((data[11] & 0xff) << 8);

            final int payloadLen = Math.max(0, data.length - 12 - 2);
            packet.incomplete = declaredLen - VeryFitConstants.FRAME_HEADER_LEN != payloadLen;
            packet.payload = new byte[payloadLen];
            System.arraycopy(data, 12, packet.payload, 0, payloadLen);

            if (!packet.incomplete) {
                final int stored = (data[data.length - 2] & 0xff) | ((data[data.length - 1] & 0xff) << 8);
                packet.crcValid = stored == crc16(data, 1, data.length - 3);
            }
            return packet;
        }

        packet.kind = Packet.Kind.SHORT;
        packet.group = data[0] & 0xff;
        packet.key = data[1] & 0xff;
        packet.payload = new byte[data.length - 2];
        System.arraycopy(data, 2, packet.payload, 0, packet.payload.length);
        return packet;
    }

    public static boolean isFramed(final byte[] data) {
        return data.length >= 12
                && data[0] == VeryFitConstants.FRAME_MARKER
                && data[1] == VeryFitConstants.FRAME_MAGIC[0]
                && data[2] == VeryFitConstants.FRAME_MAGIC[1]
                && data[3] == VeryFitConstants.FRAME_MAGIC[2]
                && data[4] == VeryFitConstants.FRAME_MAGIC[3];
    }

    /**
     * Notification body for {@link VeryFitConstants#FRAMED_NOTIFICATION}: flags, ids and lengths,
     * then the source tag, title and text back to back.
     */
    public static byte[] notification(final int id, final int appId, final String source,
                                      final String title, final String text) {
        final byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        final byte[] titleBytes = title.getBytes(StandardCharsets.UTF_8);
        final byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        out.write(0x01);
        out.write(0x00);
        out.write(0x01);
        writeIntLE(out, appId, 2);
        writeIntLE(out, id, 4);
        writeIntLE(out, titleBytes.length, 4);
        writeIntLE(out, textBytes.length, 2);
        out.write(0x01);
        out.write(sourceBytes.length);
        out.write(0x01);
        out.write(sourceBytes, 0, sourceBytes.length);
        out.write(titleBytes, 0, titleBytes.length);
        out.write(textBytes, 0, textBytes.length);
        return out.toByteArray();
    }

    /**
     * Body for {@link VeryFitConstants#FRAMED_MUSIC}: play state, position and duration, then the
     * track and the artist, each in a padded field of its own with a byte count in front of it.
     * With nothing playing the watch only wants the state.
     */
    public static byte[] musicInfo(final MusicSpec spec, final MusicStateSpec state, final int volume) {
        final byte[] body = new byte[VeryFitConstants.MUSIC_INFO_LEN];
        if (spec == null || state == null) {
            body[0] = VeryFitConstants.MUSIC_IDLE;
            return body;
        }

        body[0] = state.state == MusicStateSpec.STATE_PLAYING
                ? VeryFitConstants.MUSIC_PLAYING : VeryFitConstants.MUSIC_PAUSED;
        writeShortLE(body, 1, state.position);
        writeShortLE(body, 3, spec.duration);

        final byte[] track = text(spec.track);
        writeShortLE(body, 5, track.length);
        System.arraycopy(track, 0, body, 7, track.length);

        body[71] = VeryFitConstants.MUSIC_VOLUME_STEPS;
        body[72] = (byte) volume;

        final byte[] artist = text(spec.artist);
        writeShortLE(body, 73, artist.length);
        System.arraycopy(artist, 0, body, 75, artist.length);
        return body;
    }

    private static byte[] text(final String value) {
        final byte[] bytes = StringUtils.truncateToBytes(value, VeryFitConstants.MUSIC_TEXT_LEN);
        return bytes != null ? bytes : new byte[0];
    }

    /**
     * Body for {@link VeryFitConstants#FRAMED_ALARMS}: a count, then one fixed-size record per
     * slot. The watch replaces its whole table, so unused slots are written out as empty rather
     * than left off the end.
     */
    public static byte[] alarms(final List<? extends Alarm> alarms) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00);
        out.write(alarms.size());

        int slot = 1;
        for (final Alarm alarm : alarms) {
            final byte[] record = alarm(slot++, alarm);
            out.write(record, 0, record.length);
        }
        return out.toByteArray();
    }

    private static byte[] alarm(final int slot, final Alarm alarm) {
        final byte[] record = new byte[VeryFitConstants.ALARM_RECORD_LEN];
        record[0] = (byte) slot;
        record[1] = VeryFitConstants.ALARM_EMPTY;
        record[7] = VeryFitConstants.ALARM_DELAY_MINUTES;
        record[9] = VeryFitConstants.ALARM_REPEAT_COUNT;
        if (alarm.getUnused()) {
            return record;
        }

        // A slot in use stays shown even when the alarm is off; bit 0 of the repeat byte is the
        // on/off, and the rest of it is Monday through Sunday.
        record[1] = VeryFitConstants.ALARM_IN_USE;
        record[3] = (byte) alarm.getHour();
        record[4] = (byte) alarm.getMinute();
        record[5] = (byte) (((alarm.getRepetition() & 0x7f) << 1) | (alarm.getEnabled() ? 1 : 0));

        final byte[] title = StringUtils.truncateToBytes(alarm.getTitle(), VeryFitConstants.ALARM_NAME_LEN);
        if (title != null) {
            System.arraycopy(title, 0, record, 10, title.length);
        }
        return record;
    }

    /** Renders a returned alarm list for the log; the layout is the one {@link #alarms} writes. */
    public static String describeAlarms(final byte[] payload) {
        if (payload.length < 2) {
            return "empty";
        }
        final StringBuilder text = new StringBuilder();
        text.append(payload[1] & 0xff).append(" slots");
        for (int offset = 2; offset + VeryFitConstants.ALARM_RECORD_LEN <= payload.length;
             offset += VeryFitConstants.ALARM_RECORD_LEN) {
            if (payload[offset + 1] != VeryFitConstants.ALARM_IN_USE) {
                continue;
            }
            text.append(String.format(Locale.ROOT, "; %d: %02d:%02d repeat %02x",
                    payload[offset] & 0xff, payload[offset + 3] & 0xff, payload[offset + 4] & 0xff,
                    payload[offset + 5] & 0xff));
            final String name = new String(payload, offset + 10, VeryFitConstants.ALARM_NAME_LEN,
                    StandardCharsets.UTF_8).split("\u0000", 2)[0];
            if (!name.isEmpty()) {
                text.append(" \"").append(name).append('"');
            }
        }
        return text.toString();
    }

    private int nextSequence() {
        sequence = (sequence + 1) & 0xffff;
        return sequence;
    }

    private static void writeShortLE(final byte[] target, final int offset, final int value) {
        final int clamped = Math.max(0, Math.min(value, 0xffff));
        target[offset] = (byte) (clamped & 0xff);
        target[offset + 1] = (byte) ((clamped >> 8) & 0xff);
    }

    private static void writeIntLE(final ByteArrayOutputStream out, final int value, final int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((value >> (8 * i)) & 0xff);
        }
    }
}
