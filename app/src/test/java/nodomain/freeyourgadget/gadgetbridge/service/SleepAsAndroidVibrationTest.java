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

import android.os.Handler;
import android.os.Looper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Shadows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class SleepAsAndroidVibrationTest extends TestBase {

    /** Every find-device toggle, in order. */
    private List<Boolean> toggles;
    private SleepAsAndroidVibration vibration;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        toggles = new ArrayList<>();
        vibration = new SleepAsAndroidVibration(new Handler(Looper.getMainLooper()), on -> toggles.add(on));
    }

    private void idle(final long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** Length of one complete burst of n pulses. */
    private static long burstDuration(final int pulses) {
        return pulses * SleepAsAndroidVibration.PULSE_MS + (pulses - 1) * SleepAsAndroidVibration.GAP_MS;
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

    // --- hint -----------------------------------------------------------------------------

    @Test
    public void hintPulsesTheRequestedNumberOfTimes() {
        vibration.hint(3);
        idle(burstDuration(3));

        Assert.assertEquals(3, countOn());
        Assert.assertFalse("must not be left vibrating", toggles.get(toggles.size() - 1));
    }

    // --- alarm ----------------------------------------------------------------------------

    @Test
    public void alarmRepeatsWhileNothingStopsIt() {
        // The failure this guards: Sleep as Android sends START_ALARM once, and the band used to
        // buzz a single burst then fall silent while the phone kept ringing.
        vibration.startAlarm(0);
        idle(30_000);

        Assert.assertTrue("expected repeated bursts, saw " + countOn() + " pulses",
                countOn() > SleepAsAndroidVibration.ALARM_BURST_PULSES);
    }

    @Test
    public void alarmHonoursTheInitialDelay() {
        vibration.startAlarm(10_000);

        // startAlarm clears any previous state, which emits one "off", so count pulses instead.
        idle(9_000);
        Assert.assertEquals(0, countOn());

        idle(2_000);
        Assert.assertTrue(countOn() > 0);
    }

    @Test
    public void stopEndsTheAlarmPromptly() {
        vibration.startAlarm(0);
        idle(12_000);
        Assert.assertTrue(vibration.isAlarmRunning());

        vibration.stop();
        final int afterStop = toggles.size();

        idle(60_000);

        Assert.assertFalse(vibration.isAlarmRunning());
        Assert.assertEquals("nothing may fire after stop", afterStop, toggles.size());
        Assert.assertFalse("must leave the wearable quiet", toggles.get(toggles.size() - 1));
    }

    @Test
    public void stopFromAnotherThreadIsQueuedOnTheHandler() throws InterruptedException {
        // A connection cancels the alarm from the Bluetooth thread. Touching the schedule there
        // would let a burst that is midway through posting its next step outlive the cancel.
        vibration.startAlarm(0);
        idle(12_000);
        Assert.assertTrue(vibration.isAlarmRunning());

        final int beforeCancel = toggles.size();
        final Thread canceller = new Thread(vibration::stop);
        canceller.start();
        canceller.join();

        Assert.assertEquals("the cancel may not run on the caller's thread", beforeCancel, toggles.size());

        idle(1);
        Assert.assertFalse(vibration.isAlarmRunning());
        Assert.assertFalse("must leave the wearable quiet", toggles.get(toggles.size() - 1));

        final int afterStop = toggles.size();
        idle(60_000);
        Assert.assertEquals("nothing may fire after the cancel", afterStop, toggles.size());
    }

    @Test
    public void negativeDelayCancelsARunningAlarm() {
        vibration.startAlarm(0);
        idle(12_000);

        vibration.startAlarm(-1);
        final int afterCancel = toggles.size();
        idle(60_000);

        Assert.assertFalse(vibration.isAlarmRunning());
        Assert.assertEquals(afterCancel, toggles.size());
    }

    @Test
    public void alarmStopsAtTheSafetyCap() {
        // STOP_ALARM never arrives, so the loop must give up rather than vibrate until the
        // battery is flat.
        vibration.startAlarm(0);
        idle(SleepAsAndroidVibration.ALARM_MAX_DURATION_MS + 30_000);

        final int atCap = toggles.size();
        Assert.assertFalse(vibration.isAlarmRunning());

        idle(120_000);
        Assert.assertEquals(atCap, toggles.size());
    }

    @Test
    public void restartingTheAlarmDoesNotStackLoops() {
        vibration.startAlarm(0);
        idle(20_000);
        final int firstRun = countOn();

        vibration.startAlarm(0);
        idle(20_000);

        // A second loop running in parallel would roughly double the pulse rate.
        Assert.assertTrue("pulse rate suggests two loops: " + firstRun + " then " + (countOn() - firstRun),
                countOn() - firstRun <= firstRun + SleepAsAndroidVibration.ALARM_BURST_PULSES);
    }
}
