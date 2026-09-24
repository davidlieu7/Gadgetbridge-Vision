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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;

public class VeepooPersonInfoFrameTest {

    @Test
    public void testLayoutMatchesVendorPersonInfoOprate() {
        // {0xA3, 178 cm, 82 kg, 34 y, male, step goal 10000, sleep goal 8 h}
        byte[] frame = VeepooDeviceSupport.personInfoFrame(178, 82, 34, true, 10000, 8);
        assertEquals(VeepooConstants.PACKET_SIZE, frame.length);
        assertArrayEquals(new byte[]{
                (byte) 0xA3, (byte) 178, (byte) 82, (byte) 34, (byte) 0x01,
                (byte) (10000 >> 8), (byte) 10000,
                (byte) (8 >> 8), (byte) 8,
        }, Arrays.copyOf(frame, 9));
    }

    @Test
    public void testFemaleUsesVendorValueTwo() {
        byte[] frame = VeepooDeviceSupport.personInfoFrame(165, 60, 28, false, 8000, 7);
        assertEquals((byte) 0x02, frame[4]);
    }

    @Test
    public void testGoalsAreBigEndianU16() {
        byte[] frame = VeepooDeviceSupport.personInfoFrame(175, 70, 40, true, 65535, 24);
        assertEquals((byte) 0xFF, frame[5]);
        assertEquals((byte) 0xFF, frame[6]);
        assertEquals((byte) 0x00, frame[7]);
        assertEquals((byte) 24, frame[8]);
    }

    @Test
    public void testTailIsZeroPadded() {
        byte[] frame = VeepooDeviceSupport.personInfoFrame(175, 70, 40, true, 8000, 7);
        for (int i = 9; i < frame.length; i++) {
            assertEquals(0, frame[i]);
        }
    }
}
