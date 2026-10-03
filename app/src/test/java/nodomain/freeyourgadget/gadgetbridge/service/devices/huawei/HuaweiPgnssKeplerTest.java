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

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.Constellation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Model;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Satellite;

public class HuaweiPgnssKeplerTest {
    private static final long TOE = 2440L * 604800 + 21600;

    @Test
    public void analyticDerivativesMatchIndependentFiniteDifferences() throws Exception {
        final double[] p = parameters();
        final double[][] derivatives = new double[3][15];
        HuaweiPgnssKepler.position(Constellation.GPS, false, p, TOE, 3100, derivatives);
        for (int j = 0; j < 15; j++) {
            final int k = j + 5;
            final double step = k == 5 ? 0.01 : k >= 18 ? 1 : k >= 11 && k <= 13 ? 1e-10 : 1e-7;
            p[k] += step;
            final double[] plus = HuaweiPgnssKepler.position(Constellation.GPS, false, p, TOE, 3100, null);
            p[k] -= 2 * step;
            final double[] minus = HuaweiPgnssKepler.position(Constellation.GPS, false, p, TOE, 3100, null);
            p[k] += step;
            for (int axis = 0; axis < 3; axis++) {
                final double numeric = (plus[axis] - minus[axis]) / (2 * step);
                assertEquals("parameter " + k + " axis " + axis, numeric, derivatives[axis][j],
                        Math.max(0.1, Math.abs(numeric) * 2e-6));
            }
        }
    }

    @Test
    public void fitsSyntheticSatellitePositionsAcrossTheWindow() throws Exception {
        for (final Constellation constellation : new Constellation[]{Constellation.GPS, Constellation.GALILEO, Constellation.QZS, Constellation.BDS}) {
            final double[] original = parameters();
            final long toe = TOE - (constellation == Constellation.BDS ? 14 : 0);
            final Satellite satellite = syntheticSatellite(constellation, original, toe);
            final double[] fitted = HuaweiPgnssKepler.fit(constellation, satellite, TOE);
            for (int delta = -7100; delta <= 7100; delta += 200) {
                assertArrayEquals(HuaweiPgnssKepler.position(constellation, false, original, toe, delta, null),
                        HuaweiPgnssKepler.position(constellation, false, fitted, toe, delta, null), 0.05);
            }
            assertEquals(0.0001 + 21600 * 1e-12 - (constellation == Constellation.BDS ? 14e-12 : 0), fitted[0], 1e-15);
            assertEquals(1e-12, fitted[1], 0);
        }
    }

    @Test
    public void rejectsMissingCoverageAndInvalidOrbits() throws Exception {
        final Satellite satellite = syntheticSatellite(Constellation.GPS, parameters(), TOE);
        assertThrows(IOException.class, () -> HuaweiPgnssKepler.fit(Constellation.GPS, satellite, TOE + 43200));
        final double[] p = parameters();
        p[6] = 1;
        assertThrows(IOException.class, () -> HuaweiPgnssKepler.position(Constellation.GPS, false, p, TOE, 0, null));
    }

    private static Satellite syntheticSatellite(final Constellation constellation, final double[] p, final long toe) throws Exception {
        // A discrete cosine transform samples a known broadcast orbit independently of the fitter.
        final int samples = 256;
        final double[][] coefficients = new double[3][20];
        for (int sample = 0; sample < samples; sample++) {
            final double angle = Math.PI * (sample + 0.5) / samples;
            final double dt = Math.cos(angle) * 21600;
            final double[] position = HuaweiPgnssKepler.position(constellation, false, p, toe, dt, null);
            for (int axis = 0; axis < 3; axis++) {
                for (int term = 0; term < 20; term++) {
                    coefficients[axis][term] += position[axis] * Math.cos(term * angle)
                            * (term == 0 ? 1 : 2) / samples;
                }
            }
        }
        return new Satellite(10, 0, 0, new double[4], List.of(new Model(2440, 0, 0, 43200,
                0.0001, 1e-12, coefficients)));
    }

    private static double[] parameters() {
        return new double[]{0, 0, 0, 0, 0, 5153.7, 0.01, 0.95, 1.2, 0.3, 1.1,
                4e-9, -8e-9, 1e-10, 1e-6, -2e-6, 3e-8, -4e-8, 240, -50};
    }
}
