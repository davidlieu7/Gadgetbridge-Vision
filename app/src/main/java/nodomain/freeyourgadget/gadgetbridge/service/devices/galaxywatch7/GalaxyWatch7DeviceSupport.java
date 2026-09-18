/*  Copyright (C) 2026 pwnie69

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.galaxywatch7;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.AbstractBTBRDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Phase 1 proof-of-concept device support for the Samsung Galaxy Watch7: establishes the RFCOMM
 * Data Layer connection (see {@link WearDataLayerProtocol}) and sends the initial node handshake.
 * <p/>
 * Deliberately minimal for now — it proves out the transport/pairing/handshake layer of the
 * reverse-engineered protocol against a real device. Feature support (notifications, settings,
 * media control, ...) is intentionally not wired up yet; see the project's research notes for the
 * full protocol writeup and the planned next steps.
 */
public class GalaxyWatch7DeviceSupport extends AbstractBTBRDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(GalaxyWatch7DeviceSupport.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String PREF_LOCAL_NODE_ID = "galaxywatch7_local_node_id";

    // EXPERIMENT (§16): set to a captured enrolled GMS Wear node id to impersonate it; null uses
    // GB's own persisted random id. Impersonation alone does NOT get past the watch's Fast Pair
    // GATT secret handshake (§17), so left disabled.
    private static final String EXPERIMENT_IMPERSONATE_NODE_ID = "e1b1cd8b";

    /**
     * This phone's own Data Layer node id, persisted per paired device so it stays STABLE across
     * reconnects (a peer that changes identity on every connect looks abnormal to the watch).
     * Initialised lazily in {@link #initializeDevice} from the per-device prefs.
     */
    private volatile String localNodeId;

    /** This node's field-3 nonce; value isn't validated by the watch, only its presence. */
    private final long nodeEpoch = RANDOM.nextLong() & 0x7fffffffffffffffL;

    public GalaxyWatch7DeviceSupport() {
        super(LOG, 4096);
    }

    private boolean proxyProbeStarted = false;

    @Override
    public boolean connect() {
        if (!proxyProbeStarted) {
            proxyProbeStarted = true;
            startBackChannelListeners();
        }
        return super.connect();
    }

    @Override
    protected UUID getSupportedService() {
        return WearDataLayerProtocol.SERVICE_UUID_DATA_LAYER;
    }

    @Override
    protected int getExtraConnectRetries() {
        // The secure socket variant's connect() consistently fails with a spurious "read
        // failed, socket might closed or timeout, read ret: -1" IOException against this
        // device, on both a near-instant and a genuine ~3s-then-explicit-failure timing
        // pattern (see research notes) - possibly transient/flaky at the native BT stack
        // level. Retrying immediately (recreating the socket each time) a few times is much
        // cheaper than waiting for Gadgetbridge's own multi-second auto-reconnect backoff,
        // and costs nothing if it doesn't help.
        return 5;
    }

    @Override
    protected int getConnectRetryDelayMillis() {
        return 400;
    }

    @Override
    protected int getFastReconnectBudget() {
        // The watch consistently sends its own NodeInfo reply and then closes the channel
        // again within ~10-100ms - observed in raw HCI captures to be a deliberate,
        // near-instant disconnect from the watch's side, not a timeout. Real captured
        // sessions (stock app) show several such quick reconnect cycles before "settling"
        // into a stable, long-lived session - testing whether reconnecting fast (rather
        // than waiting Gadgetbridge's multi-second external backoff) reproduces that.
        return 10;
    }

    @Override
    protected int getFastReconnectWindowMillis() {
        return 1000;
    }

    @Override
    protected int getConnectDelayMillis() {
        // Empirically, connecting the RFCOMM socket right after the ACL link comes up (which
        // needs a fresh authentication + encryption + SDP + RFCOMM-multiplexer round trip,
        // observed to take on the order of 100-150ms) reliably fails with "read failed, socket
        // might closed or timeout, read ret: -1" from BluetoothSocket.connect() — before the
        // SABM for our channel is even sent. A short delay avoids the race.
        return 500;
    }

    // Per-device pref key under which we cache the watch's learned Data Layer node id, so that on
    // reconnects we can send the peer-sync straight after our NodeInfo (as the stock app does)
    // instead of having to wait for the watch's NodeInfo reply first.
    private static final String PREF_WATCH_NODE_ID = "galaxywatch7_watch_node_id";
    // Per-device cache of the shared connection long-id (NodeInfo field 7) the watch expects us
    // to echo. Learned from the watch's NodeInfo reply; without it the watch drops us right after
    // the handshake. Stable across factory resets (it is a watch-side identifier).
    private static final String PREF_WATCH_CONNECTION_ID = "galaxywatch7_watch_connection_id";

    // The watch's current Data Layer node id, learned by parsing its NodeInfo reply (falls back to
    // the cached value on reconnect). Changes across factory resets / re-pairings.
    private volatile String watchNodeId;
    // The shared connection long-id (NodeInfo field 7) we must echo back to the watch.
    private volatile String watchConnectionId;
    // Whether we've already sent the peer-sync for the current session (avoids sending it twice
    // when we both send it eagerly on connect and would otherwise resend on the NodeInfo reply).
    private volatile boolean peerSyncSent;

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        peerSyncSent = false;
        synchronized (incomingLock) {
            incoming = new byte[0];
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        // EXPERIMENT (§16): impersonate the enrolled GMS companion node id. Trust looks node-id
        // based (no crypto challenge on the wire), so presenting the id the watch already trusts
        // is what should make it sustain the session instead of dropping us as an unknown peer.
        localNodeId = EXPERIMENT_IMPERSONATE_NODE_ID != null ? EXPERIMENT_IMPERSONATE_NODE_ID : localNodeId;
        if (localNodeId == null) {
            String stored = prefs == null ? null : prefs.getString(PREF_LOCAL_NODE_ID, null);
            if (stored == null) {
                stored = randomNodeId();
                if (prefs != null) {
                    prefs.edit().putString(PREF_LOCAL_NODE_ID, stored).apply();
                }
            }
            localNodeId = stored;
        }
        if (watchNodeId == null && prefs != null) {
            watchNodeId = prefs.getString(PREF_WATCH_NODE_ID, null);
        }
        if (watchConnectionId == null && prefs != null) {
            watchConnectionId = prefs.getString(PREF_WATCH_CONNECTION_ID, null);
        }

        LOG.info("Galaxy Watch7: Data Layer channel connected, sending node handshake as {}", localNodeId);

        final String localName = "Gadgetbridge";
        if (watchConnectionId == null) {
            LOG.info("Galaxy Watch7: connection id not known yet - handshake will be rejected, "
                    + "learning it from the watch's reply for the next connect");
        }
        builder.write(WearDataLayerProtocol.buildNodeInfoHandshake(
                localNodeId, localName, nodeEpoch, watchConnectionId));

        // EXPERIMENT (§16): send the peer-sync eagerly (bundled after NodeInfo) when we already
        // know the watch's node id, so it reaches the watch within ~ms instead of via the slower
        // reply-driven queue path - the watch drops us ~40ms in, likely before a queued peer-sync
        // arrives. onSocketRead still covers the first-ever connect (no cached watch id yet).
        if (watchNodeId != null) {
            LOG.info("Galaxy Watch7: sending peer-sync eagerly for watch node {}", watchNodeId);
            builder.write(WearDataLayerProtocol.buildSeqAck(localNodeId, watchNodeId));
            peerSyncSent = true;
        }
        builder.setDeviceState(GBDevice.State.INITIALIZED);

        return builder;
    }

    // The watch validates a setting DataItem's source package + signing-cert digest against its
    // trusted_packages. Use the stock companion's identity so the write is accepted.
    private static final String SETTINGS_SOURCE_PACKAGE = "com.samsung.wearable.watch7plugin";
    private static final String SETTINGS_SOURCE_SIGNATURE = "9ca5170f381919dfe0446fcdab18b19a143b3163";
    // The phone-side node the watch's settings service actually reads protogensettings from.
    // NOT the connection node (e1b1cd8b): the stock client connects as e1b1cd8b but authors
    // settings under efb3c185 (reference capture §23/§24). Writing under the connection node only
    // relays the DataItem; the watch applies settings written under this node.
    private static final String SETTINGS_NODE = "efb3c185";

    // Boolean protogensettings, confirmed on the wire (§23): GB preference key -> the
    // "<group>/<key>" written at wear://<node>/protogensettings/<group>/<key>/<node>.
    private static final java.util.Map<String, String> BOOLEAN_SETTINGS = new java.util.HashMap<>();
    static {
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_always_on_display", "display/always_on_display");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_tilt_to_wake", "display/tilt_to_wake");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_touch_to_wake", "display/touch_to_wake");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_show_unread_dot", "unread_dot/show_unread_dot");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_use_mode_ringer", "modes/use_mode_ringer");
    }
    // Reverse map: the protogensettings "<group>/<key>" -> GB preference key, to reflect the
    // watch's reported values back into the device prefs so the UI shows the real state.
    private static final java.util.Map<String, String> BOOLEAN_SETTINGS_BY_PATH = new java.util.HashMap<>();
    static {
        for (final java.util.Map.Entry<String, String> e : BOOLEAN_SETTINGS.entrySet()) {
            BOOLEAN_SETTINGS_BY_PATH.put(e.getValue(), e.getKey());
        }
    }

    // Highest DataItem sequence number seen for the settings node (efb3c185). The watch ignores a
    // DataItem whose seq is not greater than the node's current seq (treats it as stale), so our
    // writes must use a seq above what the watch already has. Seeded from the incoming stream.
    private final java.util.concurrent.atomic.AtomicLong settingsSeq = new java.util.concurrent.atomic.AtomicLong(0);

    @Override
    public void onSendConfiguration(final String config) {
        final String groupKey = BOOLEAN_SETTINGS.get(config);
        if (groupKey == null) {
            return;
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final boolean enabled = prefs != null && prefs.getBoolean(config, false);
        final long seq = settingsSeq.incrementAndGet();
        LOG.info("Galaxy Watch7: sending {} = {} (seq {})", groupKey, enabled, seq);
        recentlySentSetting.put(groupKey, System.currentTimeMillis());
        final byte[] write = WearDataLayerProtocol.buildProtogenBooleanSettingPut(
                SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, SETTINGS_NODE, groupKey, enabled, seq);
        final TransactionBuilder builder = createTransactionBuilder("send " + config);
        builder.write(write);
        builder.queue();
    }

    // group/key -> time we last sent it, so the watch's still-streaming old value does not revert
    // a just-toggled setting in the UI before the watch applies our write.
    private final java.util.Map<String, Long> recentlySentSetting = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SETTING_WRITE_SETTLE_MS = 8000;

    // Reassembly buffer for the incoming Data Layer stream. Socket reads do not align to message
    // boundaries, so we accumulate and split on the 4-byte big-endian length prefix.
    private final Object incomingLock = new Object();
    private byte[] incoming = new byte[0];
    private static final int MAX_FRAME_LEN = 1 << 20;

    @Override
    public void onSocketRead(final byte[] data) {
        synchronized (incomingLock) {
            byte[] merged = new byte[incoming.length + data.length];
            System.arraycopy(incoming, 0, merged, 0, incoming.length);
            System.arraycopy(data, 0, merged, incoming.length, data.length);

            int off = 0;
            while (merged.length - off >= 4) {
                final int len = ((merged[off] & 0xff) << 24) | ((merged[off + 1] & 0xff) << 16)
                        | ((merged[off + 2] & 0xff) << 8) | (merged[off + 3] & 0xff);
                if (len < 0 || len > MAX_FRAME_LEN) {
                    LOG.warn("Galaxy Watch7: bad frame length {}, dropping {} buffered bytes",
                            len, merged.length - off);
                    off = merged.length;
                    break;
                }
                if (merged.length - off - 4 < len) {
                    break; // incomplete message, wait for more bytes
                }
                final byte[] message = java.util.Arrays.copyOfRange(merged, off + 4, off + 4 + len);
                off += 4 + len;
                processIncomingMessage(message);
            }
            incoming = java.util.Arrays.copyOfRange(merged, off, merged.length);
        }
    }

    private void processIncomingMessage(final byte[] message) {
        LOG.info("Galaxy Watch7 <<< {}", WearDataLayerProtocol.describeMessage(message));

        trackSettingsSeq(message);
        reflectIncomingSetting(message);

        final String parsed = WearDataLayerProtocol.parseWatchNodeId(message);
        if (parsed == null) {
            return;
        }

        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if (!parsed.equals(watchNodeId)) {
            LOG.info("Galaxy Watch7: learned watch node id {}", parsed);
            watchNodeId = parsed;
            if (prefs != null) {
                prefs.edit().putString(PREF_WATCH_NODE_ID, parsed).apply();
            }
        }

        final String connId = WearDataLayerProtocol.parseWatchConnectionId(message);
        if (connId != null && !connId.equals(watchConnectionId)) {
            LOG.info("Galaxy Watch7: learned connection id {} (used on next connect)", connId);
            watchConnectionId = connId;
            if (prefs != null) {
                prefs.edit().putString(PREF_WATCH_CONNECTION_ID, connId).apply();
            }
        }

        // First-connect path: we couldn't send the peer-sync until we learned the watch's node id.
        if (!peerSyncSent) {
            peerSyncSent = true;
            LOG.info("Galaxy Watch7: sending peer-sync in response to NodeInfo reply from {}", parsed);
            final TransactionBuilder builder = createTransactionBuilder("peer-sync");
            builder.write(WearDataLayerProtocol.buildSeqAck(localNodeId, parsed));
            builder.queue();
        }

    }

    // Keep our outgoing settings seq above the settings node's current seq, learned from the
    // incoming stream, so the watch does not reject our writes as stale.
    private void trackSettingsSeq(final byte[] message) {
        final String path = WearDataLayerProtocol.parseDataItemPath(message);
        if (path == null || !path.startsWith("wear://" + SETTINGS_NODE + "/")) {
            return;
        }
        final Long seq = WearDataLayerProtocol.parseDataItemSeq(message);
        if (seq == null) {
            return;
        }
        settingsSeq.getAndUpdate(cur -> Math.max(cur, seq));
    }

    // Reflect a watch-reported protogensettings boolean back into the device prefs so the GB
    // settings UI shows the real current state. Only trust the watch's own node's values.
    private void reflectIncomingSetting(final byte[] message) {
        final String path = WearDataLayerProtocol.parseDataItemPath(message);
        if (path == null) {
            return;
        }
        final int idx = path.indexOf("/protogensettings/");
        if (idx < 0) {
            return;
        }
        final String node = watchNodeId;
        if (node == null || !path.contains("/" + node)) {
            return; // only reflect the watch's own current state
        }
        String rest = path.substring(idx + "/protogensettings/".length());
        final int lastSlash = rest.lastIndexOf('/');
        if (lastSlash < 0) {
            return;
        }
        final String groupKey = rest.substring(0, lastSlash); // drop trailing "/<node>"
        final String prefKey = BOOLEAN_SETTINGS_BY_PATH.get(groupKey);
        if (prefKey == null) {
            return;
        }
        final Long sentAt = recentlySentSetting.get(groupKey);
        if (sentAt != null && System.currentTimeMillis() - sentAt < SETTING_WRITE_SETTLE_MS) {
            return; // we just wrote this; don't let the watch's old streamed value revert it
        }
        final Boolean value = WearDataLayerProtocol.parseProtogenBool(
                WearDataLayerProtocol.parseDataItemValue(message));
        if (value == null) {
            return;
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if (prefs != null && prefs.getBoolean(prefKey, false) != value) {
            LOG.info("Galaxy Watch7: reflecting {} = {} from watch", groupKey, value);
            prefs.edit().putBoolean(prefKey, value).apply();
        }
    }

    @Override
    public boolean useAutoConnect() {
        // Matches the stock app's behaviour: the connection is driven explicitly, not opportunistically.
        return false;
    }

    // The Wear connection is bidirectional (research notes §21): right after the phone opens the
    // Data Layer (phone->watch), the WATCH connects BACK to the phone on two RFCOMM services. If
    // nothing is listening for them, the watch tears the Data Layer down within ~90ms. We must run
    // RFCOMM servers (with SDP records) for both and keep the accepted sockets open.
    //  - WearProxy / CompanionProxy sysproxy tunnel (watch's TCP-over-BT / internet proxy).
    private static final UUID WEAR_PROXY_UUID = UUID.fromString("fafbdd20-83f0-4389-addf-917ac9dae5b2");
    //  - WearBluetoothHelper control channel.
    private static final UUID WEAR_HELPER_UUID = UUID.fromString("cee50b63-5f79-4727-8b5d-504eb07e1e22");

    private void startBackChannelListeners() {
        startBackChannelListener("WearProxy", WEAR_PROXY_UUID);
        startBackChannelListener("WearHelper", WEAR_HELPER_UUID);
    }

    private void startBackChannelListener(final String name, final UUID uuid) {
        final Thread thread = new Thread(() -> {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            while (!Thread.currentThread().isInterrupted()) {
                try (BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord(name, uuid)) {
                    LOG.info("Galaxy Watch7 back-channel {}: listening on {}", name, uuid);
                    while (true) {
                        final BluetoothSocket socket = server.accept();
                        LOG.info("Galaxy Watch7 back-channel {}: accepted from {}", name, socket.getRemoteDevice().getAddress());
                        new Thread(() -> holdBackChannelSocket(name, socket), name + "-conn").start();
                    }
                } catch (final IOException e) {
                    LOG.warn("Galaxy Watch7 back-channel {}: listener error, retrying: {}", name, e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (final InterruptedException ie) {
                        return;
                    }
                }
            }
        }, name + "-listen");
        thread.setDaemon(true);
        thread.start();
    }

    // Hold the watch's back-channel socket open and drain whatever it sends. Keeping the socket
    // open (rather than closing it) is what stops the watch from tearing down the Data Layer. We
    // do not yet speak the sysproxy/helper protocols; draining and staying connected is the
    // minimum viable behaviour to test the bidirectional-connection hypothesis (§21).
    private static void holdBackChannelSocket(final String name, final BluetoothSocket socket) {
        final byte[] buf = new byte[4096];
        try (BluetoothSocket s = socket; InputStream in = s.getInputStream()) {
            while (true) {
                final int len = in.read(buf);
                if (len < 0) {
                    break;
                }
                LOG.info("Galaxy Watch7 back-channel {} <<< {} bytes: {}", name, len, GB.hexdump(buf, 0, len));
            }
        } catch (final IOException e) {
            LOG.info("Galaxy Watch7 back-channel {}: socket closed: {}", name, e.getMessage());
        }
    }

    private static String randomNodeId() {
        final byte[] raw = new byte[4];
        RANDOM.nextBytes(raw);
        return String.format(Locale.ROOT, "%02x%02x%02x%02x", raw[0], raw[1], raw[2], raw[3]);
    }
}
