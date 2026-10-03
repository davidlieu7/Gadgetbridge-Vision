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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Assembles AGPS files using layouts observed in a Huawei Watch GT 7 Pro capture.
 * This class performs no orbit calculation or satellite-data validation.
 * Callers must supply valid encoded satellite records and all unknown fields.
 * Callers remain responsible for satellite validity and the meaning of timestamps.
 */
public final class HuaweiAgpsFileGenerator {
    private static final int INDEX_SLOTS = 84;
    private static final int INDEX_ENTRY_BYTES = 12;
    private static final int INDEX_BYTES = INDEX_SLOTS * INDEX_ENTRY_BYTES;
    private static final int[] EXTRA_HEADER_BYTES = {4, 4, 8, 4};
    private static final int[] EXTRA_RECORD_BYTES = {32, 32, 22, 36};
    private static final int[] EXTRA_RECORD_SLOTS = {32, 24, 36, 63};

    private HuaweiAgpsFileGenerator() {
    }

    public enum Constellation {
        GPS("HW_PGNSS_GPS", 80, 32, 1),
        BDS("HW_PGNSS_BDS", 92, 63, 1),
        GLONASS("HW_PGNSS_GLONASS", 52, 24, 8),
        GALILEO("HW_PGNSS_GALILEO", 76, 36, 1),
        QZS("HW_PGNSS_QZS", 76, 10, 1);

        private final String fileName;
        private final int recordBytes;
        private final int recordSlots;
        private final int groupCount;

        Constellation(String fileName, int recordBytes, int recordSlots, int groupCount) {
            this.fileName = fileName;
            this.recordBytes = recordBytes;
            this.recordSlots = recordSlots;
            this.groupCount = groupCount;
        }

        public String getFileName() {
            return fileName;
        }

        private int entryBytes() {
            return groupCount * (Integer.BYTES + recordSlots * recordBytes);
        }
    }

    /**
     * A raw index timestamp and ordered groups of encoded satellite records.
     * GPS, BDS, Galileo, and QZS require one group. GLONASS requires eight groups.
     * The timestamp and encoded record times must use the source format's time scale.
     * This class does not convert UTC or derive the times of GLONASS subgroups.
     */
    public record PredictionEpoch(long timestamp, List<List<byte[]>> recordGroups) {
    }

    /** An opaque EXTRA header and all encoded record slots, including unused slots. */
    public record ExtraTable(byte[] header, List<byte[]> records) {
    }

    /**
     * Frames unframed RTCM payloads in caller order with CRC24Q checksums.
     * Each payload must contain 2 to 1023 bytes.
     * Inputs exclude the RTCM frame header and its CRC bytes.
     * The caller must provide complete, valid encoded RTCM message contents.
     */
    public static byte[] generateRtcm(List<byte[]> messagePayloads) {
        require(messagePayloads != null && !messagePayloads.isEmpty(), "RTCM payloads must not be empty");
        long length = 0;
        for (byte[] payload : messagePayloads) {
            require(payload != null && payload.length >= 2 && payload.length <= 1023,
                    "Each RTCM payload must contain 2 to 1023 bytes");
            length += payload.length + 6L;
        }
        require(length <= Integer.MAX_VALUE, "The RTCM file exceeds the supported byte-array size");
        ByteBuffer output = ByteBuffer.allocate((int) length);
        for (byte[] payload : messagePayloads) {
            int start = output.position();
            output.put((byte) 0xd3);
            output.put((byte) (payload.length >>> 8));
            output.put((byte) payload.length);
            output.put(payload);
            int crc = crc24q(output.array(), start, payload.length + 3);
            output.put((byte) (crc >>> 16));
            output.put((byte) (crc >>> 8));
            output.put((byte) crc);
        }
        return output.array();
    }

    /**
     * Assembles a PGNSS index and fixed-size record groups.
     * Timestamps must increase strictly and fit unsigned 32-bit integers.
     * The caller selects the cadence and supplies records in the required order.
     * Unused index slots and record slots contain zero bytes.
     * Record sizes and capacities:
     * <ul>
     *     <li>GPS: 32 slots of 80 bytes.</li>
     *     <li>BDS: 63 slots of 92 bytes.</li>
     *     <li>Galileo: 36 slots of 76 bytes.</li>
     *     <li>QZS: 10 slots of 76 bytes.</li>
     *     <li>GLONASS: eight groups, each with 24 slots of 52 bytes.</li>
     * </ul>
     */
    public static byte[] generatePrediction(Constellation constellation, List<PredictionEpoch> epochs) {
        require(constellation != null, "The constellation must not be null");
        require(epochs != null && !epochs.isEmpty() && epochs.size() <= INDEX_SLOTS,
                "Prediction files require 1 to 84 epochs");
        long previousTimestamp = -1;
        for (PredictionEpoch epoch : epochs) {
            require(epoch != null, "An epoch must not be null");
            require(epoch.timestamp() >= 0 && epoch.timestamp() <= 0xffffffffL,
                    "An epoch timestamp must fit an unsigned 32-bit integer");
            require(epoch.timestamp() > previousTimestamp, "Epoch timestamps must increase strictly");
            previousTimestamp = epoch.timestamp();
            require(epoch.recordGroups() != null && epoch.recordGroups().size() == constellation.groupCount,
                    "The epoch has an incorrect record-group count");
            for (List<byte[]> group : epoch.recordGroups()) {
                require(group != null && group.size() <= constellation.recordSlots,
                        "The record group exceeds its slot capacity or is null");
                validateRecords(group, constellation.recordBytes);
            }
        }

        int entryBytes = constellation.entryBytes();
        ByteBuffer output = ByteBuffer.allocate(INDEX_BYTES + epochs.size() * entryBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        output.position(INDEX_BYTES);
        for (int i = 0; i < epochs.size(); i++) {
            PredictionEpoch epoch = epochs.get(i);
            int indexOffset = i * INDEX_ENTRY_BYTES;
            output.putInt(indexOffset, (int) epoch.timestamp());
            output.putInt(indexOffset + 4, output.position());
            output.putInt(indexOffset + 8, entryBytes);
            for (List<byte[]> group : epoch.recordGroups()) {
                output.putInt(group.size());
                for (byte[] record : group) {
                    output.put(record);
                }
                output.position(output.position() + (constellation.recordSlots - group.size()) * constellation.recordBytes);
            }
        }
        return output.array();
    }

    /**
     * Concatenates the observed EXTRA sections without interpreting their contents.
     * The prefix contains 1360 bytes. The trailer contains 16 bytes.
     * Table header sizes are 4, 4, 8, and 4 bytes, respectively.
     * Table record layouts are 32 by 32, 24 by 32, 36 by 22, and 63 by 36 bytes.
     * Callers must supply every slot, including zero records for unused slots.
     * Callers must keep opaque headers consistent with the supplied records.
     */
    public static byte[] generateExtra(byte[] prefix, List<ExtraTable> tables, byte[] trailer) {
        requireLength(prefix, 1360, "The EXTRA prefix");
        requireLength(trailer, 16, "The EXTRA trailer");
        require(tables != null && tables.size() == EXTRA_HEADER_BYTES.length,
                "EXTRA requires exactly four tables");
        for (int i = 0; i < tables.size(); i++) {
            ExtraTable table = tables.get(i);
            require(table != null, "An EXTRA table must not be null");
            requireLength(table.header(), EXTRA_HEADER_BYTES[i], "The EXTRA table header");
            require(table.records() != null && table.records().size() == EXTRA_RECORD_SLOTS[i],
                    "The EXTRA table has an incorrect record-slot count");
            validateRecords(table.records(), EXTRA_RECORD_BYTES[i]);
        }
        ByteBuffer output = ByteBuffer.allocate(6248);
        output.put(prefix);
        for (ExtraTable table : tables) {
            output.put(table.header());
            for (byte[] record : table.records()) {
                output.put(record);
            }
        }
        output.put(trailer);
        return output.array();
    }

    private static void validateRecords(List<byte[]> records, int recordBytes) {
        for (byte[] record : records) {
            requireLength(record, recordBytes, "The encoded record");
        }
    }

    private static void requireLength(byte[] value, int length, String label) {
        require(value != null && value.length == length, label + " must contain " + length + " bytes");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static int crc24q(byte[] bytes, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (bytes[i] & 0xff) << 16;
            for (int bit = 0; bit < 8; bit++) {
                crc <<= 1;
                if ((crc & 0x1000000) != 0) {
                    crc ^= 0x1864cfb;
                }
            }
        }
        return crc & 0xffffff;
    }
}
