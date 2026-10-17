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

import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooEcgSample;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Feature module for the single-lead ECG capture (opcode 0x93 function 1, incl. F002 light-channel frames). */
class VeepooEcgModule {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooEcgModule.class);

    private VeepooDeviceSupport support;

    VeepooEcgModule(VeepooDeviceSupport support) {
        this.support = support;
    }

    private boolean ecgDetectActive = false;
    private int ecgFrameCount = 0;
    private int ecgLatestHr = 0;
    private int ecgFinalHr = 0; // heart rate from the result frame, when the device sends one
    private int ecgProgress = 0; // acquisition progress 0-100 % from the per-second status frames
    private int ecgDrawFrequency = 0; // sample rate from the info frame (Hz)
    private int ecgNoContactStreak = 0; // consecutive status frames with the wear flag set
    private long ecgStartedAtMs = 0L;
    private long ecgLastNotifyMs = 0L;
    private final ByteArrayOutputStream ecgWaveformBuffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream ecgResultBlobBuffer = new ByteArrayOutputStream();
    private int ecgSampleCount = 0;
    private final Runnable ecgDetectTimeout = () -> {
        LOG.warn("ECG measurement window elapsed without a device completion event");
        finishEcgDetect(false, R.string.veepoo_ecg_failed_timeout);
    };

    // ---------------------------------------------------------------------
    // ECG single-lead detection, opcode 0x93 function 1 (capture phase)
    // ---------------------------------------------------------------------

    private static final long ECG_NOTIFY_INTERVAL_MS = 800L;
    /** Safety net only: normally the device signals completion itself (subcommand 4). */
    private static final long ECG_DETECTION_WINDOW_MS = 90_000L;

    /**
     * On-demand single-lead ECG measurement: start {0x93,1,1,1}; the device streams the waveform
     * for a fixed ~40 s acquisition and then signals completion (subcommand 4) or failure
     * (subcommand 3). The {@link #ECG_DETECTION_WINDOW_MS} timer is only a safety net, and a
     * no-contact watchdog aborts early when the wear flag stays set.
     */
    void startEcgDetect() {
        synchronized (this) {
            if (ecgDetectActive) {
                LOG.warn("An ECG measurement is already in progress");
                GB.toast(support.getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (support.isFetchInProgress()) {
                LOG.warn("ECG measurement ignored: a data fetch is in progress");
                GB.toast(support.getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            ecgDetectActive = true;
            ecgFrameCount = 0;
            ecgSampleCount = 0;
            ecgLatestHr = 0;
            ecgFinalHr = 0;
            ecgProgress = 0;
            ecgDrawFrequency = 0;
            ecgNoContactStreak = 0;
            ecgStartedAtMs = System.currentTimeMillis();
            ecgLastNotifyMs = 0L;
            ecgWaveformBuffer.reset();
            ecgResultBlobBuffer.reset();
        }
        try {
            TransactionBuilder builder = support.performInitialized("ECG detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_ECG_REALTIME,
                    (byte) VeepooConstants.ECG_DETECT_FUNCTION, (byte) VeepooConstants.ECG_DETECT_START, 0x01});
            builder.queue();
            support.mainHandler.postDelayed(ecgDetectTimeout, ECG_DETECTION_WINDOW_MS);
            LOG.info("ECG detection started");
        } catch (IOException e) {
            synchronized (this) {
                ecgDetectActive = false;
            }
            LOG.error("Error starting ECG detection", e);
        }
    }

    /**
     * Ends the measurement: stops the stream (unless the device already signalled completion),
     * stores what was captured and announces it. {@code failCode} 0 means success; otherwise a
     * string resource id describing why it failed (no storage, toast shown). Runs the
     * persistence on the main handler.
     */
    private void finishEcgDetect(boolean deviceSignalled, int failCode) {
        synchronized (this) {
            if (!ecgDetectActive) {
                return;
            }
            ecgDetectActive = false;
        }
        support.mainHandler.removeCallbacks(ecgDetectTimeout);
        if (!deviceSignalled) {
            try {
                TransactionBuilder builder = support.performInitialized("stop ECG detect");
                support.writeFrame(builder, new byte[]{VeepooConstants.OP_ECG_REALTIME,
                        (byte) VeepooConstants.ECG_DETECT_FUNCTION,
                        (byte) VeepooConstants.ECG_DETECT_STOP, 0x01});
                builder.queue();
            } catch (IOException e) {
                LOG.warn("Error stopping ECG detection", e);
            }
        }
        long durationMs = System.currentTimeMillis() - ecgStartedAtMs;
        int frameCount = ecgFrameCount;
        int sampleCount = ecgSampleCount;
        int heartRate = ecgFinalHr > 0 ? ecgFinalHr : ecgLatestHr;
        LOG.info("ECG measurement finished ({}): {} samples in {} frames over {} ms (HR {}, progress {} %, sample rate {} Hz, failCode {})",
                deviceSignalled ? "device event" : "timeout", sampleCount, frameCount, durationMs,
                heartRate, ecgProgress, ecgDrawFrequency, failCode);
        support.mainHandler.post(() -> {
            if (failCode != 0) {
                GB.toast(support.getContext(), failCode, Toast.LENGTH_LONG, GB.ERROR);
            }
            long savedId = failCode == 0 ? saveEcgSample(durationMs, frameCount, sampleCount) : 0L;
            support.notifyVitalSample(new VeepooDeviceSupport.VeepooEcgMeasurementResult(frameCount, sampleCount, heartRate,
                    ecgProgress, ecgDrawFrequency, savedId, failCode));
        });
    }

    /** Persists the measurement (summary + raw waveform) and returns the new row id (0 on failure). */
    private long saveEcgSample(long durationMs, int frameCount, int sampleCount) {
        if (sampleCount == 0) {
            LOG.warn("ECG measurement produced no samples; not storing");
            return 0L;
        }
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(support.getDevice(), session).getId();
            VeepooEcgSample sample = new VeepooEcgSample();
            sample.setTimestamp(ecgStartedAtMs);
            sample.setHeartRate(ecgLatestHr);
            sample.setSampleRate(ecgDrawFrequency);
            sample.setDurationMs((int) durationMs);
            sample.setSampleCount(sampleCount);
            sample.setResultBlob(ecgResultBlobBuffer.toByteArray());
            sample.setWaveform(ecgWaveformBuffer.toByteArray());
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            return session.getVeepooEcgSampleDao().insert(sample);
        } catch (Exception e) {
            LOG.error("Error saving ECG sample", e);
            return 0L;
        }
    }

    /**
     * Opcode 0x93 frame with [1]==1: ECG control/status on the main channel. The subcommand is
     * at [3]: 0 = info (little-endian u16 sample rate at [4..5]), 1 = per-second status (see
     * {@link VeepooConstants} for the layout; live HR is a single byte at [5], progress at [19],
     * wear flag at [12]), 2 = result frame (average heart rate at [13]; not sent by all
     * firmwares), 3 = failed on the device, 4 = finished successfully (the device-side
     * completion event; result chunks arrive just before it), 5 = diagnosis chunk (seq/total at
     * [4]/[5]) whose 14-byte payload at [6..19] is stored raw.
     */
    void handleFrame(byte[] data) {
        if (data.length < 4 || data[1] != VeepooConstants.ECG_DETECT_FUNCTION) {
            return;
        }
        synchronized (this) {
            if (!ecgDetectActive) {
                return;
            }
        }
        LOG.debug("ECG control frame: {}", VeepooDeviceSupport.hexBytes(data));
        int sub = data[3];
        if (sub == 4) {
            finishEcgDetect(true, 0);
            return;
        }
        if (sub == 3) {
            LOG.warn("ECG device reported measurement failure");
            finishEcgDetect(true, R.string.veepoo_ecg_failed_device);
            return;
        }
        if (sub == 2 && data.length >= 18) {
            int hr = data[13] & 0xFF;
            if (hr >= 30 && hr <= 250) {
                ecgFinalHr = hr;
            }
            return;
        }
        if (data.length < 20) {
            return;
        }
        if (sub == 0) {
            ecgDrawFrequency = (data[VeepooConstants.ECG_INFO_SAMPLE_RATE_OFFSET] & 0xFF)
                    | ((data[VeepooConstants.ECG_INFO_SAMPLE_RATE_OFFSET + 1] & 0xFF) << 8);
            LOG.info("ECG info frame: sample rate {} Hz", ecgDrawFrequency);
        } else if (sub == 1) {
            int deviceState = data[VeepooConstants.ECG_STATUS_DEVICE_STATE_OFFSET] & 0xFF;
            int hr = data[VeepooConstants.ECG_STATUS_HR_OFFSET] & 0xFF;
            if (hr >= 30 && hr <= 250) {
                ecgLatestHr = hr;
            }
            int wear = data[VeepooConstants.ECG_STATUS_WEAR_OFFSET];
            int progress = data[VeepooConstants.ECG_STATUS_PROGRESS_OFFSET] & 0xFF;
            if (progress <= 100) {
                ecgProgress = progress;
            }
            // No-contact watchdog: the vendor app aborts once the wear flag stays set for about
            // five consecutive status frames (after a couple of seconds of data).
            boolean noContact = (wear == 1) || deviceState == VeepooConstants.ECG_DEVICE_STATE_UNPASS_WEAR;
            synchronized (this) {
                if (!ecgDetectActive) {
                    return;
                }
                long runningMs = System.currentTimeMillis() - ecgStartedAtMs;
                if (noContact && runningMs > 3000) {
                    ecgNoContactStreak++;
                    LOG.warn("ECG status reports no contact/wear (streak {})", ecgNoContactStreak);
                    if (ecgNoContactStreak >= 5) {
                        finishEcgDetect(false, R.string.veepoo_ecg_failed_no_contact);
                        return;
                    }
                } else {
                    ecgNoContactStreak = 0;
                }
            }
            notifyEcgProgress();
        } else if (sub == 5) {
            synchronized (this) {
                int seq = data[4] & 0xFF;
                if (seq == 1) {
                    ecgResultBlobBuffer.reset();
                }
                if (data.length >= 20) {
                    ecgResultBlobBuffer.write(data, 6, 14);
                }
            }
        }
    }

    /** Throttled progress announcement while the measurement is running. */
    private void notifyEcgProgress() {
        synchronized (this) {
            if (!ecgDetectActive) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - ecgLastNotifyMs < ECG_NOTIFY_INTERVAL_MS) {
                return;
            }
            ecgLastNotifyMs = now;
            support.notifyVitalSample(new VeepooDeviceSupport.VeepooEcgMeasurementResult(ecgFrameCount, ecgSampleCount,
                    ecgLatestHr, ecgProgress, ecgDrawFrequency, 0L, 0));
        }
    }

    /**
     * Light-origin channel frame: {@code 0x88} + waveform samples (big-endian signed int24,
     * 3 bytes each) + a 4-byte constant tail ending in a per-frame sequence counter. Decoded
     * samples are appended to the measurement buffer; invalid markers (0xFFFFFF / 0x020202) are
     * skipped. Full frames stay in the debug log for protocol verification.
     */
    void handleLightFrame(byte[] value) {
        synchronized (this) {
            if (!ecgDetectActive) {
                LOG.debug("Dropping non-ECG F002 frame: {} bytes, first byte 0x{:02X}",
                        value.length, value[0] & 0xFF);
                return; // non-ECG light traffic (e.g. live PPG) outside a measurement
            }
        }
        int payloadStart = 1;
        int payloadLen = value.length - VeepooConstants.ECG_LIGHT_TAIL_LEN - payloadStart;
        if (value.length >= VeepooConstants.ECG_LIGHT_MIN_FRAME_LEN && payloadLen >= 3 && payloadLen % 3 == 0) {
            synchronized (this) {
                ecgFrameCount++;
                for (int i = 0; i < payloadLen / 3; i++) {
                    int b0 = value[payloadStart + 3 * i] & 0xFF;
                    int b1 = value[payloadStart + 3 * i + 1] & 0xFF;
                    int b2 = value[payloadStart + 3 * i + 2] & 0xFF;
                    int raw = (b0 << 16) | (b1 << 8) | b2;
                    if (raw == 0xFFFFFF || raw == 0x020202) {
                        continue; // invalid-sample marker
                    }
                    ecgWaveformBuffer.write(b0);
                    ecgWaveformBuffer.write(b1);
                    ecgWaveformBuffer.write(b2);
                    ecgSampleCount++;
                }
            }
            notifyEcgProgress();
        }
        LOG.debug("ECG light frame: len={}: {}", value.length, VeepooDeviceSupport.hexBytes(value));
    }

    void dispose() {
        support.mainHandler.removeCallbacks(ecgDetectTimeout);
    }
}
