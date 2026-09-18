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

import android.content.SharedPreferences;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooAutoMeasureSetting;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Feature module for the 0xB3 TLV auto-measure (periodic vitals) configuration. */
class VeepooAutoMeasureModule {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooAutoMeasureModule.class);

    private VeepooDeviceSupport support;

    VeepooAutoMeasureModule(VeepooDeviceSupport support) {
        this.support = support;
    }

    // Auto-measure settings state (0xB3): per-metric config for the periodic vitals the band
    // performs. The band refuses a read issued inside the connect burst (it answers with a
    // set-command NAK), so the first read is deferred to steady state, like the vendor app
    // which only reads when its health-settings screen is opened.
    private static final int AUTO_MEASURE_READ_MAX_ATTEMPTS = 3;
    private final Map<Integer, VeepooAutoMeasureSetting> autoMeasureSettings = new ConcurrentHashMap<>();
    private final Object autoMeasureLock = new Object();
    private final List<byte[]> autoMeasureChunks = new ArrayList<>();
    private int autoMeasureChunkTotal = 0;
    private boolean autoMeasureReadOutstanding = false;
    private int autoMeasureReadAttempts = 0;
    private int autoMeasurePendingFunType = -1; // set waiting for the read baseline to arrive
    private boolean autoMeasurePendingOn = false;
    private final Runnable autoMeasureWriteTimeout = () -> {
        synchronized (autoMeasureLock) {
            autoMeasurePendingFunType = -1;
        }
        LOG.warn("Auto-measure setting write timed out without an acknowledgement");
        GB.toast(support.getContext(), R.string.veepoo_auto_measure_failed, Toast.LENGTH_SHORT, GB.WARN);
    };
    private final Runnable autoMeasureReadTimeout = () -> {
        int pending;
        synchronized (autoMeasureLock) {
            if (!autoMeasureReadOutstanding) {
                return;
            }
            autoMeasureReadOutstanding = false;
            autoMeasureChunks.clear();
            pending = autoMeasurePendingFunType;
        }
        LOG.warn("Auto-measure settings read timed out without a reply");
        if (pending >= 0 && retryAutoMeasureRead()) {
            return;
        }
        synchronized (autoMeasureLock) {
            autoMeasurePendingFunType = -1;
        }
        if (pending >= 0) {
            GB.toast(support.getContext(), R.string.veepoo_auto_measure_failed, Toast.LENGTH_SHORT, GB.WARN);
        }
    };

    /** Schedules another read attempt while a toggle is waiting for the baseline. */
    private boolean retryAutoMeasureRead() {
        synchronized (autoMeasureLock) {
            if (autoMeasurePendingFunType < 0 || autoMeasureReadAttempts >= AUTO_MEASURE_READ_MAX_ATTEMPTS) {
                return false;
            }
        }
        LOG.info("Retrying the auto-measure settings read");
        support.mainHandler.postDelayed(() -> {
            synchronized (autoMeasureLock) {
                if (autoMeasureReadOutstanding) {
                    return;
                }
            }
            sendAutoMeasureRead();
        }, 3000L);
        return true;
    }

    /**
     * Auto-measure configuration changes coming from the device settings screen: the switch
     * state is already persisted in the per-device prefs; this applies it to the band with a
     * 0xB3 set command, preserving every other field of that metric's stored configuration.
     */
    void handleConfiguration(final String config) {
        if (!support.autoMeasureSupported) {
            LOG.debug("Ignoring auto-measure configuration {}: firmware reports the feature unsupported", config);
            return;
        }
        int funType;
        if (VeepooConstants.CONFIG_AUTO_MEASURE_HR.equals(config)) {
            funType = VeepooConstants.AUTO_MEASURE_PULSE_RATE;
        } else if (VeepooConstants.CONFIG_AUTO_MEASURE_SPO2.equals(config)) {
            funType = VeepooConstants.AUTO_MEASURE_BLOOD_OXYGEN;
        } else if (VeepooConstants.CONFIG_AUTO_MEASURE_TEMPERATURE.equals(config)) {
            funType = VeepooConstants.AUTO_MEASURE_BODY_TEMPERATURE;
        } else if (VeepooConstants.CONFIG_AUTO_MEASURE_STRESS.equals(config)) {
            funType = VeepooConstants.AUTO_MEASURE_STRESS;
        } else if (VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_PRESSURE.equals(config)) {
            funType = VeepooConstants.AUTO_MEASURE_BLOOD_PRESSURE;
        } else {
            return;
        }
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(support.getDevice().getAddress());
        setAutoMeasureEnabled(funType, prefs.getBoolean(config, false));
    }

    // ---------------------------------------------------------------------
    // Auto-measure settings, opcode 0xB3 (vendor AutoMeasureOpt)
    //
    // Read: {B3, 02} -> reply chunks {B3, 02, x, idx, payload...}; on chunk idx==1 the total
    // chunk count is at [6], the payload runs from [4] to end of frame. Chunks concatenate
    // into a little-endian TLV stream: [C0 <6 bytes>]* and per metric [A1 len <inner TLV>]
    // with inner tags D0 protocol, D1 funType, D2 switch, D3 stepUnit, D4 slot/interval
    // modify flags, D5 support window (h,m x2), D6 measure interval, D7 current window.
    // Set: a 42-byte payload chunked into 17-byte frames {B3, 01, seq, ...}; the first
    // frame's payload byte 2 carries the total chunk count. Reply ack {B3, 01, ok}.
    // ---------------------------------------------------------------------

    /** Applies an auto-measure on/off change, requesting the read baseline first if unknown. */
    private void setAutoMeasureEnabled(int funType, boolean on) {
        VeepooAutoMeasureSetting current = autoMeasureSettings.get(funType);
        if (current != null) {
            sendAutoMeasureWrite(funType, on);
            return;
        }
        boolean readStarted;
        synchronized (autoMeasureLock) {
            autoMeasurePendingFunType = funType;
            autoMeasurePendingOn = on;
            readStarted = !autoMeasureReadOutstanding;
            if (readStarted) {
                autoMeasureReadOutstanding = true;
            }
        }
        LOG.info("Auto-measure baseline for type {} not read yet; {}", funType,
                readStarted ? "requesting a read" : "waiting for the read already in flight");
        if (readStarted) {
            sendAutoMeasureRead();
        }
    }

    /** Connect hook: defers the baseline read to steady state (the band NAKs reads sent in the connect burst). */
    void scheduleSteadyStateRead() {
        support.mainHandler.postDelayed(() -> {
            synchronized (autoMeasureLock) {
                if (autoMeasureReadOutstanding || autoMeasurePendingFunType >= 0) {
                    return;
                }
            }
            sendAutoMeasureRead();
        }, 5000L);
    }

    /** Sends the 0xB3 read request and (re)starts the timeout: the reply is expected promptly. */
    private void sendAutoMeasureRead() {
        synchronized (autoMeasureLock) {
            autoMeasureChunks.clear();
            autoMeasureChunkTotal = 0;
            autoMeasureReadOutstanding = true;
            autoMeasureReadAttempts++;
        }
        try {
            TransactionBuilder builder = support.performInitialized("read auto-measure settings");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_AUTO_MEASURE, VeepooConstants.AUTO_MEASURE_CMD_DATA});
            builder.queue();
            support.mainHandler.removeCallbacks(autoMeasureReadTimeout);
            support.mainHandler.postDelayed(autoMeasureReadTimeout, 20000L);
        } catch (IOException e) {
            synchronized (autoMeasureLock) {
                autoMeasureReadOutstanding = false;
            }
            LOG.error("Error requesting auto-measure settings", e);
        }
    }

    /** Opcode 0xB3 frame: set acknowledgement or a data chunk of the settings TLV stream. */
    void handleFrame(byte[] data) {
        if (data.length < 7) {
            return;
        }
        int cmd = data[1] & 0xFF;
        if (cmd == VeepooConstants.AUTO_MEASURE_CMD_SET) {
            boolean ok = (data[2] & 0xFF) == 1;
            boolean readWasOutstanding;
            int pending;
            synchronized (autoMeasureLock) {
                readWasOutstanding = autoMeasureReadOutstanding;
                autoMeasureReadOutstanding = false;
                pending = autoMeasurePendingFunType;
            }
            if (readWasOutstanding) {
                // The band answered the settings read with a set-ack frame: zero means it refused.
                support.mainHandler.removeCallbacks(autoMeasureReadTimeout);
                if (!ok && retryAutoMeasureRead()) {
                    return;
                }
                synchronized (autoMeasureLock) {
                    autoMeasurePendingFunType = -1;
                }
                if (!ok) {
                    if (support.autoMeasureSupported) {
                        LOG.warn("Auto-measure settings read was rejected by the band");
                    } else {
                        LOG.debug("Auto-measure settings read was rejected by the band (feature unsupported)");
                    }
                    if (pending >= 0) {
                        GB.toast(support.getContext(), R.string.veepoo_auto_measure_failed, Toast.LENGTH_SHORT, GB.WARN);
                    }
                } else {
                    LOG.info("Auto-measure settings read acknowledged without data chunks");
                }
                return;
            }
            support.mainHandler.removeCallbacks(autoMeasureWriteTimeout);
            synchronized (autoMeasureLock) {
                autoMeasurePendingFunType = -1;
            }
            if (!ok) {
                LOG.warn("Band rejected the auto-measure setting change");
                GB.toast(support.getContext(), R.string.veepoo_auto_measure_failed, Toast.LENGTH_SHORT, GB.WARN);
            } else {
                LOG.info("Auto-measure setting acknowledged by the band");
            }
            return;
        }
        if (cmd != VeepooConstants.AUTO_MEASURE_CMD_DATA) {
            return;
        }
        int chunk = data[3] & 0xFF;
        if (chunk == 0) {
            return;
        }
        boolean complete = false;
        byte[] assembled;
        synchronized (autoMeasureLock) {
            if (chunk == 1) {
                autoMeasureChunks.clear();
                autoMeasureChunkTotal = data[6] & 0xFF;
            }
            int payloadLen = data.length - 4;
            if (payloadLen > 0) {
                byte[] payload = new byte[payloadLen];
                System.arraycopy(data, 4, payload, 0, payloadLen);
                autoMeasureChunks.add(payload);
            }
            complete = autoMeasureChunkTotal > 0 && chunk == autoMeasureChunkTotal;
            if (complete) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                for (byte[] part : autoMeasureChunks) {
                    buffer.write(part, 0, part.length);
                }
                assembled = buffer.toByteArray();
                autoMeasureReadOutstanding = false;
                autoMeasureReadAttempts = 0;
            } else {
                assembled = null;
            }
        }
        if (!complete) {
            LOG.debug("Auto-measure settings chunk {}/{} received", chunk, autoMeasureChunkTotal);
            return;
        }
        support.mainHandler.removeCallbacks(autoMeasureReadTimeout);
        parseAutoMeasureStream(assembled);
    }

    /** Parses the assembled 0xB3 TLV stream and stores the per-metric settings. */
    private void parseAutoMeasureStream(byte[] stream) {
        Map<Integer, VeepooAutoMeasureSetting> parsed = new ConcurrentHashMap<>();
        int pos = 0;
        while (pos < stream.length) {
            int tag = stream[pos] & 0xFF;
            pos++;
            if (tag == 0xC0) {
                // Header: 2 bytes + a uint32, skipped like the vendor does.
                pos += 6;
                if (pos > stream.length) {
                    break;
                }
                continue;
            }
            if (tag != 0xA1) {
                LOG.warn("Unexpected 0xB3 TLV tag {:02X} at position {}; stopping parse", tag, pos - 1);
                break;
            }
            if (pos >= stream.length) {
                break;
            }
            int innerLen = stream[pos] & 0xFF;
            pos++;
            if (innerLen == 0 || pos + innerLen > stream.length) {
                LOG.warn("Truncated 0xB3 metric blob: len={} at position {}", innerLen, pos - 1);
                break;
            }
            VeepooAutoMeasureSetting setting = new VeepooAutoMeasureSetting();
            int i = 0;
            while (i < innerLen) {
                int t2 = stream[pos + i] & 0xFF;
                i++;
                if (i >= innerLen) {
                    break;
                }
                int n = stream[pos + i] & 0xFF;
                i++;
                if (n == 0 || i + n > innerLen) {
                    break;
                }
                int v = pos + i;
                switch (t2) {
                    case 0xD0: // protocol type
                        setting.setProtocolType(stream[v] & 0xFF);
                        break;
                    case 0xD1: // fun type
                        setting.setFunType(stream[v] & 0xFF);
                        break;
                    case 0xD2: // switch open
                        setting.setEnabled((stream[v] & 0xFF) == 1);
                        break;
                    case 0xD3: // step unit (minutes)
                        setting.setStepUnit(((stream[v] & 0xFF) << 8) | (stream[v + 1] & 0xFF));
                        break;
                    case 0xD4: // slot/interval modify flags
                        setting.setSlotModify((stream[v] & 0xFF) == 1);
                        setting.setIntervalModify((stream[v + 1] & 0xFF) == 1);
                        break;
                    case 0xD5: // support window (h, m) x2
                        setting.setSupportStartMinute(((stream[v] & 0xFF) * 60) + (stream[v + 1] & 0xFF));
                        setting.setSupportEndMinute(((stream[v + 2] & 0xFF) * 60) + (stream[v + 3] & 0xFF));
                        break;
                    case 0xD6: // measure interval (minutes)
                        setting.setMeasureInterval(((stream[v] & 0xFF) << 8) | (stream[v + 1] & 0xFF));
                        break;
                    case 0xD7: // current window (h, m) x2
                        setting.setCurrentStartMinute(((stream[v] & 0xFF) * 60) + (stream[v + 1] & 0xFF));
                        setting.setCurrentEndMinute(((stream[v + 2] & 0xFF) * 60) + (stream[v + 3] & 0xFF));
                        break;
                    default:
                        LOG.debug("Unknown 0xB3 inner tag {:02X}", t2);
                        break;
                }
                i += n;
            }
            pos += innerLen;
            parsed.put(setting.getFunType(), setting);
        }
        autoMeasureSettings.clear();
        autoMeasureSettings.putAll(parsed);
        for (VeepooAutoMeasureSetting setting : parsed.values()) {
            LOG.info("Auto-measure {}: {}", autoMeasureTypeName(setting.getFunType()), setting);
        }
        syncAutoMeasurePrefs(parsed);
        int pendingFunType;
        boolean pendingOn;
        synchronized (autoMeasureLock) {
            pendingFunType = autoMeasurePendingFunType;
            pendingOn = autoMeasurePendingOn;
            autoMeasurePendingFunType = -1;
        }
        if (pendingFunType >= 0) {
            sendAutoMeasureWrite(pendingFunType, pendingOn);
        }
    }

    /** Maps an auto-measure funType to its display name (vendor EAutoMeasureType). */
    private static String autoMeasureTypeName(int funType) {
        switch (funType) {
            case 0: return "Pulse rate";
            case 1: return "Blood pressure";
            case 2: return "Blood glucose";
            case 3: return "Stress";
            case 4: return "Blood oxygen";
            case 5: return "Body temperature";
            case 6: return "Lorenz";
            case 7: return "HRV";
            case 8: return "Blood composition";
            default: return "Unknown (" + funType + ")";
        }
    }

    /** Mirrors the device state into the per-device prefs so the settings screen shows reality. */
    private void syncAutoMeasurePrefs(Map<Integer, VeepooAutoMeasureSetting> parsed) {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(support.getDevice().getAddress());
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<Integer, VeepooAutoMeasureSetting> entry : parsed.entrySet()) {
            String key;
            switch (entry.getKey()) {
                case VeepooConstants.AUTO_MEASURE_PULSE_RATE:
                    key = VeepooConstants.CONFIG_AUTO_MEASURE_HR;
                    break;
                case VeepooConstants.AUTO_MEASURE_BLOOD_OXYGEN:
                    key = VeepooConstants.CONFIG_AUTO_MEASURE_SPO2;
                    break;
                case VeepooConstants.AUTO_MEASURE_BODY_TEMPERATURE:
                    key = VeepooConstants.CONFIG_AUTO_MEASURE_TEMPERATURE;
                    break;
                case VeepooConstants.AUTO_MEASURE_STRESS:
                    key = VeepooConstants.CONFIG_AUTO_MEASURE_STRESS;
                    break;
                case VeepooConstants.AUTO_MEASURE_BLOOD_PRESSURE:
                    key = VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_PRESSURE;
                    break;
                default:
                    continue;
            }
            editor.putBoolean(key, entry.getValue().isEnabled());
        }
        editor.apply();
    }

    /**
     * Sends a 0xB3 set command for one metric: the stored baseline is copied, only the switch
     * is flipped (and the modify flags cleared), then the 42-byte payload is chunked exactly
     * like the vendor (17-byte frames, first frame carries the chunk count in payload byte 2).
     */
    private void sendAutoMeasureWrite(int funType, boolean on) {
        VeepooAutoMeasureSetting base = autoMeasureSettings.get(funType);
        VeepooAutoMeasureSetting setting = base != null ? new VeepooAutoMeasureSetting(base) : new VeepooAutoMeasureSetting();
        setting.setFunType(funType);
        setting.setEnabled(on);
        setting.setSlotModify(false);
        setting.setIntervalModify(false);
        if (base == null) {
            // No baseline: fall back to a full-day window and a 30-minute interval.
            setting.setProtocolType(1);
            setting.setStepUnit(0);
            setting.setSupportStartMinute(0);
            setting.setSupportEndMinute(1439);
            setting.setMeasureInterval(30);
            setting.setCurrentStartMinute(0);
            setting.setCurrentEndMinute(1439);
        }
        byte[] payload = buildAutoMeasurePayload(setting);
        int totalChunks = (int) Math.ceil(payload.length / 17.0);
        try {
            TransactionBuilder builder = support.performInitialized("set auto-measure " + funType);
            for (int i = 0; i < totalChunks; i++) {
                int seq = i + 1;
                int len = (i == totalChunks - 1) ? payload.length - 17 * i : 17;
                byte[] frame = new byte[len + 3];
                frame[0] = (byte) VeepooConstants.OP_AUTO_MEASURE;
                frame[1] = (byte) VeepooConstants.AUTO_MEASURE_CMD_SET;
                frame[2] = (byte) seq;
                System.arraycopy(payload, 17 * i, frame, 3, len);
                if (seq == 1) {
                    frame[5] = (byte) totalChunks;
                }
                support.writeFrame(builder, frame);
            }
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error sending auto-measure setting", e);
            GB.toast(support.getContext(), R.string.veepoo_auto_measure_failed, Toast.LENGTH_SHORT, GB.WARN);
            return;
        }
        synchronized (autoMeasureLock) {
            autoMeasurePendingFunType = funType;
            autoMeasurePendingOn = on;
        }
        support.mainHandler.removeCallbacks(autoMeasureWriteTimeout);
        support.mainHandler.postDelayed(autoMeasureWriteTimeout, 15000L);
        // Remember the new switch state locally so a rapid follow-up toggle starts from reality.
        setting = autoMeasureSettings.get(funType);
        if (setting != null) {
            setting.setEnabled(on);
        }
        LOG.info("Auto-measure {} set to {}", funType, on ? "on" : "off");
    }

    /** Builds the 42-byte 0xB3 set payload (vendor AutoMeasureOpt.a layout). */
    private byte[] buildAutoMeasurePayload(VeepooAutoMeasureSetting s) {
        byte[] p = new byte[42];
        p[0] = (byte) 0xC0;
        p[1] = 0x05;
        p[2] = 0x01; // overwritten with the chunk count when the payload is split
        p[3] = 35;   // uint32 LE: 35 (inner blob size)
        p[4] = 0x00;
        p[5] = 0x00;
        p[6] = 0x00;
        p[7] = (byte) 0xA1; // metric section marker (-95)
        p[8] = 33;           // inner TLV blob length
        p[9] = (byte) 0xD0;  p[10] = 0x01;  p[11] = (byte) s.getProtocolType();
        p[12] = (byte) 0xD1; p[13] = 0x01;  p[14] = (byte) s.getFunType();
        p[15] = (byte) 0xD2; p[16] = 0x01;  p[17] = (byte) (s.isEnabled() ? 1 : 0);
        p[18] = (byte) 0xD3; p[19] = 0x02;
        p[20] = (byte) (s.getStepUnit() & 0xFF);
        p[21] = (byte) ((s.getStepUnit() >> 8) & 0xFF);
        p[22] = (byte) 0xD4; p[23] = 0x02;
        p[24] = (byte) (s.isSlotModify() ? 1 : 0);
        p[25] = (byte) (s.isIntervalModify() ? 1 : 0);
        p[26] = (byte) 0xD5; p[27] = 0x04;
        p[28] = (byte) (s.getSupportStartMinute() / 60);
        p[29] = (byte) (s.getSupportStartMinute() % 60);
        p[30] = (byte) (s.getSupportEndMinute() / 60);
        p[31] = (byte) (s.getSupportEndMinute() % 60);
        p[32] = (byte) 0xD6; p[33] = 0x02;
        p[34] = (byte) (s.getMeasureInterval() & 0xFF);
        p[35] = (byte) ((s.getMeasureInterval() >> 8) & 0xFF);
        p[36] = (byte) 0xD7; p[37] = 0x04;
        p[38] = (byte) (s.getCurrentStartMinute() / 60);
        p[39] = (byte) (s.getCurrentStartMinute() % 60);
        p[40] = (byte) (s.getCurrentEndMinute() / 60);
        p[41] = (byte) (s.getCurrentEndMinute() % 60);
        return p;
    }

    void dispose() {
        support.mainHandler.removeCallbacks(autoMeasureWriteTimeout);
        support.mainHandler.removeCallbacks(autoMeasureReadTimeout);
    }
}
