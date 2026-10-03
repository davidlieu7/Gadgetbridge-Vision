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
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class HuaweiPgnssOrbitTest {
    @Test
    public void evaluatesKnownPolynomialAndItsDerivatives() throws Exception {
        double[][] coefficients = new double[3][20];
        // 3*T0 + 2*T1 + 4*T2 = 8*x*x + 2*x - 1.
        coefficients[0][0] = 3;
        coefficients[0][1] = 2;
        coefficients[0][2] = 4;
        coefficients[1][0] = 10;
        coefficients[2][1] = -3;
        HuaweiPgnssSeed.Model model = new HuaweiPgnssSeed.Model(100, 0, 0, 43200, 0, 0, coefficients);
        HuaweiPgnssOrbit.State state = HuaweiPgnssOrbit.evaluate(model, 100 * 604800L + 32400);
        assertArrayEquals(new double[]{2, 10, -1.5}, state.position(), 1e-12);
        assertArrayEquals(new double[]{10 * 2.0 / 43200, 0, -3 * 2.0 / 43200}, state.velocity(), 1e-15);
        assertArrayEquals(new double[]{16 * 4.0 / 43200 / 43200, 0, 0}, state.acceleration(), 1e-18);
    }

    @Test
    public void selectsHealthyModelsAcrossWeekBoundary() {
        HuaweiPgnssSeed.Model unhealthy = new HuaweiPgnssSeed.Model(100, 1, 561600, 0, 0, 0, new double[3][20]);
        HuaweiPgnssSeed.Model healthy = new HuaweiPgnssSeed.Model(101, 0, 0, 43200, 0, 0, new double[3][20]);
        HuaweiPgnssSeed.Satellite satellite = new HuaweiPgnssSeed.Satellite(1, 0, 0, new double[4], List.of(unhealthy, healthy));
        assertNull(HuaweiPgnssOrbit.modelAt(satellite, 100 * 604800L + 561600));
        assertEquals(healthy, HuaweiPgnssOrbit.modelAt(satellite, 101 * 604800L));
        assertNull(HuaweiPgnssOrbit.modelAt(satellite, 101 * 604800L + 43200));
        HuaweiPgnssSeed.Satellite bds = new HuaweiPgnssSeed.Satellite(59, 0, 0, new double[4], List.of(unhealthy));
        assertEquals(unhealthy, HuaweiPgnssOrbit.modelAt(bds, 101 * 604800L - 1));
    }
}
