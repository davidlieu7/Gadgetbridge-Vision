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
package nodomain.freeyourgadget.gadgetbridge.devices.veryfit;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.VeryFitStepsSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeryFitStepsSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

/**
 * The watch keeps its day in three separate series, so the samples the charts want are put back
 * together here rather than stored a second time: a step record per slot, heart rate whenever it
 * was measured, and the stretches of sleep laid over both.
 */
public class VeryFitActivitySampleProvider implements SampleProvider<GenericActivitySample> {
    private final GBDevice device;
    private final DaoSession session;

    public VeryFitActivitySampleProvider(final GBDevice device, final DaoSession session) {
        this.device = device;
        this.session = session;
    }

    @Override
    public ActivityKind normalizeType(final int rawType) {
        return ActivityKind.fromCode(rawType);
    }

    @Override
    public int toRawActivityKind(final ActivityKind activityKind) {
        return activityKind.getCode();
    }

    @Override
    public float normalizeIntensity(final int rawIntensity) {
        return rawIntensity;
    }

    @Override
    public List<GenericActivitySample> getAllActivitySamples(final int timestampFrom, final int timestampTo) {
        final Map<Integer, GenericActivitySample> byTimestamp = new HashMap<>();

        final List<VeryFitStepsSample> steps = new VeryFitStepsSampleProvider(device, session)
                .getAllSamples(timestampFrom * 1000L, timestampTo * 1000L);
        for (final VeryFitStepsSample step : steps) {
            final GenericActivitySample sample = sampleAt(byTimestamp, (int) (step.getTimestamp() / 1000L));
            sample.setSteps(step.getSteps());
            sample.setDistanceCm(step.getDistance() * 100);
            sample.setActiveCalories(step.getCalories());
            sample.setRawKind(ActivityKind.ACTIVITY.getCode());
            sample.setRawIntensity(step.getActiveMinutes());
        }

        final List<GenericHeartRateSample> heartRates = new GenericHeartRateSampleProvider(device, session)
                .getAllSamples(timestampFrom * 1000L, timestampTo * 1000L);
        for (final GenericHeartRateSample heartRate : heartRates) {
            final int timestamp = (int) (heartRate.getTimestamp() / 60000L) * 60;
            sampleAt(byTimestamp, timestamp).setHeartRate(heartRate.getHeartRate());
        }

        overlaySleep(byTimestamp, timestampFrom, timestampTo);

        final List<GenericActivitySample> samples = new ArrayList<>(byTimestamp.values());
        samples.sort(Comparator.comparingInt(GenericActivitySample::getTimestamp));
        return samples;
    }

    /** A stage covers every minute it lasts, so the samples underneath it all become sleep. */
    private void overlaySleep(final Map<Integer, GenericActivitySample> byTimestamp,
                              final int timestampFrom, final int timestampTo) {
        final List<GenericSleepStageSample> stages = new GenericSleepStageSampleProvider(device, session)
                .getAllSamples(timestampFrom * 1000L, timestampTo * 1000L);

        for (final GenericSleepStageSample stage : stages) {
            final ActivityKind kind = sleepKind(stage.getStage());
            if (kind == ActivityKind.UNKNOWN) {
                continue;
            }

            final int start = (int) (stage.getTimestamp() / 60000L) * 60;
            for (int timestamp = start; timestamp < start + stage.getDuration() * 60; timestamp += 60) {
                final GenericActivitySample sample = sampleAt(byTimestamp, timestamp);
                sample.setRawKind(kind.getCode());
                sample.setRawIntensity(ActivitySample.NOT_MEASURED);
            }
        }
    }

    private GenericActivitySample sampleAt(final Map<Integer, GenericActivitySample> byTimestamp,
                                           final int timestamp) {
        return byTimestamp.computeIfAbsent(timestamp, key -> {
            final GenericActivitySample sample = new GenericActivitySample();
            sample.setProvider(this);
            sample.setTimestamp(key);
            return sample;
        });
    }

    private ActivityKind sleepKind(final int stage) {
        switch (stage) {
            case VeryFitConstants.SLEEP_LIGHT:
                return ActivityKind.LIGHT_SLEEP;
            case VeryFitConstants.SLEEP_DEEP:
                return ActivityKind.DEEP_SLEEP;
            case VeryFitConstants.SLEEP_REM:
                return ActivityKind.REM_SLEEP;
            case VeryFitConstants.SLEEP_AWAKE:
                return ActivityKind.AWAKE_SLEEP;
            default:
                return ActivityKind.UNKNOWN;
        }
    }

    @Override
    public List<GenericActivitySample> getAllActivitySamplesHighRes(final int timestampFrom, final int timestampTo) {
        return getAllActivitySamples(timestampFrom, timestampTo);
    }

    @Override
    public boolean hasHighResData() {
        return false;
    }

    @Override
    public List<GenericActivitySample> getActivitySamples(final int timestampFrom, final int timestampTo) {
        final List<GenericActivitySample> samples = new ArrayList<>();
        for (final GenericActivitySample sample : getAllActivitySamples(timestampFrom, timestampTo)) {
            if (sample.getKind() == ActivityKind.ACTIVITY) {
                samples.add(sample);
            }
        }
        return samples;
    }

    @Override
    public void addGBActivitySample(final GenericActivitySample activitySample) {
        throw new UnsupportedOperationException("The samples are assembled from the stored series");
    }

    @Override
    public void addGBActivitySamples(@NonNull final List<GenericActivitySample> activitySamples) {
        throw new UnsupportedOperationException("The samples are assembled from the stored series");
    }

    @Override
    public GenericActivitySample createActivitySample() {
        return new GenericActivitySample();
    }

    @Override
    public GenericActivitySample getLatestActivitySample() {
        return getLatestActivitySample((int) (System.currentTimeMillis() / 1000L));
    }

    @Override
    public GenericActivitySample getLatestActivitySample(final int until) {
        final VeryFitStepsSample step = new VeryFitStepsSampleProvider(device, session).getLatestSample();
        if (step == null || step.getTimestamp() / 1000L > until) {
            return null;
        }
        final List<GenericActivitySample> samples =
                getAllActivitySamples((int) (step.getTimestamp() / 1000L), until);
        return samples.isEmpty() ? null : samples.get(samples.size() - 1);
    }

    @Override
    public GenericActivitySample getFirstActivitySample(final int after) {
        final List<GenericActivitySample> samples =
                getAllActivitySamples(after, (int) (System.currentTimeMillis() / 1000L));
        return samples.isEmpty() ? null : samples.get(0);
    }

    @Override
    public GenericActivitySample getFirstActivitySample() {
        final VeryFitStepsSample step = new VeryFitStepsSampleProvider(device, session).getFirstSample();
        if (step == null) {
            return null;
        }
        final List<GenericActivitySample> samples = getAllActivitySamples(
                (int) (step.getTimestamp() / 1000L), (int) (System.currentTimeMillis() / 1000L));
        return samples.isEmpty() ? null : samples.get(0);
    }
}
