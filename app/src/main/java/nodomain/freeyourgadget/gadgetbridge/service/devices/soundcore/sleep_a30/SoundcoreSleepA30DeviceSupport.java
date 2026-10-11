/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.sleep_a30.SoundcoreSleepA30AudioInstallHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcoreDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcoreSleepHistory;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcoreSleepHistoryEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcoreSleepHistoryStorage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcorePacket;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns a separate SDP-resolved BESOTA socket; never routes transfer bytes to the control queue. */
public final class SoundcoreSleepA30DeviceSupport extends SoundcoreDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(SoundcoreSleepA30DeviceSupport.class);
    private static final UUID BESOTA = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private Upload active;
    private final ByteArrayOutputStream controlBuffer = new ByteArrayOutputStream();
    private boolean disposed;
    private boolean historyFetchActive;
    private ScheduledFuture<?> historyTimeout;

    private static final class Upload {
        volatile BluetoothSocket socket;
        volatile boolean cancelled;
        volatile long expectedTrack;
        volatile boolean awaitingReadback;
        volatile boolean matched;
        final CountDownLatch readback = new CountDownLatch(1);
        ScheduledFuture<?> timeout;
        long timeoutVersion;
        int lastPercent = -1;
    }

    @Override public synchronized void onFetchRecordedData(int dataTypes) {
        if (disposed || historyFetchActive || getDevice().isBusy() || !getDevice().isInitialized() ||
                !getDevice().isConnected()) return;
        SoundcoreSleepA30Protocol protocol = (SoundcoreSleepA30Protocol) mDeviceProtocol;
        byte[] request = protocol.encodeFetchRecordedData(dataTypes);
        if (request == null) return;
        historyFetchActive = true;
        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        historyTimeout = timer.schedule(() -> {
            synchronized (SoundcoreSleepA30DeviceSupport.this) {
                LOG.warn("Sleep A30 history fetch timed out");
                finishHistoryFetch();
            }
        }, 30, TimeUnit.SECONDS);
        evaluateGBDeviceEvent(new nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventSendBytes(request));
    }

    @Override public synchronized void evaluateGBDeviceEvent(
            nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent event) {
        if (!(event instanceof SoundcoreSleepHistoryEvent)) {
            super.evaluateGBDeviceEvent(event);
            return;
        }
        if (!historyFetchActive) return;
        SoundcoreSleepHistory.Report report = ((SoundcoreSleepHistoryEvent) event).getReport();
        try {
            if (report == null) LOG.warn("Invalid Sleep A30 history snapshot");
            else SoundcoreSleepHistoryStorage.persist(getDevice(), report);
        } catch (Exception e) {
            LOG.error("Unable to save Sleep A30 history", e);
        } finally {
            finishHistoryFetch();
        }
    }

    private synchronized void finishHistoryFetch() {
        if (historyTimeout != null) historyTimeout.cancel(false);
        historyTimeout = null;
        ((SoundcoreSleepA30Protocol) mDeviceProtocol).cancelSleepHistory();
        if (!historyFetchActive) return;
        historyFetchActive = false;
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
        GB.signalActivityDataFinish(getDevice());
    }

    @Override public synchronized void onInstallApp(Uri uri, Bundle options) {
        if (disposed || active != null || getDevice().isBusy() || !getDevice().isInitialized()) {
            finishUi(false, "Device is not ready or an upload is already in progress");
            return;
        }
        Upload upload = new Upload();
        active = upload;
        getDevice().setBusyTask(R.string.uploading, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        byte[] digest = options.getByteArray(SoundcoreSleepA30AudioInstallHandler.EXTRA_APPROVED_SHA256);
        byte[] approvedDigest = digest == null ? null : digest.clone();
        worker.execute(() -> runUpload(upload, uri, approvedDigest));
    }

    @SuppressLint("MissingPermission")
    private void runUpload(Upload upload, Uri uri, byte[] approvedDigest) {
        boolean success = false;
        String error = null;
        try {
            SoundcoreSleepA30Audio audio = SoundcoreSleepA30AudioInstallHandler.readAudio(uri, getContext());
            audio.requireApprovedDigest(approvedDigest);
            checkActive(upload);
            synchronized (this) {
                checkActive(upload);
                upload.socket = getBluetoothAdapter().getRemoteDevice(getDevice().getAddress())
                    .createRfcommSocketToServiceRecord(BESOTA);
            }
            armTimeout(upload, 20);
            getBluetoothAdapter().cancelDiscovery();
            upload.socket.connect();
            checkActive(upload);
            SoundcoreSleepA30AudioTransfer transfer = new SoundcoreSleepA30AudioTransfer(
                upload.socket.getInputStream(), upload.socket.getOutputStream(), () -> {
                    if (upload.cancelled || !getDevice().isConnected()) throw new IllegalStateException("Audio upload disconnected or cancelled");
                    armTimeout(upload, 15);
                });
            transfer.upload(audio, (bytes, total) -> progress(upload, bytes, total));
            closeSocket(upload);
            synchronized (this) {
                checkActive(upload);
                cancelTimeout(upload);
                upload.expectedTrack = audio.trackId;
                upload.awaitingReadback = true;
            }
            createTransactionBuilder("read uploaded local audio").write(new SoundcorePacket((short) 0x0113).encode()).queue();
            if (!upload.readback.await(15, TimeUnit.SECONDS) || !upload.matched) {
                throw new IOException("Upload acknowledged but the target slot could not be verified");
            }
            checkActive(upload);
            success = true;
        } catch (Exception e) {
            error = upload.cancelled ? "Audio upload cancelled or timed out" : e.getMessage();
            LOG.warn("Sleep A30 audio upload failed", e);
        } finally {
            synchronized (this) {
                cancelTimeout(upload);
                closeSocket(upload);
                if (active == upload) {
                    active = null;
                    getDevice().unsetBusyTask();
                    finishUi(success, error);
                    getDevice().sendDeviceUpdateIntent(getContext());
                }
            }
        }
    }

    private void checkActive(Upload upload) throws IOException {
        if (upload.cancelled || !getDevice().isConnected()) throw new IOException("Audio upload disconnected or cancelled");
    }

    private synchronized void armTimeout(Upload upload, int seconds) {
        cancelTimeout(upload);
        if (upload.cancelled || disposed) return;
        long version = upload.timeoutVersion;
        upload.timeout = timer.schedule(() -> {
            synchronized (SoundcoreSleepA30DeviceSupport.this) {
                if (active != upload || upload.timeoutVersion != version) return;
                upload.cancelled = true;
                closeSocket(upload);
                upload.readback.countDown();
            }
        }, seconds, TimeUnit.SECONDS);
    }

    private static void cancelTimeout(Upload upload) {
        upload.timeoutVersion++;
        if (upload.timeout != null) upload.timeout.cancel(false);
        upload.timeout = null;
    }

    private static void closeSocket(Upload upload) {
        BluetoothSocket socket = upload.socket;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private synchronized void progress(Upload upload, int bytes, int total) {
        // Reserve completion for both final device ACKs and the control-service readback.
        int percent = Math.min(99, bytes * 100 / total);
        if (upload.cancelled || percent == upload.lastPercent) return;
        upload.lastPercent = percent;
        GB.updateInstallNotification(getContext().getString(R.string.uploading), true, percent, getContext());
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(new Intent(GB.ACTION_SET_PROGRESS_BAR)
            .putExtra(GB.PROGRESS_BAR_PROGRESS, percent));
    }

    private void finishUi(boolean success, String error) {
        String message = getContext().getString(success ? R.string.installation_successful : R.string.installation_failed_);
        if (!success && error != null) message += ": " + error;
        GB.updateInstallNotification(message, false, success ? 100 : 0, getContext());
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(getContext());
        manager.sendBroadcast(new Intent(GB.ACTION_SET_PROGRESS_TEXT).putExtra(GB.DISPLAY_MESSAGE_MESSAGE, message));
        if (success) manager.sendBroadcast(new Intent(GB.ACTION_SET_PROGRESS_BAR).putExtra(GB.PROGRESS_BAR_PROGRESS, 100));
        manager.sendBroadcast(new Intent(GB.ACTION_SET_FINISHED));
    }

    @Override public synchronized void onSocketRead(byte[] data) {
        Upload upload = active;
        controlBuffer.write(data, 0, data.length);
        byte[] pending = controlBuffer.toByteArray();
        int offset = 0;
        while (pending.length - offset >= 10) {
            if (pending[offset] != 9 || pending[offset + 1] != (byte) 0xff) {
                if (upload != null && upload.awaitingReadback) upload.readback.countDown();
                controlBuffer.reset();
                return;
            }
            int length = (pending[offset + 7] & 255) | ((pending[offset + 8] & 255) << 8);
            if (length < 10 || length > 4096) {
                if (upload != null && upload.awaitingReadback) upload.readback.countDown();
                controlBuffer.reset();
                return;
            }
            if (pending.length - offset < length) break;
            byte[] packet = Arrays.copyOfRange(pending, offset, offset + length);
            int command = (packet[5] & 255) | ((packet[6] & 255) << 8);
            if (command == 0x0113 && upload != null && upload.awaitingReadback) {
                int checksum = 0;
                for (int i = 0; i < packet.length - 1; i++) checksum += packet[i];
                if ((byte) checksum == packet[packet.length - 1]) {
                    upload.matched = SoundcoreSleepA30Audio.matchesTarget(Arrays.copyOfRange(packet, 9, packet.length - 1), upload.expectedTrack);
                }
                upload.readback.countDown();
            }
            super.onSocketRead(packet);
            offset += length;
        }
        controlBuffer.reset();
        controlBuffer.write(pending, offset, pending.length - offset);
    }

    private synchronized void cancelUpload() {
        if (active != null) {
            active.cancelled = true;
            cancelTimeout(active);
            closeSocket(active);
            active.readback.countDown();
            active = null;
            getDevice().unsetBusyTask();
            finishUi(false, "Audio upload cancelled");
            getDevice().sendDeviceUpdateIntent(getContext());
        }
    }

    @Override public void disconnect() {
        finishHistoryFetch();
        cancelUpload();
        synchronized (this) { controlBuffer.reset(); }
        super.disconnect();
    }

    @Override public synchronized nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder initializeDevice(
            nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder builder) {
        controlBuffer.reset();
        finishHistoryFetch();
        cancelUpload();
        return super.initializeDevice(builder);
    }

    @Override public void dispose() {
        synchronized (this) {
            disposed = true;
            finishHistoryFetch();
            cancelUpload();
        }
        worker.shutdownNow();
        timer.shutdownNow();
        super.dispose();
    }
}
