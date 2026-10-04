/*  Copyright (C) 2015-2024 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti, Dikay900, José Rebelo, Pavel Elagin

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
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LegendEntry;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.LineData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;


public class ActivitySleepChartFragment extends AbstractActivityChartFragment<DefaultChartsData<LineData>> {
    protected static final Logger LOG = LoggerFactory.getLogger(ActivitySleepChartFragment.class);

    private LineChart mChart;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_charts, container, false);

        mChart = rootView.findViewById(R.id.activitysleepchart);

        setupChart();

        return rootView;
    }

    @Override
    public String getTitle() {
        return getString(R.string.activity_sleepchart_activity_and_sleep);
    }

    private void setupChart() {
        mChart.setBackgroundColor(BACKGROUND_COLOR);
        mChart.getDescription().setTextColor(DESCRIPTION_COLOR);
        configureBarLineChartDefaults(mChart);


        XAxis x = mChart.getXAxis();
        x.setDrawLabelsEnabled(true);
        x.setDrawGridLinesEnabled(false);
        x.setEnabled(true);
        x.setTextColor(CHART_TEXT_COLOR);
        x.setDrawLimitLinesBehindDataEnabled(true);

        YAxis y = mChart.getAxisLeft();
        y.setDrawGridLinesEnabled(false);
//        y.setDrawLabels(false);
        // TODO: make fixed max value optional
        y.setAxisMaximum(1f);
        y.setAxisMinimum(0);
        y.setDrawTopYLabelEntryEnabled(false);
        y.setTextColor(CHART_TEXT_COLOR);

//        y.setLabelCount(5);
        y.setEnabled(true);

        YAxis yAxisRight = mChart.getAxisRight();
        yAxisRight.setDrawGridLinesEnabled(false);
        yAxisRight.setEnabled(supportsHeartrate(getChartsHost().getDevice()));
        yAxisRight.setDrawLabelsEnabled(true);
        yAxisRight.setDrawTopYLabelEntryEnabled(true);
        yAxisRight.setTextColor(CHART_TEXT_COLOR);
        yAxisRight.setAxisMaximum(HeartRateUtils.getInstance().getMaxHeartRate());
        yAxisRight.setAxisMinimum(HeartRateUtils.getInstance().getMinHeartRate());

        // refresh immediately instead of use refreshIfVisible(), for perceived performance
        refresh();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ChartsHost.REFRESH.equals(action)) {
            refresh();
        } else {
            super.onReceive(context, intent);
        }
    }

    @Override
    protected DefaultChartsData<LineData> refreshInBackground(ChartsHost chartsHost, DBHandler db, GBDevice device) {
        List<? extends ActivitySample> samples = getSamples(db, device);
        List<? extends ActivitySample> highResSamples = getSamplesHighRes(db, device);
        if (highResSamples == null)
            return refresh(device, samples);
        return refresh(device, samples, highResSamples);
    }

    @Override
    protected void updateChartsnUIThread(DefaultChartsData<LineData> dcd) {
        mChart.getLegend().setTextColor(LEGEND_TEXT_COLOR);
        mChart.setData(null); // workaround for https://github.com/PhilJay/MPAndroidChart/issues/2317
        mChart.getXAxis().setValueFormatter(dcd.getXValueFormatter());
        mChart.setData(dcd.getData());
    }

    @Override
    protected void renderCharts() {
        mChart.animateX(ANIM_TIME, Easing.INSTANCE.getEaseInOutQuart());
//        mChart.invalidate();
    }

    @Override
    protected void setupLegend(Chart<?> chart) {
        List<LegendEntry> legendEntries = new ArrayList<>(5);

        LegendEntry activityEntry = new LegendEntry();
        activityEntry.setLabel(akActivity.label);
        activityEntry.setFormColor(akActivity.color);
        legendEntries.add(activityEntry);

        LegendEntry lightSleepEntry = new LegendEntry();
        lightSleepEntry.setLabel(akLightSleep.label);
        lightSleepEntry.setFormColor(akLightSleep.color);
        legendEntries.add(lightSleepEntry);

        LegendEntry deepSleepEntry = new LegendEntry();
        deepSleepEntry.setLabel(akDeepSleep.label);
        deepSleepEntry.setFormColor(akDeepSleep.color);
        legendEntries.add(deepSleepEntry);

        if (supportsRemSleep(getChartsHost().getDevice())) {
            LegendEntry remSleepEntry = new LegendEntry();
            remSleepEntry.setLabel(akRemSleep.label);
            remSleepEntry.setFormColor(akRemSleep.color);
            legendEntries.add(remSleepEntry);
        }

        LegendEntry notWornEntry = new LegendEntry();
        notWornEntry.setLabel(akNotWorn.label);
        notWornEntry.setFormColor(akNotWorn.color);
        legendEntries.add(notWornEntry);

        if (supportsHeartrate(getChartsHost().getDevice())) {
            LegendEntry hrEntry = new LegendEntry();
            hrEntry.setLabel(HEARTRATE_LABEL);
            hrEntry.setFormColor(HEARTRATE_COLOR);
            legendEntries.add(hrEntry);
        }
        chart.getLegend().setEntries(legendEntries);
        chart.getLegend().setWordWrapEnabled(true);
        chart.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
    }

    @Override
    protected List<? extends ActivitySample> getSamples(DBHandler db, GBDevice device, int tsFrom, int tsTo) {
        return getAllSamples(db, device, tsFrom, tsTo);
    }

    @Override
    protected List<? extends ActivitySample> getSamplesHighRes(DBHandler db, GBDevice device, int tsFrom, int tsTo) {
        return getAllSamplesHighRes(db, device, tsFrom, tsTo);
    }
}
