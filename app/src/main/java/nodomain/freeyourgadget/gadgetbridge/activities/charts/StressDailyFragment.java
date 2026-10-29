/*  Copyright (C) 2023-2024 Daniel Dakhno, José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.mikephil.charting.charts.Chart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.ChartLegendView;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.ChartSelection;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.GbChartView;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.stress.StressChartData;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GaugeDrawer;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileData;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileGridUtilKt;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class StressDailyFragment extends StressFragment<StressDailyFragment.StressChartsData> {
    protected static final Logger LOG = LoggerFactory.getLogger(StressDailyFragment.class);

    private GbChartView mStressChart;
    private ChartLegendView mStressLegend;
    private ImageView mStressLevelsGauge;
    private LinearLayout mStatsContainer;
    private TextView stressDate;

    private String STRESS_AVERAGE_LABEL;

    private final Prefs prefs = GBApplication.getPrefs();
    private final boolean SHOW_CHARTS_AVERAGE = prefs.getBoolean("charts_show_average", true);

    private boolean showStressLevelInPercents = false;

    @Override
    protected void init() {
        super.init();
        STRESS_AVERAGE_LABEL = requireContext().getString(R.string.charts_legend_stress_average);
    }

    @Override
    protected StressChartsData refreshInBackground(final ChartsHost chartsHost, final DBHandler db, final GBDevice device) {
        int tsEnd = getTSEnd();
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(tsEnd * 1000L);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        final int tsStart = (int) (day.getTimeInMillis() / 1000);
        tsEnd = tsStart + 24 * 60 * 60 - 1;
        final List<? extends StressSample> samples = getStressSamples(db, device, tsStart, tsEnd);

        LOG.info("Got {} stress samples", samples.size());

        ensureStartAndEndSamples((List<StressSample>) samples, tsStart, tsEnd);

        showStressLevelInPercents = device.getDeviceCoordinator().showStressLevelInPercents();

        return new StressChartsDataBuilder(samples, device.getDeviceCoordinator().getStressRanges(), device.getDeviceCoordinator().getStressChartParameters()).build();
    }

    private String formatZoneValue(Integer value, long totalStressTime) {
        if (showStressLevelInPercents) {
            int valuePercent = (value == null || totalStressTime == 0) ? 0 : (int) Math.round(((double) value / totalStressTime) * 100);
            return String.format(Locale.ROOT, "%d%%", valuePercent);
        }
        if (value != null && value > 0) {
            return DateTimeUtils.formatDurationHoursMinutes(value, TimeUnit.SECONDS);
        }
        return getString(R.string.stats_empty_value);
    }

    private StatTileData buildZoneTile(StressType stressType, Integer value, long totalStressTime) {
        return new StatTileData(
                formatZoneValue(value, totalStressTime),
                stressType.getLabel(requireContext()),
                null,
                null,
                null,
                null,
                stressType.getColor(requireContext())
        );
    }

    @Override
    protected void updateChartsnUIThread(final StressChartsData stressData) {
        Date date = new Date((long) this.getTSEnd() * 1000);
        String formattedDate = DateTimeUtils.formatChartDate(date);
        stressDate.setText(formattedDate);

        Map<StressType, Integer> stressZoneTimes = stressData.getStressZoneTimes();
        final List<StatTileData> stats = new ArrayList<>();
        stats.add(buildZoneTile(StressType.RELAXED, stressZoneTimes.get(StressType.RELAXED), stressData.getTotalStressTime()));
        stats.add(buildZoneTile(StressType.MILD, stressZoneTimes.get(StressType.MILD), stressData.getTotalStressTime()));
        stats.add(buildZoneTile(StressType.MODERATE, stressZoneTimes.get(StressType.MODERATE), stressData.getTotalStressTime()));
        stats.add(buildZoneTile(StressType.HIGH, stressZoneTimes.get(StressType.HIGH), stressData.getTotalStressTime()));
        mStatsContainer.removeAllViews();
        StatTileGridUtilKt.addStatTileGrid(mStatsContainer, requireContext(), stats, 0);

        mStressLevelsGauge.setImageBitmap(drawStressRing(requireContext(), new long[]{
                zoneTime(stressZoneTimes, StressType.RELAXED),
                zoneTime(stressZoneTimes, StressType.MILD),
                zoneTime(stressZoneTimes, StressType.MODERATE),
                zoneTime(stressZoneTimes, StressType.HIGH),
        }, stressData.getAverage()));

        final StressType[] types = StressType.values();
        final String[] labels = new String[types.length];
        final int[] colors = new int[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = types[i].getLabel(requireContext());
            colors[i] = types[i].getColor(requireContext());
        }
        final List<? extends StressSample> samples = stressData.getSamples();
        final int[] stressRanges = stressData.getStressRanges();
        final long[] sampleSeconds = new long[samples.size()];
        final int[] sampleValues = new int[samples.size()];
        final int[] sampleLevels = new int[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            sampleSeconds[i] = samples.get(i).getTimestamp() / 1000L;
            sampleValues[i] = samples.get(i).getStress();
            sampleLevels[i] = StressType.fromStress(sampleValues[i], stressRanges).ordinal();
        }
        final long dayStart = DateTimeUtils.dayStart(date).getTime() / 1000L;
        final ChartSpec spec = StressChartData.daySpec(
                dayStart, stressData.getLevels(), labels, colors, sampleSeconds, sampleValues,
                stressData.getAverage(), SHOW_CHARTS_AVERAGE, Color.GRAY, sampleLevels
        );
        mStressChart.setSelectionContent(x -> {
            final long time = Math.round(x);
            final String title = DateFormat.getTimeFormat(requireContext()).format(new Date(time * 1000L));
            for (int i = 0; i < sampleSeconds.length; i++) {
                if (sampleSeconds[i] == time && sampleValues[i] > 0) {
                    final StressType type = StressType.fromStress(sampleValues[i], stressRanges);
                    final String text = sampleValues[i] + " (" + type.getLabel(requireContext()) + ")";
                    return new ChartSelection(
                            title,
                            Collections.singletonList(new ChartSelection.Row(type.getColor(requireContext()), text)),
                            title + ". " + getTitle() + " " + text + "."
                    );
                }
            }
            return new ChartSelection(title, Collections.emptyList(), title + ".");
        });
        mStressChart.setSpec(spec);

        final List<ChartSeries> legendSeries = new ArrayList<>();
        if (!spec.getSeries().isEmpty()) {
            for (int i = 0; i < types.length; i++) {
                legendSeries.add(ChartLegendView.lineItem(labels[i], colors[i]));
            }
            if (!spec.getLimitLines().isEmpty()) {
                legendSeries.add(ChartLegendView.lineItem(STRESS_AVERAGE_LABEL, Color.GRAY));
            }
        }
        mStressLegend.setSeries(legendSeries);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater,
                             final ViewGroup container,
                             final Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.fragment_stresschart, container, false);

        rootView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> getChartsHost().enableSwipeRefresh(scrollY == 0));

        mStressChart = rootView.findViewById(R.id.stress_line_chart);
        mStressChart.setZoomable(true);
        mStressChart.dismissSelectionOnTapOutside(rootView);
        mStressLegend = rootView.findViewById(R.id.stress_chart_legend);
        mStressLevelsGauge = rootView.findViewById(R.id.stress_gauge);
        mStatsContainer = rootView.findViewById(R.id.stress_stats_container);
        stressDate = rootView.findViewById(R.id.stress_date);


        // refresh immediately instead of use refreshIfVisible(), for perceived performance
        refresh();

        return rootView;
    }

    private static long zoneTime(final Map<StressType, Integer> times, final StressType type) {
        final Integer time = times.get(type);
        return time != null ? time : 0;
    }

    /**
     * The time in each stress zone (relaxed, mild, moderate, high) as a ring of rounded segments like the sleep
     * stages, with the average stress level in the middle.
     */
    static Bitmap drawStressRing(final Context context, final long[] zoneTimes, final int average) {
        final StressType[] types = {StressType.RELAXED, StressType.MILD, StressType.MODERATE, StressType.HIGH};
        long total = 0;
        for (final long time : zoneTimes) {
            total += Math.max(time, 0);
        }
        final int[] colors = new int[types.length];
        final float[] segments = new float[types.length];
        for (int i = 0; i < types.length; i++) {
            colors[i] = types[i].getColor(context);
            segments[i] = total > 0 ? Math.max(zoneTimes[i], 0) / (float) total : 0;
        }
        final int width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300, context.getResources().getDisplayMetrics());
        return GaugeDrawer.drawCircleGaugeSegmented(
                width,
                width / 15,
                colors,
                segments,
                true,
                average > 0 ? String.valueOf(average) : "-",
                context.getString(R.string.stress_average),
                context
        );
    }

    @Override
    protected void setupLegend(final Chart<?> chart) {
    }

    @Override
    protected void renderCharts() {
    }

    protected class StressChartsDataBuilder {
        private static final int UNKNOWN_VAL = 2;

        private final List<? extends StressSample> samples;
        private final int[] stressRanges;

        private final int sampleRate;
        private final int interval;
        private final int delta;

        private final TimestampTranslation tsTranslation = new TimestampTranslation();

        private final Map<StressType, List<ChartPoint>> lineEntriesPerLevel = new HashMap<>();
        private long baseTs;
        private final Map<StressType, Integer> accumulator = new HashMap<>();

        int previousTs;
        int currentTypeStartTs;
        StressType previousStressType;
        long averageSum;
        long averageNumSamples;

        public StressChartsDataBuilder(final List<? extends StressSample> samples, final int[] stressRanges, final int[] dataParameters) {
            this.samples = samples;
            this.stressRanges = stressRanges;
            this.sampleRate = dataParameters[0];
            this.interval = dataParameters[1];
            this.delta = dataParameters[2];
        }

        private void reset() {
            tsTranslation.reset();
            lineEntriesPerLevel.clear();
            accumulator.clear();
            for (final StressType stressType : StressType.values()) {
                lineEntriesPerLevel.put(stressType, new ArrayList<>());
                accumulator.put(stressType, 0);
            }
            previousTs = 0;
            currentTypeStartTs = 0;
            previousStressType = StressType.UNKNOWN;
        }

        private void processSamples() {
            reset();

            for (final StressSample sample : samples) {
                processSample(sample);
            }

            // Add the last block, if any
            if (currentTypeStartTs != previousTs) {
                set(previousTs, previousStressType, samples.get(samples.size() - 1).getStress());
            }
        }

        private void processSample(final StressSample sample) {
            final StressType stressType = StressType.fromStress(sample.getStress(), stressRanges);
            final int ts = tsTranslation.shorten((int) (sample.getTimestamp() / 1000L));

            if (ts == 0) {
                baseTs = sample.getTimestamp() / 1000L;
                // First sample
                previousTs = ts;
                currentTypeStartTs = ts;
                previousStressType = stressType;
                if(interval > 0 && sample.getStress() > 0) {
                    int endTime = interval - delta;
                    set(ts, stressType, sample.getStress());
                    set(endTime - 1, stressType, sample.getStress());
                    set(endTime, StressType.UNKNOWN, UNKNOWN_VAL);
                } else {
                    set(ts, stressType, sample.getStress());
                }
                return;
            }

            if(interval > 0) {
                // For interval devices bars chard should be used.
                // Emulate bars by drawing unknown type on the start and end of interval with delta for spaces.
                if(sample.getStress() > 0) {
                    int startTime = (((ts / interval)) * interval) + delta;
                    int endTime = (((ts / interval) + 1) * interval) - delta;

                    set(startTime, StressType.UNKNOWN, UNKNOWN_VAL);
                    set(startTime + 1, stressType, sample.getStress());
                    set(endTime - 1, stressType, sample.getStress());
                    set(endTime, StressType.UNKNOWN, UNKNOWN_VAL);
                }  else {
                    set(ts, stressType, sample.getStress());
                }
            } else {
                if (ts - previousTs > sampleRate * 10) {
                    // More than 15 minutes since last sample
                    // Set to unknown right after the last sample we got until the current time
                    int lastEndTs = Math.min(previousTs + sampleRate * 5, ts - 1);
                    set(lastEndTs, StressType.UNKNOWN, UNKNOWN_VAL);
                    set(ts - 1, StressType.UNKNOWN, UNKNOWN_VAL);
                }

                set(ts, stressType, sample.getStress());
            }

            if (!stressType.equals(previousStressType)) {
                currentTypeStartTs = ts;
            }

            accumulator.computeIfPresent(stressType, (k, v) -> v + sampleRate);

            if (stressType != StressType.UNKNOWN) {
                averageSum += sample.getStress();
                averageNumSamples++;
            }

            previousStressType = stressType;
            previousTs = ts;
        }

        private void set(final int ts, final StressType stressType, final int stress) {
            for (final Map.Entry<StressType, List<ChartPoint>> stressTypeListEntry : lineEntriesPerLevel.entrySet()) {
                final int value = stressTypeListEntry.getKey() == stressType ? stress : 0;
                stressTypeListEntry.getValue().add(new ChartPoint(baseTs + ts, value, null));
            }
        }

        public StressChartsData build() {
            processSamples();

            final List<List<ChartPoint>> levels = new ArrayList<>();
            final Map<StressType, Integer> stressZoneTimes = new HashMap<>();

            long totalStressTime = 0;
            for (final StressType stressType : StressType.values()) {
                levels.add(lineEntriesPerLevel.get(stressType));

                final Integer stressTime = accumulator.get(stressType);
                stressZoneTimes.put(stressType, stressTime);

                if (stressType != StressType.UNKNOWN && stressTime != null && stressTime != 0) {
                    totalStressTime += stressTime;
                }
            }

            return new StressChartsData(levels, samples, stressRanges, Math.round((float) averageSum / averageNumSamples), stressZoneTimes, totalStressTime);
        }
    }

    protected static class StressChartsData extends ChartsData {
        private final List<List<ChartPoint>> levels;
        private final List<? extends StressSample> samples;
        private final int[] stressRanges;
        private final int average;
        private final Map<StressType, Integer> stressZoneTimes;
        private final long totalStressTime;

        public StressChartsData(final List<List<ChartPoint>> levels, final List<? extends StressSample> samples,
                                final int[] stressRanges, final int average, Map<StressType, Integer> stressZoneTimes, long totalStressTime) {
            this.levels = levels;
            this.samples = samples;
            this.stressRanges = stressRanges;
            this.average = average;
            this.stressZoneTimes = stressZoneTimes;
            this.totalStressTime = totalStressTime;
        }

        public Map<StressType, Integer> getStressZoneTimes() {
            return stressZoneTimes;
        }

        public List<List<ChartPoint>> getLevels() {
            return levels;
        }

        public List<? extends StressSample> getSamples() {
            return samples;
        }

        public int[] getStressRanges() {
            return stressRanges;
        }

        public int getAverage() {
            return average;
        }

        public long getTotalStressTime() {
            return totalStressTime;
        }
    }
}