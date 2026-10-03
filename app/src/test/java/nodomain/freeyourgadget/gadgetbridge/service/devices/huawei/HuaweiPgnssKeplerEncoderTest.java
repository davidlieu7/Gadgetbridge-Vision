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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.Constellation;

public class HuaweiPgnssKeplerEncoderTest {
    private static final long TIME = 100L * 604800 + 9614;
    private static final double[] DELAYS = {Math.scalb(1.0, -30)};

    @Test
    public void gpsRecordUsesItsOffsetsScalesAndUnsignedOrbitFields() throws Exception {
        final ByteBuffer record = encode(Constellation.GPS, parameters());
        assertEquals(80, record.capacity());
        assertEquals(2, record.get(0));
        assertEquals(100, record.getShort(2));
        assertEquals(-536870912, record.getInt(8));
        assertEquals(8, record.getShort(12));
        assertEquals(1073741824, record.getInt(16));
        assertEquals(3145728000L, Integer.toUnsignedLong(record.getInt(20)));
        assertEquals(-1073741824, record.getInt(24));
        assertEquals(536870912, record.getInt(28));
        assertEquals(268435456, record.getInt(32));
        assertEquals(-256, record.getInt(36));
        assertEquals(2, record.getShort(40));
        assertEquals(3, record.getShort(42));
        assertEquals(-4, record.getShort(44));
        assertEquals(40, record.getShort(46));
        assertEquals(-80, record.getShort(48));
        assertEquals(5, record.getShort(50));
        assertEquals(-6, record.getShort(52));
        assertEquals(2, record.get(54));
        assertEquals(2048, record.getInt(56));
        assertEquals(-8, record.getInt(60));
        assertEquals(0, record.getInt(64));
        assertEquals(600, record.getShort(72));
        assertEquals(600, record.getShort(74));
        assertEquals(255, record.get(77) & 0xff);
        for (final int offset : new int[]{1, 4, 5, 6, 7, 14, 15, 55, 68, 69, 70, 71, 76, 78, 79}) {
            assertEquals(0, record.get(offset));
        }
    }

    @Test
    public void galileoAndQzsShareOrbitLayoutButUseDifferentClockAndTimeScales() throws Exception {
        for (final Constellation constellation : new Constellation[]{Constellation.GALILEO, Constellation.QZS}) {
            final boolean galileo = constellation == Constellation.GALILEO;
            final ByteBuffer record = encode(constellation, parameters());
            assertEquals(76, record.capacity());
            assertEquals(2, record.get(0));
            assertEquals(galileo ? 160 : 600, record.getInt(4));
            assertEquals(galileo ? 1 : 0, record.getInt(8));
            assertEquals(galileo ? -64 : -8, record.getInt(12));
            assertEquals(galileo ? 16384 : 2048, record.getInt(16));
            assertEquals(galileo ? 4 : 2, record.getShort(20));
            assertEquals(galileo ? 160 : 600, record.getInt(24));
            assertEquals(268435456, record.getInt(28));
            assertEquals(8, record.getShort(32));
            assertEquals(-536870912, record.getInt(36));
            assertEquals(-256, record.getInt(40));
            assertEquals(1073741824, record.getInt(44));
            assertEquals(2, record.getShort(48));
            assertEquals(3145728000L, Integer.toUnsignedLong(record.getInt(52)));
            assertEquals(536870912, record.getInt(56));
            assertEquals(-1073741824, record.getInt(60));
            assertEquals(-80, record.getShort(64));
            assertEquals(-6, record.getShort(66));
            assertEquals(-4, record.getShort(68));
            assertEquals(40, record.getShort(70));
            assertEquals(5, record.getShort(72));
            assertEquals(3, record.getShort(74));
        }
    }

    @Test
    public void bdsUsesBdtAndWiderHarmonicFields() throws Exception {
        final ByteBuffer record = encode(Constellation.BDS, parameters());
        assertEquals(92, record.capacity());
        assertEquals(2, record.get(0));
        assertEquals(1152, record.getShort(2));
        assertEquals(1200, record.getInt(8));
        assertEquals(8192, record.getInt(12));
        assertEquals(-1024, record.getInt(16));
        assertEquals(64, record.getShort(20));
        assertEquals(9, record.getShort(22));
        assertEquals(1200, record.getInt(28));
        assertEquals(3145728000L, Integer.toUnsignedLong(record.getInt(32)));
        assertEquals(1073741824, record.getInt(36));
        assertEquals(268435456, record.getInt(40));
        assertEquals(8, record.getShort(44));
        assertEquals(-536870912, record.getInt(48));
        assertEquals(-1073741824, record.getInt(52));
        assertEquals(-256, record.getInt(56));
        assertEquals(536870912, record.getInt(60));
        assertEquals(2, record.getShort(64));
        assertEquals(12, record.getInt(68));
        assertEquals(-16, record.getInt(72));
        assertEquals(80, record.getInt(76));
        assertEquals(-160, record.getInt(80));
        assertEquals(20, record.getInt(84));
        assertEquals(-24, record.getInt(88));
        final ByteBuffer boundary = ByteBuffer.wrap(HuaweiPgnssKeplerEncoder.encode(
                Constellation.BDS, 1, 604805, parameters(), DELAYS, 0)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(75598, boundary.getInt(8));
    }

    @Test
    public void quantizationRoundsToNearestWithTiesAwayFromZero() throws Exception {
        for (final double value : new double[]{3.5, 3.75}) {
            final double[] values = parameters();
            values[14] = Math.scalb(value, -29);
            values[15] = Math.scalb(-value, -29);
            final ByteBuffer record = encode(Constellation.GPS, values);
            assertEquals(4, record.getShort(42));
            assertEquals(-4, record.getShort(44));
        }
        final double[] belowHalf = parameters();
        belowHalf[14] = Math.scalb(Math.nextDown(0.5), -29);
        belowHalf[15] = -belowHalf[14];
        final ByteBuffer record = encode(Constellation.GPS, belowHalf);
        assertEquals(0, record.getShort(42));
        assertEquals(0, record.getShort(44));
    }

    @Test
    public void rejectsValuesThatRoundBeyondTheFieldBoundary() throws Exception {
        final double[] values = parameters();
        values[14] = Math.scalb(32767.5, -29);
        assertThrows(IOException.class, () -> encode(Constellation.GPS, values));
        values[14] = Math.scalb(-32768.5, -29);
        assertThrows(IOException.class, () -> encode(Constellation.GPS, values));
        values[14] = Math.scalb(-32768.25, -29);
        assertEquals(-32768, encode(Constellation.GPS, values).getShort(42));
    }

    @Test
    public void rejectsNativeOrbitOverflowsBeforeNarrowing() throws Exception {
        for (final Constellation constellation : new Constellation[]{Constellation.GPS, Constellation.BDS,
                Constellation.GALILEO, Constellation.QZS}) {
            for (final int index : new int[]{11, 12, 13, 14, 15, 16, 17, 18, 19}) {
                final double[] values = parameters();
                values[index] = switch (index) {
                    case 11 -> Math.scalb(Math.PI, -27);
                    case 12 -> Math.scalb(Math.PI, -19);
                    case 13 -> Math.scalb(Math.PI, -29);
                    case 14, 15, 16, 17 -> Math.scalb(1.0, -12);
                    default -> 4096;
                };
                assertThrows(IOException.class, () -> encode(constellation, values));
                values[index] = -values[index] * 2;
                assertThrows(IOException.class, () -> encode(constellation, values));
            }
        }
    }

    @Test
    public void rejectsInvalidInputAndUnhealthySatellites() throws Exception {
        assertThrows(IOException.class, () -> HuaweiPgnssKeplerEncoder.encode(Constellation.GLONASS, 1, TIME, parameters(), DELAYS, 0));
        assertThrows(IOException.class, () -> HuaweiPgnssKeplerEncoder.encode(Constellation.GPS, 0, TIME, parameters(), DELAYS, 0));
        assertThrows(IOException.class, () -> HuaweiPgnssKeplerEncoder.encode(Constellation.QZS, 11, TIME, parameters(), DELAYS, 0));
        assertThrows(IOException.class, () -> HuaweiPgnssKeplerEncoder.encode(Constellation.GPS, 1, TIME, parameters(), DELAYS, 1));
        assertThrows(IOException.class, () -> HuaweiPgnssKeplerEncoder.encode(Constellation.BDS, 1, 13, parameters(), DELAYS, 0));
        assertThrows(IOException.class, () -> HuaweiPgnssKeplerEncoder.encode(Constellation.GPS, 1, TIME, new double[19], DELAYS, 0));
        final double[] invalid = parameters();
        invalid[5] = Double.NaN;
        assertThrows(IOException.class, () -> encode(Constellation.GPS, invalid));
        invalid[5] = 9000;
        assertThrows(IOException.class, () -> encode(Constellation.GPS, invalid));
        invalid[5] = 0;
        assertThrows(IOException.class, () -> encode(Constellation.GPS, invalid));
    }

    private static ByteBuffer encode(final Constellation constellation, final double[] parameters) throws IOException {
        return ByteBuffer.wrap(HuaweiPgnssKeplerEncoder.encode(constellation, 3, TIME, parameters, DELAYS, 0))
                .order(ByteOrder.LITTLE_ENDIAN);
    }

    private static double[] parameters() {
        return new double[]{
                Math.scalb(1.0, -20), Math.scalb(-1.0, -40), Math.scalb(1.0, -60), 0, 0,
                6000, 0.125, Math.PI / 4, -Math.PI / 2, Math.PI / 8, -Math.PI / 4,
                Math.scalb(Math.PI, -40), Math.scalb(-Math.PI, -35), Math.scalb(Math.PI, -42),
                Math.scalb(3.0, -29), Math.scalb(-4.0, -29), Math.scalb(5.0, -29), Math.scalb(-6.0, -29),
                1.25, -2.5
        };
    }
}
