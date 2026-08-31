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
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitConstants;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
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
    public static byte[] notification(final int id, final String source, final String title, final String text) {
        final byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        final byte[] titleBytes = title.getBytes(StandardCharsets.UTF_8);
        final byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        out.write(0x01);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
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

    private static void writeIntLE(final ByteArrayOutputStream out, final int value, final int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((value >> (8 * i)) & 0xff);
        }
    }
}
