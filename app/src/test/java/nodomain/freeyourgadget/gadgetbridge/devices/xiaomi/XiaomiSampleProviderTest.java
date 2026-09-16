/* Copyright (C) 2026 Ray
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package nodomain.freeyourgadget.gadgetbridge.devices.xiaomi;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.activities.charts.SleepAnalysis;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepTimeSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class XiaomiSampleProviderTest extends TestBase {
    private static final int START = 1700000040;
    private XiaomiSampleProvider provider;
    private Device device;
    private User user;

    @Before
    public void prepareDevice() {
        final GBDevice gbDevice = createDummyGDevice("00:00:00:00:00:03");
        device = DBHelper.getDevice(gbDevice, daoSession);
        user = DBHelper.getUser(daoSession);
        provider = new XiaomiSampleProvider(gbDevice, daoSession);
    }

    @Test
    public void sleepWithoutDailyActivityReachesCharts() {
        sleep(START, START + 65 * 60);
        stage(START, 3);
        stage(START + 30 * 60, 2);
        stage(START + 50 * 60, 5);
        stage(START + 55 * 60, 4);
        final List<XiaomiActivitySample> samples = provider.getAllActivitySamples(START - 3600, START + 7200);
        final List<SleepAnalysis.SleepSession> sessions = new SleepAnalysis().calculateSleepSessions(samples);
        assertEquals(1, sessions.size());
        assertEquals(30 * 60, sessions.get(0).getLightSleepDuration());
        assertEquals(20 * 60, sessions.get(0).getDeepSleepDuration());
        assertEquals(10 * 60, sessions.get(0).getRemSleepDuration());
        assertEquals(5 * 60, sessions.get(0).getAwakeSleepDuration());
        assertEquals(START * 1000L, sessions.get(0).getSleepStart().getTime());
        // Preserve the existing minute-sample convention used by SleepAnalysis.
        assertEquals((START + 64 * 60) * 1000L, sessions.get(0).getSleepEnd().getTime());
        assertEquals(ActivityKind.UNKNOWN, samples.get(0).getKind());
        assertEquals(ActivityKind.UNKNOWN, samples.get(samples.size() - 1).getKind());
        for (final XiaomiActivitySample sample : samples) {
            assertEquals(ActivitySample.NOT_MEASURED, sample.getSteps());
            assertEquals(ActivitySample.NOT_MEASURED, sample.getHeartRate());
        }
        assertEquals(0, daoSession.getXiaomiActivitySampleDao().count());
    }

    @Test
    public void existingMeasurementsArePreserved() {
        sleep(START, START + 30 * 60);
        final XiaomiActivitySample actual = provider.createActivitySample();
        actual.setTimestamp(START + 5 * 60);
        actual.setDevice(device);
        actual.setUser(user);
        actual.setSteps(7);
        actual.setHeartRate(63);
        daoSession.getXiaomiActivitySampleDao().insert(actual);
        final List<XiaomiActivitySample> samples = provider.getAllActivitySamples(START - 60, START + 30 * 60);
        final List<XiaomiActivitySample> matches = samples.stream().filter(s -> s.getTimestamp() == START + 5 * 60).toList();
        assertEquals(1, matches.size());
        assertEquals(7, matches.get(0).getSteps());
        assertEquals(63, matches.get(0).getHeartRate());
        assertEquals(1, daoSession.getXiaomiActivitySampleDao().count());
    }

    @Test
    public void napWithoutStagesAndDisjointSessions() {
        sleep(START, START + 30 * 60);
        sleep(START + 4 * 3600, START + 4 * 3600 + 20 * 60);
        final List<SleepAnalysis.SleepSession> sessions = new SleepAnalysis().calculateSleepSessions(
                provider.getAllActivitySamples(START - 60, START + 5 * 3600));
        assertEquals(2, sessions.size());
        assertEquals(30 * 60, sessions.get(0).getLightSleepDuration());
        assertEquals(20 * 60, sessions.get(1).getLightSleepDuration());
    }

    @Test
    public void sessionCrossingQueryBoundaryIsClipped() {
        sleep(START, START + 120 * 60);
        stage(START, 2);
        final int from = START + 30 * 60;
        final int to = START + 60 * 60;
        final List<XiaomiActivitySample> samples = provider.getAllActivitySamples(from, to);
        assertEquals(31, samples.size());
        assertEquals(from, samples.get(0).getTimestamp());
        assertEquals(to, samples.get(samples.size() - 1).getTimestamp());
        assertTrue(samples.stream().allMatch(s -> s.getKind() == ActivityKind.DEEP_SLEEP));
    }

    @Test
    public void wakeBoundaryOverridesStageForSessionStartingBeforeQuery() {
        sleep(START, START + 30 * 60);
        stage(START, 2);
        stage(START + 30 * 60, 3);
        final List<XiaomiActivitySample> samples = provider.getAllActivitySamples(START + 60, START + 3600);
        assertEquals(ActivityKind.DEEP_SLEEP, samples.get(0).getKind());
        assertEquals(ActivityKind.UNKNOWN, samples.get(samples.size() - 1).getKind());
    }

    @Test
    public void oldSessionDoesNotGenerateActivity() {
        sleep(START, START + 30 * 60);
        stage(START, 2);
        assertTrue(provider.getAllActivitySamples(START + 3600, START + 86400).isEmpty());
    }

    @Test
    public void noSleepDoesNotGenerateActivity() {
        assertTrue(provider.getAllActivitySamples(START, START + 86400).isEmpty());
    }

    private void sleep(final int start, final int end) {
        final XiaomiSleepTimeSample sample = new XiaomiSleepTimeSample();
        sample.setTimestamp(start * 1000L);
        sample.setWakeupTime(end * 1000L);
        sample.setDevice(device);
        sample.setUser(user);
        daoSession.getXiaomiSleepTimeSampleDao().insert(sample);
    }

    private void stage(final int timestamp, final int kind) {
        final XiaomiSleepStageSample sample = new XiaomiSleepStageSample();
        sample.setTimestamp(timestamp * 1000L);
        sample.setStage(kind);
        sample.setDevice(device);
        sample.setUser(user);
        daoSession.getXiaomiSleepStageSampleDao().insert(sample);
    }
}
