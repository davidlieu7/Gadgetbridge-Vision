/*  Copyright (C) 2026 Gadgetbridge Contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 or later;
    see the LICENSE file for details.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class VeepooBcWeightDerivationTest {

    // Values decoded from a real KR96 Pro body-composition measurement (u16/10, little-endian):
    // [1] body fat rate 20.6 %, [2] fat weight 12.4 kg, [7] body water rate 63.6 %, [8] water content 38.1 kg.
    private static float[] captureValues() {
        float[] v = new float[14];
        v[0] = 19.5f;
        v[1] = 20.6f;
        v[2] = 12.4f;
        v[3] = 47.5f;
        v[4] = 66.3f;
        v[5] = 39.7f;
        v[6] = 14.3f;
        v[7] = 63.6f;
        v[8] = 38.1f;
        v[9] = 39.0f;
        v[10] = 2.6f;
        v[11] = 14.4f;
        v[12] = 8.6f;
        v[13] = 1601.4f;
        return v;
    }

    @Test
    public void derivesWeightFromTheWaterShare() {
        assertEquals(59.9f, VeepooDeviceSupport.deriveWeightKg(captureValues()), 0.05f);
    }

    @Test
    public void fallsBackToTheFatShareWhenTheWaterShareIsInvalid() {
        float[] v = captureValues();
        v[7] = 0f; // no usable water rate
        assertEquals(60.2f, VeepooDeviceSupport.deriveWeightKg(v), 0.05f);
    }

    @Test
    public void rejectsImplausibleShares() {
        float[] v = captureValues();
        v[7] = 120f; // water share above any plausible body
        v[1] = 0f; // and no usable fat rate for the fallback either
        assertNull(VeepooDeviceSupport.deriveWeightKg(v));
    }

    @Test
    public void rejectsImplausibleWeights() {
        float[] v = captureValues();
        v[8] = 900f; // far too much water for a person
        v[2] = 900f; // and for the fallback as well
        assertNull(VeepooDeviceSupport.deriveWeightKg(v));
    }
}
