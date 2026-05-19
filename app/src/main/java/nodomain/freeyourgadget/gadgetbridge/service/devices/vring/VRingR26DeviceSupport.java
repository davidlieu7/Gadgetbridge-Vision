/*  Copyright (C) 2026 Ariel Saghiv

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.vring;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.vring.VRingR26Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.vring.samples.VRingR26ActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.VRingR26ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;

/**
 * Gadgetbridge driver for the VRing R26 smart ring (MO YOUNG LTD "Da Ring" app).
 * <p>
 * Verified working:
 * <ul>
 *     <li>Connect / pair (no auth handshake)</li>
 *     <li>Battery level via standard 0x2A19</li>
 *     <li>Set time (GMT+8-adjusted LE32 + 0x08 tz byte)</li>
 *     <li>Direct FDD1 read = current steps/distance/calories (3 LE24 fields)</li>
 *     <li>HR history list (cat=2 cmd=9)</li>
 *     <li>Step history per day (cat=2 cmd=13) and per-bucket details (cat=2 cmd=18)</li>
 *     <li>SpO2 (cat=2 cmd=11) and stress (cat=2 cmd=32) history</li>
 *     <li>Manual heart-rate measurement</li>
 * </ul>
 */
public class VRingR26DeviceSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(VRingR26DeviceSupport.class);

    private static final int HISTORY_DAYS = 7;
    private final Map<Integer, StepDayTotals> stepTotalsByDay = new HashMap<>();

    private static final class StepDayTotals {
        private final long distance;
        private final long calories;

        private StepDayTotals(final long distance, final long calories) {
            this.distance = distance;
            this.calories = calories;
        }
    }

    public VRingR26DeviceSupport() {
        super(LOG);
        addSupportedService(GattService.SERVICE_GAP.uuid);
        addSupportedService(GattService.SERVICE_GATT.uuid);
        addSupportedService(GattService.SERVICE_BATTERY.uuid);
        addSupportedService(VRingR26Constants.UUID_SERVICE);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    public boolean getImplicitCallbackModify() {
        return true;
    }

    @Override
    public boolean getSendWriteRequestResponse() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // Subscribe to command notifications (FDD3) and step pushes (FDD1).
        BluetoothGattCharacteristic notify = getCharacteristic(VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY);
        if (notify != null) builder.notify(notify, true);

        BluetoothGattCharacteristic fdd1 = getCharacteristic(VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY_ALT);
        if (fdd1 != null) builder.notify(fdd1, true);

        BluetoothGattCharacteristic batt = getCharacteristic(
                GattCharacteristic.CHARACTERISTIC_BATTERY_LEVEL.uuid);
        if (batt != null) {
            builder.notify(batt, true);
            builder.read(batt);
        }

        setDeviceTime(builder);

        // Query device information exposed by the command channel.
        writeCmd(builder, VRingR26Packet.queryDeviceInfo());
        writeCmd(builder, VRingR26Packet.queryFirmware());

        // Read current steps directly from FDD1.
        if (fdd1 != null) builder.read(fdd1);

        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    private void setDeviceTime(final TransactionBuilder builder) {
        writeCmd(builder, VRingR26Packet.setTime(System.currentTimeMillis() / 1000L));
    }

    private void requestHistory(final TransactionBuilder builder, final int days) {
        for (int day = 0; day > -days; day--) {
            writeCmd(builder, VRingR26Packet.queryHistoryStepsDay(day));
            writeCmd(builder, VRingR26Packet.queryHistoryStepsDetailsDay(day));
            writeCmd(builder, VRingR26Packet.queryHistoryHeartRate(day));
        }
    }

    private void writeCmd(TransactionBuilder builder, VRingR26Packet pkt) {
        BluetoothGattCharacteristic chr = getCharacteristic(pkt.writeCharacteristic());
        if (chr == null) {
            LOG.warn("No characteristic for cat={} cmd={}", pkt.getCategory(), pkt.getCommand());
            return;
        }
        builder.write(chr, pkt.encode());
    }

    // -------------------------------------------------------------------------
    // GATT callbacks
    // -------------------------------------------------------------------------

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        UUID uuid = characteristic.getUuid();
        if (GattCharacteristic.CHARACTERISTIC_BATTERY_LEVEL.uuid.equals(uuid)) {
            handleBattery(value);
            return true;
        }
        if (VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY.equals(uuid)) {
            handleFddaFrame(value);
            return true;
        }
        if (VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY_ALT.equals(uuid)) {
            handleCurrentStepsRead(value);
            return true;
        }
        return false;
    }

    @Override
    public boolean onCharacteristicRead(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic characteristic,
                                        byte[] value, int status) {
        if (super.onCharacteristicRead(gatt, characteristic, value, status)) {
            return true;
        }
        UUID uuid = characteristic.getUuid();
        if (GattCharacteristic.CHARACTERISTIC_BATTERY_LEVEL.uuid.equals(uuid)) {
            handleBattery(value);
            return true;
        }
        if (VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY_ALT.equals(uuid)) {
            handleCurrentStepsRead(value);
            return true;
        }
        return false;
    }

    private void handleBattery(byte[] value) {
        if (value == null || value.length == 0) return;
        GBDeviceEventBatteryInfo evt = new GBDeviceEventBatteryInfo();
        evt.level = (short) (value[0] & 0xFF);
        handleGBDeviceEvent(evt);
    }

    // -------------------------------------------------------------------------
    // FDD1 (current step counters) - direct GATT read response
    // -------------------------------------------------------------------------

    /** FDD1 payload = cumulative totals: steps, distance and calories as LE24 fields. */
    private void handleCurrentStepsRead(byte[] value) {
        if (value == null || value.length < 9 || value.length % 3 != 0) {
            LOG.debug("FDD1 read ignored, len={} hex={}", value == null ? 0 : value.length, hex(value));
            return;
        }
        int steps = BLETypeConversions.toUint24(value, 0);
        int distanceM = BLETypeConversions.toUint24(value, 3);
        int calories = BLETypeConversions.toUint24(value, 6);
        LOG.info("VRing current: steps={} distance={}m calories={}", steps, distanceM, calories);
        // Do not persist cumulative snapshots as activity samples: repeated reads would
        // double-count steps alongside the per-slot history.
    }

    // -------------------------------------------------------------------------
    // FDDA / FDD3 command-channel frames
    // -------------------------------------------------------------------------

    private void handleFddaFrame(byte[] value) {
        VRingR26Packet pkt = VRingR26Packet.decode(value);
        if (pkt == null) {
            LOG.debug("Non-FDDA frame: {}", hex(value));
            return;
        }
        int cat = pkt.getCategory();
        int cmd = pkt.getCommand();
        byte[] p = pkt.getPayload();
        LOG.debug("FDDA cat={} cmd={} payload={}", cat, cmd, hex(p));

        switch (cat) {
            case VRingR26Constants.CAT_SET:
                handleSetResponse(cmd, p);
                break;
            case VRingR26Constants.CAT_QUERY:
                handleQueryResponse(cmd, p);
                break;
            default:
                LOG.debug("Unhandled cat={} cmd={}", cat, cmd);
        }
    }

    private void handleSetResponse(int cmd, byte[] p) {
        // cat=1 contains live measurement pushes triggered by set commands.
        switch (cmd) {
            case VRingR26Constants.CMD_START_HR_MEASURE: { // cat=1 cmd=9: live HR
                if (p.length >= 1) {
                    int bpm = p[0] & 0xFF;
                    if (isPlausibleHeartRate(bpm)) {
                        LOG.info("VRing live HR: {} bpm", bpm);
                        persistHeartRate(System.currentTimeMillis(), bpm);
                    } else if (bpm == 0xFF) {
                        LOG.warn("VRing HR rejected (ring off finger)");
                    }
                }
                break;
            }
            default:
                LOG.debug("set-response cmd={} payload={}", cmd, hex(p));
        }
    }

    private void handleQueryResponse(int cmd, byte[] p) {
        switch (cmd) {
            case VRingR26Constants.CMD_QUERY_DEVICE_INFO: {
                LOG.debug("VRing device info: {}", hex(p));
                break;
            }
            case VRingR26Constants.CMD_QUERY_HR_LATEST: {
                // cat=2 cmd=9: HR history list. Format: [skip byte] then N x [bpm + ts_u32_LE].
                parseHrHistory(p);
                break;
            }
            case VRingR26Constants.CMD_QUERY_SPO2_LATEST: {
                // cat=2 cmd=11: SpO2 history; payload[0]=count then N entries.
                parseSpo2History(p);
                break;
            }
            case VRingR26Constants.CMD_QUERY_HISTORY_STEPS: {
                // cat=2 cmd=13: day_byte + 4 x LE32 = (steps, distance, calories, activeTime).
                parseHistoryStepsDay(p);
                break;
            }
            case VRingR26Constants.CMD_QUERY_HISTORY_STEPS_DETAIL: {
                // cat=2 cmd=18: day_byte + N x LE16 step buckets (30-min bins).
                parseHistoryStepsDetail(p);
                break;
            }
            case VRingR26Constants.CMD_QUERY_STRESS_HISTORY: {
                parseStressHistory(p);
                break;
            }
            default:
                LOG.debug("query-response cmd={} payload={}", cmd, hex(p));
        }
    }

    // -------------------------------------------------------------------------
    // Parsers
    // -------------------------------------------------------------------------

    private void parseHrHistory(byte[] p) {
        if (p == null || p.length < 6 || (p.length - 1) % 5 != 0) {
            LOG.debug("HR history payload invalid: {}", hex(p));
            return;
        }
        List<GenericHeartRateSample> batch = new ArrayList<>();
        for (int i = 1; i + 4 < p.length; i += 5) {
            int bpm = p[i] & 0xFF;
            long tsSec = uint32(p, i + 1);
            if (!isPlausibleHeartRate(bpm)) continue;
            if (tsSec < 1_500_000_000L || tsSec > 4_000_000_000L) continue;
            batch.add(new GenericHeartRateSample(tsSec * 1000L, 0L, 0L, bpm));
        }
        persistHeartRateBatch(batch);
        LOG.info("Persisted {} HR history samples", batch.size());
    }

    private void parseSpo2History(byte[] p) {
        if (p == null || p.length < 1) return;
        List<GenericSpo2Sample> batch = new ArrayList<>();
        if ((p.length - 1) % 5 == 0) {
            for (int i = 1; i + 4 < p.length; i += 5) {
                int spo2 = p[i] & 0xFF;
                long tsSec = uint32(p, i + 1);
                if (spo2 < 70 || spo2 > 100) continue;
                if (tsSec < 1_500_000_000L || tsSec > 4_000_000_000L) continue;
                batch.add(new GenericSpo2Sample(tsSec * 1000L, 0L, 0L, spo2));
            }
        }
        persistSpo2Batch(batch);
        LOG.info("Persisted {} SpO2 samples", batch.size());
    }

    private void parseStressHistory(byte[] p) {
        if (p == null || p.length < 1) return;
        List<GenericStressSample> batch = new ArrayList<>();
        if ((p.length - 1) % 5 == 0) {
            for (int i = 1; i + 4 < p.length; i += 5) {
                int stress = p[i] & 0xFF;
                long tsSec = uint32(p, i + 1);
                if (stress <= 0 || stress > 100) continue;
                if (tsSec < 1_500_000_000L || tsSec > 4_000_000_000L) continue;
                batch.add(new GenericStressSample(tsSec * 1000L, 0L, 0L, stress));
            }
        }
        persistStressBatch(batch);
        LOG.info("Persisted {} stress samples", batch.size());
    }

    private void parseHistoryStepsDay(byte[] p) {
        if (p == null || p.length < 17) {
            LOG.debug("History steps day payload too short: {}", hex(p));
            return;
        }
        int dayOffset = p[0];   // 0=today, -1=yesterday, ...
        long steps = uint32(p, 1);
        long distance = uint32(p, 5);
        long calories = uint32(p, 9);
        long activeTimeMin = uint32(p, 13);
        LOG.info("Day {}: steps={} distance={}m calories={} activeMin={}",
                dayOffset, steps, distance, calories, activeTimeMin);
        stepTotalsByDay.put(dayOffset, new StepDayTotals(distance, calories));
    }

    private void parseHistoryStepsDetail(byte[] p) {
        if (p == null || p.length < 3 || (p.length - 1) % 2 != 0) {
            LOG.debug("Step detail payload invalid: {}", hex(p));
            return;
        }
        int dayOffset = p[0];
        int dayStart = startOfDayLocal(dayOffset);
        int buckets = Math.min((p.length - 1) / 2, VRingR26Constants.STEP_BUCKETS_PER_DAY);
        final int[] bucketSteps = new int[buckets];
        int totalBucketSteps = 0;
        for (int i = 0; i < buckets; i++) {
            final int idx = 1 + i * 2;
            bucketSteps[i] = BLETypeConversions.toUint16(p[idx], p[idx + 1]);
            if (bucketSteps[i] > 0) {
                totalBucketSteps += bucketSteps[i];
            }
        }
        final StepDayTotals totals = stepTotalsByDay.remove(dayOffset);
        final List<VRingR26ActivitySample> samples = new ArrayList<>();
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            VRingR26ActivitySampleProvider prov =
                    new VRingR26ActivitySampleProvider(getDevice(), session);
            for (int i = 0; i < bucketSteps.length; i++) {
                final int steps = bucketSteps[i];
                if (steps <= 0) continue;
                int ts = dayStart + i * VRingR26Constants.STEP_BUCKET_SECONDS;
                final int intensity = stepsPerMinute(steps,
                        VRingR26Constants.STEP_BUCKET_SECONDS);
                final Integer distance = prorateTotal(totals == null ? 0 : totals.distance,
                        steps, totalBucketSteps);
                final Integer calories = prorateTotal(totals == null ? 0 : totals.calories,
                        steps, totalBucketSteps);
                VRingR26ActivitySample s = new VRingR26ActivitySample(
                        ts, deviceId, userId,
                        VRingR26Constants.RawActivityKind.ACTIVITY.getRawValue(),
                        ActivitySample.NOT_MEASURED,
                        intensity,
                        steps,
                        distance, calories);
                s.setProvider(prov);
                samples.add(s);
            }
            prov.addGBActivitySamples(samples);
        } catch (Exception e) {
            LOG.error("Failed to persist step details: {}", e.getMessage(), e);
        }
        LOG.info("Day {}: persisted {} step-detail buckets", dayOffset, samples.size());
    }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    private void persistHeartRate(long timestampMs, int bpm) {
        persistHeartRateBatch(java.util.Collections.singletonList(
                new GenericHeartRateSample(timestampMs, 0L, 0L, bpm)));
    }

    private void persistHeartRateBatch(List<GenericHeartRateSample> samples) {
        if (samples == null || samples.isEmpty()) return;
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericHeartRateSampleProvider provider =
                    new GenericHeartRateSampleProvider(getDevice(), session);
            for (GenericHeartRateSample s : samples) {
                s.setUserId(userId);
                s.setDeviceId(deviceId);
                provider.addSample(s);
            }
        } catch (Exception e) {
            LOG.error("Failed to persist HR batch ({}): {}", samples.size(), e.getMessage(), e);
        }
    }

    private void persistSpo2Batch(List<GenericSpo2Sample> samples) {
        if (samples == null || samples.isEmpty()) return;
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericSpo2SampleProvider provider =
                    new GenericSpo2SampleProvider(getDevice(), session);
            for (GenericSpo2Sample s : samples) {
                s.setUserId(userId);
                s.setDeviceId(deviceId);
                provider.addSample(s);
            }
        } catch (Exception e) {
            LOG.error("Failed to persist SpO2 batch ({}): {}", samples.size(), e.getMessage(), e);
        }
    }

    private void persistStressBatch(List<GenericStressSample> samples) {
        if (samples == null || samples.isEmpty()) return;
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericStressSampleProvider provider =
                    new GenericStressSampleProvider(getDevice(), session);
            for (GenericStressSample s : samples) {
                s.setUserId(userId);
                s.setDeviceId(deviceId);
                provider.addSample(s);
            }
        } catch (Exception e) {
            LOG.error("Failed to persist stress batch ({}): {}", samples.size(), e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // External actions
    // -------------------------------------------------------------------------

    @Override
    public void onFindDevice(boolean start) {
        TransactionBuilder tb = createTransactionBuilder("findDevice");
        writeCmd(tb, VRingR26Packet.findDevice(start));
        tb.queue();
    }

    @Override
    public void onHeartRateTest() {
        TransactionBuilder tb = createTransactionBuilder("hrTest");
        writeCmd(tb, VRingR26Packet.startHeartRateMeasurement(true));
        tb.queue();
    }

    @Override
    public void onSetTime() {
        TransactionBuilder tb = createTransactionBuilder("setTime");
        setDeviceTime(tb);
        tb.queue();
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        final int days = getHistoryDaysToFetch();
        LOG.info("onFetchRecordedData(types=0x{}): pulling {} day(s) of history",
                Integer.toHexString(dataTypes), days);
        TransactionBuilder tb = createTransactionBuilder("fetchData");
        setDeviceTime(tb);
        // Read current totals.
        BluetoothGattCharacteristic fdd1 = getCharacteristic(VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY_ALT);
        if (fdd1 != null) tb.read(fdd1);
        requestHistory(tb, days);
        tb.queue();
    }

    private int getHistoryDaysToFetch() {
        try (DBHandler db = GBApplication.acquireDB()) {
            final VRingR26ActivitySampleProvider provider =
                    new VRingR26ActivitySampleProvider(getDevice(), db.getDaoSession());
            final VRingR26ActivitySample latest = provider.getLatestActivitySample();
            return calculateHistoryDaysToFetch(
                    latest == null ? 0 : latest.getTimestamp(),
                    System.currentTimeMillis());
        } catch (Exception e) {
            LOG.warn("Failed to determine latest R26 sample, fetching full history", e);
            return HISTORY_DAYS;
        }
    }

    static int calculateHistoryDaysToFetch(final int latestTimestampSeconds,
                                           final long nowMillis) {
        if (latestTimestampSeconds <= 0) {
            return HISTORY_DAYS;
        }
        final long latestMillis = latestTimestampSeconds * 1000L;
        final Date now = new Date(nowMillis);
        for (int days = 1; days <= HISTORY_DAYS; days++) {
            final Date firstDay = DateTimeUtils.dayStart(
                    DateTimeUtils.shiftByDays(now, -(days - 1)));
            if (latestMillis >= firstDay.getTime()) {
                return days;
            }
        }
        return HISTORY_DAYS;
    }

    private static int startOfDayLocal(int dayOffset) {
        return (int) (DateTimeUtils.dayStart(
                DateTimeUtils.shiftByDays(new Date(), dayOffset)
        ).getTime() / 1000L);
    }

    static int stepsPerMinute(final int steps, final int bucketSeconds) {
        if (steps <= 0 || bucketSeconds <= 0) {
            return 0;
        }
        return Math.round(steps * 60.0f / bucketSeconds);
    }

    @Nullable
    static Integer prorateTotal(final long total, final int bucketSteps,
                                final int totalSteps) {
        if (total <= 0 || bucketSteps <= 0 || totalSteps <= 0) {
            return null;
        }
        return (int) Math.round(total * (double) bucketSteps / totalSteps);
    }

    private static long uint32(final byte[] bytes, final int offset) {
        return Integer.toUnsignedLong(BLETypeConversions.toUint32(bytes, offset));
    }

    private static boolean isPlausibleHeartRate(final int bpm) {
        return bpm >= VRingR26Constants.HEART_RATE_MIN_BPM
                && bpm <= VRingR26Constants.HEART_RATE_MAX_BPM;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString();
    }
}
