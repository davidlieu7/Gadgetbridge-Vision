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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.Constellation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Model;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Satellite;

public class HuaweiPgnssSeedTest {
    private static final int[] WIDTHS = {
            38, 38, 37, 37, 36, 34, 33, 32, 30, 29, 27, 25, 23, 22, 20, 19, 17, 16, 14, 13
    };

    @Test
    public void bitReaderUsesMsbOrderAndSignedValuesAcrossByteBoundaries() throws Exception {
        final HuaweiPgnssSeed.BitReader reader = new HuaweiPgnssSeed.BitReader(new byte[]{(byte) 0xf0, 0x55, (byte) 0xab});
        assertEquals(7, reader.unsigned(3));
        assertEquals(-16, reader.signed(5));
        assertEquals(85, reader.unsigned(8));
        assertEquals(-85, reader.signed(8));
        assertEquals(24, reader.position());
        assertEquals(0, reader.remaining());
        assertThrows(IOException.class, () -> reader.unsigned(1));
        assertThrows(IOException.class, () -> new HuaweiPgnssSeed.BitReader(null));
        assertThrows(IOException.class, () -> new HuaweiPgnssSeed.BitReader(new byte[8]).unsigned(64));
    }

    @Test
    public void decodesAllCommonFieldsAndPreservesMultipleModels() throws Exception {
        for (final Constellation constellation : Constellation.values()) {
            final List<Satellite> satellites = HuaweiPgnssSeed.decode(constellation, fixture(1, 2, 2, 0), 19);
            assertEquals(1, satellites.size());
            final Satellite satellite = satellites.get(0);
            assertEquals(2, satellite.id());
            assertEquals(3, satellite.fitType());
            assertEquals(5, satellite.health());
            assertEquals(2, satellite.models().size());
            assertEquals(Math.scalb(-32768.0, -32), satellite.delays()[0], 0);
            assertEquals(Math.scalb(32767.0, -32), satellite.delays()[1], 0);
            assertEquals(Math.scalb(-1.0, -32), satellite.delays()[2], 0);
            assertEquals(Math.scalb(65535.0, -32), satellite.delays()[3], 0);
            final Model first = satellite.models().get(0);
            final int adjustment = constellation == Constellation.GLONASS ? 19 : 0;
            assertEquals(100, first.week());
            assertEquals(7, first.flags());
            assertEquals(604784 + adjustment, first.startTow());
            assertEquals(43184 + adjustment, first.endTow());
            assertEquals(Math.scalb(-(double) (1L << 30), -34), first.clockBias(), 0);
            assertEquals(Math.scalb((double) ((1L << 24) - 1), -50), first.clockDrift(), 0);
            assertEquals(101, satellite.models().get(1).week());
            assertEquals(43184 + adjustment, satellite.models().get(1).startTow());
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(20, first.coefficients()[axis].length);
                for (int coefficient = 0; coefficient < 20; coefficient++) {
                    assertEquals(Math.scalb((double) coefficient(axis, coefficient), -10),
                            first.coefficients()[axis][coefficient], 0);
                }
            }
        }
    }

    @Test
    public void rejectsInvalidCountsIdentifiersAndReservedBits() throws Exception {
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, new byte[]{0}, 0));
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, new byte[]{33}, 0));
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, fixture(1, 0, 1, 0), 0));
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.QZS, fixture(1, 11, 1, 0), 0));
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, fixture(2, 2, 1, 0), 0));
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, fixture(1, 2, 0, 0), 0));
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, fixture(1, 2, 1, 1), 0));
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(null, fixture(1, 2, 1, 0), 0));
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, fixture(1, 2, 1, 0), -1));
    }

    @Test
    public void rejectsTruncationAndNonzeroOrExcessPadding() throws Exception {
        final byte[] valid = fixture(1, 2, 1, 0);
        for (int size = 0; size < valid.length; size++) {
            final byte[] truncated = Arrays.copyOf(valid, size);
            assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, truncated, 0));
        }
        final byte[] extraByte = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, extraByte, 0));
        valid[valid.length - 1] |= 1;
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, valid, 0));
    }

    @Test
    public void qzsPadsOnlyTheFinalCoefficientWithinSevenMissingBits() throws Exception {
        for (int sections = 1; sections <= 7; sections++) {
            final byte[] complete = fixture(1, 2, sections, 0);
            final byte[] missingFinalByte = Arrays.copyOf(complete, complete.length - 1);
            final Satellite decoded = HuaweiPgnssSeed.decode(Constellation.QZS, missingFinalByte, 0).get(0);
            // These synthetic records omit one through seven low bits respectively.
            final long expected = (coefficient(2, 19) >> sections) << sections;
            assertEquals(Math.scalb((double) expected, -10),
                    decoded.models().get(sections - 1).coefficients()[2][19], 0);
            final byte[] missingMore = Arrays.copyOf(complete, complete.length - 2);
            assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.QZS, missingMore, 0));
        }
    }

    @Test
    public void rejectsOutOfRangeAndRepeatedModelTimes() throws Exception {
        final byte[] invalid = fixture(1, 2, 1, 0);
        setBits(invalid, 8 + 120 + 13 + 8, 16, 65535);
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, invalid, 0));
        final byte[] repeated = fixture(1, 2, 2, 0);
        setBits(repeated, 8 + 120 + 1729, 13, 100);
        setBits(repeated, 8 + 120 + 1729 + 13 + 8, 16, 604784 / 16);
        assertThrows(IOException.class, () -> HuaweiPgnssSeed.decode(Constellation.GPS, repeated, 0));
    }

    private static long coefficient(final int axis, final int index) {
        if (axis == 0) {
            return -(1L << (WIDTHS[index] - 1));
        }
        if (axis == 1) {
            return (1L << (WIDTHS[index] - 1)) - 1;
        }
        return -291;
    }

    private static byte[] fixture(final int satellites, final int id, final int sections, final int reserved) {
        final Writer writer = new Writer();
        writer.put(satellites, 8);
        for (int satellite = 0; satellite < satellites; satellite++) {
            writer.put(id, 8);
            writer.put(sections, 6);
            writer.put(3, 2);
            writer.put(5, 8);
            writer.put(-32768, 16);
            writer.put(32767, 16);
            writer.put(-1, 16);
            writer.put(65535, 16);
            writer.put(reserved, 32);
            for (int section = 0; section < sections; section++) {
                final long start = 100L * 604800 + 604784 + 43200L * section;
                writer.put(start / 604800, 13);
                writer.put(7, 8);
                writer.put((start % 604800) / 16, 16);
                writer.put(((start + 43200) % 604800) / 16, 16);
                writer.put(-(1L << 30), 31);
                writer.put((1L << 24) - 1, 25);
                for (int axis = 0; axis < 3; axis++) {
                    for (int i = 0; i < WIDTHS.length; i++) {
                        writer.put(coefficient(axis, i), WIDTHS[i]);
                    }
                }
            }
        }
        return writer.bytes();
    }

    private static void setBits(final byte[] bytes, final int offset, final int width, final long value) {
        for (int i = 0; i < width; i++) {
            final int bit = offset + i;
            final int mask = 1 << (7 - (bit & 7));
            bytes[bit / 8] = (byte) ((bytes[bit / 8] & ~mask)
                    | (((value >>> (width - i - 1)) & 1) == 0 ? 0 : mask));
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int byteValue;
        private int bits;

        void put(final long value, final int width) {
            for (int i = width - 1; i >= 0; i--) {
                byteValue = (byteValue << 1) | (int) ((value >>> i) & 1);
                if (++bits == 8) {
                    output.write(byteValue);
                    bits = 0;
                    byteValue = 0;
                }
            }
        }

        byte[] bytes() {
            if (bits != 0) {
                output.write(byteValue << (8 - bits));
            }
            return output.toByteArray();
        }
    }
}
