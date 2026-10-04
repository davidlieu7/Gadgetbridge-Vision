/*  Copyright (C) 2026 Ludwig Ettner

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId;
import nodomain.freeyourgadget.gadgetbridge.util.CheckSums;

public class WorkoutGpsParserTest {
    /// GPS v1 samples are time, longitude and latitude only. Their altitude is unknown, not 0,
    /// so it does not pull the elevation chart and the exports down to sea level.
    @Test
    public void v1HasNoAltitude() {
        final ByteBuffer buf = ByteBuffer.allocate(7 + 1 + 1 + 12 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[7]); // fileId placeholder
        buf.put((byte) 0);    // padding
        buf.put((byte) 0xE0); // header
        buf.putInt(1791113889);
        buf.putFloat(7.6517577f);
        buf.putFloat(47.667213f);
        final byte[] arr = buf.array();
        buf.putInt(CheckSums.getCRC32(arr, 0, arr.length - 4));

        final XiaomiActivityFileId fileId = new XiaomiActivityFileId(
                new Date(1791113889000L), 0, 1, 0x06, 0x02, 1);
        final ActivityTrack track = new WorkoutGpsParser().getActivityTrack(fileId, buf.array());

        assertNotNull(track);
        assertEquals(1, track.getAllPoints().size());
        final ActivityPoint point = track.getAllPoints().get(0);
        assertEquals(47.667213, point.getLocation().getLatitude(), 0.00001);
        assertFalse(point.getLocation().hasAltitude());
        assertEquals(GPSCoordinate.UNKNOWN_ALTITUDE, point.getAltitude(), 0.0);
    }
}
