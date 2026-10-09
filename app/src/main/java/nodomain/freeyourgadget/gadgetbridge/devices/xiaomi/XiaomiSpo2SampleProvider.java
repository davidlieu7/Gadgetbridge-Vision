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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleToTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiManualSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;

public class XiaomiSpo2SampleProvider extends AbstractSampleToTimeSampleProvider<Spo2Sample, XiaomiActivitySample> {
    private final XiaomiManualSampleProvider manualSampleProvider;
    // Overnight SpO2 from the sleep report
    private final GenericSpo2SampleProvider sleepSampleProvider;

    public XiaomiSpo2SampleProvider(final GBDevice device, final DaoSession session) {
        super(new XiaomiSampleProvider(device, session), device, session);
        manualSampleProvider = new XiaomiManualSampleProvider(device, session);
        sleepSampleProvider = new GenericSpo2SampleProvider(device, session);
    }

    @NonNull
    @Override
    public List<Spo2Sample> getAllSamples(final long timestampFrom, final long timestampTo) {
        final List<Spo2Sample> samples = new ArrayList<>(super.getAllSamples(timestampFrom, timestampTo));

        // The per-minute activity samples win where both have a value for the same time
        final Set<Long> activityTimestamps = new HashSet<>();
        for (final Spo2Sample sample : samples) {
            activityTimestamps.add(sample.getTimestamp());
        }
        for (final GenericSpo2Sample sleepSample : sleepSampleProvider.getAllSamples(timestampFrom, timestampTo)) {
            if (!activityTimestamps.contains(sleepSample.getTimestamp())) {
                samples.add(convertSleepSample(sleepSample));
            }
        }

        for (final XiaomiManualSample manualSample : manualSampleProvider.getAllSamples(timestampFrom, timestampTo)) {
            final Spo2Sample converted = convertManualSample(manualSample);
            if (converted != null) {
                samples.add(converted);
            }
        }

        samples.sort(Comparator.comparingLong(Spo2Sample::getTimestamp));
        return samples;
    }

    @Nullable
    @Override
    protected Spo2Sample convertSample(final XiaomiActivitySample sample) {
        if (sample == null || sample.getSpo2() == null || sample.getSpo2() == 0) {
            return null;
        }

        return new XiaomiSpo2Sample(
                sample.getTimestamp() * 1000L,
                sample.getSpo2(),
                Spo2Sample.Type.UNKNOWN
        );
    }

    @Nullable
    private static Spo2Sample convertSleepSample(@Nullable final GenericSpo2Sample sample) {
        if (sample == null) {
            return null;
        }

        return new XiaomiSpo2Sample(
                sample.getTimestamp(),
                sample.getSpo2(),
                Spo2Sample.Type.AUTOMATIC
        );
    }

    @Nullable
    private Spo2Sample convertManualSample(final XiaomiManualSample sample) {
        if (sample == null || sample.getType() != XiaomiManualSampleProvider.TYPE_SPO2 || sample.getValue() <= 0) {
            return null;
        }

        return new XiaomiSpo2Sample(
                sample.getTimestamp(),
                sample.getValue(),
                Spo2Sample.Type.MANUAL
        );
    }

    @Nullable
    @Override
    public Spo2Sample getLatestSample() {
        return latest(
                latest(super.getLatestSample(), convertSleepSample(sleepSampleProvider.getLatestSample())),
                convertManualSample(manualSampleProvider.getLatestSample(XiaomiManualSampleProvider.TYPE_SPO2))
        );
    }

    @Nullable
    @Override
    public Spo2Sample getLatestSample(final long until) {
        return latest(
                latest(super.getLatestSample(until), convertSleepSample(sleepSampleProvider.getLatestSample(until))),
                convertManualSample(manualSampleProvider.getLatestSample(XiaomiManualSampleProvider.TYPE_SPO2, until))
        );
    }

    @Nullable
    @Override
    public Spo2Sample getFirstSample() {
        return earliest(
                earliest(super.getFirstSample(), convertSleepSample(sleepSampleProvider.getFirstSample())),
                convertManualSample(manualSampleProvider.getFirstSample(XiaomiManualSampleProvider.TYPE_SPO2))
        );
    }

    @Nullable
    private static Spo2Sample latest(@Nullable final Spo2Sample first, @Nullable final Spo2Sample second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.getTimestamp() >= second.getTimestamp() ? first : second;
    }

    @Nullable
    private static Spo2Sample earliest(@Nullable final Spo2Sample first, @Nullable final Spo2Sample second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.getTimestamp() <= second.getTimestamp() ? first : second;
    }

    @NonNull
    @Override
    protected Property getFilterColumn() {
        return XiaomiActivitySampleDao.Properties.Spo2;
    }

    protected static class XiaomiSpo2Sample implements Spo2Sample {
        private final long timestamp;
        private final int spo2;
        private final Type type;

        public XiaomiSpo2Sample(final long timestamp, final int spo2, final Type type) {
            this.timestamp = timestamp;
            this.spo2 = spo2;
            this.type = type;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public Type getType() {
            return type;
        }

        @Override
        public int getSpo2() {
            return spo2;
        }
    }
}
