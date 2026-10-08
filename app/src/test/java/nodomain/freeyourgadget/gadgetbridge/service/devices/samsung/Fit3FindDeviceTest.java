/*  Copyright (C) 2026 Vitaliy Tomin

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3FindDevice;

public class Fit3FindDeviceTest {

    /** The phone's answers to the band, as captured from the vendor app. */
    @Test
    public void findPhoneReplies() {
        assertArrayEquals(new byte[]{0x40, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00},
                Fit3FindDevice.buildPhoneStartReply(true));
        assertArrayEquals(new byte[]{0x41, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00},
                Fit3FindDevice.buildPhoneStopReply(true));
        assertArrayEquals(new byte[]{0x40, 0x00, 0x01, 0x03, 0x00, 0x00, 0x00, 0x00},
                Fit3FindDevice.buildPhoneStartReply(false));
        assertArrayEquals(new byte[]{0x42, 0x00, 0x00}, Fit3FindDevice.buildPhoneFound());
    }

    @Test
    public void findBand() {
        assertArrayEquals(new byte[]{0x03, 0x01, (byte) 0xb4, 0x02, 0x00}, Fit3FindDevice.buildBandStart(false));
        assertArrayEquals(new byte[]{0x04, 0x03, 0x00}, Fit3FindDevice.buildBandStop());
    }
}
