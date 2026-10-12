/*  Copyright (C) 2024-2026 Severin von Wnuck-Lipinski, oddballza

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

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.Chart;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.tabs.TabLayout;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.ChartLegendView;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.ChartSelection;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.GbChartView;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.ImpedanceChartData;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.WeightChartData;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.WeightMeasurementRow;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.WeightMeasurements;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.WeightMeasurementsActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.weight.WeightMeasurementsController;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileData;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileGridUtilKt;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericImpedanceSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericImpedanceSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample;
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit;
import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionCalculator;
import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionEstimates;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.UserWeight;

public class WeightChartFragment extends AbstractChartFragment<WeightChartFragment.WeightChartsData> {
    private static final int[] RANGE_MONTHS = {1, 3, 12};
    private static final int[] RANGE_LABELS = {R.string.calendar_month, R.string.calendar_three_months, R.string.calendar_year};
    private static final String PREF_RANGE_MONTHS = "chart_weight_range_months";
    private static final int PREVIEW_MEASUREMENTS = 5;
    private static final String PREF_SHOW_GOAL_LINE = "chart_weight_show_goal_line";
    private static final int MAX_SELECTED_SERIES = 2;
    private static final String SERIES_WEIGHT = "weight";
    private static final String SERIES_IMPEDANCE = "impedance";

    private WeightUnit weightUnit = WeightUnit.KILOGRAM;
    private int weightTargetKg;

    private GbChartView chart;
    private ChartLegendView legend;
    private TextView textTimeSpan;
    private LinearLayout statsContainer;
    private static final String PREF_BODY_COMPOSITION_VALUES = "chart_weight_body_composition";

    private View addButton;
    private View seeAllButton;
    private LinearLayout measurementsContainer;
    private View measurementsEmpty;
    private WeightMeasurementsController measurements;
    private boolean refreshOnResume;
    private boolean showGoalLine = true;
    private final List<String> selectedSeries = new ArrayList<>();
    private HorizontalScrollView seriesScroll;
    private ChipGroup seriesChips;
    private int rangeMonths = 1;
    @Nullable
    private Double latestWeightKg;

    @Override
    public String getTitle() {
        return getString(R.string.menuitem_weight);
    }

    @Override
    protected void init() {
        GBPrefs prefs = GBApplication.getPrefs();

        weightUnit = prefs.getWeightUnit();

        weightTargetKg = prefs.getInt(ActivityUser.PREF_USER_GOAL_WEIGHT_KG, ActivityUser.defaultUserGoalWeightKg);
        showGoalLine = prefs.getBoolean(PREF_SHOW_GOAL_LINE, true);
        rangeMonths = prefs.getInt(PREF_RANGE_MONTHS, 1);
    }

    @Override
    protected boolean isSingleDay() {
        return false;
    }

    @Override
    protected WeightChartsData refreshInBackground(ChartsHost chartsHost, DBHandler db, GBDevice device) {
        long tsEnd = getTSEnd() * 1000L;

        DeviceCoordinator coordinator = device.getDeviceCoordinator();
        TimeSampleProvider<? extends WeightSample> provider = coordinator.getWeightSampleProvider(device, db.getDaoSession());
        long tsStart = Instant.ofEpochMilli(tsEnd).atZone(ZoneId.systemDefault()).minusMonths(rangeMonths).toInstant().toEpochMilli();
        List<? extends WeightSample> samples = provider.getAllSamples(tsStart, tsEnd);
        WeightSample latestSample = provider.getLatestSample();
        WeightSample latestInRange = provider.getLatestSample(tsEnd);
        if (latestInRange != null && latestInRange.getTimestamp() < tsStart) {
            latestInRange = null;
        }
        BodyCompositionCalculator.BodyComposition composition = BodyCompositionEstimates.composition(db.getDaoSession(), latestSample);
        Float bmi = BodyCompositionEstimates.bmi(db.getDaoSession(), latestSample);
        List<WeightSample> chartSamples = new ArrayList<>(samples.size() + 1);
        WeightSample beforeRange = provider.getLatestSample(tsStart - 1);
        if (beforeRange != null) {
            chartSamples.add(beforeRange);
        }
        chartSamples.addAll(samples);
        List<BodyCompositionCalculator.BodyComposition> compositions = new ArrayList<>(chartSamples.size());
        for (WeightSample sample : chartSamples) {
            compositions.add(BodyCompositionEstimates.composition(db.getDaoSession(), sample));
        }
        boolean manualEntry = UserWeight.supportsManualEntry(device, db.getDaoSession());
        List<? extends WeightSample> allSamples = provider.getAllSamples(0, System.currentTimeMillis());
        long[] timestamps = new long[allSamples.size()];
        double[] weights = new double[allSamples.size()];
        for (int i = 0; i < allSamples.size(); i++) {
            timestamps[i] = allSamples.get(i).getTimestamp();
            weights[i] = allSamples.get(i).getWeightKg();
        }
        List<WeightMeasurementRow> rows = WeightMeasurements.rows(timestamps, weights);
        return new WeightChartsData(samples, chartSamples, beforeRange != null, compositions,
                impedances(db, device, samples, tsStart, tsEnd), latestSample, latestInRange, composition, bmi, manualEntry, tsStart / 1000, rows);
    }

    /**
     * The readings from the impedance table, which can hold several per weigh-in, or else the single value most
     * scales store with the weight.
     */
    private static List<ImpedanceChartData.Reading> impedances(final DBHandler db, final GBDevice device,
                                                               final List<? extends WeightSample> samples,
                                                               final long tsStart, final long tsEnd) {
        final long[] weighIns = new long[samples.size()];
        for (int i = 0; i < weighIns.length; i++) {
            weighIns[i] = samples.get(i).getTimestamp() / 1000L;
        }
        final List<ImpedanceChartData.Reading> stored = new ArrayList<>();
        for (final GenericImpedanceSample row : new GenericImpedanceSampleProvider(device, db.getDaoSession()).getAllSamples(tsStart, tsEnd)) {
            stored.add(new ImpedanceChartData.Reading(row.getTimestamp() / 1000L, row.getBodySectionIdx(), row.getFrequencyHz(), row.getImpedanceOhm()));
        }
        final List<ImpedanceChartData.Reading> readings = ImpedanceChartData.atWeighIns(stored, weighIns);
        if (!readings.isEmpty()) {
            return readings;
        }
        for (final WeightSample sample : samples) {
            if (sample.getImpedanceOhm() != null) {
                readings.add(new ImpedanceChartData.Reading(sample.getTimestamp() / 1000L, 0, -1, sample.getImpedanceOhm()));
            }
        }
        return readings;
    }

    @Override
    protected void renderCharts() {
        chart.invalidate();
    }

    @Override
    protected void setupLegend(Chart<?> chart) {}

    @Override
    protected void updateChartsnUIThread(WeightChartsData chartsData) {
        updateChart(chartsData.chartSamples, chartsData.compositions, chartsData.impedances, chartsData.startTs, chartsData.carriedIn);
        textTimeSpan.setText(DateTimeUtils.formatDaysUntil(daysBetween(chartsData.startTs, getTSEnd()), getTSEnd()));

        WeightSample latestSample = chartsData.getLatestSample();
        updateStats(chartsData.samples, chartsData.latestInRange, latestSample, chartsData.getComposition(), chartsData.getBmi());
        latestWeightKg = latestSample != null ? (double) latestSample.getWeightKg() : null;
        addButton.setVisibility(chartsData.supportsManualEntry() ? View.VISIBLE : View.GONE);
        updateMeasurements(chartsData.measurements, chartsData.supportsManualEntry());

    }

    private void updateChart(final List<? extends WeightSample> samples,
                             final List<BodyCompositionCalculator.BodyComposition> compositions,
                             final List<ImpedanceChartData.Reading> impedances,
                             final long startTs,
                             final boolean carriedIn) {
        final int n = samples.size();
        final long[] seconds = new long[n];
        final double[] weights = new double[n];
        final double[] bodyFat = new double[n];
        final double[] bodyWater = new double[n];
        final double[] muscle = new double[n];
        for (int i = 0; i < n; i++) {
            final WeightSample sample = samples.get(i);
            seconds[i] = Math.max(sample.getTimestamp() / 1000L, startTs);
            weights[i] = weightFromKg(sample.getWeightKg());
            final BodyCompositionCalculator.BodyComposition composition = compositions.get(i);
            bodyFat[i] = composition != null ? composition.bodyFatPercent : Double.NaN;
            bodyWater[i] = composition != null ? composition.bodyWaterPercent : Double.NaN;
            // as a share of the weight, so it can go on the same axis as the other two
            muscle[i] = composition != null && sample.getWeightKg() > 0
                    ? 100.0 * composition.muscleMassKg / sample.getWeightKg()
                    : Double.NaN;
        }
        final int color = ContextCompat.getColor(requireContext(), R.color.accent);
        final String label = getString(R.string.menuitem_weight);
        final Set<String> enabled = enabledBodyCompositionValues();
        final List<WeightChartData.PercentLine> percentLines = new ArrayList<>();
        if (enabled.contains("body_fat")) {
            percentLines.add(new WeightChartData.PercentLine("body_fat", getString(R.string.body_composition_body_fat),
                    ContextCompat.getColor(requireContext(), R.color.chart_weight_body_fat), bodyFat));
        }
        if (enabled.contains("body_water")) {
            percentLines.add(new WeightChartData.PercentLine("body_water", getString(R.string.body_composition_body_water),
                    ContextCompat.getColor(requireContext(), R.color.chart_weight_body_water), bodyWater));
        }
        if (enabled.contains("muscle_mass")) {
            percentLines.add(new WeightChartData.PercentLine("muscle_mass", getString(R.string.body_composition_muscle_mass),
                    ContextCompat.getColor(requireContext(), R.color.chart_weight_muscle_mass), muscle));
        }
        final boolean weightAvailable = hasData(weights, null);
        final List<WeightChartData.PercentLine> availableLines = new ArrayList<>();
        for (final WeightChartData.PercentLine line : percentLines) {
            if (hasData(weights, line.getValues())) {
                availableLines.add(line);
            }
        }
        final List<String> availableKeys = new ArrayList<>();
        if (weightAvailable) {
            availableKeys.add(SERIES_WEIGHT);
        }
        for (final WeightChartData.PercentLine line : availableLines) {
            availableKeys.add(line.getKey());
        }
        final List<ImpedanceChartData.Group> impedanceGroups = enabled.contains("impedance")
                ? ImpedanceChartData.groups(impedances)
                : Collections.emptyList();
        if (!impedanceGroups.isEmpty()) {
            availableKeys.add(SERIES_IMPEDANCE);
        }
        selectedSeries.retainAll(availableKeys);
        if (selectedSeries.isEmpty()) {
            selectedSeries.addAll(availableKeys.subList(0, Math.min(MAX_SELECTED_SERIES, availableKeys.size())));
        }
        final List<WeightChartData.PercentLine> visibleLines = new ArrayList<>();
        for (final WeightChartData.PercentLine line : availableLines) {
            if (selectedSeries.contains(line.getKey())) {
                visibleLines.add(line);
            }
        }
        updateSeriesChips(weightAvailable, availableLines, !impedanceGroups.isEmpty());

        final boolean weightShown = selectedSeries.contains(SERIES_WEIGHT);
        final ChartSpec weightSpec = WeightChartData.spec(
                startTs, getTSEnd(), seconds, weights, showGoalLine ? weightFromKg(weightTargetKg) : 0, label, color, Color.GRAY,
                visibleLines, getString(R.string.unit_percentage), carriedIn, weightShown
        );
        final boolean impedanceShown = selectedSeries.contains(SERIES_IMPEDANCE);
        final int[] impedanceColors = new int[impedanceGroups.size()];
        final ChartSpec spec;
        if (impedanceShown) {
            final boolean oneSection = impedanceGroups.get(0).getSection() == impedanceGroups.get(impedanceGroups.size() - 1).getSection();
            final List<String> impedanceLabels = new ArrayList<>(impedanceGroups.size());
            for (int i = 0; i < impedanceGroups.size(); i++) {
                impedanceLabels.add(impedanceLabel(impedanceGroups.get(i), impedanceGroups.size() == 1, oneSection));
                impedanceColors[i] = impedanceGroups.size() == 1
                        ? ContextCompat.getColor(requireContext(), R.color.chart_weight_impedance)
                        : Color.HSVToColor(new float[]{360f * i / impedanceGroups.size(), 0.6f, 0.85f});
            }
            spec = ImpedanceChartData.addTo(weightSpec, startTs, getTSEnd(), impedances, impedanceGroups, impedanceLabels,
                    impedanceColors, getString(R.string.unit_ohm));
        } else {
            spec = weightSpec;
        }
        chart.setSelectionContent(x -> {
            final long time = Math.round(x);
            final String title = DateFormat.getDateFormat(requireContext()).format(new Date(time * 1000L));
            for (int i = 0; i < n; i++) {
                if (seconds[i] == time && weights[i] > 0) {
                    final String text = formatWeight((float) weights[i]);
                    final List<ChartSelection.Row> rows = new ArrayList<>();
                    final StringBuilder description = new StringBuilder(title).append('.');
                    if (weightShown) {
                        rows.add(new ChartSelection.Row(color, text));
                        description.append(' ').append(label).append(' ').append(text).append('.');
                    }
                    for (final WeightChartData.PercentLine line : visibleLines) {
                        if (Double.isNaN(line.getValues()[i])) {
                            continue;
                        }
                        final String value = formatPercent((float) line.getValues()[i]);
                        rows.add(new ChartSelection.Row(line.getColor(), value));
                        description.append(' ').append(line.getLabel()).append(' ').append(value).append('.');
                    }
                    if (impedanceShown) {
                        for (int g = 0; g < impedanceGroups.size(); g++) {
                            for (final ImpedanceChartData.Reading reading : impedances) {
                                if (reading.getSeconds() == time && reading.getSection() == impedanceGroups.get(g).getSection()
                                        && reading.getFrequencyHz() == impedanceGroups.get(g).getFrequencyHz()) {
                                    final String value = getString(R.string.body_composition_ohm, Math.round(reading.getOhm()));
                                    rows.add(new ChartSelection.Row(impedanceColors[g], value));
                                    description.append(' ').append(getString(R.string.body_composition_impedance))
                                            .append(' ').append(value).append('.');
                                }
                            }
                        }
                    }
                    return new ChartSelection(title, rows, description.toString());
                }
            }
            return new ChartSelection(title, Collections.emptyList(), title + ".");
        });
        chart.setSpec(spec);

        final List<ChartSeries> legendSeries = new ArrayList<>();
        for (final ChartSeries series : spec.getSeries()) {
            if (series.getSelectable()) {
                legendSeries.add(series);
            }
        }
        if (!spec.getSeries().isEmpty() && !spec.getLimitLines().isEmpty()) {
            legendSeries.add(ChartLegendView.lineItem(getString(R.string.weight_goal), Color.GRAY));
        }
        legend.setSeries(legendSeries.size() > 1 ? legendSeries : Collections.emptyList());
    }

    private static boolean hasData(final double[] weights, @Nullable final double[] values) {
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > 0 && (values == null || !Double.isNaN(values[i]))) {
                return true;
            }
        }
        return false;
    }

    /**
     * One chip per data set with data, shown when there are at least two. At most two are selected; picking another
     * drops the one selected first, and the only selected one cannot be turned off.
     */
    private void updateSeriesChips(final boolean weightAvailable, final List<WeightChartData.PercentLine> availableLines,
                                   final boolean impedanceAvailable) {
        seriesChips.removeAllViews();
        final int available = availableLines.size() + (weightAvailable ? 1 : 0) + (impedanceAvailable ? 1 : 0);
        seriesScroll.setVisibility(available >= 2 ? View.VISIBLE : View.GONE);
        if (available < 2) {
            return;
        }
        if (weightAvailable) {
            addSeriesChip(SERIES_WEIGHT, getString(R.string.menuitem_weight));
        }
        for (final WeightChartData.PercentLine line : availableLines) {
            addSeriesChip(line.getKey(), line.getLabel());
        }
        if (impedanceAvailable) {
            addSeriesChip(SERIES_IMPEDANCE, getString(R.string.body_composition_impedance));
        }
    }

    private String impedanceLabel(final ImpedanceChartData.Group group, final boolean only, final boolean oneSection) {
        if (only) {
            return getString(R.string.body_composition_impedance);
        }
        final String kHz = new DecimalFormat("0.##").format(group.getFrequencyHz() / 1000.0);
        if (oneSection) {
            return group.getFrequencyHz() < 0
                    ? getString(R.string.body_composition_impedance)
                    : getString(R.string.body_composition_impedance_frequency, kHz);
        }
        return group.getFrequencyHz() < 0
                ? getString(R.string.body_composition_impedance_section, group.getSection())
                : getString(R.string.body_composition_impedance_section_frequency, group.getSection(), kHz);
    }

    private void addSeriesChip(final String key, final String label) {
        final Chip chip = (Chip) LayoutInflater.from(requireContext()).inflate(R.layout.layout_chart_chip, seriesChips, false);
        chip.setText(label);
        chip.setTag(key);
        chip.setChecked(selectedSeries.contains(key));
        chip.setOnClickListener(v -> {
            if (selectedSeries.contains(key)) {
                if (selectedSeries.size() > 1) {
                    selectedSeries.remove(key);
                }
            } else {
                selectedSeries.add(key);
                if (selectedSeries.size() > MAX_SELECTED_SERIES) {
                    selectedSeries.remove(0);
                }
            }
            for (int i = 0; i < seriesChips.getChildCount(); i++) {
                final Chip other = (Chip) seriesChips.getChildAt(i);
                other.setChecked(selectedSeries.contains((String) other.getTag()));
            }
            refresh();
        });
        seriesChips.addView(chip);
    }

    private List<StatTileData> bodyCompositionTiles(@Nullable final WeightSample sample,
                                                    @Nullable final BodyCompositionCalculator.BodyComposition composition,
                                                    @Nullable final Float bmi) {
        final Set<String> enabled = enabledBodyCompositionValues();
        final List<StatTileData> tiles = new ArrayList<>();
        // BMI only needs the weight and the height, so it does not depend on the impedance below.
        if (bmi != null && enabled.contains("bmi")) {
            tiles.add(new StatTileData(getString(R.string.body_composition_bmi_value, bmi), getString(R.string.body_mass_index)));
        }
        if (sample == null || sample.getImpedanceOhm() == null) {
            return tiles;
        }
        if (enabled.contains("body_fat")) {
            tiles.add(new StatTileData(formatPercent(composition != null ? composition.bodyFatPercent : null), getString(R.string.body_composition_body_fat)));
        }
        if (enabled.contains("body_water")) {
            tiles.add(new StatTileData(formatPercent(composition != null ? composition.bodyWaterPercent : null), getString(R.string.body_composition_body_water)));
        }
        if (enabled.contains("muscle_mass")) {
            tiles.add(new StatTileData(formatOptionalWeight(composition != null ? composition.muscleMassKg : null), getString(R.string.body_composition_muscle_mass)));
        }
        if (enabled.contains("bone_mass")) {
            tiles.add(new StatTileData(formatOptionalWeight(composition != null ? composition.boneMassKg : null), getString(R.string.body_composition_bone_mass)));
        }
        if (enabled.contains("bmr")) {
            tiles.add(new StatTileData(composition != null
                    ? getString(R.string.body_composition_kcal, composition.basalMetabolicRate)
                    : getString(R.string.stats_empty_value), getString(R.string.body_composition_bmr)));
        }
        if (enabled.contains("impedance")) {
            tiles.add(new StatTileData(getString(R.string.body_composition_ohm, sample.getImpedanceOhm()), getString(R.string.body_composition_impedance)));
        }
        return tiles;
    }

    /**
     * The values picked in the chart settings; the percentages among them are also drawn on the chart.
     */
    private Set<String> enabledBodyCompositionValues() {
        return GBApplication.getPrefs().getStringSet(
                PREF_BODY_COMPOSITION_VALUES,
                new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.pref_chart_weight_body_composition_default)))
        );
    }

    private String formatPercent(final Float value) {
        return value != null ? getString(R.string.body_composition_percent, value) : getString(R.string.stats_empty_value);
    }

    private String formatOptionalWeight(final Float kg) {
        return kg != null ? formatWeight(weightFromKg(kg)) : getString(R.string.stats_empty_value);
    }

    private static int daysBetween(final long startTs, final long endTs) {
        final ZoneId zone = ZoneId.systemDefault();
        final LocalDate start = Instant.ofEpochSecond(startTs).atZone(zone).toLocalDate();
        final LocalDate end = Instant.ofEpochSecond(endTs).atZone(zone).toLocalDate();
        return (int) ChronoUnit.DAYS.between(start, end) + 1;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_weightchart, container, false);

        chart = rootView.findViewById(R.id.weight_chart);
        legend = rootView.findViewById(R.id.weight_chart_legend);
        textTimeSpan = rootView.findViewById(R.id.weight_time_span_text);
        statsContainer = rootView.findViewById(R.id.weight_stats_container);
        seriesScroll = rootView.findViewById(R.id.weight_series_scroll);
        seriesChips = rootView.findViewById(R.id.weight_series_chips);

        addButton = rootView.findViewById(R.id.weight_add);
        addButton.setOnClickListener(v -> {
            final Double currentKg = latestWeightKg != null ? latestWeightKg : (double) new ActivityUser().getWeightKg();
            measurements.add(currentKg);
        });
        rootView.findViewById(R.id.weight_goal).setOnClickListener(v ->
                WeightEntryBottomSheet.newGoalInstance(weightUnit, weightTargetKg > 0 ? (double) weightTargetKg : null, showGoalLine).show(getChildFragmentManager(), WeightEntryBottomSheet.TAG));
        getChildFragmentManager().setFragmentResultListener(WeightEntryBottomSheet.GOAL_REQUEST_KEY, this,
                (key, result) -> saveGoal(
                        result.getDouble(WeightEntryBottomSheet.RESULT_KG),
                        result.getBoolean(WeightEntryBottomSheet.RESULT_SHOW_GOAL_LINE, true)
                ));
        measurementsContainer = rootView.findViewById(R.id.weight_measurements_container);
        measurementsEmpty = rootView.findViewById(R.id.weight_measurements_empty);
        seeAllButton = rootView.findViewById(R.id.weight_measurements_see_all);
        seeAllButton.setOnClickListener(v -> {
            refreshOnResume = true;
            startActivity(new Intent(requireContext(), WeightMeasurementsActivity.class)
                    .putExtra(GBDevice.EXTRA_DEVICE, getChartsHost().getDevice()));
        });
        measurements = new WeightMeasurementsController(
                requireContext(),
                getChildFragmentManager(),
                this,
                getChartsHost().getDevice(),
                weightUnit,
                this::getView,
                () -> null,
                this::refresh
        );

        final TabLayout rangeTabs = rootView.findViewById(R.id.weight_range_tabs);
        for (int i = 0; i < RANGE_MONTHS.length; i++) {
            rangeTabs.addTab(rangeTabs.newTab().setText(RANGE_LABELS[i]).setTag(RANGE_MONTHS[i]), RANGE_MONTHS[i] == rangeMonths);
        }
        rangeTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(final TabLayout.Tab tab) {
                rangeMonths = (int) tab.getTag();
                GBApplication.getPrefs().getPreferences().edit().putInt(PREF_RANGE_MONTHS, rangeMonths).apply();
                refresh();
            }

            @Override
            public void onTabUnselected(final TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(final TabLayout.Tab tab) {
            }
        });

        chart.setZoomable(true);
        final View scrollView = rootView.findViewById(R.id.weight_scroll);
        chart.dismissSelectionOnTapOutside(scrollView);
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                getChartsHost().enableSwipeRefresh(scrollY == 0));

        refresh();

        return rootView;
    }

    private void updateStats(final List<? extends WeightSample> samples, @Nullable final WeightSample latestSample,
                             @Nullable final WeightSample newestSample,
                             @Nullable final BodyCompositionCalculator.BodyComposition composition,
                             @Nullable final Float bmi) {
        Float lowest = null;
        Float highest = null;
        for (final WeightSample sample : samples) {
            final float kg = sample.getWeightKg();
            if (kg <= 0) {
                continue;
            }
            lowest = lowest == null ? kg : Math.min(lowest, kg);
            highest = highest == null ? kg : Math.max(highest, kg);
        }
        final List<StatTileData> stats = new ArrayList<>();
        stats.add(new StatTileData(formatOptionalWeight(latestSample != null ? latestSample.getWeightKg() : null), getString(R.string.menuitem_weight)));
        if (weightTargetKg > 0) {
            stats.add(new StatTileData(formatOptionalWeight((float) weightTargetKg), getString(R.string.weight_goal)));
        }
        stats.add(new StatTileData(formatOptionalWeight(lowest), getString(R.string.lowest)));
        stats.add(new StatTileData(formatOptionalWeight(highest), getString(R.string.highest)));
        stats.addAll(bodyCompositionTiles(newestSample, composition, bmi));
        statsContainer.removeAllViews();
        StatTileGridUtilKt.addStatTileGrid(statsContainer, requireContext(), stats, 0);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (refreshOnResume) {
            refreshOnResume = false;
            refresh();
        }
    }

    private void updateMeasurements(final List<WeightMeasurementRow> rows, final boolean editable) {
        measurementsContainer.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (final WeightMeasurementRow row : rows.subList(0, Math.min(PREVIEW_MEASUREMENTS, rows.size()))) {
            final View view = inflater.inflate(R.layout.item_weight_measurement, measurementsContainer, false);
            measurements.bind(view, row, editable);
            measurementsContainer.addView(view);
        }
        measurementsEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        seeAllButton.setVisibility(rows.size() > PREVIEW_MEASUREMENTS ? View.VISIBLE : View.GONE);
    }

    private void saveGoal(final double kg, final boolean showLine) {
        weightTargetKg = Double.isNaN(kg) ? 0 : (int) Math.round(kg);
        showGoalLine = showLine;
        GBApplication.getPrefs().getPreferences().edit()
                .putString(ActivityUser.PREF_USER_GOAL_WEIGHT_KG, String.valueOf(weightTargetKg))
                .putBoolean(PREF_SHOW_GOAL_LINE, showGoalLine)
                .apply();
        if (weightTargetKg > 0) {
            GBApplication.deviceService().onSendConfiguration(ActivityUser.PREF_USER_GOAL_WEIGHT_KG);
        }
        refresh();
    }

    private float weightFromKg(float weight) {
        return (float) WeightUnit.Companion.convertWeight(weight, weightUnit);
    }

    private String formatWeight(float convertedWeight) {
        return WeightUnit.Companion.formatConvertedWeight(requireContext(), convertedWeight, weightUnit);
    }

    protected static class WeightChartsData extends ChartsData {
        private final List<? extends WeightSample> samples;
        private final List<? extends WeightSample> chartSamples;
        private final boolean carriedIn;
        private final List<BodyCompositionCalculator.BodyComposition> compositions;
        private final List<ImpedanceChartData.Reading> impedances;
        private final WeightSample latestSample;
        private final WeightSample latestInRange;
        private final BodyCompositionCalculator.BodyComposition composition;
        private final Float bmi;
        private final boolean manualEntry;
        private final long startTs;
        private final List<WeightMeasurementRow> measurements;

        public WeightChartsData(List<? extends WeightSample> samples,
                                List<? extends WeightSample> chartSamples,
                                boolean carriedIn,
                                List<BodyCompositionCalculator.BodyComposition> compositions,
                                List<ImpedanceChartData.Reading> impedances,
                                WeightSample latestSample, @Nullable WeightSample latestInRange,
                                @Nullable BodyCompositionCalculator.BodyComposition composition, @Nullable Float bmi,
                                boolean manualEntry, long startTs, List<WeightMeasurementRow> measurements) {
            this.samples = samples;
            this.chartSamples = chartSamples;
            this.carriedIn = carriedIn;
            this.compositions = compositions;
            this.impedances = impedances;
            this.latestSample = latestSample;
            this.latestInRange = latestInRange;
            this.composition = composition;
            this.bmi = bmi;
            this.manualEntry = manualEntry;
            this.startTs = startTs;
            this.measurements = measurements;
        }

        private boolean supportsManualEntry() {
            return manualEntry;
        }

        @Nullable
        private Float getBmi() {
            return bmi;
        }

        private WeightSample getLatestSample() {
            return latestSample;
        }

        @Nullable
        private BodyCompositionCalculator.BodyComposition getComposition() {
            return composition;
        }
    }

    @Nullable
    @Override
    public ChartDataRange getAvailableDataRange(final GBDevice device, final DBHandler db) {
        return ChartDataRange.ofSamples(device.getDeviceCoordinator().getWeightSampleProvider(device, db.getDaoSession()));
    }
}
