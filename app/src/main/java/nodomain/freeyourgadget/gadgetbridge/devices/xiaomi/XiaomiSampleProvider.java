/*  Copyright (C) 2023-2024 José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.devices.xiaomi;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericDurationSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.XiaomiSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.XiaomiSleepTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericDurationSample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepTimeSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.util.RangeMap;

public class XiaomiSampleProvider extends AbstractSampleProvider<XiaomiActivitySample> {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiSampleProvider.class);

    /** {@link GenericDurationSample} type of a span a Sleep as Android synthetic workout covered. */
    public static final int DURATION_TYPE_SLEEP_AS_ANDROID = 1;

    public XiaomiSampleProvider(final GBDevice device, final DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<XiaomiActivitySample, ?> getSampleDao() {
        return getSession().getXiaomiActivitySampleDao();
    }

    @Nullable
    @Override
    protected Property getRawKindSampleProperty() {
        return XiaomiActivitySampleDao.Properties.RawKind;
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return XiaomiActivitySampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return XiaomiActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public ActivityKind normalizeType(final int rawType) {
        // TODO
        return ActivityKind.fromCode(rawType);
    }

    @Override
    public int toRawActivityKind(final ActivityKind activityKind) {
        // TODO
        return activityKind.getCode();
    }

    @Override
    public float normalizeIntensity(final int rawIntensity) {
        return rawIntensity / 100f;
    }

    @Override
    public XiaomiActivitySample createActivitySample() {
        return new XiaomiActivitySample();
    }

    @Override
    protected List<XiaomiActivitySample> getGBActivitySamples(final int timestamp_from, final int timestamp_to) {
        final List<XiaomiActivitySample> samples = super.getGBActivitySamples(timestamp_from, timestamp_to);

        convertCalories(samples);
        dropSleepAsAndroidCalories(samples, timestamp_from, timestamp_to);
        overlaySleep(samples, timestamp_from, timestamp_to);

        return samples;
    }

    private void convertCalories(final List<XiaomiActivitySample> samples) {
        for (XiaomiActivitySample sample : samples) {
            sample.setActiveCalories(sample.getActiveCalories() * 1000);
        }
    }

    /**
     * Sleep as Android needs the band's raw accelerometer stream, which only runs while a workout
     * is open, so Gadgetbridge opens a synthetic one for the night. The band bills those minutes as
     * exercise at a rate its sport type fixes, regardless of heart rate or movement, and the
     * per-minute figures it later hands over carry that bill. Nobody burnt them, so they read as
     * zero.
     */
    private void dropSleepAsAndroidCalories(final List<XiaomiActivitySample> samples,
                                            final int timestamp_from,
                                            final int timestamp_to) {
        final List<GenericDurationSample> windows = new GenericDurationSampleProvider(getDevice(), getSession())
                .getSamplesOverlapping(DURATION_TYPE_SLEEP_AS_ANDROID, timestamp_from * 1000L, timestamp_to * 1000L);
        if (windows.isEmpty()) {
            return;
        }

        final RangeMap<Long, Boolean> synthetic = new RangeMap<>(RangeMap.Mode.LOWER_BOUND);
        for (final GenericDurationSample window : windows) {
            synthetic.put(window.getTimestamp(), Boolean.TRUE);
            synthetic.put(window.getEndTimestamp() + 1, Boolean.FALSE);
        }

        // A window can outlast the sleep it covered: a session the band never closed is only closed
        // on the next connection, which can be hours later. A workout the wearer really did in the
        // meantime keeps the calories it earned.
        final RangeMap<Long, Boolean> workouts = new RangeMap<>(RangeMap.Mode.LOWER_BOUND);
        for (final BaseActivitySummary workout : workoutsOverlapping(timestamp_from, timestamp_to)) {
            workouts.put(workout.getStartTime().getTime(), Boolean.TRUE);
            workouts.put(workout.getEndTime().getTime() + 1, Boolean.FALSE);
        }

        for (final XiaomiActivitySample sample : samples) {
            final long timestamp = sample.getTimestamp() * 1000L;
            if (Boolean.TRUE.equals(synthetic.get(timestamp))
                    && !Boolean.TRUE.equals(workouts.get(timestamp))) {
                sample.setActiveCalories(0);
            }
        }
    }

    private List<BaseActivitySummary> workoutsOverlapping(final int timestamp_from, final int timestamp_to) {
        final Device device = DBHelper.findDevice(getDevice(), getSession());
        if (device == null) {
            return Collections.emptyList();
        }

        return getSession().getBaseActivitySummaryDao().queryBuilder()
                .where(
                        BaseActivitySummaryDao.Properties.DeviceId.eq(device.getId()),
                        BaseActivitySummaryDao.Properties.StartTime.le(new Date(timestamp_to * 1000L)),
                        BaseActivitySummaryDao.Properties.EndTime.ge(new Date(timestamp_from * 1000L))
                )
                .build().list();
    }

    /**
     * See {@link nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.SleepDetailsParser}
     */
    private static ActivityKind getActivityKindForSample(final XiaomiSleepStageSample sample) {
        return switch (sample.getStage()) {
            case 2 -> ActivityKind.DEEP_SLEEP;
            case 3 -> ActivityKind.LIGHT_SLEEP;
            case 4 -> ActivityKind.REM_SLEEP;
            case 5 -> ActivityKind.AWAKE_SLEEP;
            default -> ActivityKind.UNKNOWN;
        };
    }

    /**
     * Overlay sleep states on activity samples, since they are stored on a separate table.
     *
     * @implNote In order to determine whether a sleep session was ongoing at the start of the
     * given range and what the detected sleep stage was at that time, the last sleep stage and
     * sleep time sample before the given range will be queried and included in the results if
     * found.
     */
    public void overlaySleep(final List<XiaomiActivitySample> samples, final int timestamp_from, final int timestamp_to) {
        final RangeMap<Long, ActivityKind> stagesMap = new RangeMap<>(RangeMap.Mode.LOWER_BOUND);

        final XiaomiSleepTimeSampleProvider sleepTimeSampleProvider = new XiaomiSleepTimeSampleProvider(getDevice(), getSession());
        final XiaomiSleepStageSampleProvider sleepStagesSampleProvider = new XiaomiSleepStageSampleProvider(getDevice(), getSession());

        // First populate all samples within this range
        final List<XiaomiSleepTimeSample> sleepTimesWithinRange = sleepTimeSampleProvider.getAllSamples(timestamp_from * 1000L, timestamp_to * 1000L);
        LOG.trace("Found {} sleep samples between {} and {}", sleepTimesWithinRange.size(), timestamp_from, timestamp_to);

        for (final XiaomiSleepTimeSample sleepTimeSample : sleepTimesWithinRange) {
            stagesMap.put(sleepTimeSample.getWakeupTime(), ActivityKind.UNKNOWN);
            stagesMap.put(sleepTimeSample.getTimestamp(), ActivityKind.LIGHT_SLEEP);
        }

        // Retrieve the last stage before this time range, as the user could have been asleep during
        // the range transition
        final XiaomiSleepStageSample lastSleepStageBeforeRange = sleepStagesSampleProvider.getLastSampleBefore(timestamp_from * 1000L);

        if (lastSleepStageBeforeRange != null) {
            LOG.debug("Last sleep stage before range: ts={}, stage={}", lastSleepStageBeforeRange.getTimestamp(), lastSleepStageBeforeRange.getStage());
            stagesMap.put(lastSleepStageBeforeRange.getTimestamp(), getActivityKindForSample(lastSleepStageBeforeRange));
        }

        // Retrieve all sleep stage samples during the range
        final List<XiaomiSleepStageSample> sleepStagesInRange = sleepStagesSampleProvider.getAllSamples(
                timestamp_from * 1000L,
                timestamp_to * 1000L
        );

        if (!sleepStagesInRange.isEmpty()) {
            // We got actual sleep stages
            LOG.debug("Found {} sleep stage samples between {} and {}", sleepStagesInRange.size(), timestamp_from, timestamp_to);

            for (final XiaomiSleepStageSample stageSample : sleepStagesInRange) {
                stagesMap.put(stageSample.getTimestamp(), getActivityKindForSample(stageSample));
            }
        }


        // Find last sleep sample before the requested range, as the recorded wake up time may be
        // in the current range
        final XiaomiSleepTimeSample lastSleepTimesBeforeRange = sleepTimeSampleProvider.getLastSampleBefore(timestamp_from * 1000L);

        if (lastSleepTimesBeforeRange != null) {
            LOG.debug("Last sleep time before range: ts={}, stage={}", lastSleepTimesBeforeRange.getTimestamp(), lastSleepTimesBeforeRange);

            stagesMap.put(lastSleepTimesBeforeRange.getWakeupTime(), ActivityKind.UNKNOWN);
            stagesMap.put(lastSleepTimesBeforeRange.getTimestamp(), ActivityKind.LIGHT_SLEEP);
        }

        // Find all wake up and sleep samples in the current time range
        final List<XiaomiSleepTimeSample> sleepTimesInRange = sleepTimeSampleProvider.getAllSamples(
                timestamp_from * 1000L,
                timestamp_to * 1000L
        );

        if (!sleepTimesInRange.isEmpty()) {
            LOG.debug("Found {} sleep samples between {} and {}", sleepTimesInRange.size(), timestamp_from, timestamp_to);
            for (final XiaomiSleepTimeSample stageSample : sleepTimesInRange) {
                if (sleepStagesInRange.isEmpty()) {
                    // Only overlay them as light sleep if we don't have actual sleep stages
                    stagesMap.put(stageSample.getTimestamp(), ActivityKind.LIGHT_SLEEP);
                }

                // We need to set the wakeup times, because some bands don't report them in the stage samples (see #3502)
                stagesMap.put(stageSample.getWakeupTime(), ActivityKind.UNKNOWN);
            }
        }

        if (!stagesMap.isEmpty()) {
            LOG.debug("Found {} sleep stage samples between {} and {}", stagesMap.size(), timestamp_from, timestamp_to);
            // FIXME if no samples were retrieved from the database that were generated by other
            //       activity files, the stages will not get overlayed/inserted and the sleep charts
            //       will stay empty.

            for (final XiaomiActivitySample sample : samples) {
                final long ts = sample.getTimestamp() * 1000L;
                final ActivityKind sleepType = stagesMap.get(ts);
                if (sleepType != null && !sleepType.equals(ActivityKind.UNKNOWN)) {
                    sample.setRawKind(sleepType.getCode());
                    sample.setRawIntensity(ActivitySample.NOT_MEASURED);
                }
            }
        }
    }
}
