package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.Chart;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep.SleepScoreQuality;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GaugeDrawer;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileData;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileGridUtilKt;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.databinding.FragmentSleepScoreDailyBinding;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.SleepScoreSample;
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.SleepRangeUtils;

/**
 * One night's sleep score as a gauge, with its quality and how long the sleep was.
 */
public class SleepScoreDailyFragment extends SleepFragment<SleepScoreDailyFragment.MyChartsData> {
    private FragmentSleepScoreDailyBinding binding;

    @Override
    public String getTitle() {
        return getString(R.string.sleep_score);
    }

    @Override
    protected boolean isSingleDay() {
        return true;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSleepScoreDailyBinding.inflate(inflater, container, false);
        final View rootView = binding.getRoot();
        rootView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                getChartsHost().enableSwipeRefresh(scrollY == 0));
        refresh();
        return rootView;
    }

    @Override
    protected MyChartsData refreshInBackground(ChartsHost chartsHost, DBHandler db, GBDevice device) {
        final int[] range = SleepRangeUtils.getSleepRange(getTSEnd(), SleepRangeUtils.getMode());
        final List<SleepSession> sessions = device.getDeviceCoordinator()
                .getSleepSessionProvider(device, db.getDaoSession())
                .getSleepSessionsEndingIn(range[0], range[1]);
        long sleepSeconds = 0;
        for (final SleepSession session : sessions) {
            sleepSeconds += session.getTotalSleepDuration();
        }

        int score = 0;
        final List<SleepScoreSample> scoreSamples = getSleepScoreSamples(db, device, getTSStart(), getTSEnd());
        if (!scoreSamples.isEmpty()) {
            score = scoreSamples.get(scoreSamples.size() - 1).getSleepScore();
        }
        return new MyChartsData(score, sleepSeconds);
    }

    @Override
    protected void updateChartsnUIThread(final MyChartsData data) {
        binding.sleepScoreDate.setText(new SimpleDateFormat("E, MMM dd").format(new Date((long) getTSEnd() * 1000)));

        final SleepScoreQuality quality = SleepScoreQuality.of(data.score);
        final String emptyValue = getString(R.string.stats_empty_value);
        final int[] colors = new int[SleepScoreQuality.GAUGE_ORDER.length];
        Arrays.fill(colors, ContextCompat.getColor(requireContext(), R.color.chart_light_sleep_light));
        new GaugeDrawer().drawSegmentedGauge(
                binding.sleepScoreGauge, colors, SleepScoreQuality.GAUGE_SEGMENTS,
                quality != null ? data.score / 100f : -1f, false, true
        );
        binding.sleepScoreGaugeValue.setText(quality != null ? String.valueOf(data.score) : emptyValue);
        binding.sleepScoreGaugeQuality.setText(quality != null ? getString(quality.getLabel()) : "");
        binding.sleepScoreGaugeText.setContentDescription(getString(R.string.sleep_score) + " " +
                (quality != null ? data.score + ", " + getString(quality.getLabel()) : emptyValue));

        final List<StatTileData> stats = new ArrayList<>();
        stats.add(new StatTileData(
                quality != null ? getString(quality.getLabel()) : emptyValue,
                getString(R.string.sleep_score_quality)
        ));
        stats.add(new StatTileData(
                data.sleepSeconds > 0 ? DateTimeUtils.formatDurationHoursMinutes(data.sleepSeconds, TimeUnit.SECONDS) : emptyValue,
                getString(R.string.activity_detail_duration_label)
        ));
        binding.sleepScoreStatsContainer.removeAllViews();
        StatTileGridUtilKt.addStatTileGrid(binding.sleepScoreStatsContainer, requireContext(), stats, 0);
    }

    @Override
    protected void renderCharts() {
    }

    @Override
    protected void setupLegend(Chart<?> chart) {
    }

    protected static class MyChartsData extends ChartsData {
        private final int score;
        private final long sleepSeconds;

        MyChartsData(final int score, final long sleepSeconds) {
            this.score = score;
            this.sleepSeconds = sleepSeconds;
        }
    }
}
