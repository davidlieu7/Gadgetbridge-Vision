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
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Model;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Satellite;

public class HuaweiPgnssGlonassTest {
    private static final int WEEK = 2438;
    private static final int LEAP_SECONDS = 18;
    private static final long WEEK_START = WEEK * 604800L;

    @Test
    public void decodesAxisSeparatedSignedSamplesAcrossWeekBoundary() throws Exception {
        Bits bits = new Bits();
        header(bits, 1, WEEK, 604320, 60, WEEK + 1, 0);
        bits.put(7, 8);
        for (int axis = 0; axis < 3; axis++) {
            for (int sample = 0; sample < 8; sample++) {
                bits.put(axis * 8 + sample - 16, 5);
            }
        }
        HuaweiPgnssGlonass.Acceleration decoded = HuaweiPgnssGlonass.decodeAcceleration(bits.bytes(), LEAP_SECONDS);
        long start = WEEK_START + 604320 + LEAP_SECONDS;
        assertEquals(start, decoded.start());
        assertEquals(WEEK_START + 604800 + LEAP_SECONDS, decoded.end());
        assertEquals(60, decoded.interval());
        assertArrayEquals(new double[]{-16, -8, 0}, unscale(decoded.at(7, start)), 0);
        assertArrayEquals(new double[]{-9, -1, 7}, unscale(decoded.at(7, start + 420)), 0);
        assertThrows(IOException.class, () -> decoded.at(7, start + 1));
        assertThrows(IOException.class, () -> decoded.at(7, decoded.end()));
        assertThrows(IOException.class, () -> decoded.at(8, start));
    }

    @Test
    public void rejectsIncompleteAndInvalidAccelerationStreams() throws Exception {
        byte[] valid = acceleration(240, new int[]{1});
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.decodeAcceleration(new byte[8], LEAP_SECONDS));
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.decodeAcceleration(Arrays.copyOf(valid, valid.length - 1), LEAP_SECONDS));
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.decodeAcceleration(Arrays.copyOf(valid, valid.length + 1), LEAP_SECONDS));
        byte[] padding = valid.clone();
        padding[padding.length - 1] |= 1;
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.decodeAcceleration(padding, LEAP_SECONDS));
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.decodeAcceleration(acceleration(240, new int[]{1, 1}), LEAP_SECONDS));
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.decodeAcceleration(acceleration(240, new int[]{25}), LEAP_SECONDS));
        for (int[] fields : new int[][]{{0, 60, 240}, {25, 60, 240}, {1, 0, 240}, {1, 60, 0}, {1, 60, 604800}}) {
            Bits bits = new Bits();
            header(bits, fields[0], WEEK, 0, fields[1], WEEK + fields[2] / 604800, fields[2] % 604800);
            assertThrows(IOException.class, () -> HuaweiPgnssGlonass.decodeAcceleration(bits.bytes(), LEAP_SECONDS));
        }
    }

    @Test
    public void packsTimeClockCoordinatesVelocityAndAcceleration() throws Exception {
        double[][] coefficients = coefficients();
        coefficients[0][1] = 21600; // One metre per second at the fixed 12-hour derivative scale.
        Model model = new Model(WEEK, 0, LEAP_SECONDS, 43200 + LEAP_SECONDS,
                Math.scalb(5.0, -30), Math.scalb(1.0, -27), coefficients);
        byte[] record = HuaweiPgnssGlonass.encodeRecord(7, model, WEEK_START + 21600 + LEAP_SECONDS,
                LEAP_SECONDS, scaled(1, -2, 3));
        ByteBuffer expected = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        expected.put(0, (byte) 6).putShort(2, (short) 36).putInt(4, -172805).putShort(8, (short) 2).put(12, (byte) 1);
        expected.putInt(16, 2048).putInt(20, 1048).put(24, (byte) 1);
        expected.putInt(28, 4096).put(36, (byte) -2);
        expected.putInt(40, 6144).put(48, (byte) 3);
        assertArrayEquals(expected.array(), record);
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.encodeRecord(7, model, WEEK_START + LEAP_SECONDS,
                LEAP_SECONDS, scaled(128, 0, 0)));
        coefficients[0][0] = Double.NaN;
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.encodeRecord(7, model, WEEK_START + LEAP_SECONDS,
                LEAP_SECONDS, scaled(0, 0, 0)));
    }

    @Test
    public void emitsThreeAndSevenDaysWithEightQuarterHourGroupsPerEpoch() throws Exception {
        List<Satellite> satellites = List.of(satellite(1, 0), satellite(2, 1), satellite(3, 0));
        byte[] acceleration = acceleration(8 * 86400, new int[]{1, 2});
        for (int days : new int[]{3, 7}) {
            byte[] bytes = HuaweiPgnssGlonass.generate(satellites, acceleration, LEAP_SECONDS,
                    WEEK_START + 12345, days);
            assertEquals(1008 + days * 12 * 10016, bytes.length);
            ByteBuffer file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int epoch = 0; epoch < days * 12; epoch++) {
                long firstTime = WEEK_START + 7200 + LEAP_SECONDS + epoch * 7200L;
                assertEquals(firstTime + 3600, Integer.toUnsignedLong(file.getInt(epoch * 12)));
                assertEquals(1008 + epoch * 10016, file.getInt(epoch * 12 + 4));
                assertEquals(10016, file.getInt(epoch * 12 + 8));
                for (int group = 0; group < 8; group++) {
                    int offset = 1008 + epoch * 10016 + group * 1252;
                    assertEquals(1, file.getInt(offset)); // Unhealthy and acceleration-less satellites are omitted.
                    assertEquals(0, file.get(offset + 4));
                    assertEquals((firstTime + group * 900 + 10800 - LEAP_SECONDS) % 86400 / 900,
                            file.getShort(offset + 6));
                    assertEquals(2048, file.getInt(offset + 20));
                    assertEquals(-2, file.get(offset + 40));
                    assertArrayEquals(new byte[23 * 52], Arrays.copyOfRange(bytes, offset + 56, offset + 1252));
                }
            }
            assertArrayEquals(new byte[(84 - days * 12) * 12], Arrays.copyOfRange(bytes, days * 12 * 12, 1008));
        }
    }

    @Test
    public void rejectsRequestsWithoutCompleteHealthyCoverage() {
        byte[] acceleration = acceleration(4 * 86400, new int[]{1});
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.generate(List.of(satellite(1, 1)),
                acceleration, LEAP_SECONDS, WEEK_START, 3));
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.generate(List.of(satellite(1, 0)),
                acceleration, LEAP_SECONDS, WEEK_START, 7));
        assertThrows(IOException.class, () -> HuaweiPgnssGlonass.generate(List.of(satellite(1, 0)),
                acceleration, LEAP_SECONDS, WEEK_START, 4));
    }

    private static Satellite satellite(int id, int flags) {
        List<Model> models = new ArrayList<>();
        for (int start = 0; start < 8 * 86400; start += 43200) {
            models.add(new Model(WEEK + start / 604800, flags, start % 604800 + LEAP_SECONDS,
                    (start + 43200) % 604800 + LEAP_SECONDS, Math.scalb(5.0, -30), 0, coefficients()));
        }
        return new Satellite(id, 0, 0, new double[4], models);
    }

    private static double[][] coefficients() {
        double[][] coefficients = new double[3][20];
        for (int axis = 0; axis < 3; axis++) {
            coefficients[axis][0] = 1000 * (axis + 1);
        }
        return coefficients;
    }

    private static byte[] acceleration(int seconds, int[] ids) {
        int interval = seconds < 900 ? 60 : 900;
        Bits bits = new Bits();
        header(bits, ids.length, WEEK, 0, interval, WEEK + seconds / 604800, seconds % 604800);
        for (int id : ids) {
            bits.put(id, 8);
            for (int value : new int[]{1, -2, 3}) {
                for (int i = 0; i < seconds / interval; i++) {
                    bits.put(value, 5);
                }
            }
        }
        return bits.bytes();
    }

    private static void header(Bits bits, int count, int startWeek, int startTow,
                               int interval, int endWeek, int endTow) {
        bits.put(count, 8);
        bits.put(startWeek, 13);
        bits.put(startTow / 16, 16);
        bits.put(interval / 60, 6);
        bits.put(endWeek, 13);
        bits.put(endTow / 16, 16);
    }

    private static double[] scaled(int x, int y, int z) {
        return new double[]{Math.scalb((double) x, -30), Math.scalb((double) y, -30), Math.scalb((double) z, -30)};
    }

    private static double[] unscale(double[] values) {
        return Arrays.stream(values).map(value -> Math.scalb(value, 30)).toArray();
    }

    private static final class Bits {
        private final byte[] data = new byte[16000];
        private int position;

        void put(long value, int width) {
            for (int bit = width - 1; bit >= 0; bit--) {
                data[position / 8] |= (byte) (((value >>> bit) & 1) << (7 - position % 8));
                position++;
            }
        }

        byte[] bytes() {
            return Arrays.copyOf(data, (position + 7) / 8);
        }
    }
}
