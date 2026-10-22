package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.ZoneId;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;

/** A training session of the watch (E/&lt;time&gt;/), as shown in the workout list. */
public final class PolarWorkouts {
    private PolarWorkouts() {
    }

    public static final class Workout {
        public final long startMillis;
        public final long endMillis;
        public final ActivityKind kind;
        public final String summaryJson;
        /** The serialized {@code PbExerciseSamples}, from which the details are built. */
        public final byte[] samples;

        Workout(final long startMillis, final long endMillis, final ActivityKind kind, final String summaryJson,
                final byte[] samples) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.kind = kind;
            this.summaryJson = summaryJson;
            this.samples = samples;
        }
    }

    /** Polar's sport ids; the watch has many more, those without a counterpart are plain exercise. */
    static ActivityKind kind(final long sport) {
        if (sport == 1) {
            return ActivityKind.RUNNING;
        } else if (sport == 2) {
            return ActivityKind.CYCLING;
        } else if (sport == 3) {
            return ActivityKind.WALKING;
        } else if (sport == 15) {
            return ActivityKind.STRENGTH_TRAINING;
        }
        return ActivityKind.EXERCISE;
    }

    private static long seconds(final PolarProto.PbDuration duration) {
        return duration.getHours() * 3600L + duration.getMinutes() * 60L + duration.getSeconds();
    }

    @Nullable
    public static Workout parse(@NonNull final PolarProto.PbTrainingSession session,
                                @Nullable final PolarProto.PbExerciseBase base, @Nullable final byte[] samples) {
        if (!session.hasStart() || !session.getStart().hasDate()) {
            return null;
        }
        final ZoneId zone = PolarHealthData.zone(session.getStart());
        final long start = PolarHealthData.epochMillis(session.getStart(), zone);
        final long durationSeconds = seconds(session.getDuration());
        final long end = session.hasEnd() && session.getEnd().hasDate()
                ? PolarHealthData.epochMillis(session.getEnd(), PolarHealthData.zone(session.getEnd()))
                : start + durationSeconds * 1000L;
        final long sport = session.hasSport() ? session.getSport().getValue()
                : base != null && base.hasSport() ? base.getSport().getValue() : 0;

        final ActivitySummaryData data = new ActivitySummaryData();
        data.add(ActivitySummaryEntries.ACTIVE_SECONDS, durationSeconds, ActivitySummaryEntries.UNIT_SECONDS);
        if (session.hasCalories()) {
            data.add(ActivitySummaryEntries.CALORIES_BURNT, session.getCalories(), ActivitySummaryEntries.UNIT_KCAL);
        }
        if (session.getDistance() > 0) {
            data.add(ActivitySummaryEntries.DISTANCE_METERS, session.getDistance(), ActivitySummaryEntries.UNIT_METERS);
        }
        if (session.hasHeartRate()) {
            data.add(ActivitySummaryEntries.HR_AVG, session.getHeartRate().getAverage(), ActivitySummaryEntries.UNIT_BPM);
            data.add(ActivitySummaryEntries.HR_MAX, session.getHeartRate().getMaximum(), ActivitySummaryEntries.UNIT_BPM);
        }
        final String[] zoneKeys = {
                ActivitySummaryEntries.HR_ZONE_WARM_UP, ActivitySummaryEntries.HR_ZONE_EASY,
                ActivitySummaryEntries.HR_ZONE_AEROBIC, ActivitySummaryEntries.HR_ZONE_THRESHOLD,
                ActivitySummaryEntries.HR_ZONE_MAXIMUM};
        for (int i = 0; i < zoneKeys.length && i < session.getHeartRateZoneDurationCount(); i++) {
            data.add(zoneKeys[i], seconds(session.getHeartRateZoneDuration(i)), ActivitySummaryEntries.UNIT_SECONDS);
        }
        if (session.hasCardioLoad() && session.getCardioLoad().getExerciseLoad() > 0) {
            data.add(ActivitySummaryEntries.TRAINING_LOAD, session.getCardioLoad().getExerciseLoad(),
                    ActivitySummaryEntries.UNIT_NONE);
        }
        if (session.hasTrainingLoad() && session.getTrainingLoad().hasRecoveryTime()) {
            data.add(ActivitySummaryEntries.RECOVERY_TIME, seconds(session.getTrainingLoad().getRecoveryTime()),
                    ActivitySummaryEntries.UNIT_SECONDS);
        }
        return new Workout(start, end, kind(sport), data.toJson(), samples == null ? new byte[0] : samples);
    }
}
