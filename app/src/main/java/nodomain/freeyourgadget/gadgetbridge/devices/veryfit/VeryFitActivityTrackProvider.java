/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.devices.veryfit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.VeryFitWorkoutGpsSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeryFitWorkoutGpsSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;

/** The fixes stored for a workout, with whatever heart rate was measured alongside them. */
public class VeryFitActivityTrackProvider implements ActivityTrackProvider {
    private static final Logger LOG = LoggerFactory.getLogger(VeryFitActivityTrackProvider.class);

    private final GBDevice device;

    public VeryFitActivityTrackProvider(final GBDevice device) {
        this.device = device;
    }

    @Nullable
    @Override
    public ActivityTrack getActivityTrack(@NonNull final BaseActivitySummary summary) {
        final ActivityTrack track = new ActivityTrack();
        track.setUser(summary.getUser());
        track.setDevice(summary.getDevice());
        track.setName(summary.getName());

        final long from = summary.getStartTime().getTime();
        final long to = summary.getEndTime().getTime();

        // The chart asking for the track holds the database already, so take it the same way.
        try (DBHandler handler = GBApplication.acquireDbReadOnly()) {
            final DaoSession session = handler.getDaoSession();
            final List<VeryFitWorkoutGpsSample> fixes =
                    new VeryFitWorkoutGpsSampleProvider(device, session).getAllSamples(from, to);
            final List<GenericHeartRateSample> heartRates =
                    new GenericHeartRateSampleProvider(device, session).getAllSamples(from, to);

            for (final VeryFitWorkoutGpsSample fix : fixes) {
                final ActivityPoint point = new ActivityPoint(new Date(fix.getTimestamp()));
                point.setLocation(new GPSCoordinate(
                        fix.getLongitude() / 10000000d, fix.getLatitude() / 10000000d));

                final GenericHeartRateSample heartRate = nearest(heartRates, fix.getTimestamp());
                if (heartRate != null) {
                    point.setHeartRate(heartRate.getHeartRate());
                }
                track.addTrackPoint(point);
            }
        } catch (final Exception e) {
            LOG.error("Failed to build the track for {}", summary.getStartTime(), e);
            return null;
        }

        return track;
    }

    @Nullable
    private static GenericHeartRateSample nearest(final List<GenericHeartRateSample> samples,
                                                  final long timestamp) {
        GenericHeartRateSample nearest = null;
        long best = Long.MAX_VALUE;
        for (final GenericHeartRateSample sample : samples) {
            final long distance = Math.abs(sample.getTimestamp() - timestamp);
            if (distance < best) {
                best = distance;
                nearest = sample;
            }
        }
        return nearest;
    }
}
