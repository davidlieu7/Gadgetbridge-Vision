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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3BatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthCapabilityCheck;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthPedometerRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthRequestData;

/**
 * Per-message request builders and reply parsers.
 */
public class Fit3MessagesTest {

    // ---- Fit3BatteryInfo (CH_SETTINGS / MSG_ID 2) ----

    @Test
    public void batteryRequestIsABareHeader() {
        assertArrayEquals(new byte[]{0x02}, Fit3BatteryInfo.buildRequest());
    }

    @Test
    public void batteryReplyIsDecoded() {
        // Captured shape: 42 06 00 05 29 -> not charging, 41 %.
        Fit3BatteryInfo info = Fit3BatteryInfo.parse(new byte[]{0x42, 0x06, 0x00, 0x05, 0x29});
        assertNotNull(info);
        assertEquals(41, info.level);
        assertFalse(info.charging);

        Fit3BatteryInfo charging = Fit3BatteryInfo.parse(new byte[]{0x42, 0x06, 0x01, 0x05, 0x64});
        assertNotNull(charging);
        assertEquals(100, charging.level);
        assertTrue(charging.charging);
    }

    @Test
    public void batteryParseStopsAtAnUnknownParamInsteadOfDesyncing() {
        // 0x11 is not a known battery param, so its width is unknown too. If it
        // is assumed to be one byte the walk desyncs and the next two bytes
        // (00 06) read as a param id and value, making "01" look like charging.
        Fit3BatteryInfo info = Fit3BatteryInfo.parse(
                new byte[]{0x42, 0x05, 0x29, 0x11, 0x00, 0x06, 0x01});
        assertNotNull(info);
        assertEquals(41, info.level);
        assertFalse("must not be picked out of a desynced walk", info.charging);
    }

    @Test
    public void batteryParseRejectsOtherMessages() {
        assertNull("a request, not a reply", Fit3BatteryInfo.parse(new byte[]{0x02}));
        assertNull("another MSG_ID", Fit3BatteryInfo.parse(new byte[]{0x43, 0x05, 0x29}));
        assertNull("variable format", Fit3BatteryInfo.parse(new byte[]{(byte) 0xc2, 0x01, 0x05, 0x29}));
    }

    @Test
    public void batteryReplyWithoutALevelReportsUnknown() {
        Fit3BatteryInfo info = Fit3BatteryInfo.parse(new byte[]{0x42, 0x06, 0x01});
        assertNotNull(info);
        assertEquals(-1, info.level);
        assertTrue(info.charging);
    }

    // ---- Fit3HealthRequestData (CH_HEALTH / MSG_ID 3) ----

    @Test
    public void requestDataReplyEchoesTheSequenceSpan() {
        byte[] request = new byte[]{0x03, (byte) 0xe0, 0x11, 0x22, 0x33, 0x44, 0x01, 0x02};
        byte[] reply = Fit3HealthRequestData.buildResponse(request);
        assertNotNull(reply);
        assertArrayEquals(new byte[]{
                0x43, 0x02,
                (byte) 0xe0, 0x11, 0x22, 0x33, 0x44,
                0x01, 0x05,
        }, reply);
    }

    @Test
    public void requestDataReplyNeedsAFullSequenceSpan() {
        assertNull(Fit3HealthRequestData.buildResponse(new byte[]{0x03, (byte) 0xe0, 0x11}));
    }

    @Test
    public void requestDataRequestCarriesTheSequenceAndLastSyncTime() {
        byte[] request = Fit3HealthRequestData.buildRequest(7, 0x01020304);
        assertEquals(0x03, request[0] & 0xFF);
        assertEquals(0xe0, request[1] & 0xFF);
        assertEquals(7, request[2] & 0xFF);
        assertEquals(0, request[3] & 0xFF);
        assertEquals("device-info record marker", 0x05, request[4] & 0xFF);
        assertEquals("25 bytes of record follow", 0x19, request[5] & 0xFF);
        assertEquals(0x19 + 6, request.length);
        // last_sync_time: tag 0x04 then a little-endian int.
        assertEquals(0x04, request[14] & 0xFF);
        assertEquals(0x01020304,
                (request[15] & 0xFF) | ((request[16] & 0xFF) << 8)
                        | ((request[17] & 0xFF) << 16) | ((request[18] & 0xFF) << 24));
    }

    // ---- Fit3HealthCapabilityCheck (CH_HEALTH / MSG_ID 0x22) ----

    @Test
    public void capabilityCheckEchoesBothParamsAndClaimsSamsungHealth() {
        byte[] request = new byte[]{
                (byte) 0xa2, 0x02,
                (byte) 0xe0, 0x34, 0x12,
                (byte) 0xe1, 0x05, 0x00,
        };
        byte[] reply = Fit3HealthCapabilityCheck.buildResponse(request);
        assertNotNull(reply);
        assertEquals(0xe2, reply[0] & 0xFF);
        assertEquals(4, reply[1] & 0xFF);
        assertEquals(0xe0, reply[2] & 0xFF);
        assertEquals(0x34, reply[3] & 0xFF);
        assertEquals(0x12, reply[4] & 0xFF);
        assertEquals(0xe1, reply[5] & 0xFF);
        assertEquals(0x05, reply[6] & 0xFF);
        assertEquals(0x00, reply[7] & 0xFF);
        assertEquals(0xd0, reply[8] & 0xFF);
        assertEquals("installed", 2, reply[9] & 0xFF);
        assertEquals(0xd2, reply[10] & 0xFF);
    }

    @Test
    public void capabilityCheckWithoutTheExpectedParamsIsNotAnswered() {
        assertNull(Fit3HealthCapabilityCheck.buildResponse(new byte[]{(byte) 0xa2, 0x01, (byte) 0xe0, 0x01, 0x00}));
        assertNull(Fit3HealthCapabilityCheck.buildResponse(new byte[]{(byte) 0xa2}));
    }

    // ---- Fit3LogDump length estimation (CH_DEBUG / MSG_ID 18) ----

    @Test
    public void logTransferBodyLengthComesFromTheDataParam() {
        byte[] body = fileTransfer(3, 7, new byte[100]);
        assertEquals(body.length,
                Fit3LogDump.expectedBodyLength(SapProtocol.CH_DEBUG, body));
        // Same answer from just the head of the body, which is the point: the
        // length is knowable before the continuation arrives.
        byte[] head = new byte[12];
        System.arraycopy(body, 0, head, 0, head.length);
        assertEquals(body.length, Fit3LogDump.expectedBodyLength(SapProtocol.CH_DEBUG, head));
    }

    @Test
    public void logTransferLengthIsUnknownUntilTheLengthFieldArrives() {
        byte[] body = fileTransfer(3, 7, new byte[100]);
        byte[] head = new byte[10];   // one byte short of the full 2-byte length
        System.arraycopy(body, 0, head, 0, head.length);
        assertEquals(-1, Fit3LogDump.expectedBodyLength(SapProtocol.CH_DEBUG, head));
    }

    @Test
    public void otherChannelsAndMessagesKeepTheDefaultBehaviour() {
        byte[] body = fileTransfer(3, 7, new byte[10]);
        assertEquals(-1, Fit3LogDump.expectedBodyLength(SapProtocol.CH_HEALTH, body));
        assertEquals(-1, Fit3LogDump.expectedBodyLength(SapProtocol.CH_DEBUG, new byte[]{0x10, 0x14, 0x02}));
        assertEquals(-1, Fit3LogDump.expectedBodyLength(SapProtocol.CH_DEBUG, new byte[]{0x12}));
    }

    @Test
    public void anUnknownParamWidthGivesUpRatherThanGuessing() {
        // param id 0x7f before DATA: its width is unknown, so no length can be
        // derived and the reassembler falls back to its default rule.
        byte[] body = new byte[]{0x12, 0x7f, 0x00, 0x04, 0x02, 0x00, 0x01, 0x02};
        assertEquals(-1, Fit3LogDump.expectedBodyLength(SapProtocol.CH_DEBUG, body));
    }

    /** {@code [hdr 18][FILE_ID][id][PACKET_INDEX][idx:4][DATA][len:2][data]}. */
    private static byte[] fileTransfer(int fileId, int index, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(18);              // fixed format, request, MSG_ID 18
        out.write(21);              // PARAM_FILE_ID
        out.write(fileId);
        out.write(5);               // PARAM_PACKET_INDEX
        out.write(index);
        out.write(index >> 8);
        out.write(index >> 16);
        out.write(index >> 24);
        out.write(4);               // PARAM_DATA
        out.write(data.length & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    // ---- Fit3HealthPedometerRecord (CH_HEALTH, marker 0x07) ----

    @Test
    public void pedometerRecordsParseRegardlessOfFieldOrder() {
        // The band does not use a fixed field order (UPDATE_TIME has been seen
        // before START_TIME), so the same fields in two different orders must
        // decode identically.
        int[] order = {1, 2, 3, 5, 16, 17, 18, 19, 20, 21, 22, 23};
        int[] shuffled = {5, 23, 16, 1, 2, 3, 22, 21, 20, 19, 18, 17};

        List<Fit3HealthPedometerRecord> a = Fit3HealthPedometerRecord.findAll(
                pedometerList(1, order), 0);
        List<Fit3HealthPedometerRecord> b = Fit3HealthPedometerRecord.findAll(
                pedometerList(1, shuffled), 0);

        assertEquals(1, a.size());
        assertEquals(1, b.size());
        assertEquals(1234, a.get(0).count);
        assertEquals(a.get(0).count, b.get(0).count);
        assertEquals(a.get(0).timestamp, b.get(0).timestamp);
        assertEquals(a.get(0).distance, b.get(0).distance, 0.001f);
        assertEquals(a.get(0).calorie, b.get(0).calorie, 0.001f);
    }

    @Test
    public void everyRecordOfAListIsReturned() {
        int[] order = {1, 2, 3, 5, 16, 17, 18, 19, 20, 21, 22, 23};
        List<Fit3HealthPedometerRecord> records = Fit3HealthPedometerRecord.findAll(
                pedometerList(3, order), 0);
        assertEquals(3, records.size());
        assertEquals(1234 * 3, records.get(0).count + records.get(1).count + records.get(2).count);
    }

    @Test
    public void recordsAreFoundAtAnyOffsetInABlob() {
        int[] order = {1, 2, 3, 5, 16, 17, 18, 19, 20, 21, 22, 23};
        byte[] list = pedometerList(1, order);
        byte[] blob = new byte[list.length + 9];
        // Leading junk, including a stray marker byte that starts nothing valid.
        blob[0] = 0x07;
        blob[1] = 0x2e;
        blob[2] = 0x00;
        System.arraycopy(list, 0, blob, 3, list.length);
        assertEquals(1, Fit3HealthPedometerRecord.findAll(blob, 0).size());
    }

    @Test
    public void aTruncatedOrMalformedListYieldsNothing() {
        int[] order = {1, 2, 3, 5, 16, 17, 18, 19, 20, 21, 22, 23};
        byte[] list = pedometerList(1, order);

        byte[] truncated = new byte[list.length - 4];
        System.arraycopy(list, 0, truncated, 0, truncated.length);
        assertTrue(Fit3HealthPedometerRecord.findAll(truncated, 0).isEmpty());

        // A duplicated tag (and therefore a missing one) is not a valid record.
        int[] duplicate = {1, 1, 3, 5, 16, 17, 18, 19, 20, 21, 22, 23};
        assertTrue(Fit3HealthPedometerRecord.findAll(pedometerList(1, duplicate), 0).isEmpty());
    }

    /**
     * Build a {@code 0x07} record list: one {@code [hasMore][record]} per entry,
     * each record's 12 tag/value fields written in {@code tagOrder}.
     */
    private static byte[] pedometerList(int recordCount, int[] tagOrder) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Fit3HealthPedometerRecord.MARKER);
        for (int i = 0; i < recordCount; i++) {
            out.write(i == recordCount - 1 ? 0x00 : 0x01);
            for (int tag : tagOrder) {
                out.write(tag);
                if (tag == 23) {                       // VERSION_CODE is the one 1-byte field
                    out.write(1);
                    continue;
                }
                int value;
                switch (tag) {
                    case 1:                            // START_TIME, 1990 epoch
                        value = 1_100_000_000;
                        break;
                    case 2:
                        value = 1_100_000_060;
                        break;
                    case 16:                           // COUNT
                        value = 1234;
                        break;
                    case 17:                           // DISTANCE, float bits
                        value = Float.floatToIntBits(987.5f);
                        break;
                    case 18:                           // SPEED
                        value = Float.floatToIntBits(1.25f);
                        break;
                    case 19:                           // CALORIE
                        value = Float.floatToIntBits(42.5f);
                        break;
                    default:
                        value = tag;
                }
                out.write(value & 0xFF);
                out.write((value >> 8) & 0xFF);
                out.write((value >> 16) & 0xFF);
                out.write((value >> 24) & 0xFF);
            }
        }
        return out.toByteArray();
    }

}
