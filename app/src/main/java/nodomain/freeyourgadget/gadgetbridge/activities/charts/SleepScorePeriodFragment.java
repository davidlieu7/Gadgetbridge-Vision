package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.Chart;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import kotlin.jvm.functions.Function1;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.DaySelections;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep.SleepScoreChartData;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileData;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileGridUtilKt;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.databinding.FragmentSleepScorePeriodBinding;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.SleepScoreSample;
import nodomain.freeyourgadget.gadgetbridge.util.Accumulator;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;

/**
 * The sleep score of each night over a week or a month, with the highest, lowest and average.
 */
public class SleepScorePeriodFragment extends SleepFragment<SleepScorePeriodFragment.MyChartsData> {
    private static final String ARG_TOTAL_DAYS = "totalDays";

    private FragmentSleepScorePeriodBinding binding;
    private int totalDays = 7;

    public static SleepScorePeriodFragment newInstance(final int totalDays) {
        final SleepScorePeriodFragment fragment = new SleepScorePeriodFragment();
        final Bundle args = new Bundle();
        args.putInt(ARG_TOTAL_DAYS, totalDays);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            totalDays = getArguments().getInt(ARG_TOTAL_DAYS, totalDays);
        }
    }

    @Override
    public String getTitle() {
        return getString(R.string.sleep_score);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSleepScorePeriodBinding.inflate(inflater, container, false);
        final View rootView = binding.getRoot();
        rootView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                getChartsHost().enableSwipeRefresh(scrollY == 0));
        binding.sleepScoreChart.setZoomable(totalDays > 7);
        binding.sleepScoreChart.dismissSelectionOnTapOutside(rootView);
        refresh();
        return rootView;
    }

    @Override
    protected MyChartsData refreshInBackground(ChartsHost chartsHost, DBHandler db, GBDevice device) {
        final Calendar day = Calendar.getInstance();
        day.setTime(chartsHost.getEndDate());
        day.add(Calendar.DATE, -totalDays + 1);

        final long[] epochDays = new long[totalDays];
        final int[] scores = new int[totalDays];
        final Accumulator accumulator = new Accumulator();
        for (int i = 0; i < totalDays; i++) {
            epochDays[i] = LocalDate.of(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH)).toEpochDay();
            final List<SleepScoreSample> samples = getSleepScoreSamples(db, device, day);
            if (!samples.isEmpty() && samples.get(samples.size() - 1).getSleepScore() > 0) {
                scores[i] = samples.get(samples.size() - 1).getSleepScore();
                accumulator.add(scores[i]);
            }
            day.add(Calendar.DATE, 1);
        }
        final boolean any = accumulator.getCount() > 0;
        return new MyChartsData(
                epochDays,
                scores,
                any ? (int) Math.round(accumulator.getMax()) : 0,
                any ? (int) Math.round(accumulator.getMin()) : 0,
                any ? (int) Math.round(accumulator.getAverage()) : 0
        );
    }

    @Override
    protected void updateChartsnUIThread(final MyChartsData data) {
        binding.sleepScoreDates.setText(DateTimeUtils.formatDaysUntil(totalDays, getTSEnd()));

        final int color = ContextCompat.getColor(requireContext(), R.color.chart_light_sleep_light);
        final String label = getString(R.string.sleep_score);
        final String emptyValue = getString(R.string.stats_empty_value);
        final Function1<Integer, String> rowText = i -> data.scores[i] > 0 ? String.valueOf(data.scores[i]) : emptyValue;
        binding.sleepScoreChart.setSelectionContent(x -> DaySelections.of(
                data.epochDays, x, Collections.singletonList(label), Collections.singletonList(color),
                Collections.singletonList(rowText), emptyValue
        ));
        binding.sleepScoreChart.setSpec(SleepScoreChartData.periodSpec(data.epochDays, data.scores, label, color));

        final List<StatTileData> stats = new ArrayList<>();
        stats.add(new StatTileData(data.highest > 0 ? String.valueOf(data.highest) : emptyValue, getString(R.string.highest)));
        stats.add(new StatTileData(data.lowest > 0 ? String.valueOf(data.lowest) : emptyValue, getString(R.string.lowest)));
        stats.add(new StatTileData(data.average > 0 ? String.valueOf(data.average) : emptyValue, getString(R.string.hr_average)));
        binding.sleepScoreStatsContainer.removeAllViews();
        StatTileGridUtilKt.addStatTileGrid(binding.sleepScoreStatsContainer, requireContext(), stats, 0);
    }

    @Override
    protected void renderCharts() {
        binding.sleepScoreChart.invalidate();
    }

    @Override
    protected void setupLegend(Chart<?> chart) {
    }

    protected static class MyChartsData extends ChartsData {
        private final long[] epochDays;
        private final int[] scores;
        private final int highest;
        private final int lowest;
        private final int average;

        MyChartsData(final long[] epochDays, final int[] scores, final int highest, final int lowest, final int average) {
            this.epochDays = epochDays;
            this.scores = scores;
            this.highest = highest;
            this.lowest = lowest;
            this.average = average;
        }
    }
}
