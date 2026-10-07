/*  Copyright (C) 2026 Vitaliy Tomin

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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.StringRes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceApp;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3FileTransfer;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Watchface;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Watchface management for the Fit3: listing, activating and deleting
 * installed faces on {@code CH_WATCHFACE}, and installing new ones, which
 * spans both SAP channels and an RFCOMM socket.
 *
 * <p>An install runs as a fixed sequence, each step gated on the band's reply
 * to the previous one:
 * <ol>
 *   <li>{@code CH_WATCHFACE} INSTALL (id, variant) - band accepts;</li>
 *   <li>{@code CH_FILE_TRANSFER} offer file (purpose, name, size) - band
 *       has room;</li>
 *   <li>{@code CH_FILE_TRANSFER} open socket - band starts its RFCOMM server;</li>
 *   <li>the file over RFCOMM ({@link Fit3SppFileSender});</li>
 *   <li>band sends INSTALL again, now with the real outcome - after which
 *       the new face is already active;</li>
 *   <li>{@code CH_FILE_TRANSFER} close socket, and a list refresh.</li>
 * </ol>
 * Every wait has a timeout, and any failure still closes the band's RFCOMM
 * server and clears the busy state.
 */
public class Fit3WatchfaceManager {
    private static final Logger LOG = LoggerFactory.getLogger(Fit3WatchfaceManager.class);

    /** Wait for a control reply over BLE. */
    private static final long REPLY_TIMEOUT_MS = 30_000;
    /** Wait for the final install outcome once the file is on the band. */
    private static final long INSTALL_RESULT_TIMEOUT_MS = 60_000;

    public interface Listener {
        void sendMessage(int channel, byte[] body, String reason);

        Context getContext();

        GBDevice getDevice();

        BluetoothDevice getBluetoothDevice();

        void onWatchfaceList(List<GBDeviceApp> apps);

        void onProgress(@StringRes int textRes, boolean ongoing, int percent);
    }

    private enum State {
        IDLE,
        INSTALL_REQUESTED,
        FILE_INFO_SENT,
        SOCKET_OPEN_SENT,
        TRANSFERRING,
        AWAITING_RESULT,
    }

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutTask = this::onTimeout;

    private State state = State.IDLE;
    private Fit3WatchfaceFile pending;
    private Fit3SppFileSender sender;
    /** An install outcome that arrived before the RFCOMM side reported done. */
    private Fit3Watchface.InstallResult earlyResult;

    public Fit3WatchfaceManager(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean isInstalling() {
        return state != State.IDLE;
    }

    // ---- UI-facing operations ----

    public void requestList() {
        listener.sendMessage(Fit3Watchface.CHANNEL, Fit3Watchface.buildListRequest(), "watchface-list");
    }

    /** @return false if the UUID isn't one of ours. */
    public boolean activate(UUID uuid) {
        int[] face = Fit3Watchface.fromUuid(uuid);
        if (face == null) {
            return false;
        }
        listener.sendMessage(Fit3Watchface.CHANNEL, Fit3Watchface.buildActivateRequest(face[0], face[1]),
                "watchface-activate");
        return true;
    }

    /** @return false if the UUID isn't one of ours. */
    public boolean delete(UUID uuid) {
        int[] face = Fit3Watchface.fromUuid(uuid);
        if (face == null) {
            return false;
        }
        listener.sendMessage(Fit3Watchface.CHANNEL, Fit3Watchface.buildDeleteRequest(face[0], face[1]),
                "watchface-delete");
        return true;
    }

    public synchronized void install(Fit3WatchfaceFile file) {
        if (state != State.IDLE) {
            GB.toast(listener.getContext(), "Samsung Fit 3: a watchface install is already running",
                    Toast.LENGTH_LONG, GB.WARN);
            return;
        }
        LOG.info("Samsung Fit 3: installing {}", file);
        pending = file;
        earlyResult = null;
        setBusy(true);
        listener.onProgress(R.string.uploading_watchface, true, 0);
        // A fresh install always asks for the face's default style.
        transition(State.INSTALL_REQUESTED, REPLY_TIMEOUT_MS);
        listener.sendMessage(Fit3Watchface.CHANNEL, Fit3Watchface.buildInstallRequest(file.id, 0),
                "watchface-install");
    }

    // ---- incoming ----

    /** A message on {@code CH_WATCHFACE}, either direction-type. */
    public void onWatchfaceMessage(byte[] body) {
        int msgId = body[0] & 0x3f;
        boolean response = (body[0] & 0x40) != 0;
        switch (msgId) {
            case Fit3Watchface.MSG_ID_GET_LIST:
                if (response) {
                    handleList(body);
                }
                return;
            case Fit3Watchface.MSG_ID_INSTALL:
                Fit3Watchface.InstallResult result = Fit3Watchface.parseInstallResult(body);
                if (result == null) {
                    LOG.warn("Samsung Fit 3: malformed watchface install reply");
                    return;
                }
                onInstallResult(result);
                return;
            case Fit3Watchface.MSG_ID_ACTIVATE:
                // Either our own request answered, or the user picked a face on
                // the band itself - both just mean the list is stale.
                LOG.info("Samsung Fit 3: active watchface is now {}", describe(Fit3Watchface.parseIdPair(body)));
                requestList();
                return;
            case Fit3Watchface.MSG_ID_DELETE:
                if (response) {
                    Boolean ok = Fit3Watchface.parseDeleteResult(body);
                    LOG.info("Samsung Fit 3: watchface delete {}: {}", describe(Fit3Watchface.parseIdPair(body)), ok);
                    if (Boolean.FALSE.equals(ok)) {
                        GB.toast(listener.getContext(), "Samsung Fit 3: the band refused to delete that watchface",
                                Toast.LENGTH_LONG, GB.WARN);
                    }
                } else {
                    LOG.info("Samsung Fit 3: watchface {} deleted on the band", describe(Fit3Watchface.parseIdPair(body)));
                }
                requestList();
                return;
            default:
                LOG.debug("Samsung Fit 3: watchface msgId={} response={} unhandled", msgId, response);
        }
    }

    /** A message on {@code CH_FILE_TRANSFER}. */
    public synchronized void onFileTransferMessage(byte[] body) {
        Fit3FileTransfer.Response response = Fit3FileTransfer.parse(body);
        if (response == null) {
            LOG.debug("Samsung Fit 3: file-transfer message ignored (not a reply)");
            return;
        }
        LOG.debug("Samsung Fit 3: file-transfer reply {}", response);
        switch (response.msgId) {
            case Fit3FileTransfer.MSG_ID_OFFER_FILE:
                if (state != State.FILE_INFO_SENT) {
                    return;
                }
                if (!response.status) {
                    fail(fileInfoRefusal(response.reason));
                    return;
                }
                transition(State.SOCKET_OPEN_SENT, REPLY_TIMEOUT_MS);
                listener.sendMessage(Fit3FileTransfer.CHANNEL, Fit3FileTransfer.buildOpenSocketRequest(), "file-bt-open");
                return;
            case Fit3FileTransfer.MSG_ID_OPEN_SOCKET:
                if (state != State.SOCKET_OPEN_SENT) {
                    return;
                }
                if (!response.status) {
                    fail("the band could not start the file transfer");
                    return;
                }
                startTransfer();
                return;
            case Fit3FileTransfer.MSG_ID_CLOSE_SOCKET:
                LOG.debug("Samsung Fit 3: band closed its file-transfer server");
                return;
            default:
                LOG.debug("Samsung Fit 3: file-transfer msgId={} unhandled", response.msgId);
        }
    }

    private void handleList(byte[] body) {
        List<Fit3Watchface.Entry> entries = Fit3Watchface.parseList(body);
        if (entries == null) {
            LOG.warn("Samsung Fit 3: malformed watchface list");
            return;
        }
        List<GBDeviceApp> apps = new ArrayList<>(entries.size());
        for (Fit3Watchface.Entry e : Fit3Watchface.sortedByPosition(entries)) {
            LOG.debug("Samsung Fit 3: watchface {}", e);
            String name = e.name != null && !e.name.isEmpty() ? e.name : "Watchface " + e.id;
            apps.add(new GBDeviceApp(Fit3Watchface.toUuid(e.id, e.variant), name, "",
                    e.version != null ? e.version : "", GBDeviceApp.Type.WATCHFACE));
        }
        listener.onWatchfaceList(apps);
    }

    private synchronized void onInstallResult(Fit3Watchface.InstallResult result) {
        LOG.info("Samsung Fit 3: watchface install reply {} in state {}", result, state);
        switch (state) {
            case INSTALL_REQUESTED:
                if (!result.isSuccess()) {
                    fail(installRefusal(result.status));
                    return;
                }
                transition(State.FILE_INFO_SENT, REPLY_TIMEOUT_MS);
                listener.sendMessage(Fit3FileTransfer.CHANNEL,
                        Fit3FileTransfer.buildOfferFileRequest(Fit3FileTransfer.PURPOSE_WATCHFACE,
                                pending.fileName, pending.getData().length),
                        "file-info");
                return;
            case TRANSFERRING:
                // Not expected before the RFCOMM side finishes, but don't lose it.
                earlyResult = result;
                return;
            case AWAITING_RESULT:
                finish(result);
                return;
            default:
                // Unsolicited: the band installed something on its own account.
                requestList();
        }
    }

    // ---- RFCOMM ----

    private void startTransfer() {
        transition(State.TRANSFERRING, 0);
        BluetoothDevice device = listener.getBluetoothDevice();
        if (device == null) {
            fail("no Bluetooth device for the file transfer");
            return;
        }
        final Fit3WatchfaceFile file = pending;
        sender = new Fit3SppFileSender(new Fit3SppFileSender.Callback() {
            @Override
            public void onProgress(int percent) {
                listener.onProgress(R.string.uploading_watchface, true, percent);
            }

            @Override
            public void onComplete() {
                onTransferComplete();
            }

            @Override
            public void onError(String message) {
                onTransferFailed(message);
            }
        });
        sender.start(device, Fit3SppFileSender.DIR_WATCHFACE, file.fileName, file.getData());
    }

    private synchronized void onTransferComplete() {
        if (state != State.TRANSFERRING) {
            return;
        }
        sender = null;
        LOG.info("Samsung Fit 3: watchface file delivered, waiting for the band to install it");
        if (earlyResult != null) {
            finish(earlyResult);
            return;
        }
        transition(State.AWAITING_RESULT, INSTALL_RESULT_TIMEOUT_MS);
    }

    private synchronized void onTransferFailed(String message) {
        if (state != State.TRANSFERRING) {
            return;
        }
        sender = null;
        fail("file transfer failed: " + message);
    }

    // ---- completion ----

    private void finish(Fit3Watchface.InstallResult result) {
        if (result.isSuccess()) {
            LOG.info("Samsung Fit 3: watchface {} installed", result.id);
            reset();
            listener.onProgress(R.string.installation_successful, false, 100);
            GB.toast(listener.getContext(), listener.getContext().getString(R.string.installation_successful),
                    Toast.LENGTH_SHORT, GB.INFO);
        } else {
            fail(installRefusal(result.status));
            return;
        }
        requestList();
    }

    private void fail(String reason) {
        LOG.warn("Samsung Fit 3: watchface install failed: {}", reason);
        reset();
        listener.onProgress(R.string.installation_failed_, false, 0);
        GB.toast(listener.getContext(), listener.getContext().getString(R.string.installation_failed_) + ": " + reason,
                Toast.LENGTH_LONG, GB.ERROR);
    }

    /** Back to idle from wherever we were, telling the band to drop its RFCOMM server if it may be up. */
    private void reset() {
        handler.removeCallbacks(timeoutTask);
        boolean socketOpenSent = state == State.SOCKET_OPEN_SENT || state == State.TRANSFERRING
                || state == State.AWAITING_RESULT;
        if (sender != null) {
            sender.cancel();
            sender = null;
        }
        state = State.IDLE;
        pending = null;
        earlyResult = null;
        setBusy(false);
        if (socketOpenSent) {
            listener.sendMessage(Fit3FileTransfer.CHANNEL, Fit3FileTransfer.buildCloseSocketRequest(), "file-bt-close");
        }
    }

    /** Drop any running install, e.g. on disconnect. Nothing is sent. */
    public synchronized void cancel() {
        handler.removeCallbacks(timeoutTask);
        if (sender != null) {
            sender.cancel();
            sender = null;
        }
        if (state != State.IDLE) {
            setBusy(false);
        }
        state = State.IDLE;
        pending = null;
        earlyResult = null;
    }

    private void transition(State next, long timeoutMs) {
        state = next;
        handler.removeCallbacks(timeoutTask);
        if (timeoutMs > 0) {
            handler.postDelayed(timeoutTask, timeoutMs);
        }
    }

    private synchronized void onTimeout() {
        if (state == State.IDLE || state == State.TRANSFERRING) {
            return;   // the RFCOMM sender has its own timeouts
        }
        fail("the band stopped responding (" + state + ")");
    }

    private void setBusy(boolean busy) {
        GBDevice device = listener.getDevice();
        Context context = listener.getContext();
        if (busy) {
            device.setBusyTask(R.string.uploading_watchface, context);
        } else if (device.isBusy()) {
            device.unsetBusyTask();
        }
        device.sendDeviceUpdateIntent(context);
    }

    private static String installRefusal(int status) {
        switch (status) {
            case Fit3Watchface.INSTALL_FAILED_LOW_BATTERY:
                return "battery too low";
            case Fit3Watchface.INSTALL_FAILED_WRONG_PATH:
                return "the band could not find the file";
            case Fit3Watchface.INSTALL_FAILED_WRONG_FORMAT:
                return "the band rejected the file format";
            case Fit3Watchface.INSTALL_FAILED_LIST_FULL:
                return "too many watchfaces installed";
            default:
                return "the band refused (code " + status + ")";
        }
    }

    private static String fileInfoRefusal(int reason) {
        switch (reason) {
            case Fit3FileTransfer.REASON_LOW_BATTERY:
                return "battery too low";
            case Fit3FileTransfer.REASON_LOW_MEMORY:
                return "not enough free space on the band";
            default:
                return "the band refused the file (reason " + reason + ")";
        }
    }

    private static String describe(int[] face) {
        return face == null ? "?" : face[0] + "/" + face[1];
    }
}
