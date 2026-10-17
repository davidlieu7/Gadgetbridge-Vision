/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.BatteryLevelProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.OuraEstimatedSleepSessionProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.samples.OuraActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraHistory;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.sleep.SleepMinute;
import nodomain.freeyourgadget.gadgetbridge.util.sleep.SleepMinuteKind;

/** Record bodies are taken from a ring's history captured on 2026-10-17. */
public class OuraHistoryImportTest extends TestBase {
    /** 2026-10-17 12:00:00 UTC, at ring tick 1 000 000. */
    private static final long SYNC_MILLIS = 1792238400_000L;
    private static final long SYNC_TICKS = 1_000_000L;
    private static final long BOOT_MILLIS = OuraHistoryImport.bootMillis(SYNC_MILLIS, SYNC_TICKS);

    private static long ticksAt(final long secondsAfterSync) {
        return SYNC_TICKS + secondsAfterSync * 10;
    }

    private static byte[] record(final int tag, final long ticks, final String bodyHex) {
        final byte[] body = GB.hexStringToByteArray(bodyHex);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        out.write(4 + body.length);
        for (int i = 0; i < 4; i++) {
            out.write((int) (ticks >> (8 * i)) & 0xff);
        }
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] concat(final byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    /** The import of a later fetch, starting from the off state the stored history left. */
    private static OuraHistoryImport after(final GBDevice device, final OuraHistoryImport earlier) {
        final OuraHistoryImport later = new OuraHistoryImport(device, BOOT_MILLIS);
        later.startAfter(earlier.getMaxTicks() + 1);
        if (earlier.getOffSince() >= 0) {
            later.startOff(earlier.getOffSince());
        }
        return later;
    }

    private List<OuraActivitySample> storedMinutes(final GBDevice device, final int fromSecond, final int toSecond) {
        return new OuraActivitySampleProvider(device, daoSession)
                .getDeviceSamples((int) (SYNC_MILLIS / 1000) + fromSecond, (int) (SYNC_MILLIS / 1000) + toSecond);
    }

    @Test
    public void walk_readsBackToBackRecords() {
        final byte[] value = concat(
                record(OuraHistory.TAG_TEMPERATURE, ticksAt(0), "ce0d420ece0b"),
                record(OuraHistory.TAG_TEXT, ticksAt(1), "626174743a203937"));
        final List<Integer> tags = new ArrayList<>();

        // A reply after the records is left for the reply parser
        final byte[] withReply = concat(value, GB.hexStringToByteArray("1102ffff"));

        assertEquals(value.length, OuraHistory.walk(withReply, (tag, ticks, body) -> tags.add(tag)));
        assertEquals(List.of(OuraHistory.TAG_TEMPERATURE, OuraHistory.TAG_TEXT), tags);
    }

    @Test
    public void decoders() {
        assertArrayEquals(new int[]{1112, 1152, 1192, 1136, 1056, 1088, 768},
                OuraHistory.beatIntervals(GB.hexStringToByteArray("8b90958e848860908896969509e1")));
        assertArrayEquals(new int[]{496, 744}, OuraHistory.beatIntervals(GB.hexStringToByteArray("3e5d0000000000aaaaaaaaaaaaaa")));
        assertEquals(35.92f, OuraHistory.skinTemperature(GB.hexStringToByteArray("ce0d420ece0b")), 0.001f);
        assertEquals(97, OuraHistory.batteryPercent(GB.hexStringToByteArray("626174743a203937")));
        assertEquals(-1, OuraHistory.batteryPercent("fea off".getBytes()));
        assertEquals(11, OuraHistory.restMovement(GB.hexStringToByteArray("0b001c000f00130016000200")));
        assertEquals(12, OuraHistory.metTenths((byte) 0x0c));
        assertEquals(130, OuraHistory.metTenths((byte) 0x81));
    }

    @Test
    public void activity_hrvAndBattery_areStoredAtTheirMinutes() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:60");
        DBHelper.getDevice(device, daoSession);
        final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
        // 72 steps over the five minutes before the record
        history.onRecord(OuraHistory.TAG_ACTIVITY, ticksAt(600), GB.hexStringToByteArray("481211130f2b"));
        // Six five-minute windows, the last one without a reading
        history.onRecord(OuraHistory.TAG_HRV, ticksAt(1800), GB.hexStringToByteArray("3a473a383a333c283d2a0000"));
        history.onRecord(OuraHistory.TAG_TEXT, ticksAt(300), GB.hexStringToByteArray("626174743a203937"));
        history.persist(daoSession, null);

        final List<OuraActivitySample> samples = new OuraActivitySampleProvider(device, daoSession)
                .getAllActivitySamples((int) (SYNC_MILLIS / 1000), (int) (SYNC_MILLIS / 1000) + 600);
        final List<OuraActivitySample> active = samples.stream().filter(s -> s.getSteps() > 0).collect(Collectors.toList());
        assertEquals(List.of(300, 360, 420, 480, 540), active.stream().map(s -> s.getTimestamp() - (int) (SYNC_MILLIS / 1000)).collect(Collectors.toList()));
        assertEquals(List.of(15, 15, 14, 14, 14), active.stream().map(OuraActivitySample::getSteps).collect(Collectors.toList()));
        assertEquals(List.of(18, 17, 19, 15, 43), active.stream().map(OuraActivitySample::getRawIntensity).collect(Collectors.toList()));

        final List<GenericHrvValueSample> hrv = new GenericHrvValueSampleProvider(device, daoSession).getAllSamples(0, Long.MAX_VALUE);
        hrv.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
        assertEquals(List.of(300L, 600L, 900L, 1200L, 1500L), hrv.stream().map(s -> (s.getTimestamp() - SYNC_MILLIS) / 1000).collect(Collectors.toList()));
        assertEquals(List.of(71, 56, 51, 40, 42), hrv.stream().map(GenericHrvValueSample::getValue).collect(Collectors.toList()));

        assertEquals(97, new BatteryLevelProvider(device, daoSession).getAllSamples(0, 0, Integer.MAX_VALUE * 1000L).get(0).getLevel());
    }

    @Test
    public void sleepMinutes_restIsRaw_wornIsAwake_offIsUnmeasurable() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:62");
        DBHelper.getDevice(device, daoSession);
        final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
        // Minute 0: worn, no rest record
        history.onRecord(OuraHistory.TAG_TEMPERATURE, ticksAt(10), GB.hexStringToByteArray("ce0d420ece0b"));
        // Minute 1: two rest records and seven beats, median 53 bpm
        history.onRecord(OuraHistory.TAG_REST_MOVEMENT, ticksAt(90), GB.hexStringToByteArray("0b001c000f00130016000200"));
        history.onRecord(OuraHistory.TAG_REST_MOVEMENT, ticksAt(119), GB.hexStringToByteArray("bc002d024401230060000800"));
        history.onRecord(OuraHistory.TAG_BEATS, ticksAt(110), GB.hexStringToByteArray("8b90958e848860908896969509e1"));
        // Minutes 3 to 4: off the finger
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(180), GB.hexStringToByteArray("013339393933"));
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(300), GB.hexStringToByteArray("03343130"));
        history.persist(daoSession, null);
        // A later fetch with another record in minute 1 keeps its rest movement
        final OuraHistoryImport later = after(device, history);
        later.onRecord(OuraHistory.TAG_TEMPERATURE, ticksAt(100), GB.hexStringToByteArray("ce0d420ece0b"));
        later.persist(daoSession, null);

        final List<SleepMinute> minutes = new OuraActivitySampleProvider(device, daoSession)
                .getDeviceSamples((int) (SYNC_MILLIS / 1000), (int) (SYNC_MILLIS / 1000) + 300).stream()
                .map(OuraEstimatedSleepSessionProvider::toMinute)
                .collect(Collectors.toList());

        assertEquals(List.of(0L, 60L, 180L, 240L), minutes.stream().map(m -> m.getTime().getEpochSecond() - SYNC_MILLIS / 1000).collect(Collectors.toList()));
        assertEquals(SleepMinuteKind.AWAKE, minutes.get(0).getKind());
        assertEquals(SleepMinuteKind.RAW, minutes.get(1).getKind());
        // 11 is below the floor; 188 is 2.65 doublings above it
        assertEquals(2.648f, minutes.get(1).getMovement(), 0.001f);
        assertEquals(53f, minutes.get(1).getHeartRate(), 0.001f);
        assertEquals(SleepMinuteKind.UNMEASURABLE, minutes.get(2).getKind());
        assertNull(minutes.get(3).getHeartRate());
        assertEquals(SleepMinuteKind.UNMEASURABLE, minutes.get(3).getKind());
    }

    @Test
    public void aFetchStartingOff_staysOffUntilWornAgain() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:63");
        DBHelper.getDevice(device, daoSession);
        final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
        history.startOff(SYNC_MILLIS);
        // A reading of the air while off, then back on the finger at minute 2
        history.onRecord(OuraHistory.TAG_TEMPERATURE, ticksAt(30), GB.hexStringToByteArray("ce0d420ece0b"));
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(120), GB.hexStringToByteArray("03343130"));
        history.persist(daoSession, null);

        final List<OuraActivitySample> samples = new OuraActivitySampleProvider(device, daoSession)
                .getDeviceSamples((int) (SYNC_MILLIS / 1000), (int) (SYNC_MILLIS / 1000) + 60);
        assertEquals(2, samples.size());
        samples.forEach(s -> assertEquals(ActivityKind.NOT_WORN.getCode(), s.getRawKind()));
        assertEquals(0, new GenericTemperatureSampleProvider(device, daoSession).getAllSamples(0, Long.MAX_VALUE).size());
    }

    @Test
    public void aFetchEndingRightAfterWornOn_leavesTheNextFetchWorn() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:64");
        DBHelper.getDevice(device, daoSession);
        final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
        // Off at 0:05, on again at 2:50: minutes 0 and 1 off, minute 2 worn
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(5), GB.hexStringToByteArray("013339393933"));
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(170), GB.hexStringToByteArray("03343130"));
        history.persist(daoSession, null);
        assertEquals(-1, history.getOffSince());

        final OuraHistoryImport later = after(device, history);
        later.onRecord(OuraHistory.TAG_TEMPERATURE, ticksAt(200), GB.hexStringToByteArray("ce0d420ece0b"));
        later.persist(daoSession, null);

        assertEquals(List.of(0, 60, 180), storedMinutes(device, 0, 180).stream()
                .map(s -> s.getTimestamp() - (int) (SYNC_MILLIS / 1000)).collect(Collectors.toList()));
        assertEquals(List.of(ActivityKind.NOT_WORN.getCode(), ActivityKind.NOT_WORN.getCode(), ActivityKind.UNKNOWN.getCode()),
                storedMinutes(device, 0, 180).stream().map(OuraActivitySample::getRawKind).collect(Collectors.toList()));
        assertEquals(1, new GenericTemperatureSampleProvider(device, daoSession).getAllSamples(0, Long.MAX_VALUE).size());
    }

    @Test
    public void aFetchEndingOff_marksTheOffSpanWhenTheNextFetchSeesTheRingOn() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:65");
        DBHelper.getDevice(device, daoSession);
        final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
        history.onRecord(OuraHistory.TAG_TEMPERATURE, ticksAt(10), GB.hexStringToByteArray("ce0d420ece0b"));
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(60), GB.hexStringToByteArray("013339393933"));
        history.persist(daoSession, null);
        assertEquals(SYNC_MILLIS + 60_000L, history.getOffSince());

        // The next fetch reads the air until the ring goes back on at minute 4
        final OuraHistoryImport later = after(device, history);
        later.onRecord(OuraHistory.TAG_TEMPERATURE, ticksAt(150), GB.hexStringToByteArray("ce0d420ece0b"));
        later.onRecord(OuraHistory.TAG_WORN, ticksAt(240), GB.hexStringToByteArray("03343130"));
        later.persist(daoSession, null);

        final List<OuraActivitySample> samples = storedMinutes(device, 0, 180);
        assertEquals(List.of(0, 60, 120, 180), samples.stream()
                .map(s -> s.getTimestamp() - (int) (SYNC_MILLIS / 1000)).collect(Collectors.toList()));
        assertEquals(ActivityKind.UNKNOWN.getCode(), samples.get(0).getRawKind());
        samples.subList(1, 4).forEach(s -> assertEquals(ActivityKind.NOT_WORN.getCode(), s.getRawKind()));
        assertEquals(1, new GenericTemperatureSampleProvider(device, daoSession).getAllSamples(0, Long.MAX_VALUE).size());
    }

    @Test
    public void aMinuteSplitBetweenFetches_addsUpItsRestAndBeats() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:66");
        DBHelper.getDevice(device, daoSession);
        final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
        history.onRecord(OuraHistory.TAG_REST_MOVEMENT, ticksAt(90), GB.hexStringToByteArray("bc002d024401230060000800"));
        history.onRecord(OuraHistory.TAG_BEATS, ticksAt(80), GB.hexStringToByteArray("8b90958e848860908896969509e1"));
        history.persist(daoSession, null);

        final OuraHistoryImport later = after(device, history);
        later.onRecord(OuraHistory.TAG_REST_MOVEMENT, ticksAt(119), GB.hexStringToByteArray("bc002d024401230060000800"));
        later.onRecord(OuraHistory.TAG_BEATS, ticksAt(118), GB.hexStringToByteArray("4b4b4b4b4b4b4b00000000000000"));
        later.persist(daoSession, null);

        final OuraActivitySample minute = storedMinutes(device, 60, 60).get(0);
        // Each record is 188, 2.65 doublings above the floor
        assertEquals(5.296f, minute.getRestMovement(), 0.001f);
        // Seven beats near 53 bpm and seven at 100 bpm: the median of all fourteen
        assertEquals(100, minute.getHeartRate());
    }

    @Test
    public void aMinuteSplitBetweenFetches_getsAHeartRateFromBothParts() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:67");
        DBHelper.getDevice(device, daoSession);
        final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
        // Three beats near 52 bpm, too few for a heart rate on their own
        history.onRecord(OuraHistory.TAG_BEATS, ticksAt(80), GB.hexStringToByteArray("8b90950000000000000000000000"));
        history.persist(daoSession, null);

        final OuraHistoryImport later = after(device, history);
        later.onRecord(OuraHistory.TAG_BEATS, ticksAt(118), GB.hexStringToByteArray("4b4b4b0000000000000000000000"));
        later.persist(daoSession, null);

        assertEquals(100, storedMinutes(device, 60, 60).get(0).getHeartRate());
    }

    @Test
    public void aRefetchFromTheStart_replacesRestMovementOutsideTheSharedMinute() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:68");
        DBHelper.getDevice(device, daoSession);
        for (int fetch = 0; fetch < 2; fetch++) {
            final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
            history.startAfter(0);
            history.onRecord(OuraHistory.TAG_REST_MOVEMENT, ticksAt(90), GB.hexStringToByteArray("bc002d024401230060000800"));
            history.persist(daoSession, null);
        }

        assertEquals(2.648f, storedMinutes(device, 60, 60).get(0).getRestMovement(), 0.001f);
    }

    @Test
    public void chargingCountsAsOff_untilOnTheFingerWithHeartRate() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:69");
        DBHelper.getDevice(device, daoSession);
        final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
        // Charging at 0:00, a second off state later keeps the first, back on (with heart rate) at 2:00
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(0), GB.hexStringToByteArray("08"));
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(70), GB.hexStringToByteArray("01"));
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(120), GB.hexStringToByteArray("05"));
        history.persist(daoSession, null);

        final List<OuraActivitySample> samples = storedMinutes(device, 0, 60);
        assertEquals(2, samples.size());
        samples.forEach(s -> assertEquals(ActivityKind.NOT_WORN.getCode(), s.getRawKind()));
        assertEquals(-1, history.getOffSince());
    }

    @Test
    public void offMinutesWithoutBeats_areNotWorn() {
        final GBDevice device = createDummyGDevice("00:00:00:00:00:61");
        DBHelper.getDevice(device, daoSession);
        final OuraHistoryImport history = new OuraHistoryImport(device, BOOT_MILLIS);
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(0), GB.hexStringToByteArray("013339393933"));
        history.onRecord(OuraHistory.TAG_WORN, ticksAt(120), GB.hexStringToByteArray("03343130"));
        history.persist(daoSession, null);
        // A later fetch without beats in those minutes leaves them not worn
        final OuraHistoryImport later = after(device, history);
        later.onRecord(OuraHistory.TAG_TEMPERATURE, ticksAt(30), GB.hexStringToByteArray("ce0d420ece0b"));
        later.persist(daoSession, null);

        final List<OuraActivitySample> samples = new OuraActivitySampleProvider(device, daoSession)
                .getAllActivitySamples((int) (SYNC_MILLIS / 1000), (int) (SYNC_MILLIS / 1000) + 60);
        assertEquals(2, samples.size());
        samples.forEach(s -> assertEquals(ActivityKind.NOT_WORN, s.getKind()));
    }
}
