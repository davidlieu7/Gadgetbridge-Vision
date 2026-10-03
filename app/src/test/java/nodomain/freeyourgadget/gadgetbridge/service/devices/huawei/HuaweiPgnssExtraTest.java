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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public class HuaweiPgnssExtraTest {
    private record RowFormat(String name, int header, int stride, int capacity, int countOffset) {
    }

    private static final RowFormat[] ROW_FORMATS = {
            new RowFormat("gloAux", 1, 3, 64, 0),
            new RowFormat("gpsRti", 11, 7, 32, 10),
            new RowFormat("gloRti", 1, 3, 64, 0),
            new RowFormat("galRti", 1, 3, 64, 0),
            new RowFormat("bdsRti", 1, 3, 64, 0),
            new RowFormat("gpsAlm", 3, 31, 32, 2),
            new RowFormat("gloAlm", 4, 29, 24, 0),
            new RowFormat("galAlm", 6, 21, 36, 0),
            new RowFormat("bdsAlm", 4, 34, 63, 0)
    };

    @Test
    public void emptyTablesHaveFixedBoundariesAndZeroPadding() throws Exception {
        final byte[] expected = new byte[6248];
        expected[24] = 1;
        expected[568] = 1;
        expected[832] = 3;
        expected[1096] = 2;
        assertArrayEquals(expected, HuaweiPgnssExtra.decode(emptyFields()));
    }

    @Test
    public void convertsUtcIonAndSeedMetadataWithoutChangingInputs() throws Exception {
        final Map<String, byte[]> fields = emptyFields();
        fields.put("gpsUtc", hex("ffffffff 80000000 0102 fefd 0304 fffe 0506 807f"));
        fields.put("gpsIon", hex("0908070605040302"));
        fields.put("seedInfo", hex("89abcdef 76543210 91 0102030405"));
        fields.put("unrelated", new byte[]{1, 2, 3});
        final Map<String, byte[]> before = new LinkedHashMap<>();
        fields.forEach((key, bytes) -> before.put(key, bytes.clone()));

        final byte[] output = HuaweiPgnssExtra.decode(fields);
        assertRegion(output, 0, "ffffffff 00000080 02fd04fe067f0000");
        assertRegion(output, 16, "0908070605040302");
        assertRegion(output, 6232, "efcdab89 10325476 00000000 91000000");
        fields.forEach((key, bytes) -> assertArrayEquals(before.get(key), bytes));
    }

    @Test
    public void convertsAuxiliaryAndRtiRecordsAtTheirSectionOffsets() throws Exception {
        final Map<String, byte[]> fields = emptyFields();
        fields.put("gloAux", hex("02 02ffc0 038040"));
        // The first ten GPS RTI bytes must not appear in the watch's empty prefix.
        fields.put("gpsRti", hex("112233445566778899aa 02 01020304050607 08090a0b0c0d0e"));
        fields.put("gloRti", hex("02 048182 058384"));
        fields.put("galRti", hex("01 068586"));
        fields.put("bdsRti", hex("01 078788"));

        final byte[] output = HuaweiPgnssExtra.decode(fields);
        assertRegion(output, 24, "01000000 02 02c001ff 03400180");
        assertZero(output, 37, 288);
        assertZero(output, 288, 304);
        assertRegion(output, 304, "0200 0102040306050700 08090b0a0d0c0e00");
        assertZero(output, 322, 568);
        assertRegion(output, 568, "01000000 0200 04008281 05008483");
        assertZero(output, 582, 832);
        assertRegion(output, 832, "03000000 0100 06008685");
        assertZero(output, 842, 1096);
        assertRegion(output, 1096, "02000000 0100 07008887");
        assertZero(output, 1106, 1360);
    }

    @Test
    public void convertsEveryAlmanacFieldAndLeavesUnusedSlotsEmpty() throws Exception {
        final Map<String, byte[]> fields = emptyFields();
        fields.put("gpsAlm", withRecord(hex("123401"), 31));
        fields.put("gloAlm", withRecord(hex("01214365"), 29));
        final byte[] galHeader = ByteBuffer.allocate(6).put((byte) 1)
                .putInt(2 * 604800 + 153600).put((byte) 0xa5).array();
        fields.put("galAlm", withRecord(galHeader, 21));
        fields.put("bdsAlm", withRecord(hex("01765432"), 34));

        final byte[] output = HuaweiPgnssExtra.decode(fields);
        assertRegion(output, 1360, "34010000");
        // Source words at offsets 3 and 9 narrow to bytes, leaving their adjacent padding empty.
        assertRegion(output, 1364,
                "01000302 05000706 09080b00 0f0e0d0c 13121110 17161514 1b1a1918 1d1c1f1e");
        assertZero(output, 1396, 2388);
        assertRegion(output, 2388, "01214365");
        // Source bytes 23..24 are omitted, and output bytes 24..25 remain empty.
        assertRegion(output, 2392,
                "02010304 08070605 0c0b0a09 100f0e0d 14131211 15001716 00001b1a 1c1d0000");
        assertZero(output, 2424, 3160);
        assertRegion(output, 3160, "01020100 000100a5");
        assertRegion(output, 3168, "01000302 05040706 08090b0a 0d0c0f0e 11101312 1514");
        assertZero(output, 3190, 3960);
        assertRegion(output, 3960, "01765432");
        assertRegion(output, 3964,
                "01020000 06050403 0a090807 0e0d0c0b 1211100f 16151413 1a191817 1c1b1e1d 201f2221");
        assertZero(output, 4000, 6232);
    }

    @Test
    public void selectsBothGalileoTimeWidthsAndWrapsTheWeekByte() throws Exception {
        final long week = 257;
        final Map<String, byte[]> fields = emptyFields();
        fields.put("galAlm", galileoHeader(week * 604800 + 153599, 0x7a));
        assertRegion(HuaweiPgnssExtra.decode(fields), 3160, "00010000 0000ff7a");

        fields.put("galAlm", galileoHeader(week * 604800 + 153600, 0x7a));
        assertRegion(HuaweiPgnssExtra.decode(fields), 3160, "00010100 0001007a");

        fields.put("galAlm", galileoHeader(week * 604800 + 604799, 0x7a));
        assertRegion(HuaweiPgnssExtra.decode(fields), 3160, "00010100 ef03007a");

        fields.put("galAlm", galileoHeader(0xffffffffL, 0x7a));
        assertRegion(HuaweiPgnssExtra.decode(fields), 3160, "00bd0100 d601007a");
    }

    @Test
    public void acceptsEachMaximumRecordCount() throws Exception {
        final Map<String, byte[]> fields = emptyFields();
        for (final RowFormat format : ROW_FORMATS) {
            fields.put(format.name(), rows(format, format.capacity()));
        }
        assertEquals(6248, HuaweiPgnssExtra.decode(fields).length);
    }

    @Test
    public void rejectsMissingTruncatedAndOversizedFields() {
        assertThrows(IOException.class, () -> HuaweiPgnssExtra.decode(null));
        for (final String name : emptyFields().keySet()) {
            final Map<String, byte[]> fields = emptyFields();
            final byte[] complete = fields.remove(name);
            assertThrows(name, IOException.class, () -> HuaweiPgnssExtra.decode(fields));
            fields.put(name, null);
            assertThrows(name, IOException.class, () -> HuaweiPgnssExtra.decode(fields));
            for (int length = 0; length < complete.length; length++) {
                fields.put(name, Arrays.copyOf(complete, length));
                assertThrows(name, IOException.class, () -> HuaweiPgnssExtra.decode(fields));
            }
            fields.put(name, Arrays.copyOf(complete, complete.length + 1));
            assertThrows(name, IOException.class, () -> HuaweiPgnssExtra.decode(fields));
        }
    }

    @Test
    public void rejectsTruncatedRecordsCountMismatchesAndCapacityOverflow() {
        for (final RowFormat format : ROW_FORMATS) {
            final Map<String, byte[]> fields = emptyFields();
            final byte[] one = rows(format, 1);
            for (int length = format.header(); length < one.length; length++) {
                fields.put(format.name(), Arrays.copyOf(one, length));
                assertThrows(format.name(), IOException.class, () -> HuaweiPgnssExtra.decode(fields));
            }
            one[format.countOffset()] = 0;
            fields.put(format.name(), one);
            assertThrows(format.name(), IOException.class, () -> HuaweiPgnssExtra.decode(fields));
            fields.put(format.name(), rows(format, format.capacity() + 1));
            assertThrows(format.name(), IOException.class, () -> HuaweiPgnssExtra.decode(fields));
        }
    }

    private static Map<String, byte[]> emptyFields() {
        final Map<String, byte[]> fields = new LinkedHashMap<>();
        fields.put("gpsUtc", new byte[20]);
        fields.put("gpsIon", new byte[8]);
        fields.put("seedInfo", new byte[14]);
        for (final RowFormat format : ROW_FORMATS) {
            fields.put(format.name(), rows(format, 0));
        }
        return fields;
    }

    private static byte[] rows(final RowFormat format, final int count) {
        final byte[] data = new byte[format.header() + count * format.stride()];
        data[format.countOffset()] = (byte) count;
        return data;
    }

    private static byte[] withRecord(final byte[] header, final int length) {
        final byte[] data = Arrays.copyOf(header, header.length + length);
        for (int i = 0; i < length; i++) {
            data[header.length + i] = (byte) (i + 1);
        }
        return data;
    }

    private static byte[] galileoHeader(final long seconds, final int flags) {
        return ByteBuffer.allocate(6).put((byte) 0).putInt((int) seconds).put((byte) flags).array();
    }

    private static byte[] hex(final String value) {
        return HexFormat.of().parseHex(value.replace(" ", ""));
    }

    private static void assertRegion(final byte[] output, final int offset, final String expectedHex) {
        final byte[] expected = hex(expectedHex);
        assertArrayEquals(expected, Arrays.copyOfRange(output, offset, offset + expected.length));
    }

    private static void assertZero(final byte[] output, final int start, final int end) {
        assertArrayEquals(new byte[end - start], Arrays.copyOfRange(output, start, end));
    }
}
