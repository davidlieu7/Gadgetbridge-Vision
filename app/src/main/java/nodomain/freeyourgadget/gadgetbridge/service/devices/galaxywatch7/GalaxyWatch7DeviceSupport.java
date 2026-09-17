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

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
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
    private static final String EXPERIMENT_IMPERSONATE_NODE_ID = null;

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
            startWearProxyListenerProbe();
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

    // Preference key from res/xml/devicesettings_galaxy_watch7.xml.
    private static final String PREF_ALWAYS_ON_DISPLAY = "pref_galaxywatch7_always_on_display";
    // See §5h of the research notes: any simple on/off watch setting is a DataItem PUT of a
    // single-key {"booleanIsChecked": value} DataMap at wear://<watchNodeId>/<path>.
    private static final String SETTINGS_PATH_ALWAYS_ON_DISPLAY = "/w_module_sec_watch_setting/display/always_on_display";

    private long nextSettingsSeq = 1;

    private byte[] buildAlwaysOnDisplayWrite() {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final boolean enabled = prefs != null && prefs.getBoolean(PREF_ALWAYS_ON_DISPLAY, false);
        LOG.info("Galaxy Watch7: sending always_on_display = {}", enabled);

        final String node = watchNodeId;
        if (node == null) {
            LOG.warn("Galaxy Watch7: cannot send always_on_display - watch node id not known yet");
            return null;
        }
        return WearDataLayerProtocol.buildBooleanSettingPut(
                BuildConfig.APPLICATION_ID,
                node,
                SETTINGS_PATH_ALWAYS_ON_DISPLAY,
                enabled,
                nextSettingsSeq++
        );
    }

    @Override
    public void onSendConfiguration(final String config) {
        if (!PREF_ALWAYS_ON_DISPLAY.equals(config)) {
            return;
        }

        final byte[] write = buildAlwaysOnDisplayWrite();
        if (write == null) {
            return;
        }
        final TransactionBuilder builder = createTransactionBuilder("send always_on_display setting");
        builder.write(write);
        builder.queue();
    }

    @Override
    public void onSocketRead(final byte[] data) {
        LOG.info("Galaxy Watch7 <<< {} bytes:\n{}", data.length, GB.hexdump(data));

        final String parsed = WearDataLayerProtocol.parseWatchNodeId(data);
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

        final String connId = WearDataLayerProtocol.parseWatchConnectionId(data);
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

    @Override
    public boolean useAutoConnect() {
        // Matches the stock app's behaviour: the connection is driven explicitly, not opportunistically.
        return false;
    }

    // EXPERIMENT: Frida instrumentation of watch7plugin (research notes section 11) showed the watch
    // opening an RFCOMM connection *to the phone* on this UUID and tunnelling TCP streams through it
    // ("WearProxy"). Listen on it read-only and dump whatever the watch sends, to learn the framing.
    private static final UUID WEAR_PROXY_UUID = UUID.fromString("fafbdd20-83f0-4389-addf-917ac9dae5b2");

    private void startWearProxyListenerProbe() {
        final Thread thread = new Thread(() -> {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            try (BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord("WearProxyProbe", WEAR_PROXY_UUID)) {
                LOG.info("Galaxy Watch7 proxy probe: listening on {}", WEAR_PROXY_UUID);
                for (int n = 0; n < 5; n++) {
                    final BluetoothSocket socket = server.accept(120_000);
                    LOG.info("Galaxy Watch7 proxy probe: accepted #{} from {}", n, socket.getRemoteDevice().getAddress());
                    new Thread(() -> dumpProxySocket(socket), "WearProxyProbe-read").start();
                }
            } catch (final IOException e) {
                LOG.warn("Galaxy Watch7 proxy probe: listener ended", e);
            }
        }, "WearProxyProbe-listen");
        thread.setDaemon(true);
        thread.start();
    }

    private static void dumpProxySocket(final BluetoothSocket socket) {
        final byte[] buf = new byte[4096];
        try (BluetoothSocket s = socket; InputStream in = s.getInputStream()) {
            for (int i = 0; i < 200; i++) {
                final int len = in.read(buf);
                if (len < 0) {
                    break;
                }
                LOG.info("Galaxy Watch7 proxy probe <<< {} bytes: {}", len, GB.hexdump(buf, 0, len));
            }
        } catch (final IOException e) {
            LOG.info("Galaxy Watch7 proxy probe: socket closed: {}", e.getMessage());
        }
    }

    private static String randomNodeId() {
        final byte[] raw = new byte[4];
        RANDOM.nextBytes(raw);
        return String.format(Locale.ROOT, "%02x%02x%02x%02x", raw[0], raw[1], raw[2], raw[3]);
    }
}
