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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBodyCompositionSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodLipidSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Feature module for the single-shot vital detections (HR 0xD0, SpO2 0x80, temperature 0x87, stress 0x89, BP 0x90, body composition 0x93 function 4, lipids 0x8A). */
class VeepooVitalDetectModule {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooVitalDetectModule.class);

    private VeepooDeviceSupport support;

    VeepooVitalDetectModule(VeepooDeviceSupport support) {
        this.support = support;
    }

    // On-demand heart rate detection state (0xD0 single-shot, mirrors the vendor home-screen flow)
    private boolean hrDetectActive = false;
    private boolean hrWearErrorSeen = false;
    private final Runnable hrDetectTimeout = () -> {
        LOG.warn("Heart rate detection timed out");
        finishHrDetect(null);
    };

    // On-demand vital detection state (SpO2 0x80, temperature 0x87, stress 0x89)
    private static final int VITAL_FAIL_NONE = 0;
    private static final int VITAL_FAIL_WEAR = 1;
    private static final int VITAL_FAIL_LOW_BATTERY = 2;
    private static final int VITAL_FAIL_BUSY = 3;
    private static final int VITAL_FAIL_NOT_SUPPORTED = 4;

    private boolean spo2DetectActive = false;
    private boolean spo2WearErrorSeen = false;
    private final Runnable spo2DetectTimeout = () -> {
        LOG.warn("SpO2 detection timed out");
        finishSpo2Detect(null, null, VITAL_FAIL_NONE);
    };
    private final Runnable spo2IdleWatchdog = () -> {
        synchronized (this) {
            if (!spo2DetectActive) {
                return;
            }
        }
        LOG.warn("SpO2 stream went idle without a reading");
        finishSpo2Detect(null, null, VITAL_FAIL_NONE);
    };

    private boolean tempDetectActive = false;
    private final Runnable tempDetectTimeout = () -> {
        LOG.warn("Temperature detection timed out");
        finishTemperatureDetect(null, null, VITAL_FAIL_NONE);
    };
    private final Runnable tempIdleWatchdog = () -> {
        synchronized (this) {
            if (!tempDetectActive) {
                return;
            }
        }
        LOG.warn("Temperature stream went idle without a reading");
        finishTemperatureDetect(null, null, VITAL_FAIL_NONE);
    };

    private boolean stressDetectActive = false;
    private final Runnable stressDetectTimeout = () -> {
        LOG.warn("Stress detection timed out");
        finishStressDetect(null, VITAL_FAIL_NONE);
    };
    private final Runnable stressIdleWatchdog = () -> {
        synchronized (this) {
            if (!stressDetectActive) {
                return;
            }
        }
        LOG.warn("Stress stream went idle without a reading");
        finishStressDetect(null, VITAL_FAIL_NONE);
    };

    private boolean bpDetectActive = false;
    private final Runnable bpDetectTimeout = () -> {
        LOG.warn("Blood pressure detection timed out");
        finishBpDetect(null, null, VITAL_FAIL_NONE);
    };
    private final Runnable bpIdleWatchdog = () -> {
        synchronized (this) {
            if (!bpDetectActive) {
                return;
            }
        }
        LOG.warn("Blood pressure stream went idle without a reading");
        finishBpDetect(null, null, VITAL_FAIL_NONE);
    };

    private boolean bcDetectActive = false;
    private final ArrayList<byte[]> bcChunks = new ArrayList<>();
    private final Runnable bcDetectTimeout = () -> {
        LOG.warn("Body composition detection timed out");
        finishBcDetect(null, VITAL_FAIL_NONE);
    };
    private final Runnable bcIdleWatchdog = () -> {
        synchronized (this) {
            if (!bcDetectActive) {
                return;
            }
        }
        LOG.warn("Body composition stream went idle without a result");
        finishBcDetect(null, VITAL_FAIL_NONE);
    };

    private boolean lipidDetectActive = false;
    private final Runnable lipidDetectTimeout = () -> {
        LOG.warn("Blood lipid detection timed out");
        finishLipidDetect(null, VITAL_FAIL_NONE);
    };
    private final Runnable lipidIdleWatchdog = () -> {
        synchronized (this) {
            if (!lipidDetectActive) {
                return;
            }
        }
        LOG.warn("Blood lipid stream went idle without a result");
        finishLipidDetect(null, VITAL_FAIL_NONE);
    };

    /**
     * Opcode 0xD0 stream: [1] = bpm (1/2 = not worn), [5] = sensor status (busy unless 0/2/3).
     * While a single-shot read is active the first plausible reading completes it; frames
     * outside a read are autonomous auto-measure pushes and are persisted silently. The band
     * also sends a compact push form ({0xD0, bpm}, no status byte) from its monitor feature.
     */
    void handleHrFrame(byte[] data) {
        if (data.length < 2) {
            LOG.debug("Dropping short 0xD0 frame: {} bytes", data.length);
            return;
        }
        final boolean longForm = data.length >= 6;
        int bpm = data[1] & 0xFF;
        int status = longForm ? data[5] & 0xFF : 0;
        synchronized (this) {
            if (!hrDetectActive) {
                persistAutonomousHeartRate(bpm, status);
                return;
            }
        }
        if (!longForm) {
            LOG.debug("Ignoring compact 0xD0 push during a manual heart-rate detect");
            return;
        }
        if (status != 0 && status != 2 && status != 3) {
            LOG.debug("Heart rate detect busy: bpm={} status={}", bpm, status);
            return;
        }
        if (bpm == 1 || bpm == 2) {
            synchronized (this) {
                hrWearErrorSeen = true;
            }
            LOG.debug("Heart rate detect: band not worn");
            return;
        }
        if (bpm < 30 || bpm > 210) {
            LOG.debug("Ignoring implausible heart rate measurement: {}", bpm);
            return;
        }
        LOG.info("Heart rate measurement: {} bpm", bpm);
        finishHrDetect(bpm);
    }

    /** Persists an autonomous auto-measure heart-rate push (no detect session in progress). */
    private void persistAutonomousHeartRate(int bpm, int status) {
        if (status != 0 && status != 2 && status != 3) {
            LOG.debug("Ignoring autonomous heart rate push: sensor status {} (expected 0/2/3)", status);
            return;
        }
        if (bpm == 1 || bpm == 2 || bpm < 30 || bpm > 210) {
            LOG.debug("Ignoring autonomous heart rate push: bpm {} not plausible", bpm);
            return;
        }
        LOG.info("Autonomous heart rate push: {} bpm", bpm);
        support.saveHeartRateSample(bpm);
    }

    /**
     * On-demand heart rate measurement, mirroring the vendor app's home-screen flow: start the
     * 0xD0 stream ({0xD0, 1}), keep the first plausible reading, then stop the stream
     * ({0xD0, 0}). The band keeps streaming until stopped or the timeout fires.
     */
    void startHeartRateDetect() {
        synchronized (this) {
            if (hrDetectActive) {
                LOG.warn("A heart rate read is already in progress");
                GB.toast(support.getContext(), R.string.veepoo_manual_hr_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (support.isFetchInProgress()) {
                LOG.warn("Heart rate read ignored: a data fetch is in progress");
                GB.toast(support.getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            hrDetectActive = true;
            hrWearErrorSeen = false;
        }
        try {
            TransactionBuilder builder = support.performInitialized("heart rate detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_HR_REALTIME, 0x01});
            builder.queue();
            support.mainHandler.postDelayed(hrDetectTimeout, 45_000L);
            LOG.info("Heart rate detection started");
        } catch (IOException e) {
            synchronized (this) {
                hrDetectActive = false;
            }
            LOG.error("Error starting heart rate detection", e);
        }
    }

    /** Sends the 0xD0 stop frame; errors are only logged. */
    private void sendStopHeartRateDetect() {
        try {
            TransactionBuilder builder = support.performInitialized("stop heart rate detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_HR_REALTIME, 0x00});
            builder.queue();
            LOG.info("Heart rate detection stopped");
        } catch (IOException e) {
            LOG.warn("Error stopping heart rate detection", e);
        }
    }

    /** Completes the single-shot read: stops the stream, then stores or reports the result. */
    private void finishHrDetect(Integer bpm) {
        boolean wearError;
        synchronized (this) {
            if (!hrDetectActive) {
                return;
            }
            hrDetectActive = false;
            wearError = hrWearErrorSeen;
        }
        support.mainHandler.removeCallbacks(hrDetectTimeout);
        sendStopHeartRateDetect();
        if (bpm != null) {
            GenericHeartRateSample sample = support.saveHeartRateSample(bpm);
            if (sample != null) {
                support.notifyVitalSample(sample);
            }
            GB.toast(support.getContext(), String.format(support.getContext().getString(R.string.veepoo_hr_ok), bpm), Toast.LENGTH_SHORT, GB.INFO);
        } else if (wearError) {
            GB.toast(support.getContext(), R.string.veepoo_hr_not_worn, Toast.LENGTH_SHORT, GB.WARN);
        } else {
            GB.toast(support.getContext(), R.string.veepoo_hr_no_reading, Toast.LENGTH_SHORT, GB.WARN);
        }
    }

    // ---------------------------------------------------------------------
    // SpO2 (blood oxygen) single-shot detection, opcode 0x80
    // ---------------------------------------------------------------------

    /**
     * On-demand SpO2 measurement: start the 0x80 stream ({0x80,1,2}); frames report progress and
     * finally a percentage; stop the stream ({0x80,2,2}) on completion or timeout.
     */
    void startSpo2Detect() {
        synchronized (this) {
            if (spo2DetectActive) {
                LOG.warn("A SpO2 measurement is already in progress");
                GB.toast(support.getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (support.isFetchInProgress()) {
                LOG.warn("SpO2 measurement ignored: a data fetch is in progress");
                GB.toast(support.getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            spo2DetectActive = true;
            spo2WearErrorSeen = false;
        }
        try {
            TransactionBuilder builder = support.performInitialized("spo2 detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_SPO2_REALTIME,
                    (byte) VeepooConstants.SPO2_DETECT_START, (byte) VeepooConstants.SPO2_DETECT_STOP});
            builder.queue();
            support.mainHandler.postDelayed(spo2DetectTimeout, 90_000L);
            support.mainHandler.postDelayed(spo2IdleWatchdog, 45_000L);
            LOG.info("SpO2 detection started");
        } catch (IOException e) {
            synchronized (this) {
                spo2DetectActive = false;
            }
            LOG.error("Error starting SpO2 detection", e);
        }
    }

    /**
     * Opcode 0x80 frame: [1]=state (0 not supported, 1 open, 2 close), [2]=what the device is
     * doing (4 = measuring SpO2, 10 = charging low, 11 = finished, 12 = not worn,
     * 13 = keep still), [3]=SpO2 percent (75-99 valid, 1 = not worn), [4]!=0 => measuring with
     * [5] progress; [6]==6 => [7]=pulse rate.
     */
    void handleSpo2Frame(byte[] data) {
        if (data.length < 6) {
            LOG.debug("Dropping short 0x80 frame: {}", VeepooDeviceSupport.hexBytes(data));
            return;
        }
        synchronized (this) {
            if (!spo2DetectActive) {
                persistAutonomousSpo2(data);
                return;
            }
        }
        LOG.debug("SpO2 frame: {}", VeepooDeviceSupport.hexBytes(data));
        support.mainHandler.removeCallbacks(spo2IdleWatchdog);
        support.mainHandler.postDelayed(spo2IdleWatchdog, 45_000L);
        int state = data[1] & 0xFF;
        if (state == 0) {
            LOG.warn("Band reports SpO2 as not supported");
            finishSpo2Detect(null, null, VITAL_FAIL_NOT_SUPPORTED);
            return;
        }
        int deviceState = data[2] & 0xFF;
        int value = data[3] & 0xFF;
        Integer hr = null;
        if (data.length >= 8 && data[6] == 6) {
            int rate = data[7] & 0xFF;
            if (rate >= 30 && rate <= 210) {
                hr = rate;
            }
        }
        if (value == 1 || deviceState == 12) {
            synchronized (this) {
                spo2WearErrorSeen = true;
            }
            LOG.debug("SpO2 detect: band not worn");
            finishSpo2Detect(null, hr, VITAL_FAIL_WEAR);
            return;
        }
        if (deviceState == 10) {
            finishSpo2Detect(null, hr, VITAL_FAIL_LOW_BATTERY);
            return;
        }
        int checking = data[4] & 0xFF;
        if (checking == 1 || checking == 2) {
            LOG.debug("SpO2 in progress: {}% (deviceState={})", data[5] & 0xFF, deviceState);
            return;
        }
        if (value >= 75 && value <= 99) {
            finishSpo2Detect(value, hr, VITAL_FAIL_NONE);
        } else if (state == 2 || deviceState == 11) {
            // The band closed the measurement or finished without a valid value.
            LOG.debug("SpO2 measurement ended without a reading (state={}, deviceState={})", state, deviceState);
            finishSpo2Detect(null, hr, VITAL_FAIL_NONE);
        } else {
            LOG.debug("Ignoring implausible SpO2 frame: value={} state={} deviceState={}", value, state, deviceState);
        }
    }

    /** Persists an autonomous auto-measure SpO2 push (no detect session in progress). */
    private void persistAutonomousSpo2(byte[] data) {
        int state = data[1] & 0xFF;
        if (state == 0) {
            LOG.debug("Ignoring autonomous SpO2 push: state byte 0");
            return;
        }
        int deviceState = data[2] & 0xFF;
        int value = data[3] & 0xFF;
        Integer hr = null;
        if (data.length >= 8 && data[6] == 6) {
            int rate = data[7] & 0xFF;
            if (rate >= 30 && rate <= 210) {
                hr = rate;
            }
        }
        if (value == 1 || deviceState == 12) { // not worn
            LOG.debug("Ignoring autonomous SpO2 push: not worn (value={} deviceState={})", value, deviceState);
            return;
        }
        int checking = data[4] & 0xFF;
        if (checking == 1 || checking == 2) { // still measuring
            LOG.debug("Ignoring autonomous SpO2 push: still measuring (checking={}) value={}", checking, value);
            return;
        }
        if (value >= 75 && value <= 99) {
            LOG.info("Autonomous SpO2 push: {}% (hr={})", value, hr);
            support.saveSpo2Sample(value);
            if (hr != null) {
                support.saveHeartRateSample(hr);
            }
        }
    }

    /** Completes the SpO2 read: stops the stream, then stores or reports the result. */
    private void finishSpo2Detect(Integer spo2, Integer hr, int failure) {
        synchronized (this) {
            if (!spo2DetectActive) {
                return;
            }
            spo2DetectActive = false;
        }
        support.mainHandler.removeCallbacks(spo2DetectTimeout);
        support.mainHandler.removeCallbacks(spo2IdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_SPO2_REALTIME,
                VeepooConstants.SPO2_DETECT_STOP, VeepooConstants.SPO2_DETECT_STOP);
        if (spo2 != null) {
            GenericSpo2Sample sample = support.saveSpo2Sample(spo2);
            if (sample != null) {
                support.notifyVitalSample(sample);
                GB.toast(support.getContext(), support.getContext().getString(R.string.veepoo_spo2_ok, spo2), Toast.LENGTH_SHORT, GB.INFO);
            }
            if (hr != null) {
                support.saveHeartRateSample(hr);
            }
        } else {
            toastVitalFailure(R.string.veepoo_spo2_no_reading, failure);
        }
    }

    // ---------------------------------------------------------------------
    // Body temperature single-shot detection, opcode 0x87
    // ---------------------------------------------------------------------

    /** On-demand body-temperature measurement: start {0x87,1,1}, stop {0x87,1,2}. */
    void startTemperatureDetect() {
        synchronized (this) {
            if (tempDetectActive) {
                LOG.warn("A temperature measurement is already in progress");
                GB.toast(support.getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (support.isFetchInProgress()) {
                LOG.warn("Temperature measurement ignored: a data fetch is in progress");
                GB.toast(support.getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            tempDetectActive = true;
        }
        try {
            TransactionBuilder builder = support.performInitialized("temperature detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_TEMP_REALTIME,
                    (byte) VeepooConstants.TEMP_DETECT_START, (byte) VeepooConstants.TEMP_DETECT_START});
            builder.queue();
            support.mainHandler.postDelayed(tempDetectTimeout, 60_000L);
            support.mainHandler.postDelayed(tempIdleWatchdog, 45_000L);
            LOG.info("Temperature detection started");
        } catch (IOException e) {
            synchronized (this) {
                tempDetectActive = false;
            }
            LOG.error("Error starting temperature detection", e);
        }
    }

    /**
     * Opcode 0x87 frame (per vendor TemptureDetectOperater): [2]=oprate (0=invalid, 2=stop ack,
     * else running), [3]=deviceState (0/7=running, 1..6=busy, 8=low battery), [4]=progress %,
     * [5..6] uint16 BE / 10.0 = body temperature, [7..8] uint16 BE / 10.0 = base (skin)
     * temperature. The band streams one frame per second while state=7 and progress climbs;
     * the measurement completes with the frame reaching progress >= 100 carrying a reading.
     */
    void handleTemperatureFrame(byte[] data) {
        if (data.length < 9) {
            LOG.debug("Dropping short 0x87 frame: {}", VeepooDeviceSupport.hexBytes(data));
            return;
        }
        synchronized (this) {
            if (!tempDetectActive) {
                persistAutonomousTemperature(data);
                return;
            }
        }
        LOG.debug("Temperature frame: {}", VeepooDeviceSupport.hexBytes(data));
        support.mainHandler.removeCallbacks(tempIdleWatchdog);
        support.mainHandler.postDelayed(tempIdleWatchdog, 45_000L);
        int oprate = data[2] & 0xFF;
        if (oprate == 0) {
            LOG.warn("Temperature measurement rejected as invalid by the band");
            finishTemperatureDetect(null, null, VITAL_FAIL_NONE);
            return;
        }
        if (oprate != 2) {
            int deviceState = data[3] & 0xFF;
            if (deviceState >= 1 && deviceState <= 6) {
                LOG.warn("Temperature measurement: band is busy (deviceState={})", deviceState);
                finishTemperatureDetect(null, null, VITAL_FAIL_BUSY);
                return;
            }
            if (deviceState == 8) {
                LOG.warn("Temperature measurement: low battery");
                finishTemperatureDetect(null, null, VITAL_FAIL_LOW_BATTERY);
                return;
            }
        }
        int progress = data[4] & 0xFF;
        Float body = plausibleTemperature(data[5], data[6]);
        Float base = plausibleTemperature(data[7], data[8]);
        if (progress >= 100 && body != null) {
            LOG.info("Temperature measurement complete: body={} C, base={} C", body, base);
            finishTemperatureDetect(body, base, VITAL_FAIL_NONE);
            return;
        }
        LOG.debug("Temperature in progress: {}% (raw [5..8]={})", progress,
                String.format("%02X %02X %02X %02X", data[5] & 0xFF, data[6] & 0xFF, data[7] & 0xFF, data[8] & 0xFF));
    }

    /** Persists an autonomous auto-measure temperature push (no detect session in progress). */
    private void persistAutonomousTemperature(byte[] data) {
        if ((data[2] & 0xFF) == 0) {
            LOG.debug("Ignoring autonomous temperature push: data[2]=0");
            return;
        }
        int progress = data[4] & 0xFF;
        Float body = plausibleTemperature(data[5], data[6]);
        if (progress >= 100 && body != null) {
            LOG.info("Autonomous temperature push: {} C", body);
            support.saveTemperatureSample(body, TemperatureSample.TYPE_BODY);
        } else {
            LOG.debug("Ignoring autonomous temperature push: progress={} body={}", progress, body);
        }
    }

    /**
     * Interprets two raw bytes as a temperature in tenths of a degree, accepting whichever byte
     * order (little- or big-endian) yields a plausible human body/skin temperature. Returns null
     * when neither order is plausible (i.e. no valid reading in this frame yet).
     */
    private Float plausibleTemperature(byte lowIndex, byte highIndex) {
        int le = (lowIndex & 0xFF) | ((highIndex & 0xFF) << 8);
        float tLe = le / 10.0f;
        if (tLe >= 20.0f && tLe <= 45.0f) {
            return tLe;
        }
        int be = (highIndex & 0xFF) | ((lowIndex & 0xFF) << 8);
        float tBe = be / 10.0f;
        if (tBe >= 20.0f && tBe <= 45.0f) {
            return tBe;
        }
        return null;
    }

    /** Completes the temperature read: stops the stream, then stores or reports the result. */
    private void finishTemperatureDetect(Float body, Float base, int failure) {
        synchronized (this) {
            if (!tempDetectActive) {
                return;
            }
            tempDetectActive = false;
        }
        support.mainHandler.removeCallbacks(tempDetectTimeout);
        support.mainHandler.removeCallbacks(tempIdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_TEMP_REALTIME,
                VeepooConstants.TEMP_DETECT_START, VeepooConstants.TEMP_DETECT_STOP);
        if (body != null) {
            final GenericTemperatureSample bodySample = support.saveTemperatureSample(body, TemperatureSample.TYPE_BODY);
            final GenericTemperatureSample skinSample = base == null ? null : support.saveTemperatureSample(base, TemperatureSample.TYPE_SKIN);
            if (bodySample == null || (base != null && skinSample == null)) {
                LOG.warn("Temperature measurement completed but sample persistence failed");
            }
            support.notifyVitalSample(new VeepooDeviceSupport.VeepooTemperatureMeasurementResult(body, base));
            GB.toast(support.getContext(), support.getContext().getString(R.string.veepoo_temp_ok, body), Toast.LENGTH_SHORT, GB.INFO);
        } else {
            toastVitalFailure(R.string.veepoo_temp_no_reading, failure);
        }
    }

    // ---------------------------------------------------------------------
    // Stress (pressure) single-shot detection, opcode 0x89 sub-parameter 6
    // ---------------------------------------------------------------------

    /** On-demand stress measurement: start {0x89,6,1}, stop {0x89,6,2}. */
    void startStressDetect() {
        synchronized (this) {
            if (stressDetectActive) {
                LOG.warn("A stress measurement is already in progress");
                GB.toast(support.getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (support.isFetchInProgress()) {
                LOG.warn("Stress measurement ignored: a data fetch is in progress");
                GB.toast(support.getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            stressDetectActive = true;
        }
        try {
            TransactionBuilder builder = support.performInitialized("stress detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_STRESS_REALTIME,
                    (byte) VeepooConstants.STRESS_DETECT_METRIC, (byte) VeepooConstants.STRESS_DETECT_START});
            builder.queue();
            support.mainHandler.postDelayed(stressDetectTimeout, 120_000L);
            support.mainHandler.postDelayed(stressIdleWatchdog, 60_000L);
            LOG.info("Stress detection started");
        } catch (IOException e) {
            synchronized (this) {
                stressDetectActive = false;
            }
            LOG.error("Error starting stress detection", e);
        }
    }

    /**
     * Opcode 0x89 frame: [1] must be 6 (stress metric), [2]=1 progress / 2 stop. Progress frames
     * carry [3] status (0 ok, 1/3 busy, 2 low battery, 4 not worn), [4] progress percent and
     * [5] the stress value once progress reaches 100.
     */
    void handleStressFrame(byte[] data) {
        if (data.length < 6 || data[1] != 6) {
            LOG.debug("Dropping unexpected 0x89 frame: {}", VeepooDeviceSupport.hexBytes(data));
            return;
        }
        synchronized (this) {
            if (!stressDetectActive) {
                persistAutonomousStress(data);
                return;
            }
        }
        LOG.debug("Stress frame: {}", VeepooDeviceSupport.hexBytes(data));
        int cmd = data[2] & 0xFF;
        if (cmd == VeepooConstants.STRESS_DETECT_STOP) {
            LOG.info("Band stopped the stress measurement");
            finishStressDetect(null, VITAL_FAIL_NONE);
            return;
        }
        int status = data[3] & 0xFF;
        if (status == 0) {
            int progress = data[4] & 0xFF;
            int value = data[5] & 0xFF;
            if (progress == 100 && value > 0) {
                finishStressDetect(value, VITAL_FAIL_NONE);
            } else {
                LOG.debug("Stress in progress: {}% (value={})", progress, value);
            }
        } else if (status == 2) {
            finishStressDetect(null, VITAL_FAIL_LOW_BATTERY);
        } else if (status == 4) {
            finishStressDetect(null, VITAL_FAIL_WEAR);
        } else {
            LOG.warn("Stress detect: device busy (status={})", status);
            finishStressDetect(null, VITAL_FAIL_BUSY);
        }
    }

    /** Persists an autonomous auto-measure stress push (no detect session in progress). */
    private void persistAutonomousStress(byte[] data) {
        int cmd = data[2] & 0xFF;
        if (cmd != VeepooConstants.STRESS_DETECT_START) {
            LOG.debug("Ignoring autonomous 0x89 frame: cmd={} (expected stress detect)", cmd);
            return;
        }
        int status = data[3] & 0xFF;
        if (status != 0) {
            LOG.debug("Ignoring autonomous stress push: status={}", status);
            return;
        }
        int progress = data[4] & 0xFF;
        int value = data[5] & 0xFF;
        if (progress == 100 && value > 0) {
            LOG.info("Autonomous stress push: {}", value);
            support.saveStressSample(value);
        } else {
            LOG.debug("Ignoring autonomous stress push: progress={} value={}", progress, value);
        }
    }

    /** Completes the stress read: stops the stream, then stores or reports the result. */
    private void finishStressDetect(Integer value, int failure) {
        synchronized (this) {
            if (!stressDetectActive) {
                return;
            }
            stressDetectActive = false;
        }
        support.mainHandler.removeCallbacks(stressDetectTimeout);
        support.mainHandler.removeCallbacks(stressIdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_STRESS_REALTIME,
                VeepooConstants.STRESS_DETECT_METRIC, VeepooConstants.STRESS_DETECT_STOP);
        if (value != null) {
            GenericStressSample sample = support.saveStressSample(value);
            if (sample != null) {
                support.notifyVitalSample(sample);
                GB.toast(support.getContext(), support.getContext().getString(R.string.veepoo_stress_ok, value), Toast.LENGTH_SHORT, GB.INFO);
            }
        } else {
            toastVitalFailure(R.string.veepoo_stress_no_reading, failure);
        }
    }

    // ---------------------------------------------------------------------
    // Blood pressure single-shot detection, opcode 0x90
    // ---------------------------------------------------------------------

    /** On-demand blood pressure measurement in public (uncalibrated) mode: start {0x90,1,0}, stop {0x90,0,0}. */
    void startBpDetect() {
        synchronized (this) {
            if (bpDetectActive) {
                LOG.warn("A blood pressure measurement is already in progress");
                GB.toast(support.getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (support.isFetchInProgress()) {
                LOG.warn("Blood pressure measurement ignored: a data fetch is in progress");
                GB.toast(support.getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            bpDetectActive = true;
        }
        try {
            TransactionBuilder builder = support.performInitialized("bp detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_BP_REALTIME,
                    (byte) VeepooConstants.BP_DETECT_START, (byte) VeepooConstants.BP_DETECT_MODEL_PUBLIC});
            builder.queue();
            support.mainHandler.postDelayed(bpDetectTimeout, 120_000L);
            support.mainHandler.postDelayed(bpIdleWatchdog, 60_000L);
            LOG.info("Blood pressure detection started");
        } catch (IOException e) {
            synchronized (this) {
                bpDetectActive = false;
            }
            LOG.error("Error starting blood pressure detection", e);
        }
    }

    /**
     * Opcode 0x90 frame: [1]=systolic, [2]=diastolic, [3]=progress, [4]=status, optional
     * [5]=hasProgress (1 = the device streams progress frames). Status codes: 1/2/4/5/0xFF
     * busy, 0xFE low battery, 0xFD charging, 0xFC not worn; anything else is a data frame.
     * Systolic/diastolic are 0 while the measurement is running and carry the result at the end.
     * The monitor feature also sends a compact push ({0x90, sys, dia, pulse}) without a status
     * byte; outside a manual detect it is persisted directly.
     */
    void handleBpFrame(byte[] data) {
        if (data.length < 4) {
            LOG.debug("Dropping short 0x90 frame: {} bytes", data.length);
            return;
        }
        final boolean longForm = data.length >= 6;
        synchronized (this) {
            if (!bpDetectActive) {
                if (longForm) {
                    persistAutonomousBp(data);
                } else {
                    persistCompactAutonomousBp(data);
                }
                return;
            }
        }
        if (!longForm) {
            LOG.debug("Ignoring compact 0x90 push during a manual blood-pressure detect");
            return;
        }
        LOG.debug("Blood pressure frame: {}", VeepooDeviceSupport.hexBytes(data));
        int status = data[4] & 0xFF;
        if (status == 0xFE) {
            finishBpDetect(null, null, VITAL_FAIL_LOW_BATTERY);
            return;
        }
        if (status == 0xFC) {
            finishBpDetect(null, null, VITAL_FAIL_WEAR);
            return;
        }
        if (status == 1 || status == 2 || status == 4 || status == 5 || status == 0xFF) {
            LOG.warn("Blood pressure detect: device busy (status={})", status);
            finishBpDetect(null, null, VITAL_FAIL_BUSY);
            return;
        }
        int systolic = data[1] & 0xFF;
        int diastolic = data[2] & 0xFF;
        if (systolic >= 60 && systolic <= 300 && diastolic >= 20 && diastolic <= 200) {
            finishBpDetect(systolic, diastolic, VITAL_FAIL_NONE);
        } else if (systolic > 0 || diastolic > 0) {
            LOG.warn("Blood pressure result out of range: {}/{}", systolic, diastolic);
            finishBpDetect(null, null, VITAL_FAIL_NONE);
        } else {
            LOG.debug("Blood pressure in progress: {}%", data[3] & 0xFF);
        }
    }

    /** Persists an autonomous auto-measure blood-pressure push (no detect session in progress). */
    private void persistAutonomousBp(byte[] data) {
        int status = data[4] & 0xFF;
        if (status != 0) {
            LOG.debug("Ignoring autonomous blood pressure push: status={}", status);
            return;
        }
        int systolic = data[1] & 0xFF;
        int diastolic = data[2] & 0xFF;
        if (systolic >= 60 && systolic <= 300 && diastolic >= 20 && diastolic <= 200) {
            LOG.info("Autonomous blood pressure push: {}/{}", systolic, diastolic);
            support.saveBpSample(systolic, diastolic);
        } else if (systolic > 0 || diastolic > 0) {
            LOG.debug("Ignoring autonomous blood pressure push out of range: {}/{}", systolic, diastolic);
        }
    }

    /** Persists the compact monitor BP push ({0x90, sys, dia, pulse}). */
    private void persistCompactAutonomousBp(byte[] data) {
        int systolic = data[1] & 0xFF;
        int diastolic = data[2] & 0xFF;
        if (systolic >= 60 && systolic <= 300 && diastolic >= 20 && diastolic <= 200) {
            int pulse = data.length >= 4 ? data[3] & 0xFF : -1;
            LOG.info("Autonomous blood pressure push (compact): {}/{} (pulse {})", systolic, diastolic, pulse);
            support.saveBpSample(systolic, diastolic);
        } else {
            LOG.debug("Ignoring compact 0x90 push out of range: {}/{}", systolic, diastolic);
        }
    }

    /** Completes the blood pressure read: stops the stream, then stores or reports the result. */
    private void finishBpDetect(Integer systolic, Integer diastolic, int failure) {
        synchronized (this) {
            if (!bpDetectActive) {
                return;
            }
            bpDetectActive = false;
        }
        support.mainHandler.removeCallbacks(bpDetectTimeout);
        support.mainHandler.removeCallbacks(bpIdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_BP_REALTIME,
                VeepooConstants.BP_DETECT_STOP, VeepooConstants.BP_DETECT_MODEL_PUBLIC);
        if (systolic != null) {
            GenericBloodPressureSample sample = support.saveBpSample(systolic, diastolic);
            if (sample != null) {
                support.notifyVitalSample(sample);
                GB.toast(support.getContext(), support.getContext().getString(R.string.veepoo_bp_ok, systolic, diastolic), Toast.LENGTH_SHORT, GB.INFO);
            }
        } else {
            toastVitalFailure(R.string.veepoo_bp_no_reading, failure);
        }
    }

    // ---------------------------------------------------------------------
    // Body composition single-shot detection, opcode 0x93
    // ---------------------------------------------------------------------

    /** On-demand body composition measurement: start {0x93,4,1}, stop {0x93,4,2}. */
    void startBcDetect() {
        synchronized (this) {
            if (bcDetectActive) {
                LOG.warn("A body composition measurement is already in progress");
                GB.toast(support.getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (support.isFetchInProgress()) {
                LOG.warn("Body composition measurement ignored: a data fetch is in progress");
                GB.toast(support.getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            bcDetectActive = true;
        }
        try {
            TransactionBuilder builder = support.performInitialized("body composition detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_BODY_COMPOSITION_REALTIME,
                    (byte) VeepooConstants.BC_DETECT_FUNCTION, (byte) VeepooConstants.BC_DETECT_START});
            builder.queue();
            support.mainHandler.postDelayed(bcDetectTimeout, 300_000L);
            support.mainHandler.postDelayed(bcIdleWatchdog, 90_000L);
            LOG.info("Body composition detection started");
        } catch (IOException e) {
            synchronized (this) {
                bcDetectActive = false;
            }
            LOG.error("Error starting body composition detection", e);
        }
    }

    /**
     * Opcode 0x93 frame: [1]=4, [2]=event (1 detect / 2 stop), [3]=state. State 0 streams
     * progress at [4]; state 1 carries data frames with [4]=seq (1-based), [5]=total and a
     * 14-byte chunk at [6..19]. The concatenated chunks start with a 2-byte header (observed as
     * A0 1C; not validated) followed by 14 little-endian u16 values (divided by 10): BMI, body
     * fat %, fat weight, FFM, muscle %,
     * muscle mass, subcutaneous fat, body water %, water content, skeletal muscle %,
     * bone mass, protein %, protein mass, BMR. States 2/3/4 are failed/busy/low battery.
     */
    void handleBodyCompositionFrame(byte[] data) {
        if (data.length < 20 || data[1] != VeepooConstants.BC_DETECT_FUNCTION) {
            return;
        }
        synchronized (this) {
            if (!bcDetectActive) {
                return;
            }
        }
        LOG.debug("Body composition frame: {}", VeepooDeviceSupport.hexBytes(data));
        if (data[2] == 2) {
            return; // stop acknowledgement
        }
        int state = data[3] & 0xFF;
        if (state == 0) {
            LOG.debug("Body composition in progress: {}%", data[4] & 0xFF);
            return;
        }
        if (state != 1) {
            finishBcDetect(null, state == 4 ? VITAL_FAIL_LOW_BATTERY : state == 3 ? VITAL_FAIL_BUSY : VITAL_FAIL_NONE);
            return;
        }
        int seq = data[4] & 0xFF;
        int total = data[5] & 0xFF;
        float[] values;
        synchronized (this) {
            if (!bcDetectActive) {
                return;
            }
            if (seq == 1) {
                bcChunks.clear();
            }
            bcChunks.add(Arrays.copyOfRange(data, 6, 20));
            if (seq != total) {
                return;
            }
            byte[] blob = new byte[bcChunks.size() * 14];
            for (int i = 0; i < bcChunks.size(); i++) {
                System.arraycopy(bcChunks.get(i), 0, blob, i * 14, 14);
            }
            bcChunks.clear();
            values = parseBodyCompositionBlob(blob);
        }
        if (values == null) {
            LOG.warn("Body composition result rejected: bad payload");
            finishBcDetect(null, VITAL_FAIL_NONE);
            return;
        }
        finishBcDetect(values, VITAL_FAIL_NONE);
    }

    /**
     * Decodes the concatenated body composition payload into 14 values, or null when invalid.
     * The first two bytes are a header (observed as A0 1C; not validated), then 14
     * little-endian u16 values divided by 10.
     */
    private static float[] parseBodyCompositionBlob(byte[] blob) {
        if (blob == null || blob.length < 30) {
            return null;
        }
        float[] values = new float[14];
        for (int i = 0; i < 14; i++) {
            int u16 = ((blob[3 + i * 2] & 0xFF) << 8) | (blob[2 + i * 2] & 0xFF);
            values[i] = u16 / 10f;
        }
        return values;
    }

    /** Completes the body composition read: stops the stream, then stores or reports the result. */
    private void finishBcDetect(float[] values, int failure) {
        synchronized (this) {
            if (!bcDetectActive) {
                return;
            }
            bcDetectActive = false;
        }
        support.mainHandler.removeCallbacks(bcDetectTimeout);
        support.mainHandler.removeCallbacks(bcIdleWatchdog);
        sendStopVitalFrame(VeepooConstants.OP_BODY_COMPOSITION_REALTIME,
                VeepooConstants.BC_DETECT_FUNCTION, VeepooConstants.BC_DETECT_STOP);
        if (values != null) {
            GenericBodyCompositionSample sample = support.saveBcSample(values);
            if (sample != null) {
                support.notifyVitalSample(sample);
                GB.toast(support.getContext(), support.getContext().getString(R.string.veepoo_bc_ok), Toast.LENGTH_SHORT, GB.INFO);
            }
        } else {
            toastVitalFailure(R.string.veepoo_bc_no_reading, failure);
        }
    }

    // ---------------------------------------------------------------------
    // Blood component (lipid) single-shot detection, opcode 0x8A
    // ---------------------------------------------------------------------

    /** On-demand blood lipid measurement in public (uncalibrated) mode: start {0x8A,1,1,0}, stop {0x8A,1,2,0}. */
    void startLipidDetect() {
        synchronized (this) {
            if (lipidDetectActive) {
                LOG.warn("A blood lipid measurement is already in progress");
                GB.toast(support.getContext(), R.string.veepoo_measure_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (support.isFetchInProgress()) {
                LOG.warn("Blood lipid measurement ignored: a data fetch is in progress");
                GB.toast(support.getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            lipidDetectActive = true;
        }
        try {
            TransactionBuilder builder = support.performInitialized("lipid detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_BLOOD_LIPID_REALTIME,
                    (byte) VeepooConstants.LIPID_DETECT_FUNCTION, (byte) VeepooConstants.LIPID_DETECT_START, 0});
            builder.queue();
            support.mainHandler.postDelayed(lipidDetectTimeout, 180_000L);
            support.mainHandler.postDelayed(lipidIdleWatchdog, 60_000L);
            LOG.info("Blood lipid detection started");
        } catch (IOException e) {
            synchronized (this) {
                lipidDetectActive = false;
            }
            LOG.error("Error starting blood lipid detection", e);
        }
    }

    /**
     * Opcode 0x8A frame: [2]=stop ack, [3]=state (0 ok, 2 low battery, 3 busy, 4 not worn),
     * [4]=progress percent; at progress 100 [5..14] carry uric acid (/10) then total
     * cholesterol, triglyceride, HDL and LDL (/100). Frames outside a manual read are
     * autonomous auto-measure pushes and are persisted.
     */
    void handleLipidFrame(byte[] data) {
        if (data.length < 15 || data[1] != VeepooConstants.LIPID_DETECT_FUNCTION) {
            LOG.debug("Dropping unexpected 0x8A frame: {}", VeepooDeviceSupport.hexBytes(data));
            return;
        }
        synchronized (this) {
            if (!lipidDetectActive) {
                persistAutonomousLipid(data);
                return;
            }
        }
        LOG.debug("Blood lipid frame: {}", VeepooDeviceSupport.hexBytes(data));
        if (data[2] == 2) {
            return; // stop acknowledgement
        }
        int state = data[3] & 0xFF;
        if (state != 0) {
            int failure = state == 2 ? VITAL_FAIL_LOW_BATTERY
                    : state == 3 ? VITAL_FAIL_BUSY
                    : state == 4 ? VITAL_FAIL_WEAR
                    : VITAL_FAIL_NONE;
            finishLipidDetect(null, failure);
            return;
        }
        int progress = data[4] & 0xFF;
        if (progress == 100) {
            finishLipidDetect(parseLipidValues(data), VITAL_FAIL_NONE);
        } else {
            LOG.debug("Blood lipid in progress: {}%", progress);
        }
    }

    /** Parses the five lipid values (uric acid, TCHO, TAG, HDL, LDL) from a data frame. */
    private static float[] parseLipidValues(byte[] data) {
        return new float[]{
                (((data[6] & 0xFF) << 8) | (data[5] & 0xFF)) / 10f,
                (((data[8] & 0xFF) << 8) | (data[7] & 0xFF)) / 100f,
                (((data[10] & 0xFF) << 8) | (data[9] & 0xFF)) / 100f,
                (((data[12] & 0xFF) << 8) | (data[11] & 0xFF)) / 100f,
                (((data[14] & 0xFF) << 8) | (data[13] & 0xFF)) / 100f};
    }

    private void persistAutonomousLipid(byte[] data) {
        if (data[2] == 2 || (data[3] & 0xFF) != 0 || (data[4] & 0xFF) != 100) {
            LOG.debug("Ignoring autonomous lipid push: data[2]={} data[3]={} data[4]={}",
                    data[2] & 0xFF, data[3] & 0xFF, data[4] & 0xFF);
            return; // stop ack, failure frame or still in progress
        }
        float[] values = parseLipidValues(data);
        LOG.info("Autonomous blood lipid push: TCHO={} TAG={}", values[1], values[2]);
        support.saveLipidSample(values);
    }

    /** Completes the blood lipid read: stops the stream, then stores or reports the result. */
    private void finishLipidDetect(float[] values, int failure) {
        synchronized (this) {
            if (!lipidDetectActive) {
                return;
            }
            lipidDetectActive = false;
        }
        support.mainHandler.removeCallbacks(lipidDetectTimeout);
        support.mainHandler.removeCallbacks(lipidIdleWatchdog);
        try {
            TransactionBuilder builder = support.performInitialized("stop lipid detect");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_BLOOD_LIPID_REALTIME,
                    (byte) VeepooConstants.LIPID_DETECT_FUNCTION, (byte) VeepooConstants.LIPID_DETECT_STOP, 0});
            builder.queue();
        } catch (IOException e) {
            LOG.warn("Error stopping blood lipid detection", e);
        }
        if (values != null) {
            GenericBloodLipidSample sample = support.saveLipidSample(values);
            if (sample != null) {
                support.notifyVitalSample(sample);
                GB.toast(support.getContext(), support.getContext().getString(R.string.veepoo_lipids_ok, values[1], values[2]),
                        Toast.LENGTH_SHORT, GB.INFO);
            }
        } else {
            toastVitalFailure(R.string.veepoo_lipids_no_reading, failure);
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers for the vital detections above
    // ---------------------------------------------------------------------

    /** Sends a vital stop frame {opcode, b1, b2}; errors are only logged. */
    private void sendStopVitalFrame(byte opcode, int b1, int b2) {
        try {
            TransactionBuilder builder = support.performInitialized("stop vital detect");
            support.writeFrame(builder, new byte[]{opcode, (byte) b1, (byte) b2});
            builder.queue();
        } catch (IOException e) {
            LOG.warn("Error stopping vital detection", e);
        }
    }

    /** Reports a failed vital measurement; {@code noReadingRes} is used when no specific cause applies. */
    private void toastVitalFailure(int noReadingRes, int failure) {
        switch (failure) {
            case VITAL_FAIL_WEAR:
                GB.toast(support.getContext(), R.string.veepoo_measure_not_worn, Toast.LENGTH_SHORT, GB.WARN);
                break;
            case VITAL_FAIL_LOW_BATTERY:
                GB.toast(support.getContext(), R.string.veepoo_measure_low_battery, Toast.LENGTH_SHORT, GB.WARN);
                break;
            case VITAL_FAIL_BUSY:
                GB.toast(support.getContext(), R.string.veepoo_measure_busy, Toast.LENGTH_SHORT, GB.WARN);
                break;
            case VITAL_FAIL_NOT_SUPPORTED:
                GB.toast(support.getContext(), R.string.veepoo_measure_not_supported, Toast.LENGTH_SHORT, GB.WARN);
                break;
            default:
                GB.toast(support.getContext(), noReadingRes, Toast.LENGTH_SHORT, GB.WARN);
        }
    }

    void dispose() {
        support.mainHandler.removeCallbacks(hrDetectTimeout);
        support.mainHandler.removeCallbacks(spo2DetectTimeout);
        support.mainHandler.removeCallbacks(spo2IdleWatchdog);
        support.mainHandler.removeCallbacks(tempDetectTimeout);
        support.mainHandler.removeCallbacks(tempIdleWatchdog);
        support.mainHandler.removeCallbacks(stressDetectTimeout);
        support.mainHandler.removeCallbacks(stressIdleWatchdog);
        support.mainHandler.removeCallbacks(bpDetectTimeout);
        support.mainHandler.removeCallbacks(bpIdleWatchdog);
    }
}
