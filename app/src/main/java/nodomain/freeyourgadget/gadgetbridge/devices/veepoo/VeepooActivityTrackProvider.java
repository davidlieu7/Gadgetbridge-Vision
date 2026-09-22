/*  Copyright (C) 2026 Gadgetbridge contributors

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.veepoo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryTableRowEntry;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryValue;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.model.GpxActivityTrackProvider;

/**
 * Rebuilds the per-minute track of a Veepoo workout from the "per_minute" summary table that
 * {@link nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo.VeepooSportDayParser} stores
 * in the workout's summaryData at import time. The band's 0xD4 workout history stream carries one
 * record per started minute (HR, steps, distance, kcal), so this restores the native resolution
 * for the Health Connect syncer (per-minute HeartRateRecord/SpeedRecord) and the workout charts.
 *
 * <p>When the workout also has a GPX track (GPS-capable firmwares embed points in the history
 * stream), the GPX route is kept and its points are enriched with the per-minute HR/speed of the
 * minute they fall into; otherwise location-less per-minute points are returned.
 */
public class VeepooActivityTrackProvider implements ActivityTrackProvider {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooActivityTrackProvider.class);

    private static final String PER_MINUTE_HEADER_KEY = "per_minute_header";
    private static final String MINUTE_ROW_PREFIX = "minute_";
    private static final String COLUMN_HEART_RATE = "heartRate";
    private static final String COLUMN_DISTANCE_METERS = "distanceMeters";

    // Per-minute distance is a BE u16 in the 0xD4 stream; anything above this m/s (~108 km/h) is
    // a sensor glitch, not a real speed.
    private static final float MAX_SPEED_MPS = 30f;

    @Nullable
    @Override
    public ActivityTrack getActivityTrack(@NonNull final BaseActivitySummary summary) {
        final List<MinuteRow> rows = parsePerMinuteRows(summary.getSummaryData());
        if (rows.isEmpty()) {
            LOG.debug("No per-minute data in workout {} summary", summary.getId());
            return null;
        }

        final long startMs = summary.getStartTime().getTime();
        final long endMs = summary.getEndTime() != null ? summary.getEndTime().getTime() : startMs;
        rows.sort(Comparator.comparingInt(row -> row.minute));

        // Per-minute speed: the band reports per-minute distance deltas, so the speed of a row is
        // its delta divided by the minutes elapsed since the previous row (rows with zero activity
        // are omitted entirely by the parser).
        int lastMinute = 0;
        for (final MinuteRow row : rows) {
            final int elapsedMinutes = Math.max(1, row.minute - lastMinute);
            lastMinute = row.minute;
            if (row.distanceMeters > 0) {
                final float speed = row.distanceMeters / (elapsedMinutes * 60f);
                row.speed = speed > 0 && speed <= MAX_SPEED_MPS ? speed : 0;
            }
        }

        final Map<Integer, MinuteRow> rowsByMinute = new HashMap<>();
        for (final MinuteRow row : rows) {
            rowsByMinute.put(row.minute, row);
        }

        // GPS workouts: keep the route and attach the per-minute HR/speed to each GPS point.
        final ActivityTrack gpxTrack = loadGpxTrack(summary);
        if (gpxTrack != null && !gpxTrack.getAllPoints().isEmpty()) {
            int enriched = 0;
            for (final ActivityPoint point : gpxTrack.getAllPoints()) {
                final Date time = point.getTime();
                if (time == null || time.getTime() < startMs) {
                    continue;
                }
                final MinuteRow row = rowsByMinute.get((int) ((time.getTime() - startMs) / 60000L) + 1);
                if (row != null) {
                    apply(row, point);
                    enriched++;
                }
            }
            // A real track (the band's own GNSS or the phone's) gives a truer speed than the
            // band's step-estimated per-minute distance: derive it from consecutive fixes and
            // keep the per-minute value only where no valid fix pair exists.
            ActivityPoint lastLocated = null;
            for (final ActivityPoint point : gpxTrack.getAllPoints()) {
                final Date time = point.getTime();
                if (time == null || point.getLocation() == null) {
                    continue;
                }
                if (lastLocated != null) {
                    final double seconds = (time.getTime() - lastLocated.getTime().getTime()) / 1000.0;
                    if (seconds >= 1 && seconds <= 300) {
                        final double meters = GPSCoordinate.distanceHaversine(lastLocated.getLocation(), point.getLocation());
                        final float speed = (float) (meters / seconds);
                        if (speed > 0 && speed <= MAX_SPEED_MPS) {
                            point.setSpeed(speed);
                        }
                    }
                }
                lastLocated = point;
            }
            LOG.debug("Workout {}: enriched {} of {} GPS points with per-minute data",
                    summary.getId(), enriched, gpxTrack.getAllPoints().size());
            return gpxTrack;
        }

        final ActivityTrack track = new ActivityTrack();
        track.setName(StringUtils.defaultIfBlank(summary.getName(), String.valueOf(summary.getId())));
        for (final MinuteRow row : rows) {
            final long timeMs = startMs + (long) (row.minute - 1) * 60000L;
            if (timeMs < startMs - 60000L || timeMs > endMs + 60000L) {
                continue;
            }
            final ActivityPoint point = new ActivityPoint(new Date(timeMs));
            apply(row, point);
            track.addTrackPoint(point);
        }
        return track;
    }

    @Nullable
    private static ActivityTrack loadGpxTrack(@NonNull final BaseActivitySummary summary) {
        try {
            return new GpxActivityTrackProvider().getActivityTrack(summary);
        } catch (final Exception e) {
            LOG.warn("Failed to load GPX track for workout {}", summary.getId(), e);
            return null;
        }
    }

    private static void apply(final MinuteRow row, final ActivityPoint point) {
        if (row.heartRate >= 30 && row.heartRate <= 200) {
            point.setHeartRate(row.heartRate);
        }
        if (row.speed > 0) {
            point.setSpeed(row.speed);
        }
    }

    @NonNull
    private static List<MinuteRow> parsePerMinuteRows(@Nullable final String summaryDataJson) {
        final List<MinuteRow> rows = new ArrayList<>();
        if (StringUtils.isBlank(summaryDataJson)) {
            return rows;
        }

        final ActivitySummaryData data = ActivitySummaryData.fromJson(summaryDataJson);
        final Object headerEntry = data.get(PER_MINUTE_HEADER_KEY);
        if (!(headerEntry instanceof ActivitySummaryTableRowEntry header)) {
            return rows;
        }

        // The table builder prunes all-zero columns on save, so map cells by header name.
        final List<String> headers = new ArrayList<>();
        for (final ActivitySummaryValue value : header.getColumns()) {
            headers.add(String.valueOf(value.value()));
        }

        for (final String key : data.getKeys()) {
            if (!key.startsWith(MINUTE_ROW_PREFIX)) {
                continue;
            }
            final int minute;
            try {
                minute = Integer.parseInt(key.substring(MINUTE_ROW_PREFIX.length()));
            } catch (final NumberFormatException e) {
                continue;
            }
            final Object entry = data.get(key);
            if (!(entry instanceof ActivitySummaryTableRowEntry row)) {
                continue;
            }

            int heartRate = 0;
            int distanceMeters = 0;
            final List<ActivitySummaryValue> cells = row.getColumns();
            for (int i = 0; i < headers.size() && i < cells.size(); i++) {
                final Object value = cells.get(i).value();
                if (!(value instanceof Number number)) {
                    continue;
                }
                switch (headers.get(i)) {
                    case COLUMN_HEART_RATE:
                        heartRate = number.intValue();
                        break;
                    case COLUMN_DISTANCE_METERS:
                        distanceMeters = number.intValue();
                        break;
                    default:
                        break;
                }
            }
            rows.add(new MinuteRow(minute, heartRate, distanceMeters));
        }
        return rows;
    }

    private static final class MinuteRow {
        final int minute;
        final int heartRate;
        final int distanceMeters;
        float speed;

        MinuteRow(final int minute, final int heartRate, final int distanceMeters) {
            this.minute = minute;
            this.heartRate = heartRate;
            this.distanceMeters = distanceMeters;
        }
    }
}
