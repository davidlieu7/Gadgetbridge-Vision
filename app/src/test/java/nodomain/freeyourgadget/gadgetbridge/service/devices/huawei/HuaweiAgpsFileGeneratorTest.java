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
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.Constellation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.ExtraTable;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.PredictionEpoch;

public class HuaweiAgpsFileGeneratorTest {
    @Test
    public void rtcmFramesHaveKnownCrc24qAndPreserveOrder() {
        final byte[] result = HuaweiAgpsFileGenerator.generateRtcm(List.of(
                new byte[]{0x3f, (byte) 0xb0, 0, 1}, new byte[]{0x40, 0x20, 0x55}));
        assertArrayEquals(new byte[]{
                (byte) 0xd3, 0, 4, 0x3f, (byte) 0xb0, 0, 1, (byte) 0xdf, 0x2f, (byte) 0xc6,
                (byte) 0xd3, 0, 3, 0x40, 0x20, 0x55, (byte) 0x94, 0x4d, (byte) 0x82
        }, result);
    }

    @Test
    public void rtcmLengthUsesTenBits() {
        final byte[] result = HuaweiAgpsFileGenerator.generateRtcm(List.of(new byte[1023]));
        assertEquals(1029, result.length);
        assertEquals(3, result[1]);
        assertEquals(255, result[2] & 0xff);
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generateRtcm(List.of()));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generateRtcm(List.of(new byte[1])));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generateRtcm(List.of(new byte[1024])));
    }

    @Test
    public void predictionProfilesWriteCountsRecordsAndZeroPadding() {
        // Independent protocol dimensions: record bytes, slots, groups, complete block bytes.
        final int[][] dimensions = {{80, 32, 1, 2564}, {92, 63, 1, 5800},
                {52, 24, 8, 10016}, {76, 36, 1, 2740}, {76, 10, 1, 764}};
        final Constellation[] profiles = {Constellation.GPS, Constellation.BDS,
                Constellation.GLONASS, Constellation.GALILEO, Constellation.QZS};
        for (int i = 0; i < profiles.length; i++) {
            final int[] dim = dimensions[i];
            final List<List<byte[]>> groups = new ArrayList<>();
            for (int group = 0; group < dim[2]; group++) {
                groups.add(List.of(filled(dim[0], group + 1)));
            }
            final byte[] data = HuaweiAgpsFileGenerator.generatePrediction(profiles[i],
                    List.of(new PredictionEpoch(3600, groups)));
            assertEquals(1008 + dim[3], data.length);
            final ByteBuffer view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(3600, view.getInt(0));
            assertEquals(1008, view.getInt(4));
            assertEquals(dim[3], view.getInt(8));
            assertArrayEquals(new byte[996], Arrays.copyOfRange(data, 12, 1008));
            for (int group = 0; group < dim[2]; group++) {
                final int start = 1008 + group * (4 + dim[0] * dim[1]);
                assertEquals(1, view.getInt(start));
                assertArrayEquals(groups.get(group).get(0),
                        Arrays.copyOfRange(data, start + 4, start + 4 + dim[0]));
                assertArrayEquals(new byte[dim[0] * (dim[1] - 1)],
                        Arrays.copyOfRange(data, start + 4 + dim[0], start + 4 + dim[0] * dim[1]));
            }
        }
    }

    @Test
    public void indexAcceptsUnsignedTimestampsAndAll84Slots() {
        final List<PredictionEpoch> epochs = new ArrayList<>();
        for (int i = 0; i < 84; i++) {
            epochs.add(epoch(0xffffffffL - 83 + i, List.of()));
        }
        final byte[] data = HuaweiAgpsFileGenerator.generatePrediction(Constellation.GPS, epochs);
        final ByteBuffer view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1008 + 84 * 2564, data.length);
        assertEquals(0xffffffffL, Integer.toUnsignedLong(view.getInt(83 * 12)));
        assertEquals(1008 + 83 * 2564, view.getInt(83 * 12 + 4));
        assertEquals(2564, view.getInt(83 * 12 + 8));
        assertArrayEquals(new byte[84 * 2564], Arrays.copyOfRange(data, 1008, data.length));
    }

    @Test
    public void predictionsRejectInvalidIndicesAndRecordShapes() {
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generatePrediction(Constellation.GPS, List.of()));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generatePrediction(Constellation.GPS,
                Collections.nCopies(85, epoch(1, List.of()))));
        for (final long time : new long[]{-1, 0x100000000L}) {
            assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generatePrediction(Constellation.GPS,
                    List.of(epoch(time, List.of()))));
        }
        for (final long secondTime : new long[]{1, 0}) {
            assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generatePrediction(Constellation.GPS,
                    List.of(epoch(1, List.of()), epoch(secondTime, List.of()))));
        }
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generatePrediction(Constellation.GPS,
                List.of(epoch(1, List.of(new byte[79])))));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generatePrediction(Constellation.GPS,
                List.of(epoch(1, Collections.nCopies(33, new byte[80])))));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generatePrediction(Constellation.GLONASS,
                List.of(epoch(1, List.of(new byte[52])))));
    }

    @Test
    public void extraPreservesOpaqueSectionsAndExplicitEmptyRecords() {
        final List<ExtraTable> tables = extraTables();
        final byte[] prefix = filled(1360, 0x31);
        final byte[] trailer = filled(16, 0x42);
        final byte[] data = HuaweiAgpsFileGenerator.generateExtra(prefix, tables, trailer);
        assertEquals(6248, data.length);
        assertArrayEquals(prefix, Arrays.copyOfRange(data, 0, 1360));
        final int[] starts = {1360, 2388, 3160, 3960};
        for (int i = 0; i < starts.length; i++) {
            int offset = starts[i];
            final ExtraTable table = tables.get(i);
            assertArrayEquals(table.header(), Arrays.copyOfRange(data, offset, offset + table.header().length));
            offset += table.header().length;
            for (final byte[] record : table.records()) {
                assertArrayEquals(record, Arrays.copyOfRange(data, offset, offset + record.length));
                offset += record.length;
            }
        }
        assertArrayEquals(trailer, Arrays.copyOfRange(data, 6232, 6248));
    }

    @Test
    public void extraRequiresEverySectionWithItsExactShape() {
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generateExtra(new byte[1359], extraTables(), new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generateExtra(new byte[1360], List.of(), new byte[16]));
        final List<ExtraTable> tables = extraTables();
        tables.set(2, new ExtraTable(new byte[4], Collections.nCopies(36, new byte[22])));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generateExtra(new byte[1360], tables, new byte[16]));
        tables.set(2, new ExtraTable(new byte[8], Collections.nCopies(35, new byte[22])));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generateExtra(new byte[1360], tables, new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsFileGenerator.generateExtra(new byte[1360], extraTables(), new byte[15]));
    }

    private static PredictionEpoch epoch(final long time, final List<byte[]> records) {
        return new PredictionEpoch(time, List.of(records));
    }

    private static byte[] filled(final int size, final int value) {
        final byte[] data = new byte[size];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static List<ExtraTable> extraTables() {
        final int[][] dimensions = {{4, 32, 32}, {4, 24, 32}, {8, 36, 22}, {4, 63, 36}};
        final List<ExtraTable> tables = new ArrayList<>();
        for (int i = 0; i < dimensions.length; i++) {
            final int[] dim = dimensions[i];
            final List<byte[]> records = new ArrayList<>();
            for (int j = 0; j < dim[1]; j++) {
                records.add(filled(dim[2], j == dim[1] - 1 ? 0 : j + 1));
            }
            tables.add(new ExtraTable(filled(dim[0], 0x60 + i), records));
        }
        return tables;
    }
}
