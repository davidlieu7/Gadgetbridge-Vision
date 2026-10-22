package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import nodomain.freeyourgadget.gadgetbridge.devices.polar.PolarIgnite3SleepSessionProvider;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;

/**
 * Turns the health files of the Ignite 3 (activity samples, 24/7 heart rate, sleep, skin
 * temperature) into plain values. All times are seconds or milliseconds since the epoch.
 */
public final class PolarHealthData {
    private PolarHealthData() {
    }

    /** Steps and mean heart rate of one minute, -1 for "not measured". */
    public static final class Minute {
        public int steps = -1;
        public int distanceCm;
        public int activeCalories;
        /** MET above resting times seconds, what the daily calories are shared by. */
        private double metExcess;
        private int heartRateSum;
        private int heartRateCount;

        public int heartRate() {
            return heartRateCount == 0 ? -1 : Math.round(heartRateSum / (float) heartRateCount);
        }
    }

    public static final class SleepStageValue {
        public final long startMillis;
        public final int durationSeconds;
        public final int stage;

        SleepStageValue(final long startMillis, final int durationSeconds, final int stage) {
            this.startMillis = startMillis;
            this.durationSeconds = durationSeconds;
            this.stage = stage;
        }
    }

    public static final class Temperature {
        public final long timestampMillis;
        public final float celsius;

        Temperature(final long timestampMillis, final float celsius) {
            this.timestampMillis = timestampMillis;
            this.celsius = celsius;
        }
    }

    /** One value of a time series, in milliseconds since the epoch. */
    public static final class Value {
        public final long timestampMillis;
        public final float value;

        Value(final long timestampMillis, final float value) {
            this.timestampMillis = timestampMillis;
            this.value = value;
        }
    }

    /** Minutes by start second, rounded down to the minute. */
    public static Map<Long, Minute> newMinutes() {
        return new TreeMap<>();
    }

    private static Minute minute(final Map<Long, Minute> minutes, final long epochSecond) {
        return minutes.computeIfAbsent(epochSecond - Math.floorMod(epochSecond, 60L), k -> new Minute());
    }

    /** Zone of the watch while it recorded {@code start}, the phone's zone if the file has none. */
    public static ZoneId zone(@Nullable final PolarProto.PbLocalDateTime start) {
        if (start != null && start.hasTimeZoneOffset()) {
            return ZoneOffset.ofTotalSeconds(start.getTimeZoneOffset() * 60);
        }
        return ZoneId.systemDefault();
    }

    private static long epochSecond(final PolarProto.PbDate date, final PolarProto.PbTime time, final ZoneId zone) {
        return LocalDate.of(date.getYear(), date.getMonth(), date.getDay())
                .atTime(time.getHour(), time.getMinute(), time.getSeconds())
                .atZone(zone).toEpochSecond();
    }

    public static void addSteps(final Map<Long, Minute> minutes, final PolarProto.PbActivitySamples samples) {
        if (!samples.hasStartTime() || !samples.getStartTime().hasDate()) {
            return;
        }
        final PolarProto.PbLocalDateTime start = samples.getStartTime();
        final PolarProto.PbDuration interval = samples.getStepsRecordingInterval();
        final int intervalSeconds = interval.getHours() * 3600 + interval.getMinutes() * 60 + interval.getSeconds();
        if (intervalSeconds <= 0) {
            return;
        }
        final long first = epochSecond(start.getDate(), start.getTime(), zone(start));
        final PolarProto.PbDuration metInterval = samples.getMetRecordingInterval();
        final int metSeconds = metInterval.getHours() * 3600 + metInterval.getMinutes() * 60 + metInterval.getSeconds();
        if (metSeconds > 0) {
            int metIndex = 0;
            for (final float met : samples.getMetSamplesList()) {
                minute(minutes, first + (long) metIndex++ * metSeconds).metExcess += Math.max(met - 1f, 0f) * metSeconds;
            }
        }
        int index = 0;
        for (final int steps : samples.getStepsSamplesList()) {
            final Minute minute = minute(minutes, first + (long) index++ * intervalSeconds);
            minute.steps = Math.max(minute.steps, 0) + steps;
        }
    }

    /**
     * Spreads the calories and distance the watch counted for {@code day} over that day's minutes:
     * calories by MET above rest, distance by steps, so the daily totals stay exactly the watch's.
     */
    public static void addDailySummary(final Map<Long, Minute> minutes, final PolarProto.PbDailySummary summary,
                                       final LocalDate day, final ZoneId zone) {
        final long from = day.atStartOfDay(zone).toEpochSecond();
        final Map<Long, Minute> dayMinutes = ((TreeMap<Long, Minute>) minutes).subMap(from, day.plusDays(1).atStartOfDay(zone).toEpochSecond());
        double excess = 0;
        long steps = 0;
        for (final Minute minute : dayMinutes.values()) {
            excess += minute.metExcess;
            steps += Math.max(minute.steps, 0);
        }
        // The rounding error is carried to the next minute, so the day adds up to the watch's totals
        double caloriesCarry = 0;
        double distanceCarry = 0;
        for (final Minute minute : dayMinutes.values()) {
            if (excess > 0) {
                caloriesCarry += summary.getActivityCalories() * minute.metExcess / excess;
                minute.activeCalories = (int) Math.floor(caloriesCarry);
                caloriesCarry -= minute.activeCalories;
            }
            if (steps > 0 && summary.hasActivityDistance()) {
                distanceCarry += summary.getActivityDistance() * 100.0 * Math.max(minute.steps, 0) / steps;
                minute.distanceCm = (int) Math.floor(distanceCarry);
                distanceCarry -= minute.distanceCm;
            }
        }
    }

    public static void addHeartRates(final Map<Long, Minute> minutes, final PolarProto.PbAutomaticSampleSessions sessions,
                                     final ZoneId zone) {
        if (!sessions.hasDay()) {
            return;
        }
        for (final PolarProto.PbAutomaticHeartRateSamples sample : sessions.getSamplesList()) {
            if (!sample.hasTime()) {
                continue;
            }
            final long second = epochSecond(sessions.getDay(), sample.getTime(), zone);
            for (final int heartRate : sample.getHeartRateList()) {
                if (heartRate > 0) {
                    final Minute minute = minute(minutes, second);
                    minute.heartRateSum += heartRate;
                    minute.heartRateCount++;
                }
            }
        }
    }

    @NonNull
    public static List<SleepStageValue> sleepStages(final PolarProto.PbSleepAnalysisResult result) {
        final List<SleepStageValue> stages = new ArrayList<>();
        if (!result.hasSleepStartTime() || !result.getSleepStartTime().hasDate() || !result.hasSleepEndTime()
                || !result.getSleepEndTime().hasDate()) {
            return stages;
        }
        final PolarProto.PbLocalDateTime start = result.getSleepStartTime();
        final PolarProto.PbLocalDateTime end = result.getSleepEndTime();
        final long startSecond = epochSecond(start.getDate(), start.getTime(), zone(start));
        final long endSecond = epochSecond(end.getDate(), end.getTime(), zone(end));
        final List<PolarProto.PbSleepWakePhase> phases = result.getSleepwakePhasesList();
        for (int i = 0; i < phases.size(); i++) {
            final long phaseStart = startSecond + phases.get(i).getSecondsFromSleepStart();
            final long phaseEnd = i + 1 < phases.size() ? startSecond + phases.get(i + 1).getSecondsFromSleepStart() : endSecond;
            final int stage = stage(phases.get(i).getSleepwakeState());
            if (stage != 0 && phaseEnd > phaseStart) {
                stages.add(new SleepStageValue(phaseStart * 1000L, (int) (phaseEnd - phaseStart), stage));
            }
        }
        return stages;
    }

    private static int stage(final PolarProto.PbSleepWakeState state) {
        switch (state) {
            case PB_WAKE:
                return PolarIgnite3SleepSessionProvider.Stage.AWAKE;
            case PB_NONREM12:
                return PolarIgnite3SleepSessionProvider.Stage.LIGHT;
            case PB_NONREM3:
                return PolarIgnite3SleepSessionProvider.Stage.DEEP;
            case PB_REM:
                return PolarIgnite3SleepSessionProvider.Stage.REM;
            default:
                return 0;
        }
    }

    @NonNull
    public static List<Temperature> skinTemperatures(final PolarProto.PbSkinTemperatureContinuous temperatures,
                                                     final LocalDate day, final ZoneId zone) {
        final long midnight = day.atStartOfDay(zone).toEpochSecond() * 1000L;
        final List<Temperature> result = new ArrayList<>();
        for (final PolarProto.PbSkinTemperatureSample sample : temperatures.getSamplesList()) {
            if (sample.hasTemperature()) {
                result.add(new Temperature(midnight + sample.getOffsetMillis(), sample.getTemperature()));
            }
        }
        return result;
    }

    /** System times of the watch are UTC. */
    private static long millis(final PolarProto.PbSystemDateTime time) {
        return epochSecond(time.getDate(), time.getTime(), ZoneOffset.UTC) * 1000L;
    }

    /** Mean breathing rate of every minute, from the one per second values of the watch. */
    @NonNull
    public static List<Value> respirationRates(final PolarProto.PbRespirationRate rates, final ZoneId zone) {
        final Map<Long, float[]> sums = new TreeMap<>();
        if (rates.hasDay()) {
            for (final PolarProto.PbRespirationRateChunk chunk : rates.getChunksList()) {
                final java.nio.FloatBuffer values = chunk.getBreathsPerMinute().asReadOnlyByteBuffer()
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                final long start = epochSecond(rates.getDay(), chunk.getStartTime(), zone);
                for (int i = 0; values.hasRemaining(); i++) {
                    final float value = values.get();
                    if (value > 0) {
                        final long second = start + i;
                        final float[] sum = sums.computeIfAbsent(second - Math.floorMod(second, 60L), k -> new float[2]);
                        sum[0] += value;
                        sum[1]++;
                    }
                }
            }
        }
        final List<Value> result = new ArrayList<>(sums.size());
        for (final Map.Entry<Long, float[]> entry : sums.entrySet()) {
            result.add(new Value(entry.getKey() * 1000L, entry.getValue()[0] / entry.getValue()[1]));
        }
        return result;
    }

    /** Overnight HRV (RMSSD in ms) and breathing rate, stamped with the time the watch computed them. */
    @NonNull
    public static List<Value> nightlyRecovery(final PolarProto.PbNightlyRecoveryStatus status, final boolean rmssd) {
        final List<Value> result = new ArrayList<>(1);
        if (!status.hasCreatedTimestamp() || !status.getCreatedTimestamp().hasDate()) {
            return result;
        }
        final long time = millis(status.getCreatedTimestamp());
        if (rmssd && status.getMeanNightlyRecoveryRMSSD() > 0) {
            result.add(new Value(time, status.getMeanNightlyRecoveryRMSSD()));
        } else if (!rmssd && status.getMeanNightlyRecoveryRespirationInterval() > 0) {
            // The interval between breaths in milliseconds
            result.add(new Value(time, 60000f / status.getMeanNightlyRecoveryRespirationInterval()));
        }
        return result;
    }

    @NonNull
    public static List<Value> sleepScore(final PolarProto.PbSleepScore score) {
        final List<Value> result = new ArrayList<>(1);
        if (score.hasCreatedTimestamp() && score.getCreatedTimestamp().hasDate() && score.getSleepScore() > 0) {
            result.add(new Value(millis(score.getCreatedTimestamp()), Math.round(score.getSleepScore())));
        }
        return result;
    }
}
