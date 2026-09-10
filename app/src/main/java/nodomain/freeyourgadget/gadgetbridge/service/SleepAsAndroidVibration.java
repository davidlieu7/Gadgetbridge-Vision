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
import android.os.SystemClock;

import androidx.annotation.Nullable;

/**
 * Drives the vibration Sleep as Android asks for on devices that have no alarm of their own to
 * ring, by toggling find-device on and off.
 * <p>
 * START_ALARM is level-based: Sleep as Android sends it once and expects the wearable to keep
 * alarming until STOP_ALARM, which can be minutes later while the user has not yet dismissed the
 * alarm or solved its captcha. A single burst therefore leaves the wearable silent for most of the
 * time the phone is ringing.
 * <p>
 * One instance drives one stream, so hints and alarms each get their own and neither cancels the
 * other's schedule. They still share the one find-device state on the wearable, so a caller that
 * starts a hint while an alarm is ringing has to hold it back itself.
 * <p>
 * A schedule is started from the handler's own thread. Only {@link #stop()} may be called from
 * elsewhere.
 */
public class SleepAsAndroidVibration {

    public interface Toggle {
        void set(boolean on);
    }

    static final long PULSE_MS = 500L;
    static final long GAP_MS = 300L;
    static final int ALARM_BURST_PULSES = 3;
    static final long ALARM_BURST_INTERVAL_MS = 5_000L;
    /** Nothing stops the alarm if STOP_ALARM never arrives, so it cannot run unbounded. */
    static final long ALARM_MAX_DURATION_MS = 5 * 60_000L;

    private final Handler handler;
    private final Toggle toggle;

    private boolean alarmRunning = false;
    private boolean toggledOn = false;
    private long alarmDeadline = 0;

    public SleepAsAndroidVibration(final Handler handler, final Toggle toggle) {
        this.handler = handler;
        this.toggle = toggle;
    }

    /**
     * Vibrate a fixed number of times, once.
     */
    public void hint(final int pulses) {
        if (pulses <= 0) {
            return;
        }
        stopNow();
        burst(pulses, 0, null);
    }

    /**
     * Start alarming after {@code delayMs}, repeating until {@link #stop()} or the safety cap.
     * A delay of -1 is Sleep as Android's cancel convention.
     */
    public void startAlarm(final int delayMs) {
        stopNow();
        if (delayMs == -1) {
            return;
        }

        alarmRunning = true;
        alarmDeadline = SystemClock.elapsedRealtime() + ALARM_MAX_DURATION_MS;
        handler.postDelayed(this::alarmTick, Math.max(0, delayMs));
    }

    /**
     * Cancel whatever is running and leave the wearable quiet.
     * <p>
     * A caller on another thread only gets the cancel queued: everything a schedule touches has to
     * run on the handler, or a burst caught midway through posting its next step survives the
     * cancel and keeps the wearable buzzing.
     */
    public void stop() {
        if (Looper.myLooper() == handler.getLooper()) {
            stopNow();
        } else {
            handler.post(this::stopNow);
        }
    }

    private void stopNow() {
        alarmRunning = false;
        handler.removeCallbacksAndMessages(null);
        set(false);
    }

    /**
     * Two streams share the one find-device state, and both are stopped together, so the state is
     * tracked to keep the second stop off the wire.
     */
    private void set(final boolean on) {
        if (toggledOn == on) {
            return;
        }
        toggledOn = on;
        toggle.set(on);
    }

    public boolean isAlarmRunning() {
        return alarmRunning;
    }

    private void alarmTick() {
        if (!alarmRunning) {
            return;
        }
        if (SystemClock.elapsedRealtime() > alarmDeadline) {
            stopNow();
            return;
        }
        burst(ALARM_BURST_PULSES, 0, () -> handler.postDelayed(this::alarmTick, ALARM_BURST_INTERVAL_MS));
    }

    private void burst(final int pulses, final int index, @Nullable final Runnable onComplete) {
        set(true);
        handler.postDelayed(() -> {
            set(false);
            if (index + 1 >= pulses) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }
            handler.postDelayed(() -> burst(pulses, index + 1, onComplete), GAP_MS);
        }, PULSE_MS);
    }
}
