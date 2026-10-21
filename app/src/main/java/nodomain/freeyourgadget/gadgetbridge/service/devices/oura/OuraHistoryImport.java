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

import android.content.Context;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import nodomain.freeyourgadget.gadgetbridge.devices.BatteryLevelProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.HeartRrIntervalSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.samples.OuraActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.BatteryLevel;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HeartRrIntervalSample;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraHistory;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraProtocol;

/**
 * Collects the history records of one fetch, or one batch of it, into per-minute activity samples, beat intervals,
 * HRV, skin temperature and battery levels. The activity samples carry the movement of the ring's
 * rest periods, from which sleep is estimated on read.
 */
class OuraHistoryImport implements OuraHistory.Consumer {
    private static final Logger LOG = LoggerFactory.getLogger(OuraHistoryImport.class);

    private static final int MIN_BEAT_MS = 300;
    private static final int MAX_BEAT_MS = 2000;
    /** Fewer beats than this in a minute give it no heart rate. */
    private static final int MIN_BEATS_PER_MINUTE = 5;
    /**
     * Rest movement at or below this is the sensor's floor while still. Above it, each doubling
     * counts one unit of estimator movement: the raw values span three orders of magnitude, and a
     * single turn over would otherwise outweigh a restless hour.
     */
    private static final int REST_MOVEMENT_FLOOR = 30;

    private final GBDevice device;
    private final long bootMillis;
    /** Start of the minute holding the last record an earlier fetch stored, the only minute two fetches share; -1 for none. */
    private long sharedMinute = -1;

    private final TreeMap<Long, Minute> minutes = new TreeMap<>();
    private final List<HeartRrIntervalSample> beats = new ArrayList<>();
    private final List<GenericHrvValueSample> hrv = new ArrayList<>();
    private final List<GenericTemperatureSample> temperatures = new ArrayList<>();
    private final List<BatteryLevel> batteryLevels = new ArrayList<>();
    private final Map<Integer, Integer> recordCounts = new HashMap<>();
    private long lastOff = -1;
    private long maxTicks = -1;

    /** @param bootMillis the boot time of {@link nodomain.freeyourgadget.gadgetbridge.devices.oura.OuraConstants#PREF_BOOT_MILLIS} */
    OuraHistoryImport(final GBDevice device, final long bootMillis) {
        this.device = device;
        this.bootMillis = bootMillis;
    }

    /** The boot time of {@link nodomain.freeyourgadget.gadgetbridge.devices.oura.OuraConstants#PREF_BOOT_MILLIS}, from a ring tick read at {@code millis}. */
    static long bootMillis(final long millis, final long ticks) {
        return millis - ticks * 1000L / OuraProtocol.TICKS_PER_SECOND;
    }

    /** Continues after the records an earlier fetch stored, the history cursor being {@code cursorTicks}. */
    void startAfter(final long cursorTicks) {
        sharedMinute = cursorTicks > 0 ? Math.floorDiv(toMillis(cursorTicks - 1), 60_000L) * 60L : -1;
    }

    /** Starts the import off the finger since {@code millis}, as an earlier fetch left the ring. */
    void startOff(final long millis) {
        lastOff = millis;
    }

    /** Since when the ring is off the finger at the end of the records so far, or -1 when worn. */
    long getOffSince() {
        return lastOff;
    }

    long getBootMillis() {
        return bootMillis;
    }

    long toMillis(final long ticks) {
        return bootMillis + ticks * 1000L / OuraProtocol.TICKS_PER_SECOND;
    }

    /** Highest ring tick of any record so far, or -1. */
    long getMaxTicks() {
        return maxTicks;
    }

    Map<Integer, Integer> getRecordCounts() {
        return recordCounts;
    }

    @Override
    public void onRecord(final int tag, final long ticks, final byte[] body) {
        maxTicks = Math.max(maxTicks, ticks);
        recordCounts.merge(tag, 1, Integer::sum);
        final long millis = toMillis(ticks);
        switch (tag) {
            case OuraHistory.TAG_BEATS:
                addBeats(millis, OuraHistory.beatIntervals(body));
                break;
            case OuraHistory.TAG_REST_MOVEMENT:
                final int movement = OuraHistory.restMovement(body);
                if (movement >= 0) {
                    minute(millis - 1).addRestMovement(movement);
                }
                break;
            case OuraHistory.TAG_ACTIVITY:
                addActivity(millis, body);
                break;
            case OuraHistory.TAG_HRV:
                addHrv(millis, body);
                break;
            case OuraHistory.TAG_TEMPERATURE:
                addTemperature(millis, OuraHistory.skinTemperature(body));
                break;
            case OuraHistory.TAG_WORN:
                addWorn(millis, body);
                break;
            case OuraHistory.TAG_TEXT:
                final int percent = OuraHistory.batteryPercent(body);
                if (percent >= 0) {
                    final BatteryLevel level = new BatteryLevel();
                    level.setTimestamp((int) (millis / 1000L));
                    level.setBatteryIndex(0);
                    level.setLevel(percent);
                    batteryLevels.add(level);
                }
                break;
            default:
                break;
        }
    }

    private Minute minute(final long millis) {
        return minutes.computeIfAbsent(Math.floorDiv(millis, 60_000L) * 60L, k -> new Minute());
    }

    /** Marks the minutes from the one holding {@code fromMillis} up to, not including, minute {@code toSeconds} off. */
    private void markOff(final long fromMillis, final long toSeconds) {
        for (long t = Math.floorDiv(fromMillis, 60_000L) * 60L; t < toSeconds; t += 60L) {
            minute(t * 1000L).off = true;
        }
    }

    /** Places the beats back from the record's time, which is the end of the last one. */
    private void addBeats(final long endMillis, final int[] intervals) {
        long end = endMillis;
        for (int i = intervals.length - 1; i >= 0; i--) {
            final int interval = intervals[i];
            if (interval >= MIN_BEAT_MS && interval <= MAX_BEAT_MS) {
                final HeartRrIntervalSample sample = new HeartRrIntervalSample();
                sample.setTimestamp(end);
                sample.setSeq(0);
                sample.setRrMillis(interval);
                beats.add(sample);
                minute(end).beats.add(60_000 / interval);
            }
            end -= interval;
        }
    }

    private void addActivity(final long endMillis, final byte[] body) {
        if (body.length < 2) {
            return;
        }
        final int bins = body.length - 1;
        final int steps = body[0] & 0xff;
        for (int i = 0; i < bins; i++) {
            final Minute minute = minute(endMillis - (long) (bins - i) * 60_000L);
            minute.metTenths = OuraHistory.metTenths(body[1 + i]);
            minute.steps = steps / bins + (i < steps % bins ? 1 : 0);
        }
    }

    private void addHrv(final long endMillis, final byte[] body) {
        final int windows = body.length / 2;
        for (int i = 0; i < windows; i++) {
            final int rmssd = body[2 * i + 1] & 0xff;
            if (rmssd == 0) {
                continue;
            }
            final GenericHrvValueSample sample = new GenericHrvValueSample();
            sample.setTimestamp(endMillis - (long) (windows - 1 - i) * OuraHistory.HRV_WINDOW_SECONDS * 1000L);
            sample.setValue(rmssd);
            hrv.add(sample);
        }
    }

    private void addTemperature(final long millis, final float celsius) {
        // Off the finger the sensor reads the air, not the skin
        if (lastOff >= 0 || Float.isNaN(celsius) || celsius < 20f || celsius > 45f) {
            return;
        }
        final GenericTemperatureSample sample = new GenericTemperatureSample();
        sample.setTimestamp(millis);
        sample.setTemperature(celsius);
        sample.setTemperatureType(TemperatureSample.TYPE_SKIN);
        sample.setTemperatureLocation(TemperatureSample.LOCATION_FINGER);
        temperatures.add(sample);
        // A skin reading means the ring was worn, so the minute gets a sample of its own
        minute(millis);
    }

    private void addWorn(final long millis, final byte[] body) {
        if (body.length < 1) {
            return;
        }
        final int state = body[0] & 0xff;
        if (OuraHistory.isNotWorn(state)) {
            if (lastOff < 0) {
                lastOff = millis;
            }
        } else if (OuraHistory.isWorn(state) && lastOff >= 0) {
            markOff(lastOff, Math.floorDiv(millis, 60_000L) * 60L);
            lastOff = -1;
        }
    }

    /**
     * Writes everything collected. Returns false when any of it could not be stored. The minutes
     * go last and only once the rest is stored, so a fetch repeated after a failure adds the shared
     * minute's rest movement once.
     */
    boolean persist(final DaoSession session, @Nullable final Context context) {
        if (lastOff >= 0 && !minutes.isEmpty()) {
            // Still off when the history ends; earlier fetches marked the minutes before this one's
            markOff(Math.max(lastOff, minutes.firstKey() * 1000L), minutes.lastKey() + 60L);
        }
        LOG.debug("Oura history: {} minutes, {} beats, {} HRV, {} temperatures, {} battery levels",
                minutes.size(), beats.size(), hrv.size(), temperatures.size(), batteryLevels.size());

        final HeartRrIntervalSampleProvider beatProvider = new HeartRrIntervalSampleProvider(device, session);
        return beatProvider.persistSamples(beats, context)
                && new GenericHrvValueSampleProvider(device, session).persistSamples(hrv, context)
                && new GenericTemperatureSampleProvider(device, session).persistSamples(temperatures, context)
                && new BatteryLevelProvider(device, session).persistSamples(batteryLevels, context)
                && persistMinutes(session, beatProvider, context);
    }

    private boolean persistMinutes(final DaoSession session,
                                   final HeartRrIntervalSampleProvider beatProvider,
                                   @Nullable final Context context) {
        if (minutes.isEmpty()) {
            return true;
        }
        final OuraActivitySampleProvider provider = new OuraActivitySampleProvider(device, session);
        final Map<Integer, OuraActivitySample> stored = new HashMap<>();
        for (final OuraActivitySample sample : provider.getDeviceSamples(
                minutes.firstKey().intValue(), minutes.lastKey().intValue())) {
            stored.put(sample.getTimestamp(), sample);
        }
        final List<OuraActivitySample> samples = new ArrayList<>(minutes.size());
        for (final Map.Entry<Long, Minute> entry : minutes.entrySet()) {
            final int timestamp = entry.getKey().intValue();
            final Minute minute = entry.getValue();
            final OuraActivitySample previous = stored.get(timestamp);
            final OuraActivitySample sample = new OuraActivitySample();
            sample.setTimestamp(timestamp);
            // The minute shared with an earlier fetch holds records of both
            final boolean shared = timestamp == sharedMinute && previous != null;
            // A heart rate record carries beats of the minutes before its own, so any stored minute
            // that gains beats is averaged over all its beats, stored first
            final int heartRate = previous != null && !minute.beats.isEmpty()
                    ? storedHeartRate(beatProvider, timestamp)
                    : minute.heartRate();
            sample.setHeartRate(heartRate > 0 ? heartRate
                    : previous != null ? previous.getHeartRate() : ActivitySample.NOT_MEASURED);
            sample.setRawIntensity(minute.metTenths >= 0 ? minute.metTenths
                    : previous != null ? previous.getRawIntensity() : ActivitySample.NOT_MEASURED);
            sample.setSteps(minute.steps >= 0 ? minute.steps
                    : previous != null ? previous.getSteps() : 0);
            final Float storedMovement = previous != null ? previous.getRestMovement() : null;
            if (minute.restMovement < 0) {
                sample.setRestMovement(storedMovement);
            } else if (shared && storedMovement != null) {
                sample.setRestMovement(storedMovement + minute.restMovement);
            } else {
                sample.setRestMovement(minute.restMovement);
            }
            // A minute stays not worn unless this fetch measured a heart rate in it
            final boolean storedOff = previous != null && previous.getRawKind() == ActivityKind.NOT_WORN.getCode();
            sample.setRawKind(heartRate <= 0 && (minute.off || storedOff)
                    ? ActivityKind.NOT_WORN.getCode()
                    : ActivityKind.UNKNOWN.getCode());
            samples.add(sample);
        }
        return provider.persistSamples(samples, context);
    }

    private static int storedHeartRate(final HeartRrIntervalSampleProvider beatProvider, final int timestamp) {
        final Minute all = new Minute();
        for (final HeartRrIntervalSample beat : beatProvider.getAllSamples(timestamp * 1000L, timestamp * 1000L + 59_999L)) {
            all.beats.add(60_000 / beat.getRrMillis());
        }
        return all.heartRate();
    }

    private static final class Minute {
        final List<Integer> beats = new ArrayList<>();
        int metTenths = -1;
        int steps = -1;
        /** Estimator movement summed over the minute's rest records, or -1 outside rest. */
        float restMovement = -1;
        boolean off;

        void addRestMovement(final int movement) {
            final double above = movement > REST_MOVEMENT_FLOOR ? Math.log((double) movement / REST_MOVEMENT_FLOOR) / Math.log(2) : 0;
            restMovement = Math.max(restMovement, 0) + (float) above;
        }

        int heartRate() {
            if (beats.size() < MIN_BEATS_PER_MINUTE) {
                return -1;
            }
            final int[] sorted = beats.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(sorted);
            return sorted[sorted.length / 2];
        }
    }
}
