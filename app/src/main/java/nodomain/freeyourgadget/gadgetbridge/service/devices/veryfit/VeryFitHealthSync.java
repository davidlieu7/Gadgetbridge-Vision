/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.veryfit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.VeryFitStepsSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.VeryFitWorkoutGpsSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitWorkoutSummaryParser;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.VeryFitStepsSample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeryFitWorkoutGpsSample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

/**
 * One pass over the watch's health store.
 * <p>
 * The round opens by announcing which types are wanted, and then walks them one at a time: a fetch
 * brings back a summary block followed by the detail records, and a close moves the watch on to
 * the next one. Each type answers for the current day only, which is as far as a single round
 * reaches.
 */
class VeryFitHealthSync {
    private static final Logger LOG = LoggerFactory.getLogger(VeryFitHealthSync.class);

    /** The types worth asking for, in the order the vendor app walks them. */
    private static final byte[] TYPES = {
            VeryFitConstants.HEALTH_SPO2,
            VeryFitConstants.HEALTH_STRESS,
            VeryFitConstants.HEALTH_STEPS,
            VeryFitConstants.HEALTH_SLEEP,
            VeryFitConstants.HEALTH_HRV,
            VeryFitConstants.HEALTH_HEART_RATE,
            VeryFitConstants.HEALTH_WORKOUT,
            VeryFitConstants.HEALTH_GPS,
    };

    /** The ones that stand on their own rather than being today's readings are asked for as such. */
    private static final byte[] TODAY = {1, 1, 1, 0, 1, 1, 0, 0};

    private final VeryFitSupport support;
    private int index;
    private boolean running;
    private long workoutStart;
    private long workoutEnd;

    VeryFitHealthSync(final VeryFitSupport support) {
        this.support = support;
    }

    boolean isRunning() {
        return running;
    }

    /** Forgets a round the connection dropped in the middle of; says whether there was one. */
    boolean reset() {
        final boolean wasRunning = running;
        running = false;
        return wasRunning;
    }

    void start() {
        index = 0;
        running = true;
        workoutStart = 0;
        workoutEnd = 0;
        support.sendFramed("veryfit health types", VeryFitConstants.FRAMED_HEALTH_TYPES,
                VeryFitProtocol.healthTypes(TYPES));
    }

    void onPacket(final int cmd, final byte[] payload) {
        if (!running) {
            return;
        }

        if (cmd == VeryFitConstants.FRAMED_HEALTH_TYPES) {
            fetch();
            return;
        }

        if (payload.length < VeryFitConstants.HEALTH_REQUEST_LEN) {
            finish();
            return;
        }

        if (payload[0] == VeryFitConstants.HEALTH_CLOSE) {
            index++;
            fetch();
            return;
        }

        try {
            handle(payload);
        } catch (final Exception e) {
            LOG.error("Failed to handle health type 0x{}", Integer.toHexString(payload[1] & 0xff), e);
        }
        support.sendFramed("veryfit health close", VeryFitConstants.FRAMED_HEALTH,
                VeryFitProtocol.healthRequest(VeryFitConstants.HEALTH_CLOSE, TYPES[index],
                        TODAY[index] != 0));
    }

    private void fetch() {
        if (index >= TYPES.length) {
            finish();
            return;
        }
        support.sendFramed("veryfit health fetch", VeryFitConstants.FRAMED_HEALTH,
                VeryFitProtocol.healthRequest(VeryFitConstants.HEALTH_FETCH, TYPES[index],
                        TODAY[index] != 0));
    }

    private void finish() {
        running = false;
        support.onHealthSyncFinished();
    }

    /**
     * The header is the request echoed back, the number of detail records, and the sizes of the
     * two blocks that follow it.
     */
    private void handle(final byte[] payload) {
        final int type = payload[1] & 0xff;
        final int headSize = u16(payload, 7);
        final int dataSize = u16(payload, 9) | (u16(payload, 11) << 16);
        final int headOffset = VeryFitConstants.HEALTH_HEADER_LEN;
        final int dataOffset = headOffset + headSize;

        if (dataOffset + dataSize > payload.length) {
            LOG.warn("Health type 0x{} is short of its {} bytes", Integer.toHexString(type), dataSize);
            return;
        }
        if (dataSize == 0) {
            LOG.debug("Health type 0x{} is empty", Integer.toHexString(type));
            return;
        }

        final byte[] head = new byte[headSize];
        System.arraycopy(payload, headOffset, head, 0, headSize);
        final byte[] detail = new byte[dataSize];
        System.arraycopy(payload, dataOffset, detail, 0, dataSize);

        switch (type) {
            case VeryFitConstants.HEALTH_STEPS:
                storeSteps(head, detail);
                break;
            case VeryFitConstants.HEALTH_SLEEP:
                storeSleep(head, detail);
                break;
            case VeryFitConstants.HEALTH_HEART_RATE:
                storeHeartRate(head, detail);
                break;
            case VeryFitConstants.HEALTH_SPO2:
                storeSpo2(head, detail);
                break;
            case VeryFitConstants.HEALTH_STRESS:
                storeStress(head, detail);
                break;
            case VeryFitConstants.HEALTH_HRV:
                storeHrv(head, detail);
                break;
            case VeryFitConstants.HEALTH_WORKOUT:
                storeWorkout(head);
                break;
            case VeryFitConstants.HEALTH_GPS:
                storeTrack(detail);
                break;
            default:
                LOG.debug("Unhandled health type 0x{}", Integer.toHexString(type));
        }
    }

    /**
     * One record per slot of the day, each holding what was walked in it rather than a running
     * total. The summary says when the day starts and how long a slot is.
     */
    private void storeSteps(final byte[] head, final byte[] detail) {
        final long start = dayStart(head, 1);
        final int interval = head[7] & 0xff;
        if (interval == 0) {
            return;
        }

        final List<VeryFitStepsSample> samples = new ArrayList<>();
        for (int offset = 0, slot = 0; offset + 10 <= detail.length; offset += 10, slot++) {
            final int steps = u16(detail, offset + 1);
            final int distance = u16(detail, offset + 6);
            final int calories = u16(detail, offset + 8);
            if (steps == 0 && distance == 0 && calories == 0) {
                continue;
            }

            final VeryFitStepsSample sample = new VeryFitStepsSample();
            sample.setTimestamp(start + slot * interval * 60_000L);
            sample.setSteps(steps);
            sample.setDistance(distance);
            sample.setCalories(calories);
            sample.setActiveMinutes(detail[offset + 3] & 0xff);
            samples.add(sample);
        }

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);
            for (final VeryFitStepsSample sample : samples) {
                sample.setDevice(device);
                sample.setUser(user);
            }
            LOG.debug("Persisting {} step samples", samples.size());
            new VeryFitStepsSampleProvider(support.getDevice(), session).addSamples(samples);
        } catch (final Exception e) {
            LOG.error("Failed to persist step samples", e);
        }
    }

    /** A night is a start and an end timestamp, then one record per stretch of a single stage. */
    private void storeSleep(final byte[] head, final byte[] detail) {
        long timestamp = timestamp(head, 2);

        final List<GenericSleepStageSample> samples = new ArrayList<>();
        for (int offset = 0; offset + 2 <= detail.length; offset += 2) {
            final int minutes = detail[offset + 1] & 0xff;
            final GenericSleepStageSample sample = new GenericSleepStageSample();
            sample.setTimestamp(timestamp);
            sample.setDuration(minutes);
            sample.setStage(detail[offset] & 0xff);
            samples.add(sample);
            timestamp += minutes * 60_000L;
        }

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);
            for (final GenericSleepStageSample sample : samples) {
                sample.setDevice(device);
                sample.setUser(user);
            }
            LOG.debug("Persisting {} sleep stage samples", samples.size());
            new GenericSleepStageSampleProvider(support.getDevice(), session).addSamples(samples);
        } catch (final Exception e) {
            LOG.error("Failed to persist sleep samples", e);
        }
    }

    /** Readings a few seconds apart, so the series starts at a second of its own. */
    private void storeHeartRate(final byte[] head, final byte[] detail) {
        final List<GenericHeartRateSample> samples = new ArrayList<>();
        long timestamp = dayStart(head, 0) + u24(head, 4) * 1000L;

        for (int offset = 0; offset + 2 <= detail.length; offset += 2) {
            final int step = detail[offset] & 0xff;
            timestamp += step * 1000L;
            if (step == VeryFitConstants.HEALTH_NO_VALUE) {
                continue;
            }

            final GenericHeartRateSample sample = new GenericHeartRateSample();
            sample.setTimestamp(timestamp);
            sample.setHeartRate(detail[offset + 1] & 0xff);
            samples.add(sample);
        }

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);
            for (final GenericHeartRateSample sample : samples) {
                sample.setDevice(device);
                sample.setUser(user);
            }
            LOG.debug("Persisting {} heart rate samples", samples.size());
            new GenericHeartRateSampleProvider(support.getDevice(), session).addSamples(samples);
        } catch (final Exception e) {
            LOG.error("Failed to persist heart rate samples", e);
        }
    }

    private void storeSpo2(final byte[] head, final byte[] detail) {
        final List<GenericSpo2Sample> samples = new ArrayList<>();
        long timestamp = seriesStart(head);

        for (int offset = 0; offset + 2 <= detail.length; offset += 2) {
            final int step = detail[offset] & 0xff;
            timestamp += step * 60_000L;
            if (step == VeryFitConstants.HEALTH_NO_VALUE) {
                continue;
            }

            final GenericSpo2Sample sample = new GenericSpo2Sample();
            sample.setTimestamp(timestamp);
            sample.setSpo2(detail[offset + 1] & 0xff);
            samples.add(sample);
        }

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);
            for (final GenericSpo2Sample sample : samples) {
                sample.setDevice(device);
                sample.setUser(user);
            }
            LOG.debug("Persisting {} spo2 samples", samples.size());
            new GenericSpo2SampleProvider(support.getDevice(), session).addSamples(samples);
        } catch (final Exception e) {
            LOG.error("Failed to persist spo2 samples", e);
        }
    }

    private void storeStress(final byte[] head, final byte[] detail) {
        final List<GenericStressSample> samples = new ArrayList<>();
        long timestamp = seriesStart(head);

        for (int offset = 0; offset + 2 <= detail.length; offset += 2) {
            final int step = detail[offset] & 0xff;
            timestamp += step * 60_000L;
            if (step == VeryFitConstants.HEALTH_NO_VALUE) {
                continue;
            }

            final GenericStressSample sample = new GenericStressSample();
            sample.setTimestamp(timestamp);
            sample.setStress(detail[offset + 1] & 0xff);
            samples.add(sample);
        }

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);
            for (final GenericStressSample sample : samples) {
                sample.setDevice(device);
                sample.setUser(user);
            }
            LOG.debug("Persisting {} stress samples", samples.size());
            new GenericStressSampleProvider(support.getDevice(), session).addSamples(samples);
        } catch (final Exception e) {
            LOG.error("Failed to persist stress samples", e);
        }
    }

    private void storeHrv(final byte[] head, final byte[] detail) {
        final List<GenericHrvValueSample> samples = new ArrayList<>();
        long timestamp = seriesStart(head);

        for (int offset = 0; offset + 2 <= detail.length; offset += 2) {
            final int step = detail[offset] & 0xff;
            timestamp += step * 60_000L;
            if (step == VeryFitConstants.HEALTH_NO_VALUE) {
                continue;
            }

            final GenericHrvValueSample sample = new GenericHrvValueSample();
            sample.setTimestamp(timestamp);
            sample.setValue(detail[offset + 1] & 0xff);
            samples.add(sample);
        }

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);
            for (final GenericHrvValueSample sample : samples) {
                sample.setDevice(device);
                sample.setUser(user);
            }
            LOG.debug("Persisting {} hrv samples", samples.size());
            new GenericHrvValueSampleProvider(support.getDevice(), session).addSamples(samples);
        } catch (final Exception e) {
            LOG.error("Failed to persist hrv samples", e);
        }
    }

    /**
     * The summary block is the workout itself; what follows it are the sample series it was
     * recorded with. Its fields are pulled out again by the parser when the workout is opened.
     */
    private void storeWorkout(final byte[] head) {
        if (head.length < VeryFitConstants.WORKOUT_SUMMARY_LEN) {
            return;
        }

        BaseActivitySummary summary = new BaseActivitySummary();
        summary.setRawSummaryData(head);
        summary.setActivityKind(ActivityKind.UNKNOWN.getCode());
        summary = new VeryFitWorkoutSummaryParser().parseBinaryData(summary, true);
        summary.setSummaryData(null);

        workoutStart = summary.getStartTime().getTime();
        workoutEnd = summary.getEndTime().getTime();

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            summary.setDevice(DBHelper.getDevice(support.getDevice(), session));
            summary.setUser(DBHelper.getUser(session));
            LOG.debug("Persisting workout from {}", summary.getStartTime());
            session.getBaseActivitySummaryDao().insertOrReplace(summary);
        } catch (final Exception e) {
            LOG.error("Failed to persist the workout", e);
        }
    }

    /**
     * Blocks of a full position followed by the fixes around it, each an offset from that position
     * in ten-thousandths of an arc minute rather than from the fix before it. The records carry no
     * time of their own, so they are spread across the workout they belong to.
     */
    private void storeTrack(final byte[] detail) {
        if (workoutEnd <= workoutStart) {
            LOG.debug("Skipping a track with no workout to attach it to");
            return;
        }

        final List<VeryFitWorkoutGpsSample> samples = new ArrayList<>();
        double blockLongitude = 0;
        double blockLatitude = 0;

        for (int offset = 0; offset < detail.length; ) {
            if (detail[offset] == VeryFitConstants.GPS_BLOCK) {
                if (offset + VeryFitConstants.GPS_BLOCK_LEN > detail.length) {
                    break;
                }
                blockLongitude = degrees(u16(detail, offset + 1), u16(detail, offset + 3));
                blockLatitude = degrees(u16(detail, offset + 5), u16(detail, offset + 7));
                offset += VeryFitConstants.GPS_BLOCK_LEN;
                continue;
            }
            if (offset + VeryFitConstants.GPS_FIX_LEN > detail.length) {
                break;
            }

            final int flags = detail[offset] & 0xff;
            final double longitude = blockLongitude
                    + step(u16(detail, offset + 1), (flags & VeryFitConstants.GPS_LONGITUDE_UP) != 0);
            final double latitude = blockLatitude
                    + step(u16(detail, offset + 3), (flags & VeryFitConstants.GPS_LATITUDE_UP) != 0);
            offset += VeryFitConstants.GPS_FIX_LEN;

            // A block with no fix reads as zero degrees, and every sample under it stays there.
            if (blockLongitude == 0 && blockLatitude == 0) {
                continue;
            }

            final VeryFitWorkoutGpsSample sample = new VeryFitWorkoutGpsSample();
            sample.setLongitude((int) Math.round(longitude * 10000000d));
            sample.setLatitude((int) Math.round(latitude * 10000000d));
            samples.add(sample);
        }

        for (int i = 0; i < samples.size(); i++) {
            samples.get(i).setTimestamp(workoutStart
                    + (workoutEnd - workoutStart) * i / Math.max(1, samples.size() - 1));
        }

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);
            for (final VeryFitWorkoutGpsSample sample : samples) {
                sample.setDevice(device);
                sample.setUser(user);
            }
            LOG.debug("Persisting {} track points", samples.size());
            new VeryFitWorkoutGpsSampleProvider(support.getDevice(), session).addSamples(samples);
        } catch (final Exception e) {
            LOG.error("Failed to persist the track", e);
        }
    }

    /** Degrees and whole minutes in the low bits, the hemisphere in the top one. */
    private static double degrees(final int value, final int fraction) {
        final int minutes = value & ~VeryFitConstants.GPS_SIGN_BIT;
        final double degrees = minutes / 100
                + (minutes % 100 + fraction / (double) VeryFitConstants.GPS_MINUTE_FRACTION) / 60d;
        return (value & VeryFitConstants.GPS_SIGN_BIT) != 0 ? degrees : -degrees;
    }

    private static double step(final int value, final boolean forward) {
        final double minutes = value / (double) VeryFitConstants.GPS_MINUTE_FRACTION / 60d;
        return forward ? minutes : -minutes;
    }

    /** Types measured a few times an hour count their start in minutes from midnight. */
    private static long seriesStart(final byte[] head) {
        return dayStart(head, 0) + u16(head, 4) * 60_000L;
    }

    private static long dayStart(final byte[] head, final int offset) {
        final Calendar calendar = GregorianCalendar.getInstance();
        calendar.clear();
        calendar.set(u16(head, offset), (head[offset + 2] & 0xff) - 1, head[offset + 3] & 0xff);
        return calendar.getTimeInMillis();
    }

    private static long timestamp(final byte[] head, final int offset) {
        final Calendar calendar = GregorianCalendar.getInstance();
        calendar.clear();
        calendar.set(u16(head, offset), (head[offset + 2] & 0xff) - 1, head[offset + 3] & 0xff,
                head[offset + 4] & 0xff, head[offset + 5] & 0xff);
        return calendar.getTimeInMillis();
    }

    private static int u16(final byte[] data, final int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int u24(final byte[] data, final int offset) {
        return u16(data, offset) | ((data[offset + 2] & 0xff) << 16);
    }
}
