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

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3CameraRemote;

public class Fit3CameraRemoteTest {

    @Test
    public void handshake() {
        assertArrayEquals(new byte[]{0x00}, Fit3CameraRemote.buildCapabilityRequest());
        assertArrayEquals(new byte[]{0x04}, Fit3CameraRemote.buildStatusRequest());
        assertArrayEquals(new byte[]{0x05, 0x05, 0x01}, Fit3CameraRemote.buildVisibility());
    }

    @Test
    public void replies() {
        assertArrayEquals(new byte[]{0x41, 0x03, 0x01}, Fit3CameraRemote.buildLaunchResult(Fit3CameraRemote.LAUNCH_OK));
        assertArrayEquals(new byte[]{0x42, 0x01, 0x00}, Fit3CameraRemote.buildShotFailed());
        assertArrayEquals(new byte[]{0x43, 0x02, 0x00}, Fit3CameraRemote.buildBackground());
        assertArrayEquals(new byte[]{0x46, 0x06, 0x01}, Fit3CameraRemote.buildExitResult(true));
        assertArrayEquals(new byte[]{0x47, 0x07, 0x00}, Fit3CameraRemote.buildVideoUnsupported(7));
    }
}
