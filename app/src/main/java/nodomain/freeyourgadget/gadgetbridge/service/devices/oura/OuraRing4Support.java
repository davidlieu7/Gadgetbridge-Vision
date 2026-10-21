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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.OuraConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraAuth;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraHistory;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

import static nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraProtocol.*;

/**
 * Connection order: firmware version, capabilities, nonce and proof, radio mode, time, event flags,
 * battery, feature states, batching, prepare history, then history until the ring reports nothing left.
 */
public class OuraRing4Support extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(OuraRing4Support.class);

    /** Bounds one fetch should the ring keep moving the cursor and reporting more data stored after it. */
    private static final int MAX_HISTORY_REQUESTS = 2000;
    /**
     * How far the phone's view of the ring's boot time may stray from the stored one before the
     * stored one is replaced. Keeping it otherwise maps a tick to the same time on every connection.
     */
    private static final long CLOCK_TOLERANCE_MS = 10 * 60_000L;
    /** A fetch the ring sends nothing for this long is dropped, and fetched again next time. */
    private static final long HISTORY_TIMEOUT_MS = 60_000L;
    /** How long to wait before asking again for records the ring is still writing, and how often. */
    private static final long PENDING_RECORDS_DELAY_MS = 500L;
    private static final int MAX_PENDING_RECORDS_WAITS = 40;

    /**
     * Handshake states. Another app connected to the ring shares the link and its notifications,
     * so its firmware replies, nonces and proof replies arrive here too.
     */
    private static final int AUTH_NONE = 0;
    private static final int AUTH_NONCE_REQUESTED = 1;
    private static final int AUTH_PROOF_SENT = 2;
    private static final int AUTH_DONE = 3;

    private static final int[] FEATURES = {SWITCH_ALL_DAY_HR, SWITCH_ACTIVITY_HR, SWITCH_BLOOD_OXYGEN, SWITCH_STEPS};

    private int authState = AUTH_NONE;
    /** Set once the ring answered a request this connection did not send. */
    private boolean otherAppSeen;
    private long syncMillis;
    /** The boot time of {@link OuraConstants#PREF_BOOT_MILLIS}; -1 before the set time reply. */
    private long bootMillis = -1;
    private OuraHistoryImport historyImport;
    /** The import of this connection's fetch while it is written to the database, else null. */
    private OuraHistoryImport storing;
    private boolean fetching;
    /** Set once a fetch has put the radio back to idle, so the next fetch of the connection sets sync again. */
    private boolean radioIdle;
    private int historyRequests;
    private long requestCursor;
    /** Record bytes this fetch received, which with the bytes the ring reports left give its progress. */
    private long fetchedBytes;
    private int pendingRecordsWaits;
    private long pendingRecordsCursor;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable historyTimeout = this::abortHistory;
    private final Runnable pendingRecordsRequest = this::requestPendingRecords;

    public OuraRing4Support() {
        super(LOG);
        addSupportedService(SERVICE);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            synchronized (this) {
                cancelHistoryCallbacks();
                if (fetching) {
                    GB.updateTransferNotification(null, "", false, 100, getContext());
                }
                fetching = false;
                historyImport = null;
                storing = null;
            }
            super.dispose();
        }
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        synchronized (this) {
            if (fetching) {
                endFetch();
            }
        }
        radioIdle = false;
        authState = AUTH_NONE;
        otherAppSeen = false;
        // The set time reply confirms the time mapping again
        bootMillis = -1;
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(CHARACTERISTIC_NOTIFY, true);
        // Android may raise this to 517; every reply is parsed from its own length bytes
        builder.requestMtu(247);
        builder.write(CHARACTERISTIC_WRITE, frame(CMD_FIRMWARE, (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x00));
        return builder;
    }

    @Override
    public void onFetchRecordedData(final int dataTypes) {
        if ((dataTypes & (RecordedDataTypes.TYPE_ACTIVITY | RecordedDataTypes.TYPE_SYNC)) != 0 || dataTypes == 0) {
            startHistory();
        }
    }

    @Override
    public void onSendConfiguration(final String config) {
        final int feature = featureForPref(config);
        if (feature < 0) {
            super.onSendConfiguration(config);
            return;
        }
        // Defaults match devicesettings_oura_health.xml
        final boolean enabled = getDevicePrefs().getBoolean(config, feature != SWITCH_BLOOD_OXYGEN);
        send("set feature " + feature, extended(EXT_FEATURE_SET, (byte) feature, (byte) (enabled ? FEATURE_ON : FEATURE_OFF)));
    }

    private static int featureForPref(final String pref) {
        if (DeviceSettingsPreferenceConst.PREF_HEARTRATE_AUTOMATIC_ENABLE.equals(pref)) {
            return SWITCH_ALL_DAY_HR;
        } else if (DeviceSettingsPreferenceConst.PREF_HEARTRATE_ACTIVITY_MONITORING.equals(pref)) {
            return SWITCH_ACTIVITY_HR;
        } else if (DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING.equals(pref)) {
            return SWITCH_BLOOD_OXYGEN;
        } else if (OuraConstants.PREF_STEPS.equals(pref)) {
            return SWITCH_STEPS;
        }
        return -1;
    }

    private static String prefForFeature(final int feature) {
        switch (feature) {
            case SWITCH_ALL_DAY_HR:
                return DeviceSettingsPreferenceConst.PREF_HEARTRATE_AUTOMATIC_ENABLE;
            case SWITCH_ACTIVITY_HR:
                return DeviceSettingsPreferenceConst.PREF_HEARTRATE_ACTIVITY_MONITORING;
            case SWITCH_BLOOD_OXYGEN:
                return DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING;
            case SWITCH_STEPS:
                return OuraConstants.PREF_STEPS;
            default:
                return null;
        }
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        if (!CHARACTERISTIC_NOTIFY.equals(characteristic.getUuid())) {
            return false;
        }
        handleNotification(value);
        return true;
    }

    private void handleNotification(final byte[] value) {
        int offset = OuraHistory.isHistory(value) ? handleHistory(value) : 0;
        while (offset + 2 <= value.length) {
            final int length = value[offset + 1] & 0xff;
            if (offset + 2 + length > value.length) {
                LOG.warn("Truncated reply {}", GB.lazyHexdump(value));
                return;
            }
            final byte[] payload = new byte[length];
            System.arraycopy(value, offset + 2, payload, 0, length);
            handleReply(value[offset] & 0xff, payload);
            offset += 2 + length;
        }
    }

    private void handleReply(final int command, final byte[] payload) {
        switch (command) {
            case RSP_FIRMWARE:
                handleFirmware(payload);
                if (authState != AUTH_NONE) {
                    unrequested("firmware reply");
                    break;
                }
                authState = AUTH_NONCE_REQUESTED;
                send("capabilities 0", extended(EXT_CAPABILITIES, (byte) 0x00));
                send("capabilities 1", extended(EXT_CAPABILITIES, (byte) 0x01));
                send("nonce", extended(EXT_NONCE));
                break;
            case RSP_BATTERY:
                handleBattery(payload);
                break;
            case RSP_SET_TIME:
                handleTime(payload);
                break;
            case RSP_HISTORY_DONE:
                handleHistoryDone(payload);
                break;
            case CMD_EXTENDED:
                if (payload.length > 0) {
                    handleExtended(payload[0] & 0xff, payload);
                }
                break;
            case RSP_RADIO_MODE:
            case RSP_EVENT_FLAGS:
            case RSP_PREPARE_HISTORY:
            case RSP_EVENT:
                LOG.debug("Reply 0x{}: {}", Integer.toHexString(command), GB.lazyHexdump(payload));
                break;
            default:
                LOG.debug("Unhandled reply 0x{}: {}", Integer.toHexString(command), GB.lazyHexdump(payload));
                break;
        }
    }

    private void handleExtended(final int subCommand, final byte[] payload) {
        switch (subCommand) {
            case EXT_NONCE_REPLY:
                handleNonce(payload);
                break;
            case EXT_PROOF_REPLY:
                handleProofReply(payload.length > 1 ? payload[1] & 0xff : -1);
                break;
            case EXT_FEATURE_GET_REPLY:
                if (payload.length >= 3) {
                    final String pref = prefForFeature(payload[1] & 0xff);
                    if (pref != null) {
                        getDevicePrefs().getPreferences().edit().putBoolean(pref, payload[2] != FEATURE_OFF).apply();
                    }
                }
                break;
            default:
                LOG.debug("Extended reply 0x{}: {}", Integer.toHexString(subCommand), GB.lazyHexdump(payload));
                break;
        }
    }

    private void handleFirmware(final byte[] payload) {
        if (payload.length < 6) {
            return;
        }
        final GBDeviceEventVersionInfo info = new GBDeviceEventVersionInfo();
        info.fwVersion = String.format(Locale.ROOT, "%d.%d.%d", payload[3] & 0xff, payload[4] & 0xff, payload[5] & 0xff);
        evaluateGBDeviceEvent(info);
    }

    private void handleBattery(final byte[] payload) {
        if (payload.length < 6) {
            return;
        }
        final GBDeviceEventBatteryInfo info = new GBDeviceEventBatteryInfo();
        info.level = payload[0] & 0xff;
        info.voltage = u16(payload, 4) / 1000f;
        info.state = info.level <= 15 ? BatteryState.BATTERY_LOW : BatteryState.BATTERY_NORMAL;
        evaluateGBDeviceEvent(info);
    }

    private void handleNonce(final byte[] payload) {
        if (authState != AUTH_NONCE_REQUESTED) {
            unrequested("nonce");
            return;
        }
        if (payload.length < 1 + OuraAuth.NONCE_LEN) {
            LOG.error("Short nonce {}", GB.lazyHexdump(payload));
            disconnectWith(null);
            return;
        }
        final byte[] key = OuraAuth.parseHexKey(getDevicePrefs().getString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, null));
        if (key == null) {
            LOG.error("No valid auth key set");
            fail();
            return;
        }
        final byte[] nonce = new byte[OuraAuth.NONCE_LEN];
        System.arraycopy(payload, 1, nonce, 0, nonce.length);
        authState = AUTH_PROOF_SENT;
        send("proof", extended(EXT_PROOF, OuraAuth.proof(nonce, key)));
    }

    private void handleProofReply(final int status) {
        if (authState != AUTH_PROOF_SENT) {
            unrequested("proof reply");
            return;
        }
        if (status != PROOF_ACCEPTED) {
            LOG.error("Ring rejected the auth key, status {} ({})", status, proofStatusName(status));
            // The other app's nonce replaces the one this connection proved
            disconnectWith(getContext().getString(otherAppSeen
                    ? R.string.authentication_failed_other_app
                    : R.string.authentication_failed_check_key));
            return;
        }
        authState = AUTH_DONE;
        send("radio mode sync", frame(CMD_RADIO_MODE, (byte) RADIO_MODE_SYNC));
        syncMillis = System.currentTimeMillis();
        send("set time", setTime(syncMillis / 1000L,
                TimeZone.getDefault().getOffset(syncMillis) / 60_000));
    }

    private void unrequested(final String reply) {
        if (!otherAppSeen) {
            LOG.warn("Ring sent a {} this connection did not ask for, another app is connected to it", reply);
            otherAppSeen = true;
        } else {
            LOG.debug("Ignoring {} for another app", reply);
        }
    }

    private static String proofStatusName(final int status) {
        switch (status) {
            case PROOF_WRONG_KEY:
                return "wrong key";
            case PROOF_RING_RESET:
                return "ring reset";
            case PROOF_OTHER_PHONE:
                return "set up with another phone";
            default:
                return "unknown";
        }
    }

    private void handleTime(final byte[] payload) {
        if (payload.length < 4) {
            return;
        }
        final long ticks = u32(payload, 0);
        // The ring reports the whole seconds sent
        final long measuredBootMillis = OuraHistoryImport.bootMillis(syncMillis / 1000L * 1000L, ticks);
        final SharedPreferences prefs = getDevicePrefs().getPreferences();
        final long storedBootMillis = prefs.getLong(OuraConstants.PREF_BOOT_MILLIS, -1L);
        final long cursor = prefs.getLong(OuraConstants.PREF_HISTORY_CURSOR, 0L);
        // Ticks behind the cursor, or a boot after the newest record stored, mean the ticks started over
        final boolean rebooted = cursor > ticks || storedBootMillis >= 0
                && measuredBootMillis > storedBootMillis + cursor * 1000L / TICKS_PER_SECOND + CLOCK_TOLERANCE_MS;
        final SharedPreferences.Editor editor = prefs.edit();
        if (rebooted || storedBootMillis < 0 || Math.abs(measuredBootMillis - storedBootMillis) > CLOCK_TOLERANCE_MS) {
            bootMillis = measuredBootMillis;
            editor.putLong(OuraConstants.PREF_BOOT_MILLIS, bootMillis);
        } else {
            bootMillis = storedBootMillis;
        }
        if (rebooted) {
            LOG.info("Ring ticks started over, fetching its whole history");
            editor.putLong(OuraConstants.PREF_HISTORY_CURSOR, 0L).remove(OuraConstants.PREF_OFF_SINCE);
        }
        editor.apply();

        send("event flags", frame(CMD_EVENT_FLAGS, (byte) EVENT_FLAGS_ALL));
        send("battery", frame(CMD_BATTERY));
        for (final int feature : FEATURES) {
            send("feature " + feature, extended(EXT_FEATURE_GET, (byte) feature));
        }
        send("batching", extended(EXT_BATCHING, (byte) 0x01));
        send("prepare history", frame(CMD_PREPARE_HISTORY, (byte) 0x00));
        getDevice().setUpdateState(GBDevice.State.INITIALIZED, getContext());
        startHistory();
    }

    private synchronized void startHistory() {
        if (bootMillis < 0 || fetching) {
            return;
        }
        fetching = true;
        final SharedPreferences prefs = getDevicePrefs().getPreferences();
        if (!prefs.contains(OuraConstants.PREF_HISTORY_CURSOR)) {
            // The first fetch reads all the ring keeps, weeks of it
            GB.toast(getContext(), getContext().getString(R.string.first_sync_takes_minutes), Toast.LENGTH_LONG, GB.INFO);
        }
        final long cursor = prefs.getLong(OuraConstants.PREF_HISTORY_CURSOR, 0L);
        historyImport = new OuraHistoryImport(getDevice(), bootMillis);
        historyImport.startAfter(cursor);
        final long offSince = prefs.getLong(OuraConstants.PREF_OFF_SINCE, -1L);
        if (offSince >= 0) {
            historyImport.startOff(offSince);
        }
        if (radioIdle) {
            send("radio mode sync", frame(CMD_RADIO_MODE, (byte) RADIO_MODE_SYNC));
            radioIdle = false;
        }
        historyRequests = 0;
        fetchedBytes = 0;
        pendingRecordsWaits = 0;
        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
        GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data), "", true, 0, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        requestHistory(cursor);
    }

    /**
     * Walks the records at the start of a notification and returns where any replies after them
     * begin. Records outside a fetch are left for the next one, which fetches them from the cursor.
     */
    private synchronized int handleHistory(final byte[] value) {
        if (!fetching || historyImport == null) {
            return OuraHistory.walk(value, (tag, ticks, body) -> {
            });
        }
        armHistoryTimeout();
        final int length = OuraHistory.walk(value, historyImport);
        fetchedBytes += length;
        return length;
    }

    private void armHistoryTimeout() {
        timeoutHandler.removeCallbacks(historyTimeout);
        timeoutHandler.postDelayed(historyTimeout, HISTORY_TIMEOUT_MS);
    }

    private void requestHistory(final long cursor) {
        armHistoryTimeout();
        requestCursor = cursor;
        historyRequests++;
        send("history", history(cursor, historyRequests == 1 ? HISTORY_FIRST_RECORDS : HISTORY_ALL_RECORDS));
    }

    private void handleHistoryDone(final byte[] payload) {
        final OuraHistoryImport batch;
        final OuraHistoryImport nextBatch;
        synchronized (this) {
            if (!fetching || historyImport == null || payload.length < 6) {
                return;
            }
            final long storedAfter = u32(payload, 2);
            // A batch without records leaves the cursor where it was requested
            final long next = Math.max(historyImport.getMaxTicks() + 1, requestCursor);
            final boolean moved = next > requestCursor;
            LOG.debug("History batch done: {} bytes stored after it, cursor {}", storedAfter, next);
            if (storedAfter > 0 && moved && historyRequests < MAX_HISTORY_REQUESTS) {
                final int percent = (int) (fetchedBytes * 100 / (fetchedBytes + storedAfter));
                GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data), "", true, percent, getContext());
                // Each batch is stored as it ends, so a fetch cut short keeps the batches before
                batch = historyImport;
                nextBatch = new OuraHistoryImport(getDevice(), batch.getBootMillis());
                nextBatch.startAfter(next);
                if (batch.getOffSince() >= 0) {
                    nextBatch.startOff(batch.getOffSince());
                }
                historyImport = nextBatch;
                requestHistory(next);
            } else if (storedAfter == 0 && payload[1] != 0 && pendingRecordsWaits < MAX_PENDING_RECORDS_WAITS) {
                pendingRecordsWaits++;
                pendingRecordsCursor = next;
                armHistoryTimeout();
                timeoutHandler.postDelayed(pendingRecordsRequest, PENDING_RECORDS_DELAY_MS);
                return;
            } else {
                cancelHistoryCallbacks();
                batch = historyImport;
                nextBatch = null;
                storing = batch;
                // Still fetching, so no other fetch starts, but records arriving now are not collected
                historyImport = null;
            }
        }
        if (nextBatch != null) {
            // The next batch arrives on this thread, so it waits for the write
            storeBatch(batch, nextBatch);
        } else {
            finishHistory(batch);
        }
    }

    private synchronized void requestPendingRecords() {
        if (fetching && historyImport != null) {
            requestHistory(pendingRecordsCursor);
        }
    }

    /**
     * Stores a batch of a fetch still running. A batch that fails to store ends the fetch, so the
     * next one fetches it again from the cursor rather than storing the batches after it.
     */
    private void storeBatch(final OuraHistoryImport batch, final OuraHistoryImport nextBatch) {
        if (store(batch)) {
            return;
        }
        synchronized (this) {
            if (fetching && historyImport == nextBatch) {
                endFetch();
            }
        }
    }

    private void finishHistory(final OuraHistoryImport finished) {
        store(finished);
        synchronized (this) {
            if (storing != finished) {
                // The connection ended or restarted during the write
                return;
            }
            send("radio mode idle", frame(CMD_RADIO_MODE, (byte) RADIO_MODE_IDLE));
            radioIdle = true;
            endFetch();
        }
    }

    /**
     * Stores fetched history, and only then moves the cursor past it and keeps whether the ring
     * was left off the finger, so a failed write is fetched again from the same state. The write
     * runs outside the lock, which the main thread takes for the fetch timers.
     */
    private boolean store(final OuraHistoryImport history) {
        boolean stored = false;
        try (DBHandler db = GBApplication.acquireDB()) {
            stored = history.persist(db.getDaoSession(), getContext());
        } catch (final Exception e) {
            LOG.error("Failed to store the ring history", e);
        }
        LOG.info("Ring history stored: {}, records {}", stored, history.getRecordCounts());
        synchronized (this) {
            final SharedPreferences prefs = getDevicePrefs().getPreferences();
            final long next = history.getMaxTicks() + 1;
            // Stored rows move the cursor even when the connection ended during the write, unless
            // the boot time changed meanwhile, as when the ticks started over
            if (stored && history.getMaxTicks() >= 0
                    && history.getBootMillis() == prefs.getLong(OuraConstants.PREF_BOOT_MILLIS, -1L)
                    && next > prefs.getLong(OuraConstants.PREF_HISTORY_CURSOR, 0L)) {
                prefs.edit()
                        .putLong(OuraConstants.PREF_HISTORY_CURSOR, next)
                        .putLong(OuraConstants.PREF_OFF_SINCE, history.getOffSince())
                        .apply();
            }
        }
        return stored;
    }

    private synchronized void abortHistory() {
        if (!fetching || historyImport == null) {
            return;
        }
        LOG.warn("Ring sent no history for {} ms, dropping the fetch", HISTORY_TIMEOUT_MS);
        endFetch();
    }

    private void cancelHistoryCallbacks() {
        timeoutHandler.removeCallbacks(historyTimeout);
        timeoutHandler.removeCallbacks(pendingRecordsRequest);
    }

    private void endFetch() {
        cancelHistoryCallbacks();
        historyImport = null;
        storing = null;
        fetching = false;
        getDevice().unsetBusyTask();
        GB.updateTransferNotification(null, "", false, 100, getContext());
        GB.signalActivityDataFinish(getDevice());
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    private void fail() {
        disconnectWith(getContext().getString(R.string.authentication_failed_check_key));
    }

    @VisibleForTesting
    protected void disconnectWith(@Nullable final String toast) {
        if (toast != null) {
            GB.toast(getContext(), toast, Toast.LENGTH_LONG, GB.ERROR);
        }
        getDevice().setUpdateState(GBDevice.State.NOT_CONNECTED, getContext());
        disconnect();
    }

    @VisibleForTesting
    protected void send(final String task, final byte[] frame) {
        final TransactionBuilder builder = createTransactionBuilder(task);
        builder.write(CHARACTERISTIC_WRITE, frame);
        builder.queue();
    }
}
