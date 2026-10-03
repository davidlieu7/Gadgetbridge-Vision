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
import java.io.InterruptedIOException;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.Constellation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssOrbit.State;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Model;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Satellite;

/** Fits broadcast orbital parameters to the position predictions in an HW_EEV2 seed. */
final class HuaweiPgnssKepler {
    private static final int SAMPLE_COUNT = 49;
    private static final int SAMPLE_STEP = 300;
    private static final int PARAMETER_COUNT = 15;
    private static final double[] FIT_SCALE = {
            1, 1, 1, 1, 1, 1, 1.0 / 86400, 1.0 / 86400, 1.0 / 86400,
            1, 1, 1, 1, 100000, 100000
    };
    private static final double COS_TILT = Math.cos(Math.toRadians(5));
    private static final double SIN_TILT = Math.sin(Math.toRadians(5));

    private HuaweiPgnssKepler() {}

    /** Returns the 20 clock/orbit parameters used by HuaweiPgnssKeplerEncoder. */
    static double[] fit(final Constellation constellation, final Satellite satellite,
                        final long gpsSeconds) throws IOException {
        if (constellation == Constellation.GLONASS || satellite.fitType() != 0) {
            throw new IOException("The PGNSS orbit model is unsupported");
        }
        final long toe = gpsSeconds - (constellation == Constellation.BDS ? 14 : 0);
        final boolean geo = constellation == Constellation.BDS && (satellite.id() <= 5 || satellite.id() >= 59);
        final double[][] observations = new double[SAMPLE_COUNT][];
        State center = null;
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            final long time = gpsSeconds + (sample - SAMPLE_COUNT / 2) * SAMPLE_STEP;
            final Model model = HuaweiPgnssOrbit.modelAt(satellite, time);
            if (model == null) {
                throw new IOException("The PGNSS orbit has no healthy coverage");
            }
            final State state = HuaweiPgnssOrbit.evaluate(model, time);
            observations[sample] = geo ? geoPosition(state.position(), time - gpsSeconds) : state.position();
            if (sample == SAMPLE_COUNT / 2) {
                center = state;
            }
        }
        final double[] parameters = initial(constellation, geo, toe, center);
        double previousError = Double.POSITIVE_INFINITY;
        for (int iteration = 0; iteration < 12; iteration++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("PGNSS prediction was cancelled");
            }
            final double[][] matrix = new double[SAMPLE_COUNT * 3][PARAMETER_COUNT];
            final double[] residual = new double[SAMPLE_COUNT * 3];
            double squaredError = 0;
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                final double[][] derivative = new double[3][PARAMETER_COUNT];
                final double[] predicted = position(constellation, geo, parameters, toe,
                        (sample - SAMPLE_COUNT / 2) * SAMPLE_STEP, derivative);
                for (int axis = 0; axis < 3; axis++) {
                    final int row = sample * 3 + axis;
                    residual[row] = observations[sample][axis] - predicted[axis];
                    squaredError += residual[row] * residual[row];
                    for (int parameter = 0; parameter < PARAMETER_COUNT; parameter++) {
                        matrix[row][parameter] = derivative[axis][parameter] * FIT_SCALE[parameter];
                    }
                }
            }
            final double error = Math.sqrt(squaredError / (residual.length - PARAMETER_COUNT));
            if (error < 0.001 || (error <= 1 && Math.abs(previousError - error) < 1e-7)) {
                break;
            }
            previousError = error;
            final double[] correction = leastSquares(matrix, residual);
            for (int parameter = 0; parameter < PARAMETER_COUNT; parameter++) {
                parameters[parameter + 5] += correction[parameter] * FIT_SCALE[parameter];
            }
        }
        // Check the final nonlinear model, not only the linearized residual used by the solver.
        double squaredError = 0;
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            final double[] predicted = position(constellation, geo, parameters, toe,
                    (sample - SAMPLE_COUNT / 2) * SAMPLE_STEP, null);
            for (int axis = 0; axis < 3; axis++) {
                squaredError += Math.pow(predicted[axis] - observations[sample][axis], 2);
            }
        }
        if (!Double.isFinite(squaredError) || squaredError > SAMPLE_COUNT * 3 - PARAMETER_COUNT) {
            throw new IOException("The PGNSS broadcast orbit fit exceeds one meter");
        }
        for (int index : new int[]{8, 9, 10}) {
            parameters[index] = Math.IEEEremainder(parameters[index], 2 * Math.PI);
        }
        final Model clock = HuaweiPgnssOrbit.modelAt(satellite, toe);
        if (clock == null) {
            throw new IOException("The PGNSS clock has no healthy coverage");
        }
        parameters[0] = clock.clockBias() + clock.clockDrift()
                * (toe - (long) clock.week() * 604800 - clock.startTow());
        parameters[1] = clock.clockDrift();
        parameters[3] = satellite.delays()[0];
        return parameters;
    }

    /** Broadcast ephemeris equations, with analytic derivatives for the 15 orbital parameters. */
    static double[] position(final Constellation constellation, final boolean geo, final double[] p,
                             final long toe, final double dt, final double[][] derivative) throws IOException {
        if (!(p[5] > 0) || p[6] < 0 || p[6] >= 1) {
            throw new IOException("The PGNSS broadcast orbit is invalid");
        }
        final double a = p[5] * p[5];
        final double meanMotion = Math.sqrt(gravity(constellation)) / (a * p[5]);
        final double meanAnomaly = p[10] + (meanMotion + p[11]) * dt;
        double eccentricAnomaly = meanAnomaly;
        for (int i = 0; i < 15; i++) {
            final double change = (eccentricAnomaly - p[6] * Math.sin(eccentricAnomaly) - meanAnomaly)
                    / (1 - p[6] * Math.cos(eccentricAnomaly));
            eccentricAnomaly -= change;
            if (Math.abs(change) < 1e-13) {
                break;
            }
        }
        final double sinE = Math.sin(eccentricAnomaly);
        final double cosE = Math.cos(eccentricAnomaly);
        final double denominator = 1 - p[6] * cosE;
        final double root = Math.sqrt(1 - p[6] * p[6]);
        final double phi = Math.atan2(root * sinE, cosE - p[6]) + p[9];
        final double sin2 = Math.sin(2 * phi);
        final double cos2 = Math.cos(2 * phi);
        final double u = phi + p[14] * cos2 + p[15] * sin2;
        final double radius = a * denominator + p[18] * cos2 + p[19] * sin2;
        final double inclination = p[7] + p[13] * dt + p[16] * cos2 + p[17] * sin2;
        final double omega = p[8] + (p[12] - (geo ? 0 : earthRate(constellation))) * dt
                - earthRate(constellation) * Math.floorMod(toe, 604800);
        final double su = Math.sin(u), cu = Math.cos(u);
        final double si = Math.sin(inclination), ci = Math.cos(inclination);
        final double so = Math.sin(omega), co = Math.cos(omega);
        final double x = radius * cu, y = radius * su;
        final double[] result = {x * co - y * ci * so, x * so + y * ci * co, y * si};
        if (derivative != null) {
            for (int j = 0; j < PARAMETER_COUNT; j++) {
                final int k = j + 5;
                final double de = k == 6 ? 1 : 0;
                final double dMean = k == 5 ? -3 * meanMotion / p[5] * dt
                        : k == 10 ? 1 : k == 11 ? dt : 0;
                final double dE = (dMean + de * sinE) / denominator;
                final double dPhi = root / denominator * dE + sinE / (root * denominator) * de
                        + (k == 9 ? 1 : 0);
                final double du = (1 + 2 * (p[15] * cos2 - p[14] * sin2)) * dPhi
                        + (k == 14 ? cos2 : k == 15 ? sin2 : 0);
                final double dr = (k == 5 ? 2 * p[5] * denominator : 0)
                        + a * (p[6] * sinE * dE - cosE * de)
                        + 2 * (p[19] * cos2 - p[18] * sin2) * dPhi
                        + (k == 18 ? cos2 : k == 19 ? sin2 : 0);
                final double di = (k == 7 ? 1 : k == 13 ? dt : 0)
                        + 2 * (p[17] * cos2 - p[16] * sin2) * dPhi
                        + (k == 16 ? cos2 : k == 17 ? sin2 : 0);
                final double dOmega = k == 8 ? 1 : k == 12 ? dt : 0;
                final double dx = dr * cu - y * du;
                final double dy = dr * su + x * du;
                derivative[0][j] = dx * co - dy * ci * so + y * si * so * di - result[1] * dOmega;
                derivative[1][j] = dx * so + dy * ci * co - y * si * co * di + result[0] * dOmega;
                derivative[2][j] = dy * si + y * ci * di;
            }
        }
        return result;
    }

    private static double[] initial(final Constellation constellation, final boolean geo,
                                    final long toe, final State state) throws IOException {
        final double[] r = (geo ? geoPosition(state.position(), 0) : state.position()).clone();
        final double rate = earthRate(constellation);
        final double[] v = state.velocity().clone();
        v[0] -= rate * state.position()[1];
        v[1] += rate * state.position()[0];
        if (geo) {
            final double y = v[1];
            v[1] = y * COS_TILT + v[2] * SIN_TILT;
            v[2] = v[2] * COS_TILT - y * SIN_TILT;
        }
        rotate(r, rate * Math.floorMod(toe, 604800));
        rotate(v, rate * Math.floorMod(toe, 604800));
        final double[] h = {r[1] * v[2] - r[2] * v[1], r[2] * v[0] - r[0] * v[2],
                r[0] * v[1] - r[1] * v[0]};
        final double radius = Math.sqrt(dot(r, r));
        final double a = 1 / (2 / radius - dot(v, v) / gravity(constellation));
        final double eCos = 1 - radius / a;
        final double eSin = dot(r, v) / Math.sqrt(gravity(constellation) * a);
        final double e2 = eCos * eCos + eSin * eSin;
        if (!(a > 0) || e2 >= 1) {
            throw new IOException("The PGNSS initial orbit is invalid");
        }
        final double[] p = new double[20];
        p[5] = Math.sqrt(a);
        p[6] = Math.sqrt(e2);
        p[7] = Math.atan2(Math.hypot(h[0], h[1]), h[2]);
        p[8] = Math.atan2(h[0], -h[1]);
        p[9] = Math.atan2(Math.sqrt(dot(h, h)) * r[2], h[0] * r[1] - h[1] * r[0])
                - Math.atan2(eSin * Math.sqrt(1 - e2), eCos - e2);
        p[10] = Math.atan2(eSin, eCos) - eSin;
        return p;
    }

    private static double[] geoPosition(final double[] position, final double elapsed) {
        final double[] result = position.clone();
        rotate(result, earthRate(Constellation.BDS) * elapsed);
        final double y = result[1];
        result[1] = y * COS_TILT + result[2] * SIN_TILT;
        result[2] = result[2] * COS_TILT - y * SIN_TILT;
        return result;
    }

    private static void rotate(final double[] vector, final double angle) {
        final double c = Math.cos(angle), s = Math.sin(angle), x = vector[0];
        vector[0] = c * x - s * vector[1];
        vector[1] = s * x + c * vector[1];
    }

    private static double gravity(final Constellation constellation) {
        // Huawei uses the GPS gravity constant for BDS in this fitting model.
        return constellation == Constellation.GALILEO ? 3.986004418e14 : 3.986005e14;
    }

    private static double earthRate(final Constellation constellation) {
        return constellation == Constellation.BDS ? 7.292115e-5 : 7.2921151467e-5;
    }

    private static double dot(final double[] a, final double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** Householder QR avoids squaring the condition number through normal equations. */
    private static double[] leastSquares(final double[][] matrix, final double[] right) throws IOException {
        final int rows = matrix.length, columns = matrix[0].length;
        for (int column = 0; column < columns; column++) {
            double norm = 0;
            for (int row = column; row < rows; row++) {
                norm = Math.hypot(norm, matrix[row][column]);
            }
            if (!Double.isFinite(norm) || norm < 1e-10) {
                throw new IOException("The PGNSS orbit fit is singular");
            }
            final double diagonal = matrix[column][column] >= 0 ? -norm : norm;
            final double[] reflector = new double[rows - column];
            for (int row = column; row < rows; row++) {
                reflector[row - column] = matrix[row][column];
            }
            reflector[0] -= diagonal;
            double square = 0;
            for (double value : reflector) {
                square += value * value;
            }
            for (int j = column + 1; j <= columns; j++) {
                double projection = 0;
                for (int row = column; row < rows; row++) {
                    projection += reflector[row - column] * (j == columns ? right[row] : matrix[row][j]);
                }
                projection *= 2 / square;
                for (int row = column; row < rows; row++) {
                    if (j == columns) {
                        right[row] -= projection * reflector[row - column];
                    } else {
                        matrix[row][j] -= projection * reflector[row - column];
                    }
                }
            }
            matrix[column][column] = diagonal;
        }
        final double[] solution = new double[columns];
        for (int row = columns - 1; row >= 0; row--) {
            double value = right[row];
            for (int j = row + 1; j < columns; j++) {
                value -= matrix[row][j] * solution[j];
            }
            solution[row] = value / matrix[row][row];
        }
        return solution;
    }
}
