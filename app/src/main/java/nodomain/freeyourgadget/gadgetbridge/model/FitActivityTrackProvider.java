/*  Copyright (C) 2026 José Rebelo, Thomas Kuehne

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
package nodomain.freeyourgadget.gadgetbridge.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.Event;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.EventType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.exception.FitParseException;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitLap;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitLength;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitSet;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitSplit;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;

public class FitActivityTrackProvider implements ActivityTrackProvider {
    private static final Logger LOG = LoggerFactory.getLogger(FitActivityTrackProvider.class);

    @Nullable
    @Override
    public ActivityTrack getActivityTrack(@NonNull final BaseActivitySummary summary) {
        final File file = FileUtils.tryFixPath(summary.getRawDetailsPath());
        if (file == null) {
            LOG.debug("Fit file no found in {}", summary.getRawDetailsPath());
            return null;
        }

        LOG.debug("Loading activity track from {}", file);

        final FitFile fitFile;
        try {
            fitFile = FitFile.parseIncoming(file);
        } catch (final IOException e) {
            LOG.error("Failed to read fit file", e);
            return null;
        } catch (final FitParseException e) {
            LOG.error("Failed to parse fit file", e);
            return null;
        }
        return getActivityTrack(summary, fitFile);
    }

    @Nullable
    public ActivityTrack getActivityTrack(@NonNull final BaseActivitySummary summary, @NonNull final FitFile fitFile) {
        final ActivityTrack activityTrack = new ActivityTrack();
        activityTrack.setName(summary.getName());
        final ActivityKind activityKind = ActivityKind.fromCode(summary.getActivityKind());

        final Iterator<FitRecord> records = fitFile.getRecords().stream()
                .filter(r -> r instanceof FitRecord)
                .map(r -> (FitRecord) r)
                .iterator();

        final List<FitLap> laps = fitFile.getRecords().stream()
                .filter(record -> record instanceof FitLap)
                .map(record -> (FitLap) record)
                .filter(FitActivityTrackProvider::isLapBoundary)
                .filter(lap -> lap.getStartTime() != null)
                .sorted(Comparator.comparingLong(FitLap::getStartTime))
                .collect(Collectors.toList());
        final boolean hasRestLaps = laps.stream()
                .anyMatch(lap -> intensityOf(lap) == ActivityTrack.SegmentIntensity.REST);

        if (!laps.isEmpty()) {
            activityTrack.setCurrentSegmentInfo(segmentInfoOf(laps.get(0), hasRestLaps));
        }
        int nextLap = 1;
        boolean segmentHasPoints = false;
        while (records.hasNext()) {
            FitRecord record = records.next();
            final Long timestamp = record.getComputedTimestamp();
            if (timestamp == null) {
                continue;
            }
            FitLap startedLap = null;
            while (nextLap < laps.size() && timestamp >= laps.get(nextLap).getStartTime()) {
                startedLap = laps.get(nextLap++);
            }
            if (startedLap != null) {
                if (segmentHasPoints) {
                    activityTrack.startNewSegment(segmentInfoOf(startedLap, hasRestLaps));
                } else {
                    activityTrack.setCurrentSegmentInfo(segmentInfoOf(startedLap, hasRestLaps));
                }
            }
            segmentHasPoints = true;
            final ActivityPoint point = record.toActivityPoint();
            normalizeStepCadence(point, activityKind);
            activityTrack.addTrackPoint(point);
        }

        // Per-length / per-split / per-set metadata — kept on the track so the
        // exporter can re-emit them on round-trip. Garmin Connect, Strava and
        // Endurain all surface these in their detail views.
        for (final var rd : fitFile.getRecords()) {
            if (rd instanceof FitLength len) {
                final Long startTime = len.getStartTime();
                if (startTime == null) continue;
                final Double elapsedSec = len.getTotalElapsedTime();
                final Double timerSec = len.getTotalTimerTime();
                activityTrack.addLength(new ActivityTrack.LengthInfo(
                        startTime,
                        elapsedSec != null ? elapsedSec : 0.0,
                        timerSec != null ? timerSec : 0.0,
                        len.getTotalStrokes(),
                        len.getAvgSpeed(),
                        len.getSwimStroke(),
                        len.getLengthType(),
                        len.getAvgSwimmingCadence()));
            } else if (rd instanceof FitSplit sp) {
                final Long startTime = sp.getStartTime();
                if (startTime == null) continue;
                activityTrack.addSplit(new ActivityTrack.SplitInfo(
                        startTime,
                        sp.getEndTime(),
                        sp.getSplitType(),
                        sp.getTotalElapsedTime(),
                        sp.getTotalTimerTime(),
                        sp.getTotalDistance(),
                        sp.getAvgSpeed(),
                        sp.getMaxSpeed(),
                        sp.getTotalAscent(),
                        sp.getTotalDescent(),
                        sp.getTotalCalories(),
                        sp.getStartElevation(),
                        sp.getStartPositionLat(),
                        sp.getStartPositionLong(),
                        sp.getEndPositionLat(),
                        sp.getEndPositionLong()));
            } else if (rd instanceof FitSet set) {
                final Long startTime = set.getStartTime();
                if (startTime == null) continue;
                activityTrack.addSet(new ActivityTrack.SetInfo(
                        startTime,
                        set.getDuration(),
                        set.getRepetitions(),
                        set.getWeight(),
                        set.getSetType(),
                        set.getWeightDisplayUnit(),
                        set.getMessageIndex()));
            }
        }

        return activityTrack;
    }

    private static boolean isLapBoundary(@NonNull final FitLap lap) {
        final Event event = lap.getEvent();
        if (event != null && event != Event.LAP) {
            return false;
        }
        final EventType eventType = lap.getEventType();
        return eventType == null || eventType == EventType.STOP;
    }

    /**
     * Maps the FIT {@code lap.intensity} enum: active 0, rest 1, warmup 2, cooldown 3,
     * recovery 4, interval 5, other 6, main 7.
     */
    @NonNull
    static ActivityTrack.SegmentIntensity intensityOf(@NonNull final FitLap lap) {
        final Integer intensity = lap.getIntensity();
        if (intensity == null) {
            return ActivityTrack.SegmentIntensity.UNKNOWN;
        }
        return switch (intensity) {
            case 0, 5, 7 -> ActivityTrack.SegmentIntensity.ACTIVE;
            case 1, 4 -> ActivityTrack.SegmentIntensity.REST;
            case 2 -> ActivityTrack.SegmentIntensity.WARMUP;
            case 3 -> ActivityTrack.SegmentIntensity.COOLDOWN;
            default -> ActivityTrack.SegmentIntensity.UNKNOWN;
        };
    }

    /**
     * Devices write intensity active on every lap of a workout without rest laps, auto-laps
     * included, so an active lap only marks an interval when some lap is a rest.
     */
    @NonNull
    private static ActivityTrack.SegmentInfo segmentInfoOf(@NonNull final FitLap lap, final boolean hasRestLaps) {
        final ActivityTrack.SegmentIntensity intensity = intensityOf(lap);
        final Double distance = lap.getTotalDistance();
        return new ActivityTrack.SegmentInfo(
                intensity == ActivityTrack.SegmentIntensity.ACTIVE && !hasRestLaps
                        ? ActivityTrack.SegmentIntensity.UNKNOWN : intensity,
                distance != null ? (int) Math.round(distance) : null,
                null);
    }

    /**
     * FIT record cadence counts one leg (strides/min) for step-based activities, while
     * {@link ActivityPoint} cadence is steps/min. Doubles a set cadence for those activities.
     */
    public static void normalizeStepCadence(@NonNull final ActivityPoint point, @NonNull final ActivityKind activityKind) {
        if (ActivityKind.getCycleUnit(activityKind) == ActivityKind.CycleUnit.STEPS && point.getCadence() > 0) {
            point.setCadence(point.getCadence() * 2);
        }
    }
}
