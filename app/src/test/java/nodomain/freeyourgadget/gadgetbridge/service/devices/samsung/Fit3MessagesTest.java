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
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3FullSettings;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthCapabilityCheck;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthPedometerRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthRecordType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthRequestData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthSyncData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3InitSetting;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Settings;

/**
 * Per-message request builders and reply parsers.
 */
public class Fit3MessagesTest {

    // ---- Fit3Settings (CH_SETTINGS, one param per message, no count byte) ----

    @Test
    public void settingsWritesAreBareParamValuePairs() {
        // All of these are confirmed on a live band - it acks each one and the
        // setting changes on the device.
        assertArrayEquals(new byte[]{0x03, 0x00, 0x01}, Fit3Settings.mute(true));
        assertArrayEquals(new byte[]{0x03, 0x00, 0x00}, Fit3Settings.mute(false));
        assertArrayEquals(new byte[]{0x03, 0x02, 0x01}, Fit3Settings.vibrationIntensityStrong(true));
        assertArrayEquals(new byte[]{0x03, 0x03, 0x01}, Fit3Settings.touchVibration(true));
        assertArrayEquals(new byte[]{0x06, 0x09, 0x01}, Fit3Settings.alwaysOnDisplay(true));
        assertArrayEquals(new byte[]{0x06, 0x0a, 0x00}, Fit3Settings.raiseWristWake(false));
    }

    @Test
    public void brightnessIsAStepNotAPercentage() {
        assertArrayEquals(new byte[]{0x06, 0x08, 0x01}, Fit3Settings.autoBrightness(true));
        assertArrayEquals(new byte[]{0x05, 0x07, 0x01}, Fit3Settings.brightness(1));
        assertArrayEquals(new byte[]{0x05, 0x07, 0x0a}, Fit3Settings.brightness(10));
        // The band only knows ten steps, so anything else is clamped rather
        // than handed over as a value it has no meaning for.
        assertArrayEquals(new byte[]{0x05, 0x07, 0x01}, Fit3Settings.brightness(0));
        assertArrayEquals(new byte[]{0x05, 0x07, 0x0a}, Fit3Settings.brightness(100));
    }

    @Test
    public void screenTimeoutIsALittleEndianShort() {
        assertArrayEquals(new byte[]{0x07, 0x0e, 0x04, 0x00}, Fit3Settings.screenTimeout(4));
        assertArrayEquals(new byte[]{0x07, 0x0e, 0x1e, 0x00}, Fit3Settings.screenTimeout(30));
    }

    // ---- Fit3InitSetting (CH_CONTROL / MSG_ID 3) ----

    @Test
    public void initSettingReplyIsFixedFormatDespiteTheVariableRequest() {
        // Verbatim from a live band, whose battery read 36 % on the settings
        // channel in the same session: 0x24 = 36.
        Fit3InitSetting info = Fit3InitSetting.parse(
                new byte[]{0x43, 0x01, 0x24, 0x02, 0x00, 0x03, 0x01});
        assertNotNull(info);
        assertEquals(36, info.batteryPercent);
        assertEquals(0, info.unknownParam2);
        assertTrue(info.localeAvailable);
        assertEquals("locale value is absent, so the documented default stands",
                90, info.localeValue);
    }

    @Test
    public void initSettingReplyTakesAnOptionalLocaleValue() {
        Fit3InitSetting info = Fit3InitSetting.parse(
                new byte[]{0x43, 0x01, 0x28, 0x02, 0x00, 0x03, 0x01, 0x04, 0x39, 0x00});
        assertNotNull(info);
        assertEquals(40, info.batteryPercent);
        assertEquals(0x39, info.localeValue);
    }

    @Test
    public void initSettingRejectsOtherMessages() {
        assertNull("a request, not a reply",
                Fit3InitSetting.parse(new byte[]{(byte) 0x83, 0x01, 0x39, 0x00}));
        assertNull("another MSG_ID", Fit3InitSetting.parse(new byte[]{0x42, 0x01, 0x24}));
    }

    // ---- Fit3FullSettings (CH_SETTINGS / MSG_ID 32) ----

    @Test
    public void fullSettingsRequestIsABareHeader() {
        assertArrayEquals(new byte[]{0x20}, Fit3FullSettings.buildRequest());
    }

    @Test
    public void fullSettingsMixesOneAndTwoByteParams() {
        // 60 [count] then [id][value], widths per the band's own schema:
        // 0 and 2 are bytes, 5 and 14 are little-endian shorts.
        byte[] body = new byte[]{
                0x60, 5,
                0, 1,                    // mute on
                2, 1,                    // intensity strong
                5, 0x03, 0x00,           // call vibration pattern 3 (short)
                7, 0x06,                 // brightness 6
                14, 0x1e, 0x00,          // screen timeout 30 s (short)
        };
        Fit3FullSettings settings = Fit3FullSettings.parse(body);
        assertNotNull(settings);
        assertEquals(5, settings.size());
        assertEquals(Boolean.TRUE, settings.getBoolean(Fit3FullSettings.PARAM_MUTE));
        assertEquals(Boolean.TRUE, settings.getBoolean(Fit3FullSettings.PARAM_VIBRATION_INTENSITY));
        assertEquals(Integer.valueOf(3), settings.get(Fit3FullSettings.PARAM_CALL_VIBRATION_PATTERN));
        assertEquals(Integer.valueOf(6), settings.get(Fit3FullSettings.PARAM_BRIGHTNESS));
        assertEquals(Integer.valueOf(30), settings.get(Fit3FullSettings.PARAM_SCREEN_TIMEOUT));
        assertNull("absent params stay absent", settings.get(Fit3FullSettings.PARAM_ALWAYS_ON_DISPLAY));
        assertNull(settings.getBoolean(Fit3FullSettings.PARAM_ALWAYS_ON_DISPLAY));
    }

    @Test
    public void fullSettingsKeepsWhatItDecodedBeforeAnUnknownParam() {
        byte[] body = new byte[]{
                0x60, 3,
                0, 0,                    // mute off
                (byte) 0x7f, 0x01,       // unknown id: width unknown, stop here
                9, 1,                    // never reached
        };
        Fit3FullSettings settings = Fit3FullSettings.parse(body);
        assertNotNull(settings);
        assertEquals(1, settings.size());
        assertEquals(Boolean.FALSE, settings.getBoolean(Fit3FullSettings.PARAM_MUTE));
        assertNull(settings.getBoolean(Fit3FullSettings.PARAM_ALWAYS_ON_DISPLAY));
    }

    @Test
    public void fullSettingsSurvivesATruncatedShort() {
        byte[] body = new byte[]{
                0x60, 2,
                7, 0x0a,                 // brightness 10
                14, 0x1e,                // short cut in half
        };
        Fit3FullSettings settings = Fit3FullSettings.parse(body);
        assertNotNull(settings);
        assertEquals(Integer.valueOf(10), settings.get(Fit3FullSettings.PARAM_BRIGHTNESS));
        assertNull(settings.get(Fit3FullSettings.PARAM_SCREEN_TIMEOUT));
    }

    @Test
    public void fullSettingsRejectsOtherMessages() {
        assertNull("a request, not a reply", Fit3FullSettings.parse(new byte[]{0x20, 0x00}));
        assertNull("another MSG_ID", Fit3FullSettings.parse(new byte[]{(byte) 0xe2, 0x01, 0x00, 0x01}));
    }

    @Test
    public void fullSettingsDecodesACapturedReply() {
        // Verbatim body of a live band's reply to the 0x20 request: header 0x60
        // (fixed format, response) plus a count byte anyway, then all 22 params.
        byte[] body = parseHex(
                "6016000001000201030104010501000601000703080109000a010b000c000d01"
                        + "0e07000f1400100000110100120000130114001501");
        Fit3FullSettings settings = Fit3FullSettings.parse(body);
        assertNotNull(settings);
        assertEquals("every param id 0..21 is reported", 22, settings.size());

        assertEquals(Boolean.FALSE, settings.getBoolean(Fit3FullSettings.PARAM_MUTE));
        assertEquals(Boolean.TRUE, settings.getBoolean(Fit3FullSettings.PARAM_VIBRATION_INTENSITY));
        assertEquals(Boolean.TRUE, settings.getBoolean(Fit3FullSettings.PARAM_TOUCH_VIBRATION));
        assertEquals(Integer.valueOf(3), settings.get(Fit3FullSettings.PARAM_BRIGHTNESS));
        assertEquals(Boolean.TRUE, settings.getBoolean(Fit3FullSettings.PARAM_AUTO_BRIGHTNESS));
        assertEquals(Boolean.FALSE, settings.getBoolean(Fit3FullSettings.PARAM_ALWAYS_ON_DISPLAY));
        assertEquals(Boolean.TRUE, settings.getBoolean(Fit3FullSettings.PARAM_RAISE_WRIST_WAKE));
        // The two-byte fields are the real check on the width table - a wrong
        // width here would shift everything after it.
        assertEquals(Integer.valueOf(7), settings.get(Fit3FullSettings.PARAM_SCREEN_TIMEOUT));
        assertEquals(Integer.valueOf(20), settings.get(Fit3FullSettings.PARAM_SHOW_LAST_APP_TIMEOUT));
        assertEquals(Integer.valueOf(1), settings.get(Fit3FullSettings.PARAM_DOUBLE_PRESS));
        assertEquals(Boolean.TRUE, settings.getBoolean(Fit3FullSettings.PARAM_BUTTON_POSITION));
    }

    private static byte[] parseHex(final String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ---- Record-type identification (CH_HEALTH outer marker) ----

    /**
     * The leading bytes of four real band-to-phone payloads, each a different
     * record type. Enough of each to cover the header, the sequence number and
     * the first record's marker plus its length prefix.
     */
    private static final String CAPTURED_PREFERENCES = "8203e0202795661e000010 87d3ad4401".replace(" ", "");
    private static final String CAPTURED_WORKOUT = "8203e0a52880004117000075 3661 3463".replace(" ", "");
    private static final String CAPTURED_PER_MINUTE = "8203e07f278b00b24f000075 3137 3833".replace(" ", "");
    private static final String CAPTURED_HEART_RATE = "8203e00328 0a 01 29000000 0129 18b044".replace(" ", "");

    @Test
    public void everyCapturedPayloadIsIdentifiedByItsFirstMarker() {
        assertEquals(0x95, Fit3HealthSyncData.firstRecordMarker(parseHex(CAPTURED_PREFERENCES)));
        assertEquals(0x80, Fit3HealthSyncData.firstRecordMarker(parseHex(CAPTURED_WORKOUT)));
        assertEquals(0x8b, Fit3HealthSyncData.firstRecordMarker(parseHex(CAPTURED_PER_MINUTE)));
        assertEquals(0x0a, Fit3HealthSyncData.firstRecordMarker(parseHex(CAPTURED_HEART_RATE)));

        assertEquals("app preferences", Fit3HealthRecordType.name(0x95));
        assertEquals("workout", Fit3HealthRecordType.name(0x80));
        assertEquals("per-minute activity", Fit3HealthRecordType.name(0x8b));
        assertEquals("heart rate", Fit3HealthRecordType.name(0x0a));
    }

    @Test
    public void aPreferencesBlobIsNotScannedForActivityRecords() {
        // Every bulk transfer seen so far is this one record type: kilobytes of
        // app preferences with no activity data in them at all.
        int marker = Fit3HealthSyncData.firstRecordMarker(parseHex(CAPTURED_PREFERENCES));
        assertEquals(Fit3HealthRecordType.MARKER_APP_PREFERENCES, marker);
        assertFalse(Fit3HealthRecordType.carriesActivityRecords(marker));
    }

    @Test
    public void everythingElseIsStillScanned() {
        // Including record types with no name yet, and payloads whose framing
        // didn't parse - skipping the scan is opt-in, never the fallback.
        assertTrue(Fit3HealthRecordType.carriesActivityRecords(0x80));
        assertTrue(Fit3HealthRecordType.carriesActivityRecords(0x0a));
        assertTrue("undecoded record type", Fit3HealthRecordType.carriesActivityRecords(0x6b));
        assertTrue("unparseable framing", Fit3HealthRecordType.carriesActivityRecords(-1));
        assertEquals("unknown", Fit3HealthRecordType.name(0x6b));
        assertEquals("unknown", Fit3HealthRecordType.name(-1));
    }

    @Test
    public void aPayloadWithoutTheSequenceNumberHasNoMarker() {
        assertEquals(-1, Fit3HealthSyncData.firstRecordMarker(parseHex("8203e02027")));  // header only
        assertEquals(-1, Fit3HealthSyncData.firstRecordMarker(parseHex("820301202795")));  // 0xe0 missing
        assertEquals(-1, Fit3HealthSyncData.firstRecordMarker(new byte[0]));
    }

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
