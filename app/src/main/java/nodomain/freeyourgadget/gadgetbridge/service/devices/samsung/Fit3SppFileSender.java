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

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Bulk file upload over classic Bluetooth RFCOMM - the path the band uses for
 * anything big (watchfaces, firmware). It only works after the band has been
 * asked to accept the file and to open its server over BLE (see
 * {@code Fit3FileTransfer}); this class is the part after that.
 *
 * <p>The protocol is a small ASCII command/reply exchange, each command a
 * two-character code and each reply that code plus a status digit
 * ({@code 0} = ok):
 *
 * <pre>
 *   phone: "30"                                   band: "300"   hello
 *   phone: "33" ext "," dir name "," size         band: "330"   file header
 *   phone: block (≤39600 B) + CRC-32 (4 B LE)     band: "310"   one per block
 *                                                       "311"   CRC bad, resend block
 *   phone: "32"                                   band: "320"   file complete
 *   phone: "34"                                   band: "340"   session closed
 * </pre>
 *
 * The header for a watchface is e.g.
 * {@code 33bin,/user/wf/SM-R390_00021_256x402.bin,3956234}. The CRC is plain
 * CRC-32 (zlib) of the block's data alone, appended little-endian. All of it
 * is confirmed byte-for-byte against a vendor capture of a 3.9 MB watchface
 * upload (100 blocks, every CRC matching); the {@code 311} resend path is
 * not exercised there.
 */
public class Fit3SppFileSender {
    private static final Logger LOG = LoggerFactory.getLogger(Fit3SppFileSender.class);

    /** SDP service the band's RFCOMM server registers. */
    public static final UUID SERVICE_UUID = UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3");

    /** Band directory for watchface files. */
    public static final String DIR_WATCHFACE = "/user/wf/";

    /** Payload bytes per block, before the CRC. */
    public static final int BLOCK_SIZE = 39600;

    /** How often a single block may be re-sent on a {@code 311} before giving up. */
    private static final int MAX_BLOCK_RETRIES = 10;
    /** How many times to try the RFCOMM connect itself. */
    private static final int MAX_CONNECT_ATTEMPTS = 3;
    /** A reply not arriving within this long fails the transfer. */
    private static final long REPLY_TIMEOUT_MS = 20_000;
    /** Pause between "file complete" and "close", as the band expects a gap there. */
    private static final long CLOSE_DELAY_MS = 250;

    public interface Callback {
        void onProgress(int percent);

        void onComplete();

        void onError(String message);
    }

    private final Callback callback;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean cancelled;
    private volatile Closeable connection;
    private Thread thread;

    public Fit3SppFileSender(Callback callback) {
        this.callback = callback;
    }

    /**
     * Connect to the band and upload {@code data} as {@code dir + fileName},
     * on a background thread. Exactly one of {@link Callback#onComplete} or
     * {@link Callback#onError} is called, from that thread.
     */
    public void start(final BluetoothDevice device, final String dir, final String fileName, final byte[] data) {
        thread = new Thread(() -> {
            try {
                BluetoothSocket socket = connect(device);
                connection = socket;
                try {
                    transfer(socket.getInputStream(), socket.getOutputStream(), dir, fileName, data);
                } finally {
                    closeQuietly(socket);
                }
                callback.onComplete();
            } catch (IOException e) {
                LOG.warn("Samsung Fit 3: RFCOMM file transfer failed", e);
                callback.onError(cancelled ? "cancelled" : e.getMessage());
            } finally {
                watchdog.shutdownNow();
            }
        }, "Fit3SppFileSender");
        thread.start();
    }

    /** Abort a running transfer; the callback reports it as an error. */
    public void cancel() {
        cancelled = true;
        Closeable c = connection;
        if (c != null) {
            closeQuietly(c);
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @SuppressLint("MissingPermission")
    private BluetoothSocket connect(BluetoothDevice device) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS && !cancelled; attempt++) {
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
            try {
                socket.connect();
                LOG.debug("Samsung Fit 3: RFCOMM connected on attempt {}", attempt);
                return socket;
            } catch (IOException e) {
                last = e;
                closeQuietly(socket);
                LOG.debug("Samsung Fit 3: RFCOMM connect attempt {} failed: {}", attempt, e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last != null ? last : new IOException("RFCOMM connect cancelled");
    }

    /**
     * Run the whole upload over an already-connected stream pair. Blocks until
     * the band has confirmed the close, or throws.
     */
    void transfer(InputStream in, OutputStream out, String dir, String fileName, byte[] data) throws IOException {
        command(in, out, "30", "300");
        command(in, out, buildHeader(dir, fileName, data.length), "330");

        int blocks = blockCount(data.length);
        for (int i = 0; i < blocks; i++) {
            byte[] block = buildBlock(data, i);
            int retries = 0;
            while (true) {
                checkCancelled();
                out.write(block);
                out.flush();
                String reply = awaitReply(in, "310", "311");
                if (reply.equals("310")) {
                    break;
                }
                if (++retries > MAX_BLOCK_RETRIES) {
                    throw new IOException("block " + i + " rejected " + retries + " times");
                }
                LOG.debug("Samsung Fit 3: band asked to resend block {} (retry {})", i, retries);
            }
            callback.onProgress((int) ((long) (i + 1) * 100 / blocks));
        }

        command(in, out, "32", "320");
        try {
            Thread.sleep(CLOSE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        command(in, out, "34", "340");
    }

    private void command(InputStream in, OutputStream out, String cmd, String expected) throws IOException {
        checkCancelled();
        LOG.debug("Samsung Fit 3: RFCOMM -> {}", cmd);
        out.write(cmd.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        awaitReply(in, expected);
    }

    /**
     * Read until one of the expected 3-character replies shows up. Replies
     * are short ASCII and may arrive split or glued to a neighbour, so they
     * are matched as substrings of what has accumulated.
     */
    private String awaitReply(InputStream in, String... expected) throws IOException {
        StringBuilder seen = new StringBuilder();
        byte[] buf = new byte[256];
        ScheduledFuture<?> timeout = armTimeout();
        try {
            while (true) {
                int n = in.read(buf);
                if (n < 0) {
                    throw new IOException("connection closed waiting for " + String.join("/", expected));
                }
                seen.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                String s = seen.toString();
                for (String e : expected) {
                    if (s.contains(e)) {
                        LOG.debug("Samsung Fit 3: RFCOMM <- {}", e);
                        return e;
                    }
                }
                if (s.contains("error")) {
                    throw new IOException("band reported: " + s.trim());
                }
                if (seen.length() > 64) {
                    throw new IOException("unexpected reply: " + s.trim());
                }
            }
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }

    private ScheduledFuture<?> armTimeout() {
        final Closeable c = connection;
        if (c == null || watchdog.isShutdown()) {
            return null;
        }
        return watchdog.schedule(() -> {
            LOG.warn("Samsung Fit 3: RFCOMM reply timed out");
            closeQuietly(c);
        }, REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void checkCancelled() throws IOException {
        if (cancelled) {
            throw new IOException("cancelled");
        }
    }

    // ---- framing, exposed for tests ----

    /** {@code "33" + ext + "," + dir + name + "," + size}. */
    static String buildHeader(String dir, String fileName, int size) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1) : "";
        String d = dir.startsWith("/") ? dir : "/" + dir;
        if (!d.endsWith("/")) {
            d = d + "/";
        }
        return "33" + ext + "," + d + fileName + "," + size;
    }

    static int blockCount(int size) {
        return Math.max(1, (size + BLOCK_SIZE - 1) / BLOCK_SIZE);
    }

    /** Block {@code index} of {@code data}, with its CRC-32 appended little-endian. */
    static byte[] buildBlock(byte[] data, int index) {
        int offset = index * BLOCK_SIZE;
        int len = Math.min(BLOCK_SIZE, data.length - offset);
        CRC32 crc = new CRC32();
        crc.update(data, offset, len);
        long value = crc.getValue();
        byte[] block = new byte[len + 4];
        System.arraycopy(data, offset, block, 0, len);
        block[len] = (byte) value;
        block[len + 1] = (byte) (value >> 8);
        block[len + 2] = (byte) (value >> 16);
        block[len + 3] = (byte) (value >> 24);
        return block;
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
