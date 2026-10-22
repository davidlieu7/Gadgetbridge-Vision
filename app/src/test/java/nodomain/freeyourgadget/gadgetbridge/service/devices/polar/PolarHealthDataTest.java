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
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.devices.polar.PolarIgnite3SleepSessionProvider;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;

public class PolarHealthDataTest {
    private static final ZoneOffset PLUS_TWO = ZoneOffset.ofHours(2);
    // 2026-10-22 00:00 at +02:00
    private static final long MIDNIGHT = LocalDate.of(2026, 10, 22).atStartOfDay(PLUS_TWO).toEpochSecond();

    private static PolarProto.PbDate date() {
        return PolarProto.PbDate.newBuilder().setYear(2026).setMonth(10).setDay(22).build();
    }

    private static PolarProto.PbTime time(final int hour, final int minute, final int second) {
        return PolarProto.PbTime.newBuilder().setHour(hour).setMinute(minute).setSeconds(second).build();
    }

    private static PolarProto.PbLocalDateTime local(final int hour, final int minute, final int second) {
        return PolarProto.PbLocalDateTime.newBuilder().setDate(date()).setTime(time(hour, minute, second))
                .setTimeZoneOffset(120).build();
    }

    private static PolarProto.PbDuration seconds(final int seconds) {
        return PolarProto.PbDuration.newBuilder().setMinutes(seconds / 60).setSeconds(seconds % 60).build();
    }

    @Test
    public void stepsAreBucketedPerMinuteInTheZoneOfTheWatch() {
        final Map<Long, PolarHealthData.Minute> minutes = PolarHealthData.newMinutes();
        PolarHealthData.addSteps(minutes, PolarProto.PbActivitySamples.newBuilder()
                .setStartTime(local(0, 0, 0))
                .setStepsRecordingInterval(seconds(60))
                .addAllStepsSamples(List.of(0, 5, 7))
                .build());

        assertEquals(3, minutes.size());
        assertEquals(0, minutes.get(MIDNIGHT).steps);
        assertEquals(5, minutes.get(MIDNIGHT + 60).steps);
        assertEquals(7, minutes.get(MIDNIGHT + 120).steps);
    }

    @Test
    public void heartRatesAreAveragedPerMinute() {
        final Map<Long, PolarHealthData.Minute> minutes = PolarHealthData.newMinutes();
        PolarHealthData.addHeartRates(minutes, PolarProto.PbAutomaticSampleSessions.newBuilder()
                .setDay(date())
                .addSamples(PolarProto.PbAutomaticHeartRateSamples.newBuilder().setTime(time(0, 1, 5)).addHeartRate(60))
                .addSamples(PolarProto.PbAutomaticHeartRateSamples.newBuilder().setTime(time(0, 1, 35)).addHeartRate(70))
                .build(), PLUS_TWO);

        assertEquals(65, minutes.get(MIDNIGHT + 60).heartRate());
    }

    @Test
    public void dailySummaryIsSpreadOverTheDayWithoutLosingAnything() {
        final Map<Long, PolarHealthData.Minute> minutes = PolarHealthData.newMinutes();
        final PolarProto.PbActivitySamples.Builder samples = PolarProto.PbActivitySamples.newBuilder()
                .setStartTime(local(0, 0, 0))
                .setStepsRecordingInterval(seconds(60))
                .setMetRecordingInterval(seconds(60));
        for (int i = 0; i < 100; i++) {
            samples.addStepsSamples(i % 3);
            samples.addMetSamples(1f + (i % 7) * 0.1f);
        }
        PolarHealthData.addSteps(minutes, samples.build());
        PolarHealthData.addDailySummary(minutes, PolarProto.PbDailySummary.newBuilder()
                .setActivityCalories(132).setActivityDistance(176.8f).build(), LocalDate.of(2026, 10, 22), PLUS_TWO);

        int calories = 0;
        int distanceCm = 0;
        for (final PolarHealthData.Minute minute : minutes.values()) {
            calories += minute.activeCalories;
            distanceCm += minute.distanceCm;
        }
        // The last fraction stays in the carry, the totals are exact up to one unit
        assertEquals(132, calories, 1);
        assertEquals(17680, distanceCm, 1);
    }

    @Test
    public void sleepPhasesBecomeStagesUntilTheNextPhaseOrTheEnd() {
        final List<PolarHealthData.SleepStageValue> stages = PolarHealthData.sleepStages(
                PolarProto.PbSleepAnalysisResult.newBuilder()
                        .setSleepStartTime(PolarProto.PbLocalDateTime.newBuilder().setDate(date()).setTime(time(3, 0, 0))
                                .setTimeZoneOffset(120))
                        .setSleepEndTime(PolarProto.PbLocalDateTime.newBuilder().setDate(date()).setTime(time(4, 0, 0))
                                .setTimeZoneOffset(120))
                        .addSleepwakePhases(PolarProto.PbSleepWakePhase.newBuilder().setSecondsFromSleepStart(0)
                                .setSleepwakeState(PolarProto.PbSleepWakeState.PB_WAKE))
                        .addSleepwakePhases(PolarProto.PbSleepWakePhase.newBuilder().setSecondsFromSleepStart(600)
                                .setSleepwakeState(PolarProto.PbSleepWakeState.PB_NONREM3))
                        .addSleepwakePhases(PolarProto.PbSleepWakePhase.newBuilder().setSecondsFromSleepStart(1800)
                                .setSleepwakeState(PolarProto.PbSleepWakeState.PB_REM))
                        .build());

        assertEquals(3, stages.size());
        assertEquals(PolarIgnite3SleepSessionProvider.Stage.AWAKE, stages.get(0).stage);
        assertEquals(600, stages.get(0).durationSeconds);
        assertEquals(PolarIgnite3SleepSessionProvider.Stage.DEEP, stages.get(1).stage);
        assertEquals(1200, stages.get(1).durationSeconds);
        assertEquals((MIDNIGHT + 3 * 3600 + 1800) * 1000L, stages.get(2).startMillis);
        assertEquals(1800, stages.get(2).durationSeconds);
    }

    @Test
    public void respirationIsAveragedPerMinute() {
        final ByteBuffer values = ByteBuffer.allocate(120 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 120; i++) {
            values.putFloat(i < 60 ? 12f : 16f);
        }
        final List<PolarHealthData.Value> rates = PolarHealthData.respirationRates(PolarProto.PbRespirationRate.newBuilder()
                .setDay(date())
                .addChunks(PolarProto.PbRespirationRateChunk.newBuilder().setStartTime(time(0, 0, 0))
                        .setBreathsPerMinute(ByteString.copyFrom(values.array())))
                .build(), PLUS_TWO);

        assertEquals(2, rates.size());
        assertEquals(12f, rates.get(0).value, 0.001f);
        assertEquals(16f, rates.get(1).value, 0.001f);
        assertEquals((MIDNIGHT + 60) * 1000L, rates.get(1).timestampMillis);
    }

    @Test
    public void skinTemperatureIsOffsetFromMidnight() {
        final List<PolarHealthData.Temperature> temperatures = PolarHealthData.skinTemperatures(
                PolarProto.PbSkinTemperatureContinuous.newBuilder()
                        .addSamples(PolarProto.PbSkinTemperatureSample.newBuilder().setTemperature(32.5f))
                        .addSamples(PolarProto.PbSkinTemperatureSample.newBuilder().setOffsetMillis(300000).setTemperature(33f))
                        .build(), LocalDate.of(2026, 10, 22), PLUS_TWO);

        assertEquals(2, temperatures.size());
        assertEquals(MIDNIGHT * 1000L + 300000, temperatures.get(1).timestampMillis);
        assertEquals(33f, temperatures.get(1).celsius, 0.001f);
    }
}
