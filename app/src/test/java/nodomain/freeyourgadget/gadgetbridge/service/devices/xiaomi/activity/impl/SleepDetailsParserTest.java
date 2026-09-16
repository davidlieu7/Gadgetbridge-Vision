/*  Copyright (C) 2026 Ray

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
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepTimeSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class SleepDetailsParserTest extends TestBase {
    private static final int BED_TIME = 1700000000;
    private GBDevice device;

    @Before
    public void prepareDevice() {
        device = createDummyGDevice("00:00:00:00:00:01");
    }

    @Test
    public void version6PersistsSummaryAndStages() {
        assertTrue(parse(6, sleepFile(6, false)));
        final XiaomiSleepTimeSample summary = daoSession.getXiaomiSleepTimeSampleDao().loadAll().get(0);
        assertEquals(BED_TIME * 1000L, summary.getTimestamp());
        assertEquals(Long.valueOf((BED_TIME + 65 * 60) * 1000L), summary.getWakeupTime());
        assertEquals(Integer.valueOf(60), summary.getTotalDuration());
        assertEquals(Integer.valueOf(5), summary.getAwakeDuration());
        assertEquals(Integer.valueOf(30), summary.getLightSleepDuration());
        assertEquals(Integer.valueOf(20), summary.getDeepSleepDuration());
        assertEquals(Integer.valueOf(10), summary.getRemSleepDuration());

        final List<XiaomiSleepStageSample> stages = daoSession.getXiaomiSleepStageSampleDao().loadAll();
        stages.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
        assertEquals(4, stages.size());
        final int[] kinds = {3, 2, 5, 4};
        final int[] offsets = {0, 30, 50, 55};
        for (int i = 0; i < stages.size(); i++) {
            assertEquals(Integer.valueOf(kinds[i]), stages.get(i).getStage());
            assertEquals((BED_TIME + offsets[i] * 60L) * 1000L, stages.get(i).getTimestamp());
        }

        // Reprocessing a retained file must not duplicate sleep data.
        assertTrue(parse(6, sleepFile(6, false)));
        assertEquals(1, daoSession.getXiaomiSleepTimeSampleDao().count());
        assertEquals(4, daoSession.getXiaomiSleepStageSampleDao().count());
    }

    @Test
    public void version6NapWithoutStages() {
        assertTrue(parse(6, sleepFile(6, true)));
        assertEquals(Integer.valueOf(30), daoSession.getXiaomiSleepTimeSampleDao().loadAll().get(0).getTotalDuration());
        assertEquals(0, daoSession.getXiaomiSleepStageSampleDao().count());
    }

    @Test
    public void repeatedSummaryPreservesHeaderState() {
        assertTrue(parse(6, sleepFile(6, false, true)));
        assertEquals(1, daoSession.getXiaomiSleepTimeSampleDao().count());
        assertEquals(Boolean.TRUE, daoSession.getXiaomiSleepTimeSampleDao().loadAll().get(0).getIsAwake());
    }

    @Test
    public void unknownVersion6SummaryLayoutIsRejected() {
        final byte[] bytes = sleepFile(6, false);
        bytes[9] &= (byte) ~0x40; // Missing one of the newly observed summary fields
        assertFalse(parse(6, bytes));
        assertEquals(0, daoSession.getXiaomiSleepTimeSampleDao().count());
    }

    @Test
    public void version5StillParses() {
        assertTrue(parse(5, sleepFile(5, false)));
        assertEquals(Integer.valueOf(60), daoSession.getXiaomiSleepTimeSampleDao().loadAll().get(0).getTotalDuration());
        assertEquals(4, daoSession.getXiaomiSleepStageSampleDao().count());
    }

    @Test
    public void truncatedVersion6HeaderIsRejected() {
        assertFalse(parse(6, Arrays.copyOf(sleepFile(6, false), 40)));
        assertEquals(0, daoSession.getXiaomiSleepTimeSampleDao().count());
    }

    @Test
    public void truncatedVersion6SampleArrayIsRejected() {
        assertFalse(parse(6, Arrays.copyOf(sleepFile(6, false), 70)));
        assertEquals(0, daoSession.getXiaomiSleepTimeSampleDao().count());
    }

    private boolean parse(final int version, final byte[] bytes) {
        return new SleepDetailsParser().parse(getContext(), device, fileId(version), bytes);
    }

    private static XiaomiActivityFileId fileId(final int version) {
        return new XiaomiActivityFileId(new Date(BED_TIME * 1000L), 0, 0, 8, 1, version);
    }

    /** Synthetic values; no user's health data or device identifiers. */
    private static byte[] sleepFile(final int version, final boolean nap) {
        return sleepFile(version, nap, false);
    }

    private static byte[] sleepFile(final int version, final boolean nap, final boolean repeatSummary) {
        final ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        b.put(fileId(version).toBytes()).put((byte) 0);
        if (version == 6) {
            b.put(new byte[]{(byte) 0xdf, (byte) 0xff, (byte) 0xf4});
        } else {
            b.put(new byte[]{(byte) 0xdf, (byte) 0xe0});
        }
        b.put((byte) 1).putInt(BED_TIME).putInt(BED_TIME + (nap ? 30 : 65) * 60);
        b.put((byte) 80); // quality
        b.put((byte) 0x60).putInt(60).putInt(3960).putInt(BED_TIME - 60).putInt(BED_TIME + 3900);
        if (version == 6) {
            b.putInt(61).putShort((short) 72).putShort((short) 45).putInt(85).putInt(BED_TIME);
            b.putShort((short) 98).putShort((short) 12).putShort((short) 90).putShort((short) 36);
        }
        b.putShort((short) 60).putShort((short) 2).putInt(BED_TIME).put(new byte[]{60, 62});
        b.putShort((short) 600).putShort((short) 1).putInt(BED_TIME).put((byte) 98);
        if (version == 6) {
            // Additional v6 data before the self-framed packets is not decoded yet.
            b.put(new byte[10]);
            // These events have flags in the length field, but no payload. Place
            // them directly before useful packets so a one-byte overread fails.
            packetHeader(b, 0x12, 1);
        }
        final int summaryStart = b.position();
        packetHeader(b, 16, 13);
        b.order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 0x10).putShort((short) (nap ? 30 : 60)).putShort((short) (nap ? 0 : 5));
        b.putShort((short) (nap ? 0 : 30)).putShort((short) (nap ? 0 : 10)).putShort((short) (nap ? 0 : 20));
        b.put((byte) (nap ? 1 : 0x15)).put((byte) 0);
        if (repeatSummary) {
            b.put(b.array(), summaryStart, b.position() - summaryStart);
        }
        if (version == 6) {
            packetHeader(b, 0x13, 1);
        }
        packetHeader(b, 17, nap ? 0 : 8);
        if (!nap) {
            b.order(ByteOrder.BIG_ENDIAN);
            b.putShort((short) 0x101e).putShort((short) 0x2014).putShort((short) 0x0005).putShort((short) 0x300a);
        }
        return Arrays.copyOf(b.array(), b.position());
    }

    private static void packetHeader(final ByteBuffer b, final int type, final int length) {
        b.order(ByteOrder.LITTLE_ENDIAN).putInt(0xfffcfafb).put((byte) 17).putLong(BED_TIME);
        b.put((byte) 0).put((byte) type).order(ByteOrder.BIG_ENDIAN).putShort((short) length);
    }
}
