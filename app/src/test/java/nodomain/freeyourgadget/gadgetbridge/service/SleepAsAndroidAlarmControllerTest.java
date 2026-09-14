/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service;

import android.os.Bundle;
import android.os.Looper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Shadows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.externalevents.sleepasandroid.SleepAsAndroidAction;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

/**
 * The policy that decides when a wearable with no alarm of its own buzzes for Sleep as Android.
 * <p>
 * Every case ends by asserting the wearable was left quiet, because the failure that matters here
 * is a band still buzzing after the phone has finished with the alarm.
 */
public class SleepAsAndroidAlarmControllerTest extends TestBase {

    private static final boolean IN_SESSION = true;
    private static final boolean NO_SESSION = false;
    private static final boolean ALARMS_ON = true;
    private static final boolean ALARMS_OFF = false;

    private static final long SHORT_CAP = SleepAsAndroidVibration.ALARM_MAX_DURATION_NO_SESSION_MS;
    private static final long LONG_CAP = SleepAsAndroidVibration.ALARM_MAX_DURATION_MS;
    /** Comfortably longer than one burst plus the gap to the next one. */
    private static final long SEVERAL_BURSTS_MS = 30_000L;

    /** Every find-device toggle, in order. */
    private List<Boolean> toggles;
    private int wakes;
    private SleepAsAndroidAlarmController controller;
    /** Stands in for a link that cannot carry the command right now. */
    private boolean toggleSuppressed;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        toggles = new ArrayList<>();
        wakes = 0;
        toggleSuppressed = false;
        controller = new SleepAsAndroidAlarmController(
                Looper.getMainLooper(),
                new SleepAsAndroidVibration.Toggle() {
                    @Override
                    public void set(final boolean on) {
                        if (!toggleSuppressed) {
                            toggles.add(on);
                        }
                    }

                    @Override
                    public void wake() {
                        wakes++;
                    }
                });
    }

    // --- driving ----------------------------------------------------------------------------

    private void idle(final long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private void send(final String action, final Bundle extras, final boolean trackingOngoing, final boolean alarmsEnabled) {
        controller.onAction(action, extras, trackingOngoing, alarmsEnabled);
    }

    private void send(final String action, final boolean trackingOngoing) {
        send(action, null, trackingOngoing, ALARMS_ON);
    }

    private void startAlarm(final int delayMs, final boolean trackingOngoing, final boolean alarmsEnabled) {
        final Bundle extras = new Bundle();
        extras.putInt("DELAY", delayMs);
        send(SleepAsAndroidAction.START_ALARM, extras, trackingOngoing, alarmsEnabled);
    }

    private void startAlarm(final boolean trackingOngoing) {
        startAlarm(0, trackingOngoing, ALARMS_ON);
    }

    private void hint(final int repeat) {
        controller.hint(repeat);
    }

    private int countOn() {
        int n = 0;
        for (final boolean on : toggles) {
            if (on) {
                n++;
            }
        }
        return n;
    }

    /** The assertion every case shares: nothing is still buzzing once the dust has settled. */
    private void assertLeftQuiet() {
        final int settled = toggles.size();
        idle(LONG_CAP * 2);

        Assert.assertFalse("the alarm is still running", controller.isAlarmRunning());
        Assert.assertEquals("something fired after everything should have stopped",
                settled, toggles.size());
        if (!toggles.isEmpty()) {
            Assert.assertFalse("the wearable was left vibrating", toggles.get(toggles.size() - 1));
        }
    }

    // --- alarm lifecycle --------------------------------------------------------------------

    @Test
    public void stopAlarmStopsTheAlarm() {
        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);
        Assert.assertTrue(controller.isAlarmRunning());

        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        idle(1);

        assertLeftQuiet();
    }

    @Test
    public void stopTrackingStopsTheAlarm() {
        // The reported failure: Sleep as Android rang an alarm that no session claimed, the user
        // finished with it in the app, and no STOP_ALARM ever came. The band kept buzzing until the
        // wearable was disconnected by hand.
        startAlarm(NO_SESSION);
        idle(SEVERAL_BURSTS_MS);
        Assert.assertTrue(controller.isAlarmRunning());

        send(SleepAsAndroidAction.STOP_TRACKING, NO_SESSION);
        idle(1);

        assertLeftQuiet();
    }

    @Test
    public void startTrackingDoesNotStopTheAlarm() {
        // Sleep as Android repeats START_TRACKING as its own watchdog, and a smart alarm rings
        // inside a session, so the wearable has to keep buzzing through one.
        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);

        send(SleepAsAndroidAction.START_TRACKING, IN_SESSION);
        final int atWatchdog = countOn();
        idle(SEVERAL_BURSTS_MS);

        Assert.assertTrue(controller.isAlarmRunning());
        Assert.assertTrue("the alarm fell silent on a watchdog START_TRACKING",
                countOn() > atWatchdog);

        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        idle(1);
        assertLeftQuiet();
    }

    @Test
    public void anUnstoppedAlarmGivesUpAtTheCap() {
        startAlarm(IN_SESSION);
        idle(LONG_CAP + SEVERAL_BURSTS_MS);

        assertLeftQuiet();
    }

    @Test
    public void aNegativeDelayIsSleepAsAndroidsCancel() {
        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);

        startAlarm(-1, IN_SESSION, ALARMS_ON);
        idle(1);

        assertLeftQuiet();
    }

    @Test
    public void aDelayIsHonouredBeforeTheFirstBurst() {
        startAlarm(10_000, IN_SESSION, ALARMS_ON);

        idle(9_000);
        Assert.assertEquals(0, countOn());

        idle(SleepAsAndroidVibration.WAKE_LEAD_MS + 2_000);
        Assert.assertTrue(countOn() > 0);

        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        idle(1);
        assertLeftQuiet();
    }

    @Test
    public void aSecondStartAlarmDoesNotStackLoops() {
        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);
        final int firstRun = countOn();

        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);

        Assert.assertTrue("pulse rate suggests two loops running at once",
                countOn() - firstRun <= firstRun + SleepAsAndroidVibration.ALARM_BURST_PULSES);

        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        idle(1);
        assertLeftQuiet();
    }

    @Test
    public void aStopWithNothingRunningSaysNothing() {
        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        send(SleepAsAndroidAction.STOP_TRACKING, IN_SESSION);
        idle(1);

        Assert.assertTrue("a redundant stop must not reach the wire", toggles.isEmpty());
        assertLeftQuiet();
    }

    @Test
    public void unhandledActionsChangeNothing() {
        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);
        final int running = countOn();

        send(SleepAsAndroidAction.CHECK_CONNECTED, IN_SESSION);
        send(SleepAsAndroidAction.UPDATE_ALARM, IN_SESSION);
        send(SleepAsAndroidAction.SET_BATCH_SIZE, IN_SESSION);
        send(SleepAsAndroidAction.SHOW_NOTIFICATION, IN_SESSION);
        // Hints come in through hint(), not through an action.
        send(SleepAsAndroidAction.HINT, IN_SESSION);
        send("com.urbandroid.sleep.watch.SOMETHING_NEW", IN_SESSION);
        controller.onAction(null, null, IN_SESSION, ALARMS_ON);
        idle(1);

        Assert.assertTrue(controller.isAlarmRunning());
        Assert.assertEquals(running, countOn());

        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        idle(1);
        assertLeftQuiet();
    }

    @Test
    public void theNightThatWasReported() {
        // 2026-09-14, replayed from the logs. The smart alarm rang inside a session and was
        // snoozed, the snoozed alarm rang half an hour later with no session behind it and never
        // got a STOP_ALARM, and the session that closed a minute later had to be what stopped it.
        startAlarm(IN_SESSION);
        idle(100_000);
        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        send(SleepAsAndroidAction.STOP_TRACKING, IN_SESSION);
        idle(1);
        Assert.assertFalse("the dismissed alarm must not survive the session", controller.isAlarmRunning());

        final int afterSnooze = toggles.size();
        idle(Duration.ofMinutes(29).toMillis());
        Assert.assertEquals("nothing may buzz between the snooze and the next alarm",
                afterSnooze, toggles.size());

        startAlarm(NO_SESSION);
        idle(60_000);
        for (int i = 0; i < 5; i++) {
            send(SleepAsAndroidAction.CHECK_CONNECTED, NO_SESSION);
        }
        hint(1);
        send(SleepAsAndroidAction.START_TRACKING, NO_SESSION);
        send(SleepAsAndroidAction.UPDATE_ALARM, NO_SESSION);
        idle(3_000);
        Assert.assertTrue("the alarm has not been stopped yet", controller.isAlarmRunning());

        send(SleepAsAndroidAction.STOP_TRACKING, IN_SESSION);
        idle(1);

        assertLeftQuiet();
    }

    // --- cap selection ----------------------------------------------------------------------

    @Test
    public void anAlarmInsideASessionKeepsTheLongCap() {
        startAlarm(IN_SESSION);

        idle(SHORT_CAP + SEVERAL_BURSTS_MS);
        Assert.assertTrue("an alarm Sleep as Android is tracking behind must not give up early",
                controller.isAlarmRunning());

        idle(LONG_CAP);
        assertLeftQuiet();
    }

    @Test
    public void anAlarmOutsideASessionGetsTheShortCap() {
        // Nothing else can stop this one: there is no session whose end would, and Sleep as Android
        // has been seen not to send STOP_ALARM for it at all.
        startAlarm(NO_SESSION);

        idle(SHORT_CAP + SEVERAL_BURSTS_MS);

        assertLeftQuiet();
    }

    // --- hint against alarm -----------------------------------------------------------------

    @Test
    public void aHintIsIgnoredWhileTheAlarmRings() {
        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);
        final int beforeHint = countOn();

        hint(3);
        idle(SEVERAL_BURSTS_MS);

        Assert.assertTrue("the alarm must survive a hint", controller.isAlarmRunning());
        Assert.assertTrue("the hint interrupted the alarm", countOn() > beforeHint);

        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        idle(1);
        assertLeftQuiet();
    }

    @Test
    public void aHintOnItsOwnPulsesTheRequestedNumberOfTimes() {
        hint(3);
        idle(SEVERAL_BURSTS_MS);

        Assert.assertEquals(3, countOn());
        assertLeftQuiet();
    }

    @Test
    public void stoppingTheAlarmAlsoStopsARunningHint() {
        // Both streams drive the one find-device state, so a hint left running would keep the
        // wearable buzzing after the alarm was stopped.
        hint(20);
        idle(SleepAsAndroidVibration.WAKE_LEAD_MS + SleepAsAndroidVibration.PULSE_MS);

        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        idle(1);

        assertLeftQuiet();
    }

    @Test
    public void anAlarmStartingMidHintTakesOverTheWearable() {
        // The two streams keep separate schedules and separate ideas of the find-device state, so a
        // hint left running would put its own off in the middle of one of the alarm's pulses.
        hint(20);
        idle(SleepAsAndroidVibration.WAKE_LEAD_MS + SleepAsAndroidVibration.PULSE_MS);

        startAlarm(IN_SESSION);
        idle(1);
        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        idle(1);

        // The hint had eighteen pulses left to give.
        assertLeftQuiet();
    }

    // --- feature gating ---------------------------------------------------------------------

    @Test
    public void alarmsTurnedOffKeepTheWearableQuiet() {
        startAlarm(0, IN_SESSION, ALARMS_OFF);
        idle(SEVERAL_BURSTS_MS);

        Assert.assertFalse(controller.isAlarmRunning());
        Assert.assertTrue(toggles.isEmpty());
        assertLeftQuiet();
    }

    @Test
    public void stoppingIsNeverGatedOnTheAlarmFeature() {
        // validateAction gates STOP_TRACKING on the sensor features and START_ALARM on the alarm
        // one, so a preference changed mid-alarm must not leave the alarm unstoppable.
        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);
        Assert.assertTrue(controller.isAlarmRunning());

        send(SleepAsAndroidAction.STOP_TRACKING, null, IN_SESSION, ALARMS_OFF);
        idle(1);

        assertLeftQuiet();
    }

    // --- connection state -------------------------------------------------------------------

    @Test
    public void cancelStopsEverything() {
        // What dispose and a new connection both run.
        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);

        controller.cancel();
        idle(1);

        assertLeftQuiet();
    }

    @Test
    public void anAlarmOnADeadLinkStillGivesUpOnItsOwn() {
        // setFindWatchIfInitialized drops the toggle while the wearable is not initialized. The
        // schedule has to keep running so the cap can end it, rather than wedging until a reconnect.
        toggleSuppressed = true;
        startAlarm(NO_SESSION);
        idle(SEVERAL_BURSTS_MS);
        Assert.assertTrue(controller.isAlarmRunning());
        Assert.assertTrue("nothing may reach a link that cannot carry it", toggles.isEmpty());

        idle(SHORT_CAP + SEVERAL_BURSTS_MS);

        Assert.assertFalse(controller.isAlarmRunning());
    }

    @Test
    public void everyBurstWakesTheLinkFirst() {
        // An idle link takes around 700ms to carry its first command, long enough to swallow the
        // leading pulse of a burst that did not wake it.
        startAlarm(IN_SESSION);
        idle(SEVERAL_BURSTS_MS);

        Assert.assertTrue("expected one wake per burst, saw " + wakes + " for " + countOn() + " pulses",
                wakes >= countOn() / SleepAsAndroidVibration.ALARM_BURST_PULSES);

        send(SleepAsAndroidAction.STOP_ALARM, IN_SESSION);
        idle(1);
        assertLeftQuiet();
    }

    // --- escape hatch -----------------------------------------------------------------------

    @Test
    public void theFindDeviceControlStopsARunawayAlarm() {
        // Without this the next burst overrides whatever the user asked for a few seconds later,
        // leaving disconnecting the wearable as the only way out.
        startAlarm(NO_SESSION);
        idle(SEVERAL_BURSTS_MS);
        Assert.assertTrue(controller.isAlarmRunning());

        controller.onFindDevice();
        idle(1);

        assertLeftQuiet();
    }

    @Test
    public void theFindDeviceControlSaysNothingWhenNothingIsRunning() {
        controller.onFindDevice();
        idle(1);

        Assert.assertTrue(toggles.isEmpty());
        assertLeftQuiet();
    }
}
