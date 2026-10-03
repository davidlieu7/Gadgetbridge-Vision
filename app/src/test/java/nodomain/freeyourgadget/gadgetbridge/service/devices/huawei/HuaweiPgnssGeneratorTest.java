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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.Constellation;

import static org.junit.Assert.*;

public class HuaweiPgnssGeneratorTest {
    @Test
    public void decodesFieldsAndRejectsDuplicateOrInvalidValues() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3}, HuaweiPgnssGenerator.decodeFields(json("{\"HiEE\":[{\"gpsNav\":\"AQID\"}]}" )).get("gpsNav"));
        for (final String invalid : new String[]{"{}", "null", "[]", "{\"HiEE\":null}",
                "{\"HiEE\":{}}", "{\"HiEE\":7}", "{\"HiEE\":[null]}",
                "{\"HiEE\":[7]}", "{\"HiEE\":[[]]}", "{\"HiEE\":[{}]}",
                "{\"HiEE\":[{\"x\":7}]}", "{\"HiEE\":[{\"x\":\"?\"}]}",
                "{\"HiEE\":[{\"x\":\"AQ==\"},{\"x\":\"Ag==\"}]}", "not json"}) {
            assertThrows(invalid, IOException.class, () -> HuaweiPgnssGenerator.decodeFields(json(invalid)));
        }
    }

    @Test
    public void requiresCurrentCoverageForAllThreeDays() throws Exception {
        final long gps = 2440L * 604800 + 30000;
        final long unixMillis = (gps + 315964800 - 18) * 1000;
        final byte[] info = ByteBuffer.allocate(14).putInt((int) (gps - 86400))
                .putInt((int) (gps + 6 * 86400)).put((byte) 18).array();
        assertEquals(gps, HuaweiPgnssGenerator.checkedGpsTime(info, unixMillis));
        assertThrows(IOException.class, () -> HuaweiPgnssGenerator.checkedGpsTime(info, unixMillis - 2 * 86400000L));
        assertThrows(IOException.class, () -> HuaweiPgnssGenerator.checkedGpsTime(info, unixMillis + 4 * 86400000L));
        assertThrows(IOException.class, () -> HuaweiPgnssGenerator.checkedGpsTime(new byte[13], unixMillis));
        info[8] = 100;
        assertThrows(IOException.class, () -> HuaweiPgnssGenerator.checkedGpsTime(info, unixMillis));
    }

    @Test
    public void interruptedGenerationStopsBeforeFittingAnySatellite() {
        final HuaweiPgnssSeed.Satellite satellite = new HuaweiPgnssSeed.Satellite(
                1, 0, 0, new double[4], List.of());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> HuaweiPgnssGenerator.generateConstellation(
                    Constellation.GPS, List.of(satellite), 2440L * 604800));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static byte[] json(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
