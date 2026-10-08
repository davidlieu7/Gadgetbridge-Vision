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
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3WorldClock;

public class Fit3WorldClockTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static final Instant WINTER = Instant.parse("2026-01-15T12:00:00Z");

    @Test
    public void clockAppFlagIsAnInt() {
        assertArrayEquals(hex("04 07 01000000"), Fit3WorldClock.buildClockApp(true));
    }

    @Test
    public void cityIsFiveLengthPrefixedStrings() {
        Fit3WorldClock.City tokyo = new Fit3WorldClock.City("Tokyo", "Asia/Tokyo", "", "", "+9");
        byte[] msg = Fit3WorldClock.buildCityList(Collections.singletonList(tokyo)).get(0);
        assertArrayEquals(hex("00 01 01 09 01"
                + "02 05000000 546f6b796f"
                + "03 00000000"
                + "04 00000000"
                + "05 0a000000 417369612f546f6b796f"
                + "08 02000000 2b39"), msg);
    }

    @Test
    public void emptyListIsChunkZero() {
        List<byte[]> msgs = Fit3WorldClock.buildCityList(new ArrayList<>());
        assertEquals(1, msgs.size());
        assertArrayEquals(hex("00 01 00 09 00"), msgs.get(0));
    }

    @Test
    public void listIsChunkedByTenFromOne() {
        List<Fit3WorldClock.City> cities = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            cities.add(new Fit3WorldClock.City("c" + i, "UTC", "", "", "+0"));
        }
        List<byte[]> msgs = Fit3WorldClock.buildCityList(cities);
        assertEquals(2, msgs.size());
        assertArrayEquals(hex("00 01 0c 09 01"), java.util.Arrays.copyOf(msgs.get(0), 5));
        assertArrayEquals(hex("00 01 0c 09 02"), java.util.Arrays.copyOf(msgs.get(1), 5));
    }

    @Test
    public void offsetsDropZeroMinutes() {
        assertEquals("+9", Fit3WorldClock.utcOffset(ZoneId.of("Asia/Tokyo"), WINTER));
        assertEquals("+5:30", Fit3WorldClock.utcOffset(ZoneId.of("Asia/Kolkata"), WINTER));
        assertEquals("-3:30", Fit3WorldClock.utcOffset(ZoneId.of("America/St_Johns"), WINTER));
        assertEquals("+0", Fit3WorldClock.utcOffset(ZoneId.of("UTC"), WINTER));
        assertEquals("-5", Fit3WorldClock.utcOffset(ZoneId.of("America/New_York"), WINTER));
    }

    @Test
    public void currentZoneIsAString() {
        assertArrayEquals(hex("01 06 03000000 555443"), Fit3WorldClock.buildCurrentZone("UTC"));
    }
}
