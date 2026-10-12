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

import android.graphics.Color;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.Chart;

import java.text.DecimalFormat;
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

public class WeightChartFragment extends AbstractChartFragment<WeightChartFragment.WeightChartsData> {
    private int totalDays;
    private WeightUnit weightUnit = WeightUnit.KILOGRAM;
    private int weightTargetKg;

    private GbChartView chart;
    private ChartLegendView legend;
    private GbChartView impedanceChart;
    private ChartLegendView impedanceLegend;
    private TextView textTimeSpan;
    private TextView textWeightLatest;
    private static final String PREF_BODY_COMPOSITION_VALUES = "chart_weight_body_composition";

    private TextView textWeightTarget;
    private TextView textBmi;
    private TextView textBodyFat;
    private TextView textBodyWater;
    private TextView textMuscleMass;
    private TextView textBoneMass;
    private TextView textBmr;
    private TextView textImpedance;

    @Override
    public String getTitle() {
        return getString(R.string.menuitem_weight);
    }

    @Override
    protected void init() {
        GBPrefs prefs = GBApplication.getPrefs();

        if (prefs.getBoolean("charts_range", true))
            totalDays = 30;
        else
            totalDays = 7;

        weightUnit = prefs.getWeightUnit();

        weightTargetKg = prefs.getInt(ActivityUser.PREF_USER_GOAL_WEIGHT_KG, ActivityUser.defaultUserGoalWeightKg);
    }

    @Override
    protected boolean isSingleDay() {
        return false;
    }

    @Override
    protected WeightChartsData refreshInBackground(ChartsHost chartsHost, DBHandler db, GBDevice device) {
        long tsStart = getTSStart() * 1000L;
        long tsEnd = getTSEnd() * 1000L;

        DeviceCoordinator coordinator = device.getDeviceCoordinator();
        TimeSampleProvider<? extends WeightSample> provider = coordinator.getWeightSampleProvider(device, db.getDaoSession());
        List<? extends WeightSample> samples = provider.getAllSamples(tsStart, tsEnd);
        WeightSample latestSample = provider.getLatestSample();
        BodyCompositionCalculator.BodyComposition composition = BodyCompositionEstimates.composition(db.getDaoSession(), latestSample);
        Float bmi = BodyCompositionEstimates.bmi(db.getDaoSession(), latestSample);
        List<BodyCompositionCalculator.BodyComposition> compositions = new ArrayList<>(samples.size());
        for (WeightSample sample : samples) {
            compositions.add(BodyCompositionEstimates.composition(db.getDaoSession(), sample));
        }
        return new WeightChartsData(samples, compositions, impedances(db, device, samples, tsStart, tsEnd),
                latestSample, composition, bmi);
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
        updateChart(chartsData.samples, chartsData.compositions);
        updateImpedanceChart(chartsData.impedances);
        textTimeSpan.setText(DateTimeUtils.formatDaysUntil(totalDays, getTSEnd()));

        WeightSample latestSample = chartsData.getLatestSample();
        if (latestSample != null)
            textWeightLatest.setText(formatWeight(weightFromKg(latestSample.getWeightKg())));

        textWeightTarget.setText(formatWeight(weightFromKg(weightTargetKg)));
        updateBodyComposition(latestSample, chartsData.getComposition(), chartsData.getBmi());
    }

    private void updateChart(final List<? extends WeightSample> samples,
                             final List<BodyCompositionCalculator.BodyComposition> compositions) {
        final int n = samples.size();
        final long[] seconds = new long[n];
        final double[] weights = new double[n];
        final double[] bodyFat = new double[n];
        final double[] bodyWater = new double[n];
        final double[] muscle = new double[n];
        for (int i = 0; i < n; i++) {
            final WeightSample sample = samples.get(i);
            seconds[i] = sample.getTimestamp() / 1000L;
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
        final ChartSpec spec = WeightChartData.spec(
                getTSStart(), getTSEnd(), seconds, weights, weightFromKg(weightTargetKg), label, color, Color.GRAY,
                percentLines, getString(R.string.unit_percentage)
        );
        chart.setSelectionContent(x -> {
            final long time = Math.round(x);
            final String title = DateFormat.getDateFormat(requireContext()).format(new Date(time * 1000L));
            for (int i = 0; i < n; i++) {
                if (seconds[i] == time && weights[i] > 0) {
                    final String text = formatWeight((float) weights[i]);
                    final List<ChartSelection.Row> rows = new ArrayList<>();
                    rows.add(new ChartSelection.Row(color, text));
                    final StringBuilder description = new StringBuilder(title).append(". ")
                            .append(label).append(' ').append(text).append('.');
                    for (final WeightChartData.PercentLine line : percentLines) {
                        if (Double.isNaN(line.getValues()[i])) {
                            continue;
                        }
                        final String value = formatPercent((float) line.getValues()[i]);
                        rows.add(new ChartSelection.Row(line.getColor(), value));
                        description.append(' ').append(line.getLabel()).append(' ').append(value).append('.');
                    }
                    return new ChartSelection(title, rows, description.toString());
                }
            }
            return new ChartSelection(title, Collections.emptyList(), title + ".");
        });
        chart.setSpec(spec);

        final List<ChartSeries> legendSeries = new ArrayList<>(spec.getSeries());
        if (!spec.getSeries().isEmpty() && !spec.getLimitLines().isEmpty()) {
            legendSeries.add(ChartLegendView.lineItem(getString(R.string.target), Color.GRAY));
        }
        legend.setSeries(legendSeries.size() > 1 ? legendSeries : Collections.emptyList());
    }

    private void updateImpedanceChart(final List<ImpedanceChartData.Reading> readings) {
        final List<ImpedanceChartData.Group> groups = ImpedanceChartData.groups(readings);
        final boolean show = !groups.isEmpty() && enabledBodyCompositionValues().contains("impedance");
        impedanceChart.setVisibility(show ? View.VISIBLE : View.GONE);
        impedanceLegend.setVisibility(show && groups.size() > 1 ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        final boolean oneSection = groups.get(0).getSection() == groups.get(groups.size() - 1).getSection();
        final List<String> labels = new ArrayList<>(groups.size());
        final int[] colors = new int[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            labels.add(impedanceLabel(groups.get(i), groups.size() == 1, oneSection));
            colors[i] = groups.size() == 1
                    ? ContextCompat.getColor(requireContext(), R.color.chart_weight_impedance)
                    : Color.HSVToColor(new float[]{360f * i / groups.size(), 0.6f, 0.85f});
        }
        final ChartSpec spec = ImpedanceChartData.spec(getTSStart(), getTSEnd(), readings, groups, labels, colors, getString(R.string.unit_ohm));
        impedanceChart.setSelectionContent(x -> {
            final long time = Math.round(x);
            final String title = DateFormat.getDateFormat(requireContext()).format(new Date(time * 1000L));
            final List<ChartSelection.Row> rows = new ArrayList<>();
            final StringBuilder description = new StringBuilder(title).append('.');
            for (int i = 0; i < groups.size(); i++) {
                for (final ImpedanceChartData.Reading reading : readings) {
                    if (reading.getSeconds() == time && reading.getSection() == groups.get(i).getSection()
                            && reading.getFrequencyHz() == groups.get(i).getFrequencyHz()) {
                        final String value = getString(R.string.body_composition_ohm, Math.round(reading.getOhm()));
                        rows.add(new ChartSelection.Row(colors[i], groups.size() == 1 ? value : labels.get(i) + ": " + value));
                        description.append(' ').append(labels.get(i)).append(' ').append(value).append('.');
                    }
                }
            }
            return new ChartSelection(title, rows, description.toString());
        });
        impedanceChart.setSpec(spec);
        impedanceLegend.setSeries(groups.size() > 1 ? spec.getSeries() : Collections.emptyList());
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

    private void updateBodyComposition(@Nullable final WeightSample sample,
                                       @Nullable final BodyCompositionCalculator.BodyComposition composition,
                                       @Nullable final Float bmi) {
        final Set<String> enabled = enabledBodyCompositionValues();
        // BMI only needs the weight and the height, so it does not depend on the impedance below.
        final boolean showBmi = bmi != null && enabled.contains("bmi");
        showTile(textBmi, showBmi, showBmi ? getString(R.string.body_composition_bmi_value, bmi) : getString(R.string.stats_empty_value));
        // Without an impedance there is nothing to derive; the values are hidden individually so
        // the remaining ones close ranks in the grid.
        final boolean hasImpedance = sample != null && sample.getImpedanceOhm() != null;
        if (!hasImpedance) {
            for (final TextView valueView : new TextView[]{textBodyFat, textBodyWater, textMuscleMass, textBoneMass, textBmr, textImpedance}) {
                showTile(valueView, false, getString(R.string.stats_empty_value));
            }
            return;
        }
        showTile(textBodyFat, enabled.contains("body_fat"), formatPercent(composition != null ? composition.bodyFatPercent : null));
        showTile(textBodyWater, enabled.contains("body_water"), formatPercent(composition != null ? composition.bodyWaterPercent : null));
        showTile(textMuscleMass, enabled.contains("muscle_mass"), formatOptionalWeight(composition != null ? composition.muscleMassKg : null));
        showTile(textBoneMass, enabled.contains("bone_mass"), formatOptionalWeight(composition != null ? composition.boneMassKg : null));
        showTile(textBmr, enabled.contains("bmr"), composition != null
                ? getString(R.string.body_composition_kcal, composition.basalMetabolicRate)
                : getString(R.string.stats_empty_value));
        showTile(textImpedance, enabled.contains("impedance"), getString(R.string.body_composition_ohm, sample.getImpedanceOhm()));
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

    /**
     * Shows or hides a whole tile (the parent layout holding the line, value and label).
     */
    private void showTile(final TextView valueView, final boolean enabled, final String value) {
        valueView.setText(value);
        ((View) valueView.getParent()).setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private String formatPercent(final Float value) {
        return value != null ? getString(R.string.body_composition_percent, value) : getString(R.string.stats_empty_value);
    }

    private String formatOptionalWeight(final Float kg) {
        return kg != null ? formatWeight(weightFromKg(kg)) : getString(R.string.stats_empty_value);
    }

    @Override
    protected int getTSStart() {
        return DateTimeUtils.shiftDays(getTSEnd(), -totalDays + 1);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_weightchart, container, false);

        chart = rootView.findViewById(R.id.weight_chart);
        legend = rootView.findViewById(R.id.weight_chart_legend);
        impedanceChart = rootView.findViewById(R.id.weight_impedance_chart);
        impedanceLegend = rootView.findViewById(R.id.weight_impedance_legend);
        textTimeSpan = rootView.findViewById(R.id.weight_time_span_text);
        textWeightLatest = rootView.findViewById(R.id.weight_latest_text);
        textWeightTarget = rootView.findViewById(R.id.weight_target_text);
        textBmi = rootView.findViewById(R.id.weight_bmi_text);
        textBodyFat = rootView.findViewById(R.id.weight_body_fat_text);
        textBodyWater = rootView.findViewById(R.id.weight_body_water_text);
        textMuscleMass = rootView.findViewById(R.id.weight_muscle_mass_text);
        textBoneMass = rootView.findViewById(R.id.weight_bone_mass_text);
        textBmr = rootView.findViewById(R.id.weight_bmr_text);
        textImpedance = rootView.findViewById(R.id.weight_impedance_text);

        chart.setZoomable(true);
        chart.dismissSelectionOnTapOutside(rootView);
        impedanceChart.dismissSelectionOnTapOutside(rootView);

        refresh();

        return rootView;
    }

    private float weightFromKg(float weight) {
        return (float) WeightUnit.Companion.convertWeight(weight, weightUnit);
    }

    private String formatWeight(float convertedWeight) {
        return WeightUnit.Companion.formatConvertedWeight(requireContext(), convertedWeight, weightUnit);
    }

    protected static class WeightChartsData extends ChartsData {
        private final List<? extends WeightSample> samples;
        private final List<BodyCompositionCalculator.BodyComposition> compositions;
        private final List<ImpedanceChartData.Reading> impedances;
        private final WeightSample latestSample;
        private final BodyCompositionCalculator.BodyComposition composition;
        private final Float bmi;

        public WeightChartsData(List<? extends WeightSample> samples,
                                List<BodyCompositionCalculator.BodyComposition> compositions,
                                List<ImpedanceChartData.Reading> impedances,
                                WeightSample latestSample,
                                @Nullable BodyCompositionCalculator.BodyComposition composition, @Nullable Float bmi) {
            this.samples = samples;
            this.compositions = compositions;
            this.impedances = impedances;
            this.latestSample = latestSample;
            this.composition = composition;
            this.bmi = bmi;
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
