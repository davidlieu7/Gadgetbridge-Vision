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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.Constellation;

/**
 * Decodes the common bit layout of Huawei HW_EEV2 navigation seeds.
 * The layout and scales were checked against the Huawei native decoder and GT 7 Pro data.
 * This class preserves all model sections. It does not calculate broadcast ephemerides.
 */
public final class HuaweiPgnssSeed {
    private static final int[] COEFFICIENT_BITS = {
            38, 38, 37, 37, 36, 34, 33, 32, 30, 29, 27, 25, 23, 22, 20, 19, 17, 16, 14, 13
    };
    private static final int SECONDS_PER_WEEK = 604_800;

    public record Satellite(int id, int fitType, int health, double[] delays, List<Model> models) {}

    /** GLONASS times include the supplied leap seconds. The week remains the encoded GPS week. */
    public record Model(int week, int flags, int startTow, int endTow,
                        double clockBias, double clockDrift, double[][] coefficients) {}

    private HuaweiPgnssSeed() {}

    public static List<Satellite> decode(final Constellation constellation, final byte[] nav,
                                         final int leapSeconds) throws IOException {
        if (constellation == null || leapSeconds < 0 || leapSeconds > 255) {
            throw new IOException("The PGNSS constellation or leap seconds are invalid");
        }
        final int maximumSatellites = switch (constellation) {
            case GPS -> 32;
            case BDS -> 63;
            case GLONASS -> 24;
            case GALILEO -> 36;
            case QZS -> 10;
        };
        final BitReader bits = new BitReader(nav);
        final int satelliteCount = (int) bits.unsigned(8);
        if (satelliteCount < 1 || satelliteCount > maximumSatellites) {
            throw new IOException("The PGNSS satellite count is invalid");
        }
        final List<Satellite> satellites = new ArrayList<>(satelliteCount);
        final Set<Integer> ids = new HashSet<>();
        for (int satelliteIndex = 0; satelliteIndex < satelliteCount; satelliteIndex++) {
            final int id = (int) bits.unsigned(8);
            final int sectionCount = (int) bits.unsigned(6);
            final int fitType = (int) bits.unsigned(2);
            final int health = (int) bits.unsigned(8);
            if (id < 1 || id > maximumSatellites || !ids.add(id) || sectionCount < 1) {
                throw new IOException("The PGNSS satellite header is invalid");
            }
            final double[] delays = {
                    Math.scalb((double) bits.signed(16), -32),
                    Math.scalb((double) bits.signed(16), -32),
                    Math.scalb((double) bits.signed(16), -32),
                    Math.scalb((double) bits.unsigned(16), -32)
            };
            if (bits.unsigned(32) != 0) {
                throw new IOException("The PGNSS satellite header has unsupported reserved bits");
            }
            final List<Model> models = new ArrayList<>(sectionCount);
            long previousStart = -1;
            for (int section = 0; section < sectionCount; section++) {
                final int week = (int) bits.unsigned(13);
                final int flags = (int) bits.unsigned(8);
                final int startTow = (int) bits.unsigned(16) * 16;
                final int endTow = (int) bits.unsigned(16) * 16;
                final long start = (long) week * SECONDS_PER_WEEK + startTow;
                if (startTow >= SECONDS_PER_WEEK || endTow >= SECONDS_PER_WEEK
                        || startTow == endTow || start <= previousStart) {
                    throw new IOException("The PGNSS model time is invalid");
                }
                previousStart = start;
                final double bias = Math.scalb((double) bits.signed(31), -34);
                final double drift = Math.scalb((double) bits.signed(25), -50);
                final double[][] coefficients = new double[3][COEFFICIENT_BITS.length];
                for (int axis = 0; axis < coefficients.length; axis++) {
                    for (int coefficient = 0; coefficient < COEFFICIENT_BITS.length; coefficient++) {
                        final int width = COEFFICIENT_BITS[coefficient];
                        final int missing = width - bits.remaining();
                        final long encoded;
                        if (constellation == Constellation.QZS && satelliteIndex == satelliteCount - 1
                                && section == sectionCount - 1 && axis == 2 && coefficient == 19
                                && missing > 0 && missing <= 7) {
                            // A live HW_EEV2 QZS response omits four low bits here.
                            // The native reader fills those missing bits with zero.
                            encoded = bits.signed(width - missing) << missing;
                        } else {
                            encoded = bits.signed(width);
                        }
                        coefficients[axis][coefficient] = Math.scalb((double) encoded, -10);
                    }
                }
                final int timeAdjustment = constellation == Constellation.GLONASS ? leapSeconds : 0;
                models.add(new Model(week, flags, startTow + timeAdjustment, endTow + timeAdjustment,
                        bias, drift, coefficients));
            }
            satellites.add(new Satellite(id, fitType, health, delays, List.copyOf(models)));
        }
        if (bits.remaining() > 7 || (bits.remaining() > 0 && bits.unsigned(bits.remaining()) != 0)) {
            throw new IOException("The PGNSS seed has invalid trailing padding");
        }
        return List.copyOf(satellites);
    }

    /** Reads MSB-first integers. Signed integers use two's complement. */
    static final class BitReader {
        private final byte[] bytes;
        private int position;

        BitReader(final byte[] bytes) throws IOException {
            if (bytes == null || bytes.length > Integer.MAX_VALUE / 8) {
                throw new IOException("The PGNSS bit stream size is invalid");
            }
            this.bytes = bytes;
        }

        int position() {
            return position;
        }

        int remaining() {
            return bytes.length * 8 - position;
        }

        long unsigned(final int width) throws IOException {
            if (width < 1 || width > 63 || remaining() < width) {
                throw new IOException("The PGNSS bit stream is truncated or its field width is invalid");
            }
            long value = 0;
            int needed = width;
            while (needed > 0) {
                final int count = Math.min(needed, 8 - (position & 7));
                final int shift = 8 - (position & 7) - count;
                value = (value << count) | ((bytes[position >>> 3] >>> shift) & ((1 << count) - 1));
                position += count;
                needed -= count;
            }
            return value;
        }

        long signed(final int width) throws IOException {
            final long value = unsigned(width);
            return (value << (64 - width)) >> (64 - width);
        }
    }
}
