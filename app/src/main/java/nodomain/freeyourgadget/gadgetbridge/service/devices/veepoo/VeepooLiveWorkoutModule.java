/*  Copyright (C) 2025 Gadgetbridge contributors

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo;

import android.location.Location;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationProviderType;
import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationService;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Feature module for 0xDA live workouts (phone-initiated sport control). */
class VeepooLiveWorkoutModule {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooLiveWorkoutModule.class);

    private VeepooDeviceSupport support;

    VeepooLiveWorkoutModule(VeepooDeviceSupport support) {
        this.support = support;
    }

    // Phone-initiated workout state (0xDA sport control)
    private final Object liveWorkoutLock = new Object();
    private int liveSportType = 0;        // ESportType of the active session (0 = unknown/none)
    private boolean liveWorkoutActive = false;
    private boolean workoutCommandPending = false; // awaiting an ack for a start/stop command
    private boolean pendingCommandIsStop = false;
    private boolean precheckInProgress = false; // awaiting the run-state report before sending START
    private int precheckSportType = 0;          // sport type requested in startWorkout
    private boolean stopPrecheckInProgress = false; // awaiting the run-state report to learn the active sport type for STOP
    private final List<byte[]> liveReportChunks = new ArrayList<>();
    private int liveReportTotalChunks = 0;
    private boolean postStopFetchScheduled = false;

    // Last per-day workout-history CRCs from an unsolicited 0xD3 push; the first report of a
    // connection only sets the baseline, since the connect sync picks up anything already stored.
    private int[] sportHistoryCrcs = null;

    // Run-state polling and stop recovery: the band can end a workout on its own or ack a stop
    // command it then ignores, so while a session is tracked the run state keeps getting polled,
    // the phone GPS only stops once the band reports the session as ended (a hard deadline is the
    // backstop), and a failed stop is retried with the sport type the band itself reports.
    static final long LIVE_STATE_POLL_INTERVAL_MS = 60_000L;
    private static final int MAX_STOP_ATTEMPTS = 3;
    private static final long STOP_CONFIRM_WINDOW_MS = 2 * 60_000L;
    private static final long PHONE_GPS_STOP_DEADLINE_MS = 5 * 60_000L;
    private boolean stopRequested = false;
    private int stopAttempts = 0;
    private boolean stopRetryExhaustedToasted = false;
    private long lastStopAckOkAtMs = 0L;

    // Phone GPS track recording (the band has no GNSS of its own). The track is buffered while a
    // phone-initiated workout runs and handed to saveSportSession once the session is fetched.
    static final float MAX_GPS_SPEED_MPS = 30f; // ~108 km/h; a faster fix-to-fix jump is a glitch
    private static final int PHONE_GPS_INTERVAL_MS = 5000;
    private boolean phoneGpsRecording = false;
    private final List<ActivityPoint> phoneGpsPoints = new ArrayList<>();
    private long phoneGpsStartMs = 0L;

    /**
     * Starts a workout via 0xDA. First re-syncs time (A1+A5), opens the live control report
     * (vendor flow) and queries the device run state with readSportControlInfo. The START
     * command is only sent once the band reports no active session, so a workout started
     * elsewhere (or a zombie from a failed stop) is never stacked over with a second one.
     */
    void startWorkout(int sportType) {
        if (sportType <= 0 || sportType > 188) {
            LOG.warn("Ignoring workout start with unknown sport type {}", sportType);
            return;
        }
        synchronized (liveWorkoutLock) {
            if (workoutCommandPending) {
                LOG.warn("A workout command is already pending; ignoring the start request");
                GB.toast(support.getContext(), R.string.veepoo_workout_command_pending, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (liveWorkoutActive) {
                LOG.warn("A workout is already running; ignoring the start request");
                // The card may still show "start" if the session was adopted or started before a
                // reconnect; flip it to "stop" so the user can end the session.
                support.onLiveWorkoutStateChanged(true);
                GB.toast(support.getContext(), R.string.veepoo_workout_already_running, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            liveSportType = sportType;
            workoutCommandPending = true;
            pendingCommandIsStop = false;
            precheckInProgress = true;
            precheckSportType = sportType;
            liveReportChunks.clear();
            stopRequested = false;
            stopAttempts = 0;
            stopRetryExhaustedToasted = false;
            lastStopAckOkAtMs = 0L;
        }
        support.mainHandler.removeCallbacks(phoneGpsStopDeadline);
        try {
            TransactionBuilder builder = support.performInitialized("start workout");
            support.writeFrame(builder, support.buildPwdConfirmTimeFrame());
            support.writeFrame(builder, support.buildSyncTimeFrame());
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL});
            // readSportControlInfo: the device answers with a live report carrying the run state.
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL, 0x02,
                    (byte) (sportType & 0xFF), (byte) ((sportType >> 8) & 0xFF),
                    (byte) VeepooConstants.SPORT_CTRL_READ});
            builder.queue();
            support.mainHandler.postDelayed(workoutCommandTimeout, 10000L);
            LOG.info("Workout start pre-check sent (sport type {})", sportType);
        } catch (IOException e) {
            synchronized (liveWorkoutLock) {
                workoutCommandPending = false;
                precheckInProgress = false;
                precheckSportType = 0;
            }
            LOG.error("Error requesting workout start", e);
        }
    }

    /** Sends the START command, called after the pre-check confirmed no session is active. */
    private void sendWorkoutStartCommand(int sportType) {
        synchronized (liveWorkoutLock) {
            workoutCommandPending = true;
            pendingCommandIsStop = false;
            precheckInProgress = false;
            precheckSportType = 0;
            liveSportType = sportType;
        }
        try {
            TransactionBuilder builder = support.performInitialized("send workout start command");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL});
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL, 0x01,
                    (byte) (sportType & 0xFF), (byte) ((sportType >> 8) & 0xFF),
                     (byte) VeepooConstants.SPORT_CTRL_START});
            builder.queue();
            support.mainHandler.removeCallbacks(workoutCommandTimeout);
            support.mainHandler.postDelayed(workoutCommandTimeout, 10000L);
            LOG.info("Workout start command sent (sport type {})", sportType);
        } catch (IOException e) {
            synchronized (liveWorkoutLock) {
                workoutCommandPending = false;
            }
            LOG.error("Error sending workout start command", e);
        }
    }

    /**
     * Stops the running workout. Always queries the device run state first: the vendor firmware
     * acks but ignores a STOP that does not carry the active session's sport type, so the STOP is
     * sent with the reported one. After a successful ack the finished session is re-fetched.
     */
    void stopWorkout() {
        synchronized (liveWorkoutLock) {
            if (workoutCommandPending) {
                LOG.warn("A workout command is already pending; ignoring the stop request");
                GB.toast(support.getContext(), R.string.veepoo_workout_command_pending, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            workoutCommandPending = true;
            pendingCommandIsStop = true;
            stopRequested = true;
            stopRetryExhaustedToasted = false;
            liveReportChunks.clear();
            stopPrecheckInProgress = true;
        }
        try {
            TransactionBuilder builder = support.performInitialized("stop workout");
            // readSportControlInfo: the device answers with a live report carrying the run state
            // and the active sport type, which the STOP command must repeat.
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL});
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL, 0x02,
                    0x00, 0x00, (byte) VeepooConstants.SPORT_CTRL_READ});
            builder.queue();
            support.mainHandler.removeCallbacks(workoutCommandTimeout);
            support.mainHandler.postDelayed(workoutCommandTimeout, 10000L);
            LOG.info("Workout stop: querying the device for the active sport type");
        } catch (IOException e) {
            synchronized (liveWorkoutLock) {
                workoutCommandPending = false;
                pendingCommandIsStop = false;
                stopPrecheckInProgress = false;
            }
            LOG.error("Error requesting workout stop", e);
        }
        refreshLiveStatePolling();
    }

    /** Sends the STOP command with the given sport type (vendor requires the active one). */
    private void sendStopCommand(int sportType) {
        synchronized (liveWorkoutLock) {
            if (sportType > 0) {
                liveSportType = sportType;
            }
            stopPrecheckInProgress = false;
            workoutCommandPending = true;
            pendingCommandIsStop = true;
            stopAttempts++;
        }
        try {
            TransactionBuilder builder = support.performInitialized("send workout stop command");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL, 0x01,
                    (byte) (liveSportType & 0xFF), (byte) ((liveSportType >> 8) & 0xFF),
                    (byte) VeepooConstants.SPORT_CTRL_STOP});
            builder.queue();
            support.mainHandler.removeCallbacks(workoutCommandTimeout);
            support.mainHandler.postDelayed(workoutCommandTimeout, 10000L);
            LOG.info("Workout stop command sent (attempt {}, sport type {})", stopAttempts, liveSportType);
        } catch (IOException e) {
            synchronized (liveWorkoutLock) {
                workoutCommandPending = false;
                pendingCommandIsStop = false;
            }
            LOG.error("Error sending workout stop command", e);
        }
    }

    /** Clears a stuck pending start/stop command if the device never responds. */
    private final Runnable workoutCommandTimeout = () -> {
        boolean wasStop;
        synchronized (liveWorkoutLock) {
            if (!workoutCommandPending) {
                return;
            }
            LOG.warn("No sport control response received in time; clearing the pending command");
            wasStop = pendingCommandIsStop;
            workoutCommandPending = false;
            pendingCommandIsStop = false;
            precheckInProgress = false;
            precheckSportType = 0;
            stopPrecheckInProgress = false;
        }
        GB.toast(support.getContext(), wasStop ? R.string.veepoo_workout_stop_failed : R.string.veepoo_workout_start_failed,
                Toast.LENGTH_SHORT, GB.ERROR);
    };

    private final Runnable postStopFetchFirst = () -> {
        synchronized (liveWorkoutLock) {
            postStopFetchScheduled = false;
        }
        support.startPostWorkoutFetch();
    };

    /** Second fetch pass: the firmware has been observed to take minutes to finalize day 0. */
    private final Runnable postStopFetchRetry = () -> {
        synchronized (liveWorkoutLock) {
            postStopFetchScheduled = false;
        }
        support.startPostWorkoutFetch();
    };

    /** Periodically reads the device run state so band-side stops and ignored stops are detected. */
    private final Runnable liveStatePollTask = () -> {
        final boolean wanted;
        synchronized (liveWorkoutLock) {
            wanted = livePollingWanted();
        }
        if (!wanted) {
            return;
        }
        int sportType;
        synchronized (liveWorkoutLock) {
            sportType = liveSportType;
        }
        try {
            TransactionBuilder builder = support.performInitialized("poll the live workout state");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL});
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL, 0x02,
                    (byte) (sportType & 0xFF), (byte) ((sportType >> 8) & 0xFF),
                    (byte) VeepooConstants.SPORT_CTRL_READ});
            builder.queue();
        } catch (IOException e) {
            LOG.warn("Error polling the live workout state", e);
        }
        refreshLiveStatePolling();
    };

    /** One-shot backstop: the phone GPS must not keep recording if no end report ever arrives. */
    private final Runnable phoneGpsStopDeadline = () -> {
        boolean recording;
        synchronized (liveWorkoutLock) {
            recording = phoneGpsRecording;
        }
        if (!recording) {
            return;
        }
        LOG.warn("No end-of-session report arrived in time; stopping the phone GPS after the deadline");
        stopPhoneGps();
    };

    /** Delays the day-0 history fetch (and a second pass later) so the firmware can finalize. */
    private void schedulePostStopFetch() {
        synchronized (liveWorkoutLock) {
            if (postStopFetchScheduled) {
                return;
            }
            postStopFetchScheduled = true;
        }
        support.mainHandler.removeCallbacks(postStopFetchFirst);
        support.mainHandler.removeCallbacks(postStopFetchRetry);
        // The device takes a while to finalize the finished session into day 0 (minutes have
        // been observed), so do not poll too early.
        support.mainHandler.postDelayed(postStopFetchFirst, 60_000L);
        support.mainHandler.postDelayed(postStopFetchRetry, 240_000L);
    }

    /** True while the run state needs polling: an active session, a pending stop, or the short
     *  confirm window after an ok stop ack (the firmware has been seen to ack stops it ignores). */
    private boolean livePollingWanted() {
        return liveWorkoutActive || phoneGpsRecording || stopRequested
                || (lastStopAckOkAtMs > 0L
                        && System.currentTimeMillis() - lastStopAckOkAtMs < STOP_CONFIRM_WINDOW_MS);
    }

    /** Keeps the run-state poll scheduled while a session is tracked and drops it otherwise. */
    private void refreshLiveStatePolling() {
        final boolean wanted;
        synchronized (liveWorkoutLock) {
            wanted = livePollingWanted();
        }
        if (wanted) {
            support.mainHandler.removeCallbacks(liveStatePollTask);
            support.mainHandler.postDelayed(liveStatePollTask, LIVE_STATE_POLL_INTERVAL_MS);
        } else {
            support.mainHandler.removeCallbacks(liveStatePollTask);
        }
    }

    /** Schedules the one-shot deadline that force-stops the phone GPS. */
    private void schedulePhoneGpsStopDeadline() {
        support.mainHandler.removeCallbacks(phoneGpsStopDeadline);
        support.mainHandler.postDelayed(phoneGpsStopDeadline, PHONE_GPS_STOP_DEADLINE_MS);
    }

    /**
     * The band reports the session as ended (run state finished or not started): stop the phone
     * GPS, fetch the finished session and drop back to the idle card state. Covers stops done on
     * the watch and stop commands the device acked but did not act on.
     */
    private void handleSessionEndedByBand() {
        final boolean wasTracked;
        synchronized (liveWorkoutLock) {
            wasTracked = liveWorkoutActive || phoneGpsRecording || stopRequested;
            liveWorkoutActive = false;
            stopRequested = false;
            stopAttempts = 0;
            stopRetryExhaustedToasted = false;
        }
        if (!wasTracked) {
            return;
        }
        LOG.info("The band reported the workout as ended");
        stopPhoneGps();
        schedulePostStopFetch();
        support.onLiveWorkoutStateChanged(false);
        refreshLiveStatePolling();
    }

    /**
     * Reacts to an unsolicited 0xD3 workout-history-CRC push: state 2 means the band finalized a
     * session on its own, and a changed CRC list means a history slot was rewritten. Either way a
     * finished record is (or will be) in day 0, so run the same end-of-session path as a
     * band-side stop report; outside a tracked session only the delayed fetch is needed.
     */
    void onSportHistoryCrcReport(int state, int[] crcs) {
        final boolean changed;
        synchronized (liveWorkoutLock) {
            if (sportHistoryCrcs == null) {
                sportHistoryCrcs = crcs.clone();
                changed = false;
            } else {
                changed = crcs.length != sportHistoryCrcs.length || !Arrays.equals(crcs, sportHistoryCrcs);
                if (changed) {
                    sportHistoryCrcs = crcs.clone();
                }
            }
        }
        final boolean finalized = state == 2;
        LOG.info("Workout history CRC report: state={} crcs={}", state, Arrays.toString(crcs));
        if (!finalized && !changed) {
            return;
        }
        final boolean wasTracked;
        synchronized (liveWorkoutLock) {
            wasTracked = liveWorkoutActive || phoneGpsRecording || stopRequested;
        }
        if (wasTracked) {
            LOG.info("The band finalized a tracked workout session");
            handleSessionEndedByBand();
        } else {
            LOG.info("The band reports new workout history; scheduling a day-0 fetch");
            schedulePostStopFetch();
        }
    }

    /** Reacts to a report that still shows the session running although it was expected to end. */
    private void handleActiveSessionReport(final int reportedSportType) {
        final boolean reassertStop;
        synchronized (liveWorkoutLock) {
            reassertStop = !stopRequested && lastStopAckOkAtMs > 0L
                    && System.currentTimeMillis() - lastStopAckOkAtMs < STOP_CONFIRM_WINDOW_MS;
        }
        if (reassertStop) {
            LOG.warn("The band still reports a running workout after an ok stop ack; re-issuing the stop");
            synchronized (liveWorkoutLock) {
                stopRequested = true;
                stopAttempts = 0;
                stopRetryExhaustedToasted = false;
            }
            support.onLiveWorkoutStateChanged(true);
        }
        maybeRetryStop(reportedSportType);
    }

    /** Re-sends the stop while the band still reports a running session, up to a bounded count. */
    private void maybeRetryStop(final int reportedSportType) {
        final boolean shouldRetry;
        synchronized (liveWorkoutLock) {
            shouldRetry = stopRequested && !workoutCommandPending && !stopPrecheckInProgress
                    && stopAttempts < MAX_STOP_ATTEMPTS;
        }
        if (shouldRetry) {
            LOG.warn("The band still reports a running workout; retrying the stop (attempt {})", stopAttempts + 1);
            support.mainHandler.post(() -> sendStopCommand(reportedSportType));
            return;
        }
        final boolean exhaustedNow;
        synchronized (liveWorkoutLock) {
            exhaustedNow = stopRequested && !stopRetryExhaustedToasted && stopAttempts >= MAX_STOP_ATTEMPTS;
            if (exhaustedNow) {
                stopRetryExhaustedToasted = true;
            }
        }
        if (exhaustedNow) {
            LOG.error("The band keeps reporting a running workout after {} stop attempts", MAX_STOP_ATTEMPTS);
            GB.toast(support.getContext(), R.string.veepoo_workout_still_running, Toast.LENGTH_SHORT, GB.ERROR);
        }
    }

    /**
     * Opcode 0xDA frame (sport control): [1]==1 is the command ack ([2]==1 success). [1]==2/3 is
     * a chunked live workout report: chunk index at [3] (1-based), total chunks at [6], and the
     * little-endian TLV payload continues from byte [4] of every chunk.
     */
    void handleFrame(byte[] data) {
        if (data.length < 3) {
            LOG.debug("Dropping short 0xDA frame: {} bytes ({})", data.length, VeepooDeviceSupport.hexBytes(data));
            return;
        }
        int action = data[1] & 0xFF;
        if (action == 1) {
            boolean ok = (data[2] & 0xFF) == 1;
            boolean wasStop;
            synchronized (liveWorkoutLock) {
                if (!workoutCommandPending) {
                    LOG.debug("Sport control ack without a pending command");
                    return;
                }
                if (precheckInProgress || stopPrecheckInProgress) {
                    // Ack for a readSportControlInfo of a start/stop pre-check; the report follows.
                    LOG.debug("Pre-check read ack ({}); waiting for the run-state report", ok ? "ok" : "failed");
                    return;
                }
                workoutCommandPending = false;
                wasStop = pendingCommandIsStop;
                pendingCommandIsStop = false;
                if (wasStop) {
                    // A failed stop leaves the session running on the band; the run-state poll
                    // retries it, so keep the session marked active until the band agrees.
                    if (ok) {
                        liveWorkoutActive = false;
                        stopRequested = false;
                    }
                } else {
                    liveWorkoutActive = ok;
                }
            }
            support.mainHandler.removeCallbacks(workoutCommandTimeout);
            if (wasStop) {
                LOG.info("Workout stopped by the app (ack: {})", ok ? "success" : "FAILED");
                GB.toast(support.getContext(), ok ? R.string.veepoo_workout_stopped : R.string.veepoo_workout_stop_failed,
                        Toast.LENGTH_SHORT, ok ? GB.INFO : GB.ERROR);
                if (ok) {
                    // The ack only means the command was accepted. Keep recording until the band
                    // reports the session as ended (the poll checks within a minute, which also
                    // catches the firmware acking a stop it ignores), with the hard deadline as a
                    // backstop, and fetch the finished session afterwards.
                    synchronized (liveWorkoutLock) {
                        lastStopAckOkAtMs = System.currentTimeMillis();
                    }
                    schedulePhoneGpsStopDeadline();
                    schedulePostStopFetch();
                    support.onLiveWorkoutStateChanged(false);
                }
            } else {
                LOG.info("Workout start command {}", ok ? "accepted" : "FAILED by the device");
                GB.toast(support.getContext(), ok ? R.string.veepoo_workout_started : R.string.veepoo_workout_start_failed,
                        Toast.LENGTH_SHORT, ok ? GB.INFO : GB.ERROR);
                if (ok) {
                    startPhoneGps();
                    support.onLiveWorkoutStateChanged(true);
                }
            }
            refreshLiveStatePolling();
            return;
        }

        if (data.length < 7) {
            LOG.debug("Dropping short 0xDA data frame: {} bytes ({})", data.length, VeepooDeviceSupport.hexBytes(data));
            return;
        }
        int chunkIndex = data[3] & 0xFF;
        byte[] payload;
        synchronized (liveWorkoutLock) {
            if (chunkIndex == 1) {
                liveReportChunks.clear();
                liveReportTotalChunks = data[6] & 0xFF;
            }
            liveReportChunks.add(data);
            if (chunkIndex < 1 || chunkIndex != liveReportTotalChunks) {
                return;
            }
            int total = 0;
            for (byte[] frame : liveReportChunks) {
                total += Math.max(frame.length - 4, 0);
            }
            payload = new byte[total];
            int offset = 0;
            for (byte[] frame : liveReportChunks) {
                int size = Math.max(frame.length - 4, 0);
                System.arraycopy(frame, 4, payload, offset, size);
                offset += size;
            }
            liveReportChunks.clear();
        }
        applyLiveWorkoutData(payload);
    }

    /**
     * Parses the assembled little-endian TLV stream of a live workout report and updates the
     * session state. Tags mirror the vendor's SportControlDataInfo: 0xA1 sport type (BE u16),
     * 0xA2 opcode, 0xA3 run state, 0xA4 device state, 0xA5 duration s, 0xA6 distance m,
     * 0xA7 heart rate bpm, 0xA8 calories, 0xA9 pace (BE u16), 0xAA speed (BE u16),
     * 0xAB GNSS flag + signal. Each tag is followed by a length byte that is skipped.
     */
    private void applyLiveWorkoutData(byte[] payload) {
        int sportType = 0, opcode = 0, runState = 0, duration = -1, distance = -1;
        int heartRate = 0, calories = -1, pace = -1, speed = -1;
        boolean gnss = false;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.hasRemaining()) {
                int tag = buffer.get() & 0xFF;
                switch (tag) {
                    case 0xA0:
                        buffer.get();
                        buffer.get();
                        break;
                    case 0xA1:
                        buffer.get();
                        sportType = ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
                        break;
                    case 0xA2:
                        buffer.get();
                        opcode = buffer.get() & 0xFF;
                        break;
                    case 0xA3:
                        buffer.get();
                        runState = buffer.get() & 0xFF;
                        break;
                    case 0xA4:
                        buffer.get();
                        break;
                    case 0xA5:
                        buffer.get();
                        duration = buffer.getInt();
                        break;
                    case 0xA6:
                        buffer.get();
                        distance = buffer.getInt();
                        break;
                    case 0xA7:
                        buffer.get();
                        heartRate = buffer.get() & 0xFF;
                        break;
                    case 0xA8:
                        buffer.get();
                        calories = buffer.getInt();
                        break;
                    case 0xA9:
                        buffer.get();
                        pace = ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
                        break;
                    case 0xAA:
                        buffer.get();
                        speed = ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
                        break;
                    case 0xAB:
                        buffer.get();
                        gnss = (buffer.get() & 0xFF) == 1;
                        break;
                    default:
                        LOG.debug("Unknown sport control TLV tag: 0x{}", Integer.toHexString(tag));
                }
            }
        } catch (BufferUnderflowException e) {
            LOG.warn("Truncated live workout report ({} bytes)", payload.length);
            return;
        }

        // RunState: 0 not started, 1 running, 2 paused, 3 resuming, 4 finished.
        final boolean sessionActive = runState == 1 || runState == 2 || runState == 3;
        int precheckDecision = 0; // 1 = proceed with start, -1 = session already active
        int stopPrecheckDecision = 0; // 1 = send stop, -1 = no active session
        final int[] startTypeRef = {0};
        final boolean[] adopted = {false};
        synchronized (liveWorkoutLock) {
            if (sportType > 0) {
                liveSportType = sportType;
            }
            if (runState == 4 || runState == 0) {
                liveWorkoutActive = false;
            } else if (sessionActive) {
                // A session the band runs that we never started in this connection (started from
                // the vendor app, or surviving a reconnect): adopt it so the card offers a stop.
                if (!liveWorkoutActive && !stopRequested) {
                    adopted[0] = true;
                }
                liveWorkoutActive = true;
            }
            if (precheckInProgress) {
                startTypeRef[0] = precheckSportType;
                precheckInProgress = false;
                precheckSportType = 0;
                if (sessionActive) {
                    workoutCommandPending = false;
                    precheckDecision = -1;
                } else {
                    precheckDecision = 1;
                }
            } else if (stopPrecheckInProgress) {
                stopPrecheckInProgress = false;
                if (sessionActive) {
                    stopPrecheckDecision = 1;
                } else {
                    workoutCommandPending = false;
                    pendingCommandIsStop = false;
                    stopPrecheckDecision = -1;
                }
            }
        }

        LOG.debug("Live workout: state {} ({} s, {} m, HR {}, {} kcal, pace {}, speed {}){}",
                runState, duration, distance, heartRate, calories, pace, speed, gnss ? ", GNSS" : "");
        if (precheckDecision == -1) {
            LOG.warn("The band already has an active workout; not starting a new one");
            GB.toast(support.getContext(), R.string.veepoo_workout_already_running, Toast.LENGTH_SHORT, GB.WARN);
        } else if (precheckDecision == 1) {
            support.mainHandler.post(() -> sendWorkoutStartCommand(startTypeRef[0]));
        }
        if (stopPrecheckDecision == 1) {
            int stopType = sportType;
            if (stopType == 0) {
                LOG.warn("Device reports an active session but no sport type; falling back to STOP(NONE)");
            }
            support.mainHandler.post(() -> sendStopCommand(stopType));
        } else if (stopPrecheckDecision == -1) {
            LOG.info("No active workout on the band; nothing to stop");
            GB.toast(support.getContext(), R.string.veepoo_workout_none_active, Toast.LENGTH_SHORT, GB.INFO);
        }
        if (runState == 0 || runState == 4) {
            handleSessionEndedByBand();
        } else if (sessionActive) {
            handleActiveSessionReport(sportType);
            if (adopted[0]) {
                LOG.info("The band reports a workout we were not tracking; adopting it");
                support.onLiveWorkoutStateChanged(true);
                startPhoneGps();
                refreshLiveStatePolling();
            }
        }
        persistLiveHeartRate(heartRate);
    }

    /** Stores a live workout heart rate so it shows up in the regular heart-rate views, and
     *  broadcasts it so the live activity screen can draw its HR line while the session runs. */
    private void persistLiveHeartRate(int bpm) {
        if (bpm < 30 || bpm > 220) {
            return;
        }
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(support.getDevice(), session).getId();

            GenericHeartRateSample sample = new GenericHeartRateSample();
            sample.setTimestamp(System.currentTimeMillis());
            sample.setHeartRate(bpm);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider(support.getDevice(), session).addSample(sample);
            support.notifyVitalSample(sample);
        } catch (Exception e) {
            LOG.error("Error saving live workout heart rate", e);
        }
    }

    /**
     * Starts the phone-side GPS capture when the "record workout with phone GPS" setting is on
     * and the phone GPS provider is available. The band has no GNSS of its own, so the route is
     * recorded on the phone and attached to the session once it is saved.
     */
    void startPhoneGps() {
        if (!support.getDevicePrefs().getBoolean(VeepooConstants.CONFIG_WORKOUT_PHONE_GPS, false)) {
            return;
        }
        if (!GBLocationService.isGpsSupportedAndEnabled()) {
            LOG.warn("Phone GPS is enabled for the workout but the phone GPS provider is off");
            GB.toast(support.getContext(), R.string.veepoo_workout_phone_gps_unavailable, Toast.LENGTH_SHORT, GB.WARN);
            return;
        }
        synchronized (liveWorkoutLock) {
            if (phoneGpsRecording) {
                return;
            }
            phoneGpsPoints.clear();
            phoneGpsStartMs = System.currentTimeMillis();
            phoneGpsRecording = true;
        }
        try {
            GBLocationService.start(support.getContext(), support.getDevice(), GBLocationProviderType.GPS, PHONE_GPS_INTERVAL_MS);
            LOG.info("Started the phone GPS recording for the live workout");
        } catch (Exception e) {
            synchronized (liveWorkoutLock) {
                phoneGpsRecording = false;
            }
            LOG.error("Error starting the phone GPS", e);
        }
    }

    /** Stops the phone-side GPS capture; the buffered points stay pending for saveSportSession. */
    void stopPhoneGps() {
        boolean wasRecording;
        synchronized (liveWorkoutLock) {
            wasRecording = phoneGpsRecording;
            phoneGpsRecording = false;
        }
        if (!wasRecording) {
            return;
        }
        try {
            GBLocationService.stop(support.getContext(), support.getDevice());
            LOG.info("Stopped the phone GPS recording");
        } catch (Exception e) {
            LOG.error("Error stopping the phone GPS", e);
        }
    }

    /**
     * Appends a fix from the shared phone GPS service to the pending track. Fixes with no
     * coordinates, duplicate/out-of-order timestamps and physically impossible jumps are dropped.
     */
    void addPhoneGpsPoint(@Nullable Location location) {
        if (location == null) {
            return;
        }
        synchronized (liveWorkoutLock) {
            if (!phoneGpsRecording) {
                return;
            }
            if (location.getLatitude() == 0 && location.getLongitude() == 0) {
                return; // some providers emit a (0,0) sentinel when there is no fix
            }
            final long timeMs = location.getTime();
            final ActivityPoint last = phoneGpsPoints.isEmpty() ? null : phoneGpsPoints.get(phoneGpsPoints.size() - 1);
            if (last != null) {
                if (timeMs <= last.getTime().getTime()) {
                    return;
                }
                final GPSCoordinate lastLocation = last.getLocation();
                if (lastLocation != null) {
                    final double meters = GPSCoordinate.distanceHaversine(
                            lastLocation, new GPSCoordinate(location.getLongitude(), location.getLatitude()));
                    final double seconds = (timeMs - last.getTime().getTime()) / 1000.0;
                    if (seconds > 0 && meters / seconds > MAX_GPS_SPEED_MPS) {
                        LOG.debug("Dropping an implausible GPS jump of {} m in {} s", (int) meters, (long) seconds);
                        return;
                    }
                }
            }
            final ActivityPoint point = new ActivityPoint(new Date(timeMs));
            final double altitude = location.getAltitude();
            if (Double.isFinite(altitude) && altitude > 0) {
                point.setLocation(new GPSCoordinate(location.getLongitude(), location.getLatitude(), altitude));
            } else {
                point.setLocation(new GPSCoordinate(location.getLongitude(), location.getLatitude()));
            }
            phoneGpsPoints.add(point);
        }
    }

    /** Returns the track buffered during the last finished workout, or null if no fix was recorded. */
    @Nullable
    PhoneGpsTrack consumePendingPhoneGpsTrack() {
        synchronized (liveWorkoutLock) {
            if (phoneGpsPoints.isEmpty()) {
                return null;
            }
            final PhoneGpsTrack track = new PhoneGpsTrack(new ArrayList<>(phoneGpsPoints), phoneGpsStartMs);
            phoneGpsPoints.clear();
            phoneGpsStartMs = 0L;
            return track;
        }
    }

    /** The phone GPS fixes recorded during the last finished workout. */
    static final class PhoneGpsTrack {
        final List<ActivityPoint> points;
        final long startMs;

        PhoneGpsTrack(final List<ActivityPoint> points, final long startMs) {
            this.points = points;
            this.startMs = startMs;
        }
    }

    void dispose() {
        support.mainHandler.removeCallbacks(postStopFetchFirst);
        support.mainHandler.removeCallbacks(postStopFetchRetry);
        support.mainHandler.removeCallbacks(workoutCommandTimeout);
        support.mainHandler.removeCallbacks(liveStatePollTask);
        support.mainHandler.removeCallbacks(phoneGpsStopDeadline);
        stopPhoneGps();
        synchronized (liveWorkoutLock) {
            phoneGpsPoints.clear();
        }
    }
}
