/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * History records, as the ring sends them after {@link OuraProtocol#CMD_HISTORY}:
 * {@code [tag u8 >= 0x41][length u8][ring ticks u32][body]}, the length counting the ticks and the
 * body, several records back to back in one notification. Only the tags read here are listed.
 */
public final class OuraHistory {
    /** ASCII text; hourly lines {@code "batt: NN"} give the battery percent. */
    public static final int TAG_TEXT = 0x43;
    /** Three u16 temperatures in hundredths of °C, the first two from the skin side, once a minute. */
    public static final int TAG_TEMPERATURE = 0x46;
    /** {@code [steps u8][one MET byte per minute]}, the last minute ending at the record. */
    public static final int TAG_ACTIVITY = 0x50;
    /** {@code [state u8][ASCII]}, the state read by {@link #isWorn} and {@link #isNotWorn}. */
    public static final int TAG_WORN = 0x53;
    /** Six {@code [heart rate bpm u8][RMSSD ms u8]} pairs, one per five minutes, the last ending at the record; zero for no reading. */
    public static final int TAG_HRV = 0x5d;
    /** Seven beat intervals in units of 8 ms, then seven bytes not read here; the last beat ends at the record, 0 pads. */
    public static final int TAG_BEATS = 0x60;
    /** Six u16 movement values for the 30 s ending at the record, written while the ring considers its wearer at rest. */
    public static final int TAG_REST_MOVEMENT = 0x72;

    /** Largest length byte of a record: 20 bytes in all. */
    private static final int MAX_RECORD_LENGTH = 18;

    private static final int BEATS_PER_RECORD = 7;
    private static final int BEAT_UNIT_MS = 8;
    public static final int HRV_WINDOW_SECONDS = 300;

    private static final Pattern BATTERY_TEXT = Pattern.compile("^batt: (\\d{1,3})$");

    public interface Consumer {
        void onRecord(int tag, long ticks, byte[] body);
    }

    /** Walks the records at the start of one notification and returns the offset after the last one. */
    public static int walk(final byte[] value, final Consumer consumer) {
        int offset = 0;
        while (offset + 6 <= value.length) {
            final int tag = value[offset] & 0xff;
            final int length = value[offset + 1] & 0xff;
            if (tag < 0x41 || length < 4 || length > MAX_RECORD_LENGTH || offset + 2 + length > value.length) {
                break;
            }
            final byte[] body = new byte[length - 4];
            System.arraycopy(value, offset + 6, body, 0, body.length);
            consumer.onRecord(tag, OuraProtocol.u32(value, offset + 2), body);
            offset += 2 + length;
        }
        return offset;
    }

    /** On the finger: 3 to 6, with or without heart rate, moving or at rest. */
    public static boolean isWorn(final int state) {
        return state >= 3 && state <= 6;
    }

    /** Off the finger: 1, 2 while still detecting it, 7 out of power, 8 charging, 9 in low power. */
    public static boolean isNotWorn(final int state) {
        return state >= 1 && state <= 2 || state >= 7 && state <= 9;
    }

    public static boolean isHistory(final byte[] value) {
        return value.length > 0 && (value[0] & 0xff) >= 0x41;
    }

    /** The battery percent of a {@link #TAG_TEXT} body, or -1 when it holds other text. */
    public static int batteryPercent(final byte[] textBody) {
        final Matcher matcher = BATTERY_TEXT.matcher(new String(textBody, StandardCharsets.US_ASCII));
        if (!matcher.matches()) {
            return -1;
        }
        final int percent = Integer.parseInt(matcher.group(1));
        return percent <= 100 ? percent : -1;
    }

    /** Beat intervals in ms, earliest first; zero padding dropped. */
    public static int[] beatIntervals(final byte[] body) {
        final int n = Math.min(BEATS_PER_RECORD, body.length);
        int count = 0;
        final int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            final int code = body[i] & 0xff;
            if (code != 0) {
                all[count++] = code * BEAT_UNIT_MS;
            }
        }
        final int[] out = new int[count];
        System.arraycopy(all, 0, out, 0, count);
        return out;
    }

    /** Average of the two skin-side readings in °C, or NaN for a short body. */
    public static float skinTemperature(final byte[] body) {
        if (body.length < 4) {
            return Float.NaN;
        }
        return (OuraProtocol.u16(body, 0) + OuraProtocol.u16(body, 2)) / 200f;
    }

    /** Movement of a {@link #TAG_REST_MOVEMENT} body: its first value, or -1 for a short body. */
    public static int restMovement(final byte[] body) {
        return body.length < 12 ? -1 : OuraProtocol.u16(body, 0);
    }

    /** MET of one per-minute byte of a {@link #TAG_ACTIVITY} body, in tenths: 0.1 steps below 12.8, 0.2 steps above. */
    public static int metTenths(final byte value) {
        final int b = value & 0xff;
        return b < 128 ? b : 128 + (b - 128) * 2;
    }

    private OuraHistory() {
    }
}
