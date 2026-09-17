/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 or later;
    see the LICENSE file for details.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.veepoo;

import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_RAW_STRING;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.DefaultChartsProvider;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummarySimpleEntry;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooSleepSummarySample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooSleepSummarySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.RespiratoryRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.util.Accumulator;

/**
 * Custom chart data for the Veepoo KR96 Pro: appends per-night sleep vitals (quality, wake
 * count, heart rate, SpO2, temperature, respiration and apnea aggregates) to the daily
 * sleep stats grid.
 */
public class VeepooChartsProvider extends DefaultChartsProvider {

    @NonNull
    @Override
    public Map<String, ActivitySummarySimpleEntry> getDailySleepStats(
            final Context context, final DBHandler db, final GBDevice device,
            final int tsStart, final int tsEnd) {
        final Map<String, ActivitySummarySimpleEntry> stats = new LinkedHashMap<>();
        final DaoSession session = db.getDaoSession();
        final Device dbDevice = DBHelper.findDevice(device, session);
        if (dbDevice == null) {
            return stats;
        }

        final long windowStartMs = tsStart * 1000L;
        final long windowEndMs = tsEnd * 1000L;

        // Anchor on the per-night summary; without one there is nothing to display.
        final List<VeepooSleepSummarySample> summaries = session.getVeepooSleepSummarySampleDao().queryBuilder()
                .where(VeepooSleepSummarySampleDao.Properties.DeviceId.eq(dbDevice.getId()),
                        VeepooSleepSummarySampleDao.Properties.Timestamp.ge(windowStartMs),
                        VeepooSleepSummarySampleDao.Properties.Timestamp.le(windowEndMs))
                .orderDesc(VeepooSleepSummarySampleDao.Properties.Timestamp)
                .build()
                .list();
        if (summaries.isEmpty()) {
            return stats;
        }
        final VeepooSleepSummarySample summary = summaries.get(0);

        // Narrow to the recorded night, clamped to the requested range.
        final long startMs = Math.max(windowStartMs, summary.getTimestamp());
        final long endMs = Math.min(windowEndMs, summary.getTimestamp() + (long) summary.getDurationMin() * 60_000L);
        if (endMs <= startMs) {
            return stats;
        }
        final int startSec = (int) (startMs / 1000L);
        final int endSec = (int) (endMs / 1000L);

        // Heart rate plus apnea counters from the per-minute activity rows (second timestamps).
        final HeartRateUtils heartRateUtils = HeartRateUtils.getInstance();
        final Accumulator heartRate = new Accumulator();
        long apneaEvents = 0;
        long hypoxiaMinutes = 0;
        long cardiacLoadMinutes = 0;
        final List<VeepooActivitySample> activitySamples = session.getVeepooActivitySampleDao().queryBuilder()
                .where(VeepooActivitySampleDao.Properties.DeviceId.eq(dbDevice.getId()),
                        VeepooActivitySampleDao.Properties.Timestamp.ge(startSec),
                        VeepooActivitySampleDao.Properties.Timestamp.le(endSec))
                .build()
                .list();
        for (final VeepooActivitySample sample : activitySamples) {
            if (heartRateUtils.isValidHeartRateValue(sample.getHeartRate())) {
                heartRate.add(sample.getHeartRate());
            }
            if (sample.getApneaCount() != null) {
                apneaEvents += sample.getApneaCount();
            }
            if (sample.getHypoxiaMinutes() != null) {
                hypoxiaMinutes += sample.getHypoxiaMinutes();
            }
            if (sample.getCardiacLoadMinutes() != null) {
                cardiacLoadMinutes += sample.getCardiacLoadMinutes();
            }
        }

        // Merge standalone heart-rate samples (autonomous overnight pushes, manual reads).
        final List<GenericHeartRateSample> hrSamples = session.getGenericHeartRateSampleDao().queryBuilder()
                .where(GenericHeartRateSampleDao.Properties.DeviceId.eq(dbDevice.getId()),
                        GenericHeartRateSampleDao.Properties.Timestamp.ge(startMs),
                        GenericHeartRateSampleDao.Properties.Timestamp.le(endMs))
                .build()
                .list();
        for (final GenericHeartRateSample sample : hrSamples) {
            if (heartRateUtils.isValidHeartRateValue(sample.getHeartRate())) {
                heartRate.add(sample.getHeartRate());
            }
        }

        if (summary.getQuality() != null) {
            stats.put(context.getString(R.string.sleep_quality),
                    new ActivitySummarySimpleEntry(formatSleepQualityStars(summary.getQuality()), UNIT_RAW_STRING));
        }
        if (summary.getWakeCount() != null) {
            stats.put(context.getString(R.string.wake_count),
                    new ActivitySummarySimpleEntry(String.valueOf(summary.getWakeCount()), UNIT_RAW_STRING));
        }
        if (heartRate.getCount() > 0) {
            stats.put(context.getString(R.string.heart_rate_during_sleep), new ActivitySummarySimpleEntry(
                    formatRange((int) heartRate.getMin(), (int) Math.round(heartRate.getAverage()), (int) heartRate.getMax(),
                            context.getString(R.string.bpm)), UNIT_RAW_STRING));
        }

        final Accumulator spo2 = accumulateSpo2(device, session, dbDevice.getId(), startMs, endMs);
        if (spo2.getCount() > 0) {
            stats.put(context.getString(R.string.spo2_during_sleep), new ActivitySummarySimpleEntry(
                    formatRange((int) spo2.getMin(), (int) Math.round(spo2.getAverage()), (int) spo2.getMax(), "%"), UNIT_RAW_STRING));
        }

        final Accumulator temperature = accumulateTemperature(device, session, dbDevice.getId(), startMs, endMs);
        if (temperature.getCount() > 0) {
            stats.put(context.getString(R.string.temperature_during_sleep), new ActivitySummarySimpleEntry(
                    formatRange((int) Math.round(temperature.getMin()), (int) Math.round(temperature.getAverage()),
                            (int) Math.round(temperature.getMax()), "°C"), UNIT_RAW_STRING));
        }

        final Accumulator respiratoryRate = accumulateRespiratoryRate(device, session, dbDevice.getId(), startMs, endMs);
        if (respiratoryRate.getCount() > 0) {
            stats.put(context.getString(R.string.respiratory_rate_during_sleep), new ActivitySummarySimpleEntry(
                    formatRange((int) Math.round(respiratoryRate.getMin()), (int) Math.round(respiratoryRate.getAverage()),
                            (int) Math.round(respiratoryRate.getMax()), context.getString(R.string.breaths_per_min)), UNIT_RAW_STRING));
        }

        stats.put(context.getString(R.string.apnea_events),
                new ActivitySummarySimpleEntry(String.valueOf(apneaEvents), UNIT_RAW_STRING));
        if (hypoxiaMinutes > 0) {
            stats.put(context.getString(R.string.hypoxia_minutes),
                    new ActivitySummarySimpleEntry(String.valueOf(hypoxiaMinutes), UNIT_RAW_STRING));
        }
        if (cardiacLoadMinutes > 0) {
            stats.put(context.getString(R.string.cardiac_load_minutes),
                    new ActivitySummarySimpleEntry(String.valueOf(cardiacLoadMinutes), UNIT_RAW_STRING));
        }

        return stats;
    }

    private Accumulator accumulateSpo2(final GBDevice device, final DaoSession session,
                                       final long deviceId, final long startMs, final long endMs) {
        final Accumulator accumulator = new Accumulator();
        final TimeSampleProvider<? extends Spo2Sample> provider = device.getDeviceCoordinator().getSpo2SampleProvider(device, session);
        if (provider != null) {
            for (final Spo2Sample sample : provider.getAllSamples(startMs, endMs)) {
                accumulator.add(sample.getSpo2());
            }
        } else {
            final List<GenericSpo2Sample> samples = session.getGenericSpo2SampleDao().queryBuilder()
                    .where(GenericSpo2SampleDao.Properties.DeviceId.eq(deviceId),
                            GenericSpo2SampleDao.Properties.Timestamp.ge(startMs),
                            GenericSpo2SampleDao.Properties.Timestamp.le(endMs))
                    .build()
                    .list();
            for (final GenericSpo2Sample sample : samples) {
                accumulator.add(sample.getSpo2());
            }
        }
        return accumulator;
    }

    private Accumulator accumulateTemperature(final GBDevice device, final DaoSession session,
                                              final long deviceId, final long startMs, final long endMs) {
        final Accumulator accumulator = new Accumulator();
        final TimeSampleProvider<? extends TemperatureSample> provider = device.getDeviceCoordinator().getTemperatureSampleProvider(device, session);
        if (provider != null) {
            for (final TemperatureSample sample : provider.getAllSamples(startMs, endMs)) {
                accumulator.add(sample.getTemperature());
            }
        } else {
            final List<GenericTemperatureSample> samples = session.getGenericTemperatureSampleDao().queryBuilder()
                    .where(GenericTemperatureSampleDao.Properties.DeviceId.eq(deviceId),
                            GenericTemperatureSampleDao.Properties.Timestamp.ge(startMs),
                            GenericTemperatureSampleDao.Properties.Timestamp.le(endMs))
                    .build()
                    .list();
            for (final GenericTemperatureSample sample : samples) {
                accumulator.add(sample.getTemperature());
            }
        }
        return accumulator;
    }

    private Accumulator accumulateRespiratoryRate(final GBDevice device, final DaoSession session,
                                                  final long deviceId, final long startMs, final long endMs) {
        final Accumulator accumulator = new Accumulator();
        final TimeSampleProvider<? extends RespiratoryRateSample> provider = device.getDeviceCoordinator().getRespiratoryRateSampleProvider(device, session);
        if (provider != null) {
            for (final RespiratoryRateSample sample : provider.getAllSamples(startMs, endMs)) {
                accumulator.add(sample.getRespiratoryRate());
            }
        } else {
            final List<GenericRespiratoryRateSample> samples = session.getGenericRespiratoryRateSampleDao().queryBuilder()
                    .where(GenericRespiratoryRateSampleDao.Properties.DeviceId.eq(deviceId),
                            GenericRespiratoryRateSampleDao.Properties.Timestamp.ge(startMs),
                            GenericRespiratoryRateSampleDao.Properties.Timestamp.le(endMs))
                    .build()
                    .list();
            for (final GenericRespiratoryRateSample sample : samples) {
                accumulator.add(sample.getRespiratoryRate());
            }
        }
        return accumulator;
    }

    private static String formatSleepQualityStars(final int quality) {
        final int clamped = Math.max(1, Math.min(5, quality));
        final StringBuilder stars = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            stars.append(i < clamped ? "★" : "☆");
        }
        return stars.toString();
    }

    private static String formatRange(final int min, final int average, final int max, final String unit) {
        return String.format(Locale.getDefault(), "%d / %d / %d %s", min, average, max, unit);
    }
}
