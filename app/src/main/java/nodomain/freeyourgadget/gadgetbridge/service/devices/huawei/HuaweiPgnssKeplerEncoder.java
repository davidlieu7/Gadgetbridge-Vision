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

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.Constellation;

/** Encodes the fixed Huawei PGNSS records observed for the GT 7 Pro. */
public final class HuaweiPgnssKeplerEncoder {
    private HuaweiPgnssKeplerEncoder() {}

    /**
     * Encodes one healthy satellite. The caller supplies GPST seconds since the GPS epoch.
     * BDS record times use BDT, which is GPST minus 14 seconds.
     * Parameters are physical values in this order: af0, af1, af2, unused, unused, sqrtA,
     * eccentricity, i0, Omega0, omega, M0, deltaN, OmegaDot, iDot, Cuc, Cus, Cic, Cis, Crc, Crs.
     * Angles use radians. Distances use metres. Clock values use seconds and their derivatives.
     * The first delay supplies TGD in seconds. Remaining delays do not enter these records.
     * Quantization rounds to nearest, with ties away from zero, as in the native format encoder.
     */
    public static byte[] encode(final Constellation constellation, final int svid, final long gpsSeconds,
                                final double[] parameters, final double[] delays, final int health) throws IOException {
        if (constellation == null || constellation == Constellation.GLONASS
                || gpsSeconds < 0 || gpsSeconds > 0xffffffffL || health != 0
                || parameters == null || parameters.length != 20 || delays == null || delays.length < 1) {
            throw new IOException("The PGNSS Kepler record input is invalid");
        }
        final int maximumSvid = switch (constellation) {
            case GPS -> 32;
            case BDS -> 63;
            case GALILEO -> 36;
            case QZS -> 10;
            default -> throw new IOException("This constellation has no Kepler record encoder");
        };
        if (svid < 1 || svid > maximumSvid || (constellation == Constellation.BDS && gpsSeconds < 14)) {
            throw new IOException("The PGNSS satellite or time is invalid");
        }
        for (final double value : parameters) {
            if (!Double.isFinite(value)) {
                throw new IOException("The PGNSS parameters must be finite");
            }
        }
        for (final double delay : delays) {
            if (!Double.isFinite(delay)) {
                throw new IOException("The PGNSS delays must be finite");
            }
        }
        if (parameters[5] <= 0 || parameters[6] < 0 || parameters[6] >= 1) {
            throw new IOException("The PGNSS orbit size or eccentricity is invalid");
        }
        final boolean bds = constellation == Constellation.BDS;
        final long[] orbit = new long[20];
        orbit[5] = quantize(Math.scalb(parameters[5], 19), 32, false);
        orbit[6] = quantize(Math.scalb(parameters[6], 33), 32, false);
        for (int i = 7; i <= 10; i++) {
            orbit[i] = quantize(Math.scalb(parameters[i], 31) / Math.PI, 32, true);
        }
        orbit[11] = quantize(Math.scalb(parameters[11], 43) / Math.PI, 16, true);
        orbit[12] = quantize(Math.scalb(parameters[12], 43) / Math.PI, 24, true);
        orbit[13] = quantize(Math.scalb(parameters[13], 43) / Math.PI, 14, true);
        for (int i = 14; i <= 17; i++) {
            orbit[i] = quantize(Math.scalb(parameters[i], bds ? 31 : 29), bds ? 18 : 16, true);
        }
        for (int i = 18; i <= 19; i++) {
            orbit[i] = quantize(Math.scalb(parameters[i], bds ? 6 : 5), bds ? 18 : 16, true);
        }
        final int size = bds ? 92 : constellation == Constellation.GPS ? 80 : 76;
        final ByteBuffer record = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        record.put(0, (byte) (svid - 1));
        final long tow = (gpsSeconds - (bds ? 14 : 0)) % 604800;
        if (constellation == Constellation.GPS) {
            record.putShort(2, (short) (gpsSeconds / 604800));
            record.putInt(8, (int) orbit[10]);
            record.putShort(12, (short) orbit[11]);
            record.putInt(16, (int) orbit[6]);
            record.putInt(20, (int) orbit[5]);
            record.putInt(24, (int) orbit[8]);
            record.putInt(28, (int) orbit[7]);
            record.putInt(32, (int) orbit[9]);
            record.putInt(36, (int) orbit[12]);
            record.putShort(40, (short) orbit[13]);
            record.putShort(42, (short) orbit[14]);
            record.putShort(44, (short) orbit[15]);
            record.putShort(46, (short) orbit[18]);
            record.putShort(48, (short) orbit[19]);
            record.putShort(50, (short) orbit[16]);
            record.putShort(52, (short) orbit[17]);
            record.put(54, (byte) quantize(Math.scalb(delays[0], 31), 8, true));
            record.putInt(56, (int) quantize(Math.scalb(parameters[0], 31), 32, true));
            record.putInt(60, (int) quantize(Math.scalb(parameters[1], 43), 32, true));
            record.putInt(64, (int) quantize(Math.scalb(parameters[2], 55), 32, true));
            record.putShort(72, (short) (tow / 16));
            record.putShort(74, (short) (tow / 16));
            record.put(77, (byte) 0xff);
        } else if (bds) {
            final int toe = (int) (tow / 8);
            record.putShort(2, (short) (toe & 0xffc0));
            record.putInt(8, toe);
            record.putInt(12, (int) quantize(Math.scalb(parameters[0], 33), 32, true));
            record.putInt(16, (int) quantize(Math.scalb(parameters[1], 50), 32, true));
            record.putShort(20, (short) quantize(Math.scalb(parameters[2], 66), 16, true));
            record.putShort(22, (short) quantize(delays[0] * 1e10, 16, true));
            record.putInt(28, toe);
            record.putInt(32, (int) orbit[5]);
            record.putInt(36, (int) orbit[6]);
            record.putInt(40, (int) orbit[9]);
            record.putShort(44, (short) orbit[11]);
            record.putInt(48, (int) orbit[10]);
            record.putInt(52, (int) orbit[8]);
            record.putInt(56, (int) orbit[12]);
            record.putInt(60, (int) orbit[7]);
            record.putShort(64, (short) orbit[13]);
            record.putInt(68, (int) orbit[14]);
            record.putInt(72, (int) orbit[15]);
            record.putInt(76, (int) orbit[18]);
            record.putInt(80, (int) orbit[19]);
            record.putInt(84, (int) orbit[16]);
            record.putInt(88, (int) orbit[17]);
        } else {
            final boolean galileo = constellation == Constellation.GALILEO;
            if (galileo) {
                record.putInt(4, (int) (tow / 60));
                record.putInt(24, (int) (tow / 60));
                record.putShort(20, (short) quantize(Math.scalb(delays[0], 32), 16, true));
            } else {
                record.putShort(4, (short) (tow / 16));
                record.putShort(24, (short) (tow / 16));
                record.put(20, (byte) quantize(Math.scalb(delays[0], 31), 8, true));
            }
            record.putInt(8, (int) quantize(Math.scalb(parameters[2], galileo ? 59 : 55), 32, true));
            record.putInt(12, (int) quantize(Math.scalb(parameters[1], galileo ? 46 : 43), 32, true));
            record.putInt(16, (int) quantize(Math.scalb(parameters[0], galileo ? 34 : 31), 32, true));
            record.putInt(28, (int) orbit[9]);
            record.putShort(32, (short) orbit[11]);
            record.putInt(36, (int) orbit[10]);
            record.putInt(40, (int) orbit[12]);
            record.putInt(44, (int) orbit[6]);
            record.putShort(48, (short) orbit[13]);
            record.putInt(52, (int) orbit[5]);
            record.putInt(56, (int) orbit[7]);
            record.putInt(60, (int) orbit[8]);
            record.putShort(64, (short) orbit[19]);
            record.putShort(66, (short) orbit[17]);
            record.putShort(68, (short) orbit[15]);
            record.putShort(70, (short) orbit[18]);
            record.putShort(72, (short) orbit[16]);
            record.putShort(74, (short) orbit[14]);
        }
        return record.array();
    }

    private static long quantize(final double value, final int width, final boolean signed) throws IOException {
        if (!Double.isFinite(value)) {
            throw new IOException("The encoded PGNSS value is not finite");
        }
        // The native encoders use AArch64 FCVTAS/FCVTAU, including for half-way values.
        final double magnitude = Math.abs(value);
        double rounded = Math.floor(magnitude);
        if (magnitude - rounded >= 0.5) {
            rounded += 1;
        }
        final long quantized = (long) Math.copySign(rounded, value);
        final long minimum = signed ? -(1L << (width - 1)) : 0;
        final long maximum = signed ? (1L << (width - 1)) - 1 : (1L << width) - 1;
        if (quantized < minimum || quantized > maximum) {
            throw new IOException("The encoded PGNSS value exceeds its field range");
        }
        return quantized;
    }
}
