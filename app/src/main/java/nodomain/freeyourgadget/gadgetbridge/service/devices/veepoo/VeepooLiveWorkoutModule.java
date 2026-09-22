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
                GB.toast(support.getContext(), R.string.veepoo_workout_already_running, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            liveSportType = sportType;
            workoutCommandPending = true;
            pendingCommandIsStop = false;
            precheckInProgress = true;
            precheckSportType = sportType;
            liveReportChunks.clear();
        }
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
     * Stops the running workout with the same sport type it was started with (vendor behavior).
     * If the active sport type is unknown (session started elsewhere), the device run state is
     * queried first and its reported sport type used. After a successful ack, day 0 of the
     * workout history is re-fetched so the finished session shows up immediately.
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
        }
        try {
            TransactionBuilder builder = support.performInitialized("stop workout");
            if (liveSportType == 0) {
                // Unknown active sport type: the vendor's STOP(NONE) is acked but ignored by the
                // device, so query the run state first and stop with the reported sport type.
                synchronized (liveWorkoutLock) {
                    stopPrecheckInProgress = true;
                    liveReportChunks.clear();
                }
                support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL});
                support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL, 0x02,
                        0x00, 0x00, (byte) VeepooConstants.SPORT_CTRL_READ});
                LOG.info("Workout stop: querying the device for the active sport type");
            } else {
                support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL, 0x01,
                        (byte) (liveSportType & 0xFF), (byte) ((liveSportType >> 8) & 0xFF),
                        (byte) VeepooConstants.SPORT_CTRL_STOP});
                LOG.info("Workout stop requested");
            }
            builder.queue();
            support.mainHandler.postDelayed(workoutCommandTimeout, 10000L);
        } catch (IOException e) {
            synchronized (liveWorkoutLock) {
                workoutCommandPending = false;
                pendingCommandIsStop = false;
                stopPrecheckInProgress = false;
            }
            LOG.error("Error requesting workout stop", e);
        }
    }

    /** Sends the STOP command with the given sport type (vendor requires the active one). */
    private void sendStopCommand(int sportType) {
        synchronized (liveWorkoutLock) {
            if (sportType > 0) {
                liveSportType = sportType;
            }
            stopPrecheckInProgress = false;
        }
        try {
            TransactionBuilder builder = support.performInitialized("send workout stop command");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPORT_CONTROL, 0x01,
                    (byte) (liveSportType & 0xFF), (byte) ((liveSportType >> 8) & 0xFF),
                    (byte) VeepooConstants.SPORT_CTRL_STOP});
            builder.queue();
            support.mainHandler.postDelayed(workoutCommandTimeout, 10000L);
            LOG.info("Workout stop command sent (sport type {})", liveSportType);
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

    private final Runnable postStopFetchTask = () -> {
        synchronized (liveWorkoutLock) {
            postStopFetchScheduled = false;
        }
        support.startPostWorkoutFetch();
    };

    /** Delays the day-0 history fetch so the firmware can finalize the stopped session. */
    private void schedulePostStopFetch() {
        synchronized (liveWorkoutLock) {
            if (postStopFetchScheduled) {
                return;
            }
            postStopFetchScheduled = true;
        }
        // The device takes a while to finalize the finished session into day 0 (minutes have
        // been observed), so do not poll too early.
        support.mainHandler.postDelayed(postStopFetchTask, 60000L);
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
                liveWorkoutActive = !wasStop && ok;
            }
            support.mainHandler.removeCallbacks(workoutCommandTimeout);
            if (wasStop) {
                LOG.info("Workout stopped by the app (ack: {})", ok ? "success" : "FAILED");
                GB.toast(support.getContext(), ok ? R.string.veepoo_workout_stopped : R.string.veepoo_workout_stop_failed,
                        Toast.LENGTH_SHORT, ok ? GB.INFO : GB.ERROR);
                if (ok) {
                    stopPhoneGps();
                    schedulePostStopFetch();
                }
            } else {
                LOG.info("Workout start command {}", ok ? "accepted" : "FAILED by the device");
                GB.toast(support.getContext(), ok ? R.string.veepoo_workout_started : R.string.veepoo_workout_start_failed,
                        Toast.LENGTH_SHORT, ok ? GB.INFO : GB.ERROR);
                if (ok) {
                    startPhoneGps();
                }
            }
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

        boolean finished = false;
        int precheckDecision = 0; // 1 = proceed with start, -1 = session already active
        int stopPrecheckDecision = 0; // 1 = send stop, -1 = no active session
        final int[] startTypeRef = {0};
        synchronized (liveWorkoutLock) {
            if (sportType > 0) {
                liveSportType = sportType;
            }
            // RunState: 0 not started, 1 running, 2 paused, 3 resuming, 4 finished.
            if (runState == 4) {
                finished = true;
                liveWorkoutActive = false;
            } else if (runState == 0) {
                liveWorkoutActive = false;
            } else if (runState == 1 || runState == 2 || runState == 3) {
                liveWorkoutActive = true;
            }
            boolean sessionActive = runState == 1 || runState == 2 || runState == 3;
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
        if (finished) {
            stopPhoneGps();
            schedulePostStopFetch();
        }
        persistLiveHeartRate(heartRate);
    }

    /** Stores a live workout heart rate so it shows up in the regular heart-rate views. */
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
        support.mainHandler.removeCallbacks(postStopFetchTask);
        support.mainHandler.removeCallbacks(workoutCommandTimeout);
        stopPhoneGps();
        synchronized (liveWorkoutLock) {
            phoneGpsPoints.clear();
        }
    }
}
