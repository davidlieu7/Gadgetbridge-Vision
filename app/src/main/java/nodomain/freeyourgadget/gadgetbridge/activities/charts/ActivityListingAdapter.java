/*  Copyright (C) 2020-2024 José Rebelo, Petr Vaněk

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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.ActivityGauge;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GaugeDrawer;
import nodomain.freeyourgadget.gadgetbridge.adapter.AbstractActivityListingAdapter;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityListItem;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySession;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.FormatUtils;

public class ActivityListingAdapter extends AbstractActivityListingAdapter<ActivitySession> {
    protected static final Logger LOG = LoggerFactory.getLogger(ActivityListingAdapter.class);
    private final GaugeDrawer gaugeDrawer = new GaugeDrawer();
    ActivityUser activityUser = new ActivityUser();
    int stepsGoal = activityUser.getStepsGoal();
    int distanceGoalMeters = activityUser.getDistanceGoalMeters();
    long activeTimeGoalTimeMillis = activityUser.getActiveTimeGoalMinutes() * 60 * 1000L;

    public ActivityListingAdapter(Context context) {
        super(context);
    }

    @NonNull
    @Override
    public AbstractActivityListingViewHolder<ActivitySession> onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        return switch (viewType) {
            case 0 -> // dashboard
                new DashboardViewHolder(LayoutInflater.from(getContext()).inflate(R.layout.activity_list_dashboard_item, parent, false));
            case 2 -> // item
                new ActivityItemViewHolder(LayoutInflater.from(getContext()).inflate(R.layout.activity_list_item, parent, false));
            default -> super.onCreateViewHolder(parent, viewType);
        };
    }

    private static String formatPercent(final float value, final float target) {
        return String.format(Locale.getDefault(), "%d%%", (int) (value * 100 / target));
    }

    public static class ActivityItemViewHolder extends AbstractActivityListingViewHolder<ActivitySession> {
        final View rootView;
        final ActivityListItem activityListItem;

        public ActivityItemViewHolder(@NonNull final View itemView) {
            super(itemView);
            this.rootView = itemView;
            this.activityListItem = new ActivityListItem(itemView);
        }

        @Override
        public void fill(final int position, final ActivitySession session, final boolean selected) {
            this.activityListItem.update(
                session.getStartTime(),
                session.getEndTime(),
                session.getActivityKind(),
                null,
                session.getActiveSteps(),
                session.getDistance(),
                session.getHeartRateAverage(),
                session.getIntensity(),
                session.getEndTime().getTime() - session.getStartTime().getTime(),
                false,
                false,
                null,
                null,
                position % 2 == 1,
                selected
            );
        }
    }

    public class DashboardViewHolder extends AbstractActivityListingViewHolder<ActivitySession> {
        final ActivityGauge ActiveStepsChart;
        final ActivityGauge DistanceChart;
        final ActivityGauge ActiveTimeChart;
        final TextView stepLabel;
        final TextView stepTotalLabel;
        final TextView distanceLabel;
        final TextView hrLabel;
        final TextView intensityLabel;
        final TextView intensity2Label;
        final TextView durationLabel;
        final TextView sessionCountLabel;
        final LinearLayout durationLayout;
        final LinearLayout countLayout;
        final View hrLayout;
        final LinearLayout stepsLayout;
        final LinearLayout stepsTotalLayout;
        final LinearLayout distanceLayout;
        final View intensityLayout;
        final View intensity2Layout;

        public DashboardViewHolder(@NonNull final View itemView) {
            super(itemView);

            ActiveStepsChart = new ActivityGauge(itemView.findViewById(R.id.activity_dashboard_gauge1));
            DistanceChart = new ActivityGauge(itemView.findViewById(R.id.activity_dashboard_gauge2));
            ActiveTimeChart = new ActivityGauge(itemView.findViewById(R.id.activity_dashboard_gauge3));
            stepLabel = itemView.findViewById(R.id.line_layout_step_label);
            stepTotalLabel = itemView.findViewById(R.id.line_layout_total_step_label);
            distanceLabel = itemView.findViewById(R.id.line_layout_distance_label);
            hrLabel = itemView.findViewById(R.id.heartrate_widget_label);
            intensityLabel = itemView.findViewById(R.id.intensity_widget_label);
            intensity2Label = itemView.findViewById(R.id.line_layout_intensity2_label);
            durationLabel = itemView.findViewById(R.id.line_layout_duration_label);
            sessionCountLabel = itemView.findViewById(R.id.line_layout_count_label);
            durationLayout = itemView.findViewById(R.id.line_layout_duration);
            countLayout = itemView.findViewById(R.id.line_layout_count);
            hrLayout = itemView.findViewById(R.id.heartrate_widget_icon);
            stepsLayout = itemView.findViewById(R.id.line_layout_step);
            stepsTotalLayout = itemView.findViewById(R.id.line_layout_total_step);
            distanceLayout = itemView.findViewById(R.id.line_layout_distance);
            intensityLayout = itemView.findViewById(R.id.intensity_widget_icon);
            intensity2Layout = itemView.findViewById(R.id.line_layout_intensity2);
        }

        @Override
        public void fill(final int position, final ActivitySession session, final boolean selected) {
            final int steps = session.getActiveSteps();
            ActiveStepsChart.update(gaugeDrawer, steps, stepsGoal, formatPercent(steps, stepsGoal), getContext().getString(R.string.activity_list_summary_active_steps));

            final float distance = session.getDistance();
            DistanceChart.update(gaugeDrawer, distance, distanceGoalMeters, formatPercent(distance, distanceGoalMeters), getContext().getString(R.string.distance));

            final long duration = session.getEndTime().getTime() - session.getStartTime().getTime();
            ActiveTimeChart.update(gaugeDrawer, duration, activeTimeGoalTimeMillis, formatPercent(duration, activeTimeGoalTimeMillis), getContext().getString(R.string.activity_list_summary_active_time));

            durationLabel.setText(DateTimeUtils.formatDurationHoursMinutes(duration, TimeUnit.MILLISECONDS));
            sessionCountLabel.setText(String.valueOf(session.getSessionCount()));

            if (session.getHeartRateAverage() > 0) {
                hrLabel.setText(String.valueOf(session.getHeartRateAverage()));
                hrLayout.setVisibility(View.VISIBLE);
                hrLabel.setVisibility(View.VISIBLE);
            } else {
                hrLayout.setVisibility(View.GONE);
                hrLabel.setVisibility(View.GONE);
            }

            if (session.getIntensity() > 0) {
                final DecimalFormat df = new DecimalFormat("###");
                intensityLabel.setText(df.format(session.getIntensity()));
                intensity2Label.setText(df.format(session.getIntensity()));
                intensityLayout.setVisibility(View.VISIBLE);
                intensity2Layout.setVisibility(View.VISIBLE);
                intensityLabel.setVisibility(View.VISIBLE);
                intensity2Label.setVisibility(View.VISIBLE);
            } else {
                intensityLayout.setVisibility(View.GONE);
                intensity2Layout.setVisibility(View.GONE);
                intensityLabel.setVisibility(View.GONE);
                intensity2Label.setVisibility(View.GONE);
            }

            if (session.getDistance() > 0) {
                distanceLabel.setText(FormatUtils.getFormattedDistanceLabel(session.getDistance()));
                distanceLayout.setVisibility(View.VISIBLE);
            } else {
                distanceLayout.setVisibility(View.GONE);
            }

            if (session.getActiveSteps() > 0) {
                stepLabel.setText(String.valueOf(session.getActiveSteps()));
                stepsLayout.setVisibility(View.VISIBLE);
            } else {
                stepsLayout.setVisibility(View.GONE);
            }

            if (session.getTotalDaySteps() > 0) {
                stepTotalLabel.setText(String.valueOf(session.getTotalDaySteps()));
                stepsTotalLayout.setVisibility(View.VISIBLE);
                countLayout.setVisibility(View.VISIBLE);
                durationLayout.setVisibility(View.VISIBLE);
            } else {
                stepsTotalLayout.setVisibility(View.GONE);
                countLayout.setVisibility(View.GONE);
                durationLayout.setVisibility(View.GONE);
            }
        }
    }
}
