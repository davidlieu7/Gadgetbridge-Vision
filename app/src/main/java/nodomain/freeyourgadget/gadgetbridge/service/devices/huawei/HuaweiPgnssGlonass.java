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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.BitReader;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Model;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Satellite;

/** Encodes GLONASS predictions from Huawei's decoded orbit and acceleration models. */
final class HuaweiPgnssGlonass {
    private static final long WEEK_SECONDS = 604800;
    private static final long DAY_SECONDS = 86400;
    private static final int GROUP_SECONDS = 900;
    private static final int EPOCH_SECONDS = 7200;

    private HuaweiPgnssGlonass() {
    }

    record Acceleration(long start, long end, int interval, Map<Integer, double[][]> satellites) {
        double[] at(int satellite, long time) throws IOException {
            final double[][] samples = satellites.get(satellite);
            if (samples == null || time < start || time >= end || (time - start) % interval != 0) {
                throw new IOException("No GLONASS acceleration sample for the requested satellite and time");
            }
            final long index = (time - start) / interval;
            if (index >= samples.length) {
                throw new IOException("The GLONASS acceleration model does not cover the requested time");
            }
            return samples[(int) index];
        }
    }

    static Acceleration decodeAcceleration(byte[] data, int leapSeconds) throws IOException {
        if (leapSeconds < 0 || leapSeconds > 255) {
            throw new IOException("Invalid GLONASS leap-second offset");
        }
        final BitReader bits = new BitReader(data);
        final int count = (int) bits.unsigned(8);
        final long start = bits.unsigned(13) * WEEK_SECONDS + bits.unsigned(16) * 16 + leapSeconds;
        final int interval = (int) bits.unsigned(6) * 60;
        final long end = bits.unsigned(13) * WEEK_SECONDS + bits.unsigned(16) * 16 + leapSeconds;
        if (count < 1 || count > 24 || interval == 0 || end <= start) {
            throw new IOException("Invalid GLONASS acceleration header");
        }
        final long sections = (end - start) / interval;
        if (sections < 1 || sections > 1000) {
            throw new IOException("Invalid GLONASS acceleration section count");
        }
        final Map<Integer, double[][]> satellites = new HashMap<>();
        for (int satellite = 0; satellite < count; satellite++) {
            final int id = (int) bits.unsigned(8);
            if (id < 1 || id > 24 || satellites.containsKey(id)) {
                throw new IOException("Invalid or duplicate GLONASS satellite identifier");
            }
            final double[][] samples = new double[(int) sections][3];
            for (int axis = 0; axis < 3; axis++) {
                for (int section = 0; section < sections; section++) {
                    samples[section][axis] = Math.scalb((double) bits.signed(5), -30);
                }
            }
            satellites.put(id, samples);
        }
        if (bits.remaining() > 7 || (bits.remaining() > 0 && bits.unsigned(bits.remaining()) != 0)) {
            throw new IOException("Unexpected data after the GLONASS acceleration model");
        }
        return new Acceleration(start, end, interval, satellites);
    }

    static byte[] generate(List<Satellite> satellites, byte[] accelerationData, int leapSeconds,
                           long gpsSeconds, int days) throws IOException {
        if (gpsSeconds < 0 || (days != 3 && days != 7) || satellites.isEmpty() || satellites.size() > 24) {
            throw new IOException("Invalid GLONASS prediction request");
        }
        final Acceleration acceleration = decodeAcceleration(accelerationData, leapSeconds);
        final long base = gpsSeconds / EPOCH_SECONDS * EPOCH_SECONDS + leapSeconds;
        final List<HuaweiAgpsFileGenerator.PredictionEpoch> epochs = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            for (int epoch = 0; epoch < 12; epoch++) {
                final List<List<byte[]>> groups = new ArrayList<>();
                for (int group = 0; group < 8; group++) {
                    final long target = base + day * DAY_SECONDS + epoch * EPOCH_SECONDS + group * GROUP_SECONDS;
                    // Acceleration epochs follow GLONASS time (UTC+3h). Orbit models use GPS time.
                    final long offset = 10800L - leapSeconds;
                    final long time = (target + offset) / acceleration.interval() * acceleration.interval() - offset;
                    final List<byte[]> records = new ArrayList<>();
                    for (Satellite satellite : satellites) {
                        final Model model = HuaweiPgnssOrbit.modelAt(satellite, time);
                        if (model == null || !acceleration.satellites().containsKey(satellite.id())) {
                            continue;
                        }
                        records.add(encodeRecord(satellite.id(), model, time, leapSeconds,
                                acceleration.at(satellite.id(), time)));
                    }
                    if (records.isEmpty()) {
                        throw new IOException("GLONASS prediction models do not cover the requested period");
                    }
                    groups.add(records);
                }
                // The index labels the midpoint of its eight consecutive 15-minute groups.
                epochs.add(new HuaweiAgpsFileGenerator.PredictionEpoch(
                        base + day * DAY_SECONDS + epoch * EPOCH_SECONDS + EPOCH_SECONDS / 2, groups));
            }
        }
        return HuaweiAgpsFileGenerator.generatePrediction(HuaweiAgpsFileGenerator.Constellation.GLONASS, epochs);
    }

    static byte[] encodeRecord(int satellite, Model model, long gpsSeconds, int leapSeconds,
                               double[] acceleration) throws IOException {
        if (satellite < 1 || satellite > 24 || acceleration.length != 3) {
            throw new IOException("Invalid GLONASS output record");
        }
        final HuaweiPgnssOrbit.State state = HuaweiPgnssOrbit.evaluate(model, gpsSeconds);
        final double elapsed = gpsSeconds - (model.week() * WEEK_SECONDS + model.startTow());
        final double clock = model.clockBias() + model.clockDrift() * elapsed;
        final ByteBuffer output = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        output.put(0, (byte) (satellite - 1));
        output.putShort(2, (short) (((gpsSeconds + 10800L - leapSeconds) % DAY_SECONDS) / GROUP_SECONDS));
        output.putInt(4, quantize(-clock * 1073741824.0));
        output.putShort(8, (short) quantize(model.clockDrift() / 3600.0 * 1099511627776.0));
        output.put(12, (byte) 1);
        for (int axis = 0; axis < 3; axis++) {
            final int offset = 16 + axis * 12;
            output.putInt(offset, quantize(state.position()[axis] / 1000.0 * 2048.0));
            output.putInt(offset + 4, quantize(state.velocity()[axis] / 1000.0 * 1048576.0));
            final int scaledAcceleration = quantize(acceleration[axis] * 1073741824.0);
            if (scaledAcceleration < -128 || scaledAcceleration > 127) {
                throw new IOException("GLONASS acceleration exceeds its output field");
            }
            output.put(offset + 8, (byte) scaledAcceleration);
        }
        return output.array();
    }

    private static int quantize(double value) throws IOException {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value >= 2147483648.0) {
            throw new IOException("GLONASS prediction exceeds its output field");
        }
        return (int) value;
    }
}
