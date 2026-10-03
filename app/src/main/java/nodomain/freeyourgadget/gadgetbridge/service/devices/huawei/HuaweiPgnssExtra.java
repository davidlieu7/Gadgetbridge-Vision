/*  Copyright (C) 2026 Ulveon

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.ExtraTable;

/** Converts the HW_EEV2 assistance fields into the watch's HW_PGNSS_EXTRA layout. */
public final class HuaweiPgnssExtra {
    public static final String FILE_NAME = "HW_PGNSS_EXTRA";
    private static final long SECONDS_PER_WEEK = 604800;

    private HuaweiPgnssExtra() {
    }

    /**
     * Converts already base64-decoded HiEE fields without changing the input arrays.
     * All EXTRA fields must be present. Unrelated fields are ignored.
     * This checks field lengths and record capacities, not satellite validity.
     */
    public static byte[] decode(final Map<String, byte[]> fields) throws IOException {
        if (fields == null) {
            throw new IOException("The PGNSS fields are missing");
        }
        final ByteBuffer prefix = output(1360);
        prefix.put(gpsUtc(fields));
        prefix.put(fixed(fields, "gpsIon", 8).array());
        prefix.put(gloAux(fields));
        prefix.put(gpsRti(fields));
        prefix.put(gnssRti(fields, "gloRti", 1));
        prefix.put(gnssRti(fields, "galRti", 3));
        prefix.put(gnssRti(fields, "bdsRti", 2));
        return HuaweiAgpsFileGenerator.generateExtra(prefix.array(), List.of(
                gpsAlmanac(fields), gloAlmanac(fields), galAlmanac(fields), bdsAlmanac(fields)
        ), seedInfo(fields));
    }

    private static byte[] gpsUtc(final Map<String, byte[]> fields) throws IOException {
        final ByteBuffer input = fixed(fields, "gpsUtc", 20);
        final ByteBuffer result = output(16);
        result.putInt(input.getInt()).putInt(input.getInt());
        // These six wire fields retain the low byte of each source word.
        for (int i = 0; i < 6; i++) {
            result.put((byte) input.getShort());
        }
        return result.array();
    }

    private static byte[] gloAux(final Map<String, byte[]> fields) throws IOException {
        final Rows rows = rows(fields, "gloAux", 1, 3, 64, 0);
        final ByteBuffer input = rows.input();
        final ByteBuffer result = output(264);
        result.putInt(1).put((byte) rows.count());
        for (int i = 0; i < rows.count(); i++) {
            final byte satellite = input.get();
            final byte frequency = input.get();
            final byte flags = input.get();
            result.put(satellite).put(flags).put((byte) 1).put(frequency);
        }
        return result.array();
    }

    private static byte[] gpsRti(final Map<String, byte[]> fields) throws IOException {
        final Rows rows = rows(fields, "gpsRti", 11, 7, 32, 10);
        final ByteBuffer input = rows.input();
        final ByteBuffer result = output(280);
        // The watch layout leaves its initial 16 bytes empty.
        result.put(16, (byte) rows.count());
        result.position(18);
        for (int i = 0; i < rows.count(); i++) {
            result.put(input.get()).put(input.get());
            result.putShort(input.getShort()).putShort(input.getShort());
            result.put(input.get()).put((byte) 0);
        }
        return result.array();
    }

    private static byte[] gnssRti(final Map<String, byte[]> fields, final String name,
                                  final int constellation) throws IOException {
        final Rows rows = rows(fields, name, 1, 3, 64, 0);
        final ByteBuffer input = rows.input();
        final ByteBuffer result = output(264);
        result.putInt(constellation).put((byte) rows.count()).put((byte) 0);
        for (int i = 0; i < rows.count(); i++) {
            result.put(input.get()).put((byte) 0).putShort(input.getShort());
        }
        return result.array();
    }

    private static ExtraTable gpsAlmanac(final Map<String, byte[]> fields) throws IOException {
        final Rows rows = rows(fields, "gpsAlm", 3, 31, 32, 2);
        final ByteBuffer input = rows.input();
        final ByteBuffer result = output(1028);
        result.put(0, (byte) input.getShort(0));
        result.put(1, (byte) rows.count());
        for (int i = 0; i < rows.count(); i++) {
            final int offset = 4 + 32 * i;
            result.put(offset, input.get());
            result.putShort(offset + 2, input.getShort());
            result.put(offset + 4, (byte) input.getShort());
            result.putShort(offset + 6, input.getShort());
            result.putShort(offset + 8, input.getShort());
            result.put(offset + 10, (byte) input.getShort());
            for (int field = 0; field < 4; field++) {
                result.putInt(offset + 12 + 4 * field, input.getInt());
            }
            result.putShort(offset + 28, input.getShort());
            result.putShort(offset + 30, input.getShort());
        }
        return table(result, 4, 32, 32);
    }

    private static ExtraTable gloAlmanac(final Map<String, byte[]> fields) throws IOException {
        final Rows rows = rows(fields, "gloAlm", 4, 29, 24, 0);
        final ByteBuffer input = rows.input();
        final ByteBuffer result = output(772);
        result.put(input.array(), 0, 4);
        for (int i = 0; i < rows.count(); i++) {
            final int offset = 4 + 32 * i;
            result.putShort(offset, input.getShort());
            result.put(offset + 2, input.get());
            result.put(offset + 3, input.get());
            for (int field = 0; field < 4; field++) {
                result.putInt(offset + 4 + 4 * field, input.getInt());
            }
            result.put(offset + 20, input.get());
            result.putShort(offset + 22, input.getShort());
            // Source bytes 23..24 have no representation in the observed watch format.
            input.getShort();
            result.putShort(offset + 26, input.getShort());
            result.put(offset + 28, input.get());
            result.put(offset + 29, input.get());
        }
        return table(result, 4, 24, 32);
    }

    private static ExtraTable galAlmanac(final Map<String, byte[]> fields) throws IOException {
        final Rows rows = rows(fields, "galAlm", 6, 21, 36, 0);
        final ByteBuffer input = rows.input();
        final ByteBuffer result = output(800);
        final long seconds = Integer.toUnsignedLong(input.getInt(1));
        final int timeOfAlmanac = (int) ((seconds % SECONDS_PER_WEEK) / 600);
        result.put(0, (byte) rows.count());
        // The GST/GPS epoch difference is 1024 weeks, so the low week byte is identical.
        result.put(1, (byte) (seconds / SECONDS_PER_WEEK));
        if (timeOfAlmanac < 256) {
            result.put(6, (byte) timeOfAlmanac);
        } else {
            result.put(2, (byte) 1);
            result.putShort(4, (short) timeOfAlmanac);
        }
        result.put(7, input.get(5));
        for (int i = 0; i < rows.count(); i++) {
            final int offset = 8 + 22 * i;
            result.put(offset, input.get());
            for (int field = 0; field < 3; field++) {
                result.putShort(offset + 2 + 2 * field, input.getShort());
            }
            result.put(offset + 8, input.get());
            result.put(offset + 9, input.get());
            for (int field = 0; field < 6; field++) {
                result.putShort(offset + 10 + 2 * field, input.getShort());
            }
        }
        return table(result, 8, 36, 22);
    }

    private static ExtraTable bdsAlmanac(final Map<String, byte[]> fields) throws IOException {
        final Rows rows = rows(fields, "bdsAlm", 4, 34, 63, 0);
        final ByteBuffer input = rows.input();
        final ByteBuffer result = output(2272);
        result.put(input.array(), 0, 4);
        for (int i = 0; i < rows.count(); i++) {
            final int offset = 4 + 36 * i;
            result.put(offset, input.get());
            result.put(offset + 1, input.get());
            for (int field = 0; field < 6; field++) {
                result.putInt(offset + 4 + 4 * field, input.getInt());
            }
            for (int field = 0; field < 4; field++) {
                result.putShort(offset + 28 + 2 * field, input.getShort());
            }
        }
        return table(result, 4, 63, 36);
    }

    private static byte[] seedInfo(final Map<String, byte[]> fields) throws IOException {
        final ByteBuffer input = fixed(fields, "seedInfo", 14);
        final ByteBuffer result = output(16);
        result.putInt(input.getInt()).putInt(input.getInt());
        result.put(12, input.get());
        return result.array();
    }

    private static ExtraTable table(final ByteBuffer result, final int headerBytes,
                                    final int slots, final int recordBytes) {
        final byte[] bytes = result.array();
        final List<byte[]> records = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            final int offset = headerBytes + i * recordBytes;
            records.add(Arrays.copyOfRange(bytes, offset, offset + recordBytes));
        }
        return new ExtraTable(Arrays.copyOf(bytes, headerBytes), records);
    }

    private static ByteBuffer output(final int length) {
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer fixed(final Map<String, byte[]> fields, final String name,
                                    final int length) throws IOException {
        final ByteBuffer input = field(fields, name);
        if (input.remaining() != length) {
            throw new IOException("The PGNSS field " + name + " must contain " + length + " bytes");
        }
        return input;
    }

    private record Rows(ByteBuffer input, int count) {
    }

    private static Rows rows(final Map<String, byte[]> fields, final String name, final int headerBytes,
                             final int recordBytes, final int capacity, final int countOffset) throws IOException {
        final ByteBuffer input = field(fields, name);
        if (input.remaining() < headerBytes) {
            throw new IOException("The PGNSS field " + name + " has a truncated header");
        }
        final int count = input.get(countOffset) & 0xff;
        if (count > capacity) {
            throw new IOException("The PGNSS field " + name + " exceeds its record capacity");
        }
        if (input.remaining() != headerBytes + count * recordBytes) {
            throw new IOException("The PGNSS field " + name + " has an invalid record length");
        }
        input.position(headerBytes);
        return new Rows(input, count);
    }

    private static ByteBuffer field(final Map<String, byte[]> fields, final String name) throws IOException {
        final byte[] bytes = fields.get(name);
        if (bytes == null) {
            throw new IOException("The PGNSS field " + name + " is missing");
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    }
}
