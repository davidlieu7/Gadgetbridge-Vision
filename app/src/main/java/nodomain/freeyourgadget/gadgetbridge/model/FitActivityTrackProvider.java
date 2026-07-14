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

        // Collect the lap messages (in order) so each track segment can carry its lap's
        // metadata — intensity + measured distance — and be tagged as a genuine lap
        // boundary (as opposed to an incidental recording break). Segment i corresponds
        // to laps.get(i): the implicit initial segment is lap 0, and every subsequent lap
        // start opens the next segment.
        final List<FitLap> laps = fitFile.getRecords().stream()
                .filter(record -> record instanceof FitLap)
                .map(record -> (FitLap) record)
                .filter(lap -> {
                    Event event = lap.getEvent();
                    if (event != null && event != Event.LAP) {
                        return false;
                    }
                    EventType eventType = lap.getEventType();
                    return (eventType == null || eventType == EventType.STOP);
                })
                .filter(lap -> lap.getStartTime() != null)
                .collect(Collectors.toList());

        // Attach lap 0's metadata to the implicit initial segment (only when the file
        // actually has laps; otherwise the single default segment is left untagged).
        if (!laps.isEmpty()) {
            activityTrack.setCurrentSegmentInfo(segmentInfoFromLap(laps.get(0)));
        }

        int lapIdx = 0; // index of the lap owning the segment currently being filled
        long nextLapStart = laps.size() > 1 ? laps.get(1).getStartTime() : Long.MAX_VALUE;
        while (records.hasNext()) {
            FitRecord record = records.next();
            if (record.getComputedTimestamp() >= nextLapStart) {
                // Advance past every lap this record has already crossed — this skips
                // empty laps that own no records — then open one segment for the lap we
                // land on, carrying that lap's metadata.
                while (lapIdx + 1 < laps.size()
                        && record.getComputedTimestamp() >= laps.get(lapIdx + 1).getStartTime()) {
                    lapIdx++;
                }
                activityTrack.startNewSegment(segmentInfoFromLap(laps.get(lapIdx)));
                nextLapStart = (lapIdx + 1 < laps.size())
                        ? laps.get(lapIdx + 1).getStartTime() : Long.MAX_VALUE;
            }
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

    /**
     * FIT record cadence counts one leg (strides/min) for step-based activities, while
     * {@link ActivityPoint} cadence is steps/min. Doubles a set cadence for those activities.
     */
    public static void normalizeStepCadence(@NonNull final ActivityPoint point, @NonNull final ActivityKind activityKind) {
        if (ActivityKind.getCycleUnit(activityKind) == ActivityKind.CycleUnit.STEPS && point.getCadence() > 0) {
            point.setCadence(point.getCadence() * 2);
        }
    }

    /** Build a lap-tagged {@link ActivityTrack.SegmentInfo} from a FIT lap message,
     *  carrying its intensity and measured total distance (when present). */
    private static ActivityTrack.SegmentInfo segmentInfoFromLap(@NonNull final FitLap lap) {
        final Double totalDistance = lap.getTotalDistance();
        final Integer distanceMeters = (totalDistance != null
                && totalDistance > 0 && totalDistance <= Integer.MAX_VALUE)
                ? (int) Math.round(totalDistance) : null;
        return new ActivityTrack.SegmentInfo(
                mapLapIntensity(lap.getIntensity()), distanceMeters, null, true);
    }

    /** Map a FIT {@code intensity_t} lap code to {@link ActivityTrack.SegmentIntensity}.
     *  FIT: 0 active, 1 rest, 2 warmup, 3 cooldown, 4 recovery, 5 interval, 6 other.
     *  Rest + recovery are recovery phases; everything else (incl. a missing code) is
     *  treated as active so it surfaces as a work lap. */
    private static ActivityTrack.SegmentIntensity mapLapIntensity(@Nullable final Integer fitIntensity) {
        if (fitIntensity == null) {
            return ActivityTrack.SegmentIntensity.ACTIVE;
        }
        switch (fitIntensity) {
            case 1: // rest
            case 4: // recovery
                return ActivityTrack.SegmentIntensity.REST;
            default:
                return ActivityTrack.SegmentIntensity.ACTIVE;
        }
    }
}
