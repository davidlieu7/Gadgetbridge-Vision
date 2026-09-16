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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

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

    /**
     * This phone's own Data Layer node id. Randomly generated per device-support instance for
     * now; a real implementation should probably persist this per paired device so it stays
     * stable across reconnects (unclear yet whether the watch actually depends on that).
     */
    private final String localNodeId = randomNodeId();

    public GalaxyWatch7DeviceSupport() {
        super(LOG, 4096);
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

    // EXPERIMENT: hardcoded, empirically-observed-stable node id of this specific watch, to
    // test whether promptly following the NodeInfo handshake with a SeqAck/Ping message (as
    // seen in real captured sessions) is what keeps the channel from being closed ~100ms
    // after the handshake exchange. Needs to be learned dynamically (by parsing the watch's
    // own NodeInfo reply) rather than hardcoded, if this pans out.
    private static final String EXPERIMENT_WATCH_NODE_ID = "19eaaf8";

    // EXPERIMENT: send nothing at all after connecting, to check whether the watch closes
    // the channel on its own timer regardless of client behavior, or only in reaction to
    // something we send. Findings: sessions last noticeably longer (500ms-2s vs ~100ms)
    // when nothing is sent, but still get cut short - correlated with Samsung's own
    // watch7plugin reactivating mid-session. See research notes for the full picture.
    private static final boolean EXPERIMENT_SEND_NOTHING = false;

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        if (EXPERIMENT_SEND_NOTHING) {
            LOG.info("Galaxy Watch7: Data Layer channel connected, sending NOTHING (experiment)");
            builder.setDeviceState(GBDevice.State.INITIALIZED);
            return builder;
        }

        LOG.info("Galaxy Watch7: Data Layer channel connected, sending node handshake as {}", localNodeId);

        final String localName = "Gadgetbridge";
        builder.write(WearDataLayerProtocol.buildNodeInfoHandshake(localNodeId, localName));
        builder.write(WearDataLayerProtocol.buildSeqAck(EXPERIMENT_WATCH_NODE_ID, 0));
        builder.setDeviceState(GBDevice.State.INITIALIZED);

        return builder;
    }

    @Override
    public void onSocketRead(final byte[] data) {
        LOG.info("Galaxy Watch7 <<< {} bytes:\n{}", data.length, GB.hexdump(data));
    }

    @Override
    public boolean useAutoConnect() {
        // Matches the stock app's behaviour: the connection is driven explicitly, not opportunistically.
        return false;
    }

    private static String randomNodeId() {
        final byte[] raw = new byte[4];
        RANDOM.nextBytes(raw);
        return String.format(Locale.ROOT, "%02x%02x%02x%02x", raw[0], raw[1], raw[2], raw[3]);
    }
}
