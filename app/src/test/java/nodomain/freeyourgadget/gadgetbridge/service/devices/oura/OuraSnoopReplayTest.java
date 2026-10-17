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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession;
import nodomain.freeyourgadget.gadgetbridge.model.SleepStage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraAuth;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraHistory;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraProtocol;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

/**
 * Replays the ring traffic of Android bug reports through the history import into the test
 * database. Bug reports hold private data and are not committed: the test runs when
 * {@code src/test/resources/oura_snoops/} (or {@code GB_OURA_SNOOP_DIR}) holds some.
 *
 * <p>{@code GB_OURA_KEY} set to the ring's key also checks every proof the phone sent;
 * {@code GB_OURA_DB_OUT} set to a file path writes the resulting database there.
 */
public class OuraSnoopReplayTest extends TestBase {
    private static final Logger LOG = LoggerFactory.getLogger(OuraSnoopReplayTest.class);

    /** Span the ring places the night in: {@code [start ticks u32][end ticks u32]...} */
    private static final int TAG_NIGHT_SPAN = 0x76;

    private static final String[] SNOOP_ENTRIES = {
            "FS/data/misc/bluetooth/logs/btsnoop_hci.log.last",
            "FS/data/misc/bluetooth/logs/btsnoop_hci.log",
    };

    private final List<long[]> nightSpans = new ArrayList<>();
    private final Map<Integer, Integer> recordCounts = new TreeMap<>();
    private int proofsChecked;

    private OuraHistoryImport history;
    /** History cursor of the fetches replayed so far. */
    private long cursor;
    private long pendingSyncMillis = -1;
    private byte[] pendingNonce;
    private byte[] key;

    @Test
    public void replayCapturedTraffic() throws IOException {
        final String dirName = System.getenv("GB_OURA_SNOOP_DIR");
        final File dir = new File(dirName != null ? dirName : "src/test/resources/oura_snoops");
        final File[] reports = dir.listFiles((d, name) -> name.startsWith("bugreport") && name.endsWith(".zip"));
        assumeTrue("no bug reports in " + dir.getAbsolutePath(), reports != null && reports.length > 0);
        Arrays.sort(reports, Comparator.comparing(File::getName));
        key = OuraAuth.parseHexKey(System.getenv("GB_OURA_KEY"));

        final GBDevice device = new GBDevice("00:00:00:00:00:70", "Oura", null, null, DeviceType.OURA_RING_4);
        DBHelper.getDevice(device, daoSession);

        for (final File report : reports) {
            try (ZipFile zip = new ZipFile(report)) {
                for (final String name : SNOOP_ENTRIES) {
                    final ZipEntry entry = zip.getEntry(name);
                    if (entry == null) {
                        continue;
                    }
                    final byte[] log;
                    try (InputStream in = zip.getInputStream(entry)) {
                        log = in.readAllBytes();
                    }
                    LOG.info("Replaying {} {}", report.getName(), name);
                    replay(device, BtSnoopAtt.read(log));
                    // Each log starts mid-way, so nothing carries over from the previous one
                    flush();
                    pendingSyncMillis = -1;
                    pendingNonce = null;
                }
            }
        }

        LOG.info("Records per tag: {}", recordCounts);
        assertTrue("no history records replayed", recordCounts.getOrDefault(OuraHistory.TAG_BEATS, 0) > 0);
        if (key != null) {
            LOG.info("{} proofs match the key", proofsChecked);
            assertTrue("no proof to check", proofsChecked > 0);
        }

        final List<SleepSession> sessions = device.getDeviceCoordinator()
                .getSleepSessionProvider(device, daoSession)
                // The provider reads a day past the end of the range, which must stay inside an int
                .getSleepSessions(0, Integer.MAX_VALUE - 2 * 86400);
        logSessions(sessions);
        for (final long[] span : nightSpans) {
            final long asleep = asleepMillis(sessions, span[0], span[1]);
            LOG.info("Night span {} to {}: {} min estimated asleep", Instant.ofEpochMilli(span[0]),
                    Instant.ofEpochMilli(span[1]), asleep / 60_000L);
            if (span[1] - span[0] >= 4 * 3600_000L) {
                assertTrue("less than half the night span estimated asleep", asleep * 2 >= span[1] - span[0]);
            }
        }

        final String out = System.getenv("GB_OURA_DB_OUT");
        if (out != null) {
            final File file = new File(out);
            if (file.exists() && !file.delete()) {
                throw new IOException("cannot replace " + file);
            }
            daoSession.getDatabase().execSQL("VACUUM INTO '" + file.getAbsolutePath().replace("'", "''") + "'");
            LOG.info("Database written to {}", file.getAbsolutePath());
        }
    }

    private void replay(final GBDevice device, final List<BtSnoopAtt.Pdu> pdus) {
        for (final BtSnoopAtt.Pdu pdu : pdus) {
            if (pdu.isWrite()) {
                frames(pdu.value, this::onWrite);
            } else if (pdu.isNotification()) {
                frames(pdu.value, (cmd, payload) -> onNotification(device, cmd, payload));
            }
        }
    }

    private interface FrameConsumer {
        void onFrame(int cmd, byte[] payload);
    }

    /** Splits a value into its {@code [cmd][length][payload]} frames. */
    private static void frames(final byte[] value, final FrameConsumer consumer) {
        int offset = 0;
        while (offset + 2 <= value.length) {
            final int length = value[offset + 1] & 0xff;
            if (offset + 2 + length > value.length) {
                return;
            }
            consumer.onFrame(value[offset] & 0xff, Arrays.copyOfRange(value, offset + 2, offset + 2 + length));
            offset += 2 + length;
        }
    }

    private void onWrite(final int cmd, final byte[] payload) {
        if (cmd == OuraProtocol.CMD_SET_TIME && payload.length >= 8) {
            long unix = 0;
            for (int i = 7; i >= 0; i--) {
                unix = unix << 8 | (payload[i] & 0xff);
            }
            pendingSyncMillis = unix * 1000L;
        } else if (cmd == OuraProtocol.CMD_EXTENDED && payload.length == 17
                && (payload[0] & 0xff) == OuraProtocol.EXT_PROOF && key != null && pendingNonce != null) {
            assertArrayEquals(OuraAuth.proof(pendingNonce, key), Arrays.copyOfRange(payload, 1, 17));
            proofsChecked++;
            pendingNonce = null;
        }
    }

    private void onNotification(final GBDevice device, final int cmd, final byte[] payload) {
        if (cmd == OuraProtocol.RSP_SET_TIME && payload.length >= 4 && pendingSyncMillis >= 0) {
            flush();
            history = new OuraHistoryImport(device, OuraHistoryImport.bootMillis(pendingSyncMillis, OuraProtocol.u32(payload, 0)));
            history.startAfter(cursor);
            pendingSyncMillis = -1;
        } else if (cmd == OuraProtocol.CMD_EXTENDED && payload.length == 16
                && (payload[0] & 0xff) == OuraProtocol.EXT_NONCE_REPLY) {
            pendingNonce = Arrays.copyOfRange(payload, 1, 16);
        } else if (cmd >= 0x41 && payload.length >= 4 && history != null) {
            final long ticks = OuraProtocol.u32(payload, 0);
            final byte[] body = Arrays.copyOfRange(payload, 4, payload.length);
            recordCounts.merge(cmd, 1, Integer::sum);
            if (cmd == TAG_NIGHT_SPAN && body.length >= 8) {
                nightSpans.add(new long[]{
                        history.toMillis(OuraProtocol.u32(body, 0)),
                        history.toMillis(OuraProtocol.u32(body, 4))});
            }
            history.onRecord(cmd, ticks, body);
        }
    }

    private void flush() {
        if (history != null && history.getMaxTicks() >= 0) {
            history.persist(daoSession, getContext());
            cursor = Math.max(cursor, history.getMaxTicks() + 1);
        }
        history = null;
    }

    private static void logSessions(final List<SleepSession> sessions) {
        for (final SleepSession session : sessions) {
            LOG.info("Estimated session {} to {}", Instant.ofEpochMilli(session.getStartTime()), Instant.ofEpochMilli(session.getEndTime()));
            for (final SleepStage stage : session.getStages()) {
                LOG.debug("  {} until {}", stage.getKind(), Instant.ofEpochMilli(stage.getEndTime()));
            }
        }
    }

    /** Light, deep and REM time inside {@code [from, to]}. */
    private static long asleepMillis(final List<SleepSession> sessions, final long from, final long to) {
        long asleep = 0;
        for (final SleepSession session : sessions) {
            for (final SleepStage stage : session.getStages()) {
                final ActivityKind kind = stage.getKind();
                if (kind == ActivityKind.LIGHT_SLEEP || kind == ActivityKind.DEEP_SLEEP || kind == ActivityKind.REM_SLEEP) {
                    asleep += Math.max(0, Math.min(to, stage.getEndTime()) - Math.max(from, stage.getStartTime()));
                }
            }
        }
        return asleep;
    }
}
