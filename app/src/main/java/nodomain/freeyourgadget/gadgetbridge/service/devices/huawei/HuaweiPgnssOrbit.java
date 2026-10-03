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

/** Evaluates the position polynomials carried by Huawei prediction seeds. */
final class HuaweiPgnssOrbit {
    static final long WEEK_SECONDS = 604_800;
    private static final double DERIVATIVE_INTERVAL_SECONDS = 43_200;

    private HuaweiPgnssOrbit() {
    }

    record State(double[] position, double[] velocity, double[] acceleration) {
    }

    static HuaweiPgnssSeed.Model modelAt(HuaweiPgnssSeed.Satellite satellite, long gpsSeconds) {
        for (HuaweiPgnssSeed.Model model : satellite.models()) {
            if (model.flags() != 0 && (satellite.id() < 59 || satellite.id() > 63)) {
                continue;
            }
            long start = model.week() * WEEK_SECONDS + model.startTow();
            long span = Math.floorMod(model.endTow() - model.startTow(), WEEK_SECONDS);
            if (gpsSeconds >= start && gpsSeconds < start + span) {
                return model;
            }
        }
        return null;
    }

    static State evaluate(HuaweiPgnssSeed.Model model, long gpsSeconds) throws IOException {
        long span = Math.floorMod(model.endTow() - model.startTow(), WEEK_SECONDS);
        if (span == 0) {
            throw new IOException("The prediction model has an empty time interval");
        }
        double elapsed = gpsSeconds - model.week() * WEEK_SECONDS - model.startTow();
        double x = 2 * elapsed / span - 1;
        double[] terms = new double[20];
        double[] first = new double[20];
        double[] second = new double[20];
        terms[0] = 1;
        terms[1] = x;
        first[1] = 1;
        for (int i = 2; i < terms.length; i++) {
            terms[i] = 2 * x * terms[i - 1] - terms[i - 2];
            first[i] = 2 * terms[i - 1] + 2 * x * first[i - 1] - first[i - 2];
            second[i] = 4 * first[i - 1] + 2 * x * second[i - 1] - second[i - 2];
        }
        double[] position = new double[3];
        double[] velocity = new double[3];
        double[] acceleration = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < terms.length; i++) {
                double coefficient = model.coefficients()[axis][i];
                position[axis] += coefficient * terms[i];
                velocity[axis] += coefficient * first[i];
                acceleration[axis] += coefficient * second[i];
            }
            // Huawei uses a fixed 12-hour derivative scale, including GLONASS leap-second adjustment.
            velocity[axis] = velocity[axis] * 2 / DERIVATIVE_INTERVAL_SECONDS;
            acceleration[axis] = acceleration[axis] * 4 / DERIVATIVE_INTERVAL_SECONDS / DERIVATIVE_INTERVAL_SECONDS;
        }
        return new State(position, velocity, acceleration);
    }
}
