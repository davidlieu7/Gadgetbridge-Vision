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
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Feature module for the 0xDE stored heart-rate read-back. */
class VeepooManualDetectModule {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooManualDetectModule.class);

    private VeepooDeviceSupport support;

    VeepooManualDetectModule(VeepooDeviceSupport support) {
        this.support = support;
    }

    // Manual heart rate read-back state (0xDE)
    private final Object manualDetectLock = new Object();
    private boolean manualDetectActive = false;
    private int manualDetectTotal = -1;
    private int manualDetectNextIndex = 0;
    private final ByteArrayOutputStream manualDetectPayload = new ByteArrayOutputStream();
    private final Runnable manualDetectWatchdog = () -> {
        LOG.warn("Manual heart rate read timed out");
        GB.toast(support.getContext(), R.string.veepoo_manual_hr_failed, Toast.LENGTH_SHORT, GB.WARN);
        resetManualDetect();
    };

    /**
     * Triggers the manual heart rate read-back (0xDE). The band stores its on-demand measurement
     * results on-device, so the vendor app reads them back instead of streaming; pos=0 means
     * "all stored records" and only the heart-rate type bit is requested.
     */
    void readManualHeartRate() {
        synchronized (manualDetectLock) {
            if (manualDetectActive) {
                LOG.warn("A manual heart rate read is already in progress");
                GB.toast(support.getContext(), R.string.veepoo_manual_hr_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            if (support.isFetchInProgress()) {
                LOG.warn("Manual heart rate read ignored: a data fetch is in progress");
                GB.toast(support.getContext(), R.string.veepoo_sync_in_progress, Toast.LENGTH_SHORT, GB.WARN);
                return;
            }
            manualDetectActive = true;
            manualDetectTotal = -1;
            manualDetectNextIndex = 0;
            manualDetectPayload.reset();
        }
        try {
            TransactionBuilder builder = support.performInitialized("manual heart rate read");
            byte[] command = new byte[VeepooConstants.PACKET_SIZE];
            command[0] = VeepooConstants.OP_MANUAL_DETECT;
            command[1] = 0x02;
            // command[2..5] pos = 0 (no cursor: read all stored records)
            command[6] = (byte) VeepooConstants.MANUAL_MASK_HEART_RATE; // readMask: heart rate only
            support.writeFrame(builder, command);
            builder.queue();
            support.mainHandler.postDelayed(manualDetectWatchdog, 120_000L);
            LOG.info("Manual heart rate read requested (all stored HR records)");
        } catch (IOException e) {
            resetManualDetect();
            LOG.error("Error triggering manual heart rate read", e);
        }
    }

    /**
     * Opcode 0xDE response: [2..5] record index (LE32, 0-based), first frame only carries the
     * total count at [8..11], payload bytes start at [6]. Frames arrive in strict sequence; the
     * concatenated stream is parsed once all {@code total} frames have been received.
     */
    void handleFrame(byte[] data) {
        if (data.length < 12) {
            return;
        }
        int index = (int) VeepooDeviceSupport.le32(data, 2);
        boolean complete = false;
        synchronized (manualDetectLock) {
            if (!manualDetectActive) {
                LOG.debug("Ignoring unexpected manual detect frame (index {})", index);
                return;
            }
            if (index == 0) {
                manualDetectTotal = (int) VeepooDeviceSupport.le32(data, 8);
                manualDetectNextIndex = 1;
                manualDetectPayload.reset();
                LOG.info("Manual heart rate read: {} payload frame(s) expected", manualDetectTotal);
                if (manualDetectTotal == 0) {
                    complete = true;
                }
            } else if (index != manualDetectNextIndex) {
                LOG.warn("Out-of-sequence manual detect frame: got index {}, expected {}", index, manualDetectNextIndex);
                GB.toast(support.getContext(), R.string.veepoo_manual_hr_failed, Toast.LENGTH_SHORT, GB.WARN);
                resetManualDetect();
                return;
            }
            if (!complete) {
                manualDetectPayload.write(data, 6, data.length - 6);
                manualDetectNextIndex = index + 1;
                if (manualDetectTotal > 0 && manualDetectNextIndex >= manualDetectTotal) {
                    complete = true;
                }
            }
        }
        if (!complete) {
            return;
        }
        support.mainHandler.removeCallbacks(manualDetectWatchdog);
        byte[] stream = manualDetectPayload.toByteArray();
        int saved = parseAndSaveManualHeartRate(stream);
        synchronized (manualDetectLock) {
            manualDetectActive = false;
            manualDetectTotal = -1;
            manualDetectNextIndex = 0;
            manualDetectPayload.reset();
        }
        LOG.info("Manual heart rate read complete: {} byte(s) of stream, {} reading(s) saved", stream.length, saved);
        if (saved > 0) {
            GB.toast(support.getContext(), support.getContext().getString(R.string.veepoo_manual_hr_saved, saved), Toast.LENGTH_SHORT, GB.INFO);
        } else {
            GB.toast(support.getContext(), R.string.veepoo_manual_hr_empty, Toast.LENGTH_SHORT, GB.WARN);
        }
    }

    /**
     * Parses the concatenated 0xDE stream and saves the heart rate records (type 1), mirroring
     * the vendor parser (p000if/y.java): 0xA0 blocks are skipped; a 0xA1 block is
     * [0xA1][unused byte][type][len LE32] followed by len bytes of [0xB0][recLen LE32][record].
     * Each HR record carries sub-TLVs 0xC0 (timestamp), 0xC1 (version) and 0xC2 (n × bpm).
     */
    private int parseAndSaveManualHeartRate(byte[] stream) {
        int saved = 0;
        ByteBuffer bb = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (bb.hasRemaining()) {
                int tag = bb.get() & 0xFF;
                if (tag == 0xA0) {
                    int skip = bb.get() & 0xFF;
                    bb.position(bb.position() + skip);
                } else if (tag == 0xA1) {
                    bb.get(); // unused length/count byte (discarded by the vendor parser)
                    int type = bb.get() & 0xFF;
                    byte[] group = new byte[bb.getInt()];
                    bb.get(group);
                    if (type != 1) {
                        continue;
                    }
                    ByteBuffer gb = ByteBuffer.wrap(group).order(ByteOrder.LITTLE_ENDIAN);
                    while (gb.hasRemaining()) {
                        if ((gb.get() & 0xFF) != 0xB0) {
                            continue;
                        }
                        byte[] record = new byte[gb.getInt()];
                        gb.get(record);
                        saved += saveManualHeartRateRecord(record);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse the manual heart rate stream ({} bytes)", stream.length, e);
        }
        return saved;
    }

    /** Parses one HR record and stores it as a heart rate sample if it carries valid samples. */
    private int saveManualHeartRateRecord(byte[] record) {
        long timestampSeconds = 0;
        List<Integer> samples = null;
        ByteBuffer rb = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (rb.hasRemaining()) {
                switch (rb.get() & 0xFF) {
                    case 0xC0:
                        rb.get(); // len byte (discarded)
                        timestampSeconds = Integer.toUnsignedLong(rb.getInt());
                        break;
                    case 0xC1:
                        rb.get(); // len byte (discarded)
                        rb.get(); // version
                        break;
                    case 0xC2:
                        int count = rb.get() & 0xFF;
                        samples = new ArrayList<>(count);
                        for (int i = 0; i < count && rb.hasRemaining(); i++) {
                            samples.add(rb.get() & 0xFF);
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse a manual heart rate record", e);
            return 0;
        }
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (int bpm : samples) {
            sum += bpm;
        }
        int average = sum / samples.size();
        if (average < 30 || average > 220) {
            LOG.debug("Ignoring implausible manual heart rate record: {} bpm over {} sample(s)", average, samples.size());
            return 0;
        }
        long timestampMs = timestampSeconds > 0 ? timestampSeconds * 1000L : System.currentTimeMillis();
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(support.getDevice(), session).getId();
            GenericHeartRateSampleDao dao = session.getGenericHeartRateSampleDao();
            boolean alreadySaved = !dao.queryBuilder()
                    .where(GenericHeartRateSampleDao.Properties.Timestamp.eq(timestampMs),
                            GenericHeartRateSampleDao.Properties.DeviceId.eq(deviceId))
                    .list().isEmpty();
            if (alreadySaved) {
                LOG.debug("Manual heart rate reading already saved ({})", new Date(timestampMs));
                return 0;
            }
            GenericHeartRateSample sample = new GenericHeartRateSample();
            sample.setTimestamp(timestampMs);
            sample.setHeartRate(average);
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            new nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider(support.getDevice(), session).addSample(sample);
            return 1;
        } catch (Exception e) {
            LOG.error("Error saving manual heart rate record", e);
            return 0;
        }
    }

    private void resetManualDetect() {
        support.mainHandler.removeCallbacks(manualDetectWatchdog);
        synchronized (manualDetectLock) {
            manualDetectActive = false;
            manualDetectTotal = -1;
            manualDetectNextIndex = 0;
            manualDetectPayload.reset();
        }
    }

    void dispose() {
        support.mainHandler.removeCallbacks(manualDetectWatchdog);
    }
}
