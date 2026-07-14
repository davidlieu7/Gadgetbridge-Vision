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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

/**
 * Drives the band's on-device log-file pull on {@link SapProtocol#CH_DEBUG}
 * (0x0C). The band keeps a set of diagnostic log files; this walks them one by
 * one and hands each fully reassembled file back to the caller.
 *
 * <p>The exchange is a simple state machine, phone-initiated:
 * <pre>
 *   phone -> FILE_COUNT request              (bare header)
 *   band  -> FILE_COUNT response  [TOTAL_FILE]                       (how many files)
 *   for fileId = 0 .. TOTAL_FILE-1:
 *     phone -> FILE_INIT request  [REQ_TYPE=1][FILE_ID]
 *     band  -> FILE_INIT response [FILE_ID][ACK][TOTAL_PACKET]       (chunk count for this file)
 *     band  -> FILE_TRANSFER x N  [FILE_ID][PACKET_INDEX][DATA]      (band streams the chunks)
 *     phone -> transfer ack       [FILE_ID][ACK]  (after each chunk, keeps the band sending)
 *   ...file complete once PACKET_INDEX reaches TOTAL_PACKET-1, then request the next file.
 * </pre>
 *
 * <p>Each chunk arrives as a whole SAP message (transport fragmentation is
 * already reassembled upstream, see {@link SapReassembler}); the per-file
 * {@code DATA} bytes are just concatenated in packet order. The reassembled
 * files are typically gzip-compressed.
 *
 * <p>Only the read/orchestration side lives here; actually writing the message
 * bytes and persisting completed files is the {@link Listener}'s job.
 */
public final class Fit3LogDump {

    private static final Logger LOG = LoggerFactory.getLogger(Fit3LogDump.class);

    // MSG_IDs on CH_DEBUG (header bits 5-0).
    private static final int MSG_FILE_COUNT = 16;
    private static final int MSG_FILE_INIT = 17;
    private static final int MSG_FILE_TRANSFER = 18;

    // Param ids. Widths are schema-defined, not tagged on the wire.
    private static final int PARAM_REQ_TYPE = 1;      // byte, always 1
    private static final int PARAM_ACK = 2;           // byte, 0 = ack, 1 = nack
    private static final int PARAM_TOTAL_PACKET = 3;  // int32 LE
    private static final int PARAM_DATA = 4;          // [len:2 LE][bytes]
    private static final int PARAM_PACKET_INDEX = 5;  // int32 LE
    private static final int PARAM_TOTAL_FILE = 20;   // byte
    private static final int PARAM_FILE_ID = 21;      // byte

    private static final int ACK_OK = 0;

    /** Guards against a runaway/garbage TOTAL_FILE from ever looping unbounded. */
    private static final int MAX_FILES = 64;

    /**
     * How long a dump may sit without a single inbound message before {@link
     * #start()} treats it as dead and restarts. The band answers each request
     * within a few hundred ms, so anything past this is a lost reply (BLE
     * hiccup, band-side abort) rather than a slow transfer - without this a
     * single stall would wedge {@link #active} until the app is restarted.
     */
    private static final long STALL_TIMEOUT_MS = 30_000L;

    /** Callbacks for the two things this class can't do itself: send and persist. */
    public interface Listener {
        /** Send an already-built payload on {@link SapProtocol#CH_DEBUG}. */
        void sendDebugMessage(byte[] body, String reason);

        /** A file finished reassembling. {@code data} is usually gzip-compressed. */
        void onLogFile(int fileId, byte[] data);

        /** The whole dump finished (or was found empty). */
        void onLogDumpFinished(int fileCount);
    }

    private final Listener listener;

    private boolean active = false;
    private int totalFiles = 0;
    private int currentFileId = -1;
    private int expectedPackets = 0;
    private long lastActivityMs = 0;
    private final ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();

    public Fit3LogDump(Listener listener) {
        this.listener = listener;
    }

    /**
     * Whether a dump is running <i>and</i> still being fed by the band. A run
     * that went silent past {@link #STALL_TIMEOUT_MS} reports false, so the
     * user can simply trigger the action again instead of being told a dead
     * dump is still in progress.
     */
    public boolean isActive() {
        return active && !isStalled();
    }

    private boolean isStalled() {
        return active && System.currentTimeMillis() - lastActivityMs > STALL_TIMEOUT_MS;
    }

    /**
     * Abandon a dump in progress without notifying the listener of completion.
     * Safe to call when nothing is running.
     */
    public void cancel() {
        if (!active) {
            return;
        }
        LOG.info("Samsung Fit 3: cancelling device log dump");
        reset();
    }

    /** Kick off a dump: ask the band how many log files it has. */
    public void start() {
        if (active) {
            if (!isStalled()) {
                LOG.debug("Samsung Fit 3: log dump already in progress");
                return;
            }
            // The band stopped answering mid-dump; drop the stale state and
            // start over rather than staying wedged.
            LOG.warn("Samsung Fit 3: previous log dump stalled, restarting");
            reset();
        }
        active = true;
        lastActivityMs = System.currentTimeMillis();
        totalFiles = 0;
        currentFileId = -1;
        expectedPackets = 0;
        fileBuffer.reset();
        LOG.info("Samsung Fit 3: starting device log dump");
        listener.sendDebugMessage(buildFileCountRequest(), "logdump-file-count");
    }

    /**
     * Feed one inbound CH_DEBUG message into the state machine. Ignores
     * everything if no dump is in progress, so it's safe to route all
     * CH_DEBUG traffic here.
     */
    public void onMessage(int msgId, byte[] body) {
        if (!active) {
            return;
        }
        lastActivityMs = System.currentTimeMillis();
        try {
            switch (msgId) {
                case MSG_FILE_COUNT:
                    onFileCount(body);
                    break;
                case MSG_FILE_INIT:
                    onFileInit(body);
                    break;
                case MSG_FILE_TRANSFER:
                    onFileTransfer(body);
                    break;
                default:
                    LOG.debug("Samsung Fit 3: unexpected log-dump msgId {}", msgId);
            }
        } catch (RuntimeException e) {
            LOG.error("Samsung Fit 3: log-dump message parse failed, aborting", e);
            finish();
        }
    }

    private void onFileCount(byte[] body) {
        SapPayloadParser p = new SapPayloadParser(body);
        int count = 0;
        while (p.hasMore()) {
            int id = p.u8();
            if (id == PARAM_TOTAL_FILE) {
                count = p.u8();
            } else if (id == PARAM_ACK) {
                p.u8();
            } else {
                break;  // unknown id: widths unknown, stop cleanly
            }
        }
        totalFiles = Math.min(count, MAX_FILES);
        LOG.info("Samsung Fit 3: band reports {} log file(s)", totalFiles);
        if (totalFiles <= 0) {
            finish();
            return;
        }
        requestFile(0);
    }

    private void onFileInit(byte[] body) {
        SapPayloadParser p = new SapPayloadParser(body);
        int fileId = currentFileId;
        int ack = ACK_OK;
        int packets = 0;
        while (p.hasMore()) {
            int id = p.u8();
            switch (id) {
                case PARAM_FILE_ID:
                    fileId = p.u8();
                    break;
                case PARAM_ACK:
                    ack = p.u8();
                    break;
                case PARAM_TOTAL_PACKET:
                    packets = p.le32();
                    break;
                default:
                    p.rest();  // unknown id: stop the walk
                    break;
            }
        }
        if (ack != ACK_OK) {
            LOG.warn("Samsung Fit 3: band NACK'd log file {}, skipping", fileId);
            advanceOrFinish(fileId);
            return;
        }
        expectedPackets = packets;
        fileBuffer.reset();
        LOG.info("Samsung Fit 3: log file {} has {} packet(s)", fileId, packets);
        if (packets <= 0) {
            // Empty file: nothing will be streamed, complete it now.
            completeFile(fileId);
        }
    }

    private void onFileTransfer(byte[] body) {
        SapPayloadParser p = new SapPayloadParser(body);
        int fileId = currentFileId;
        int index = -1;
        byte[] data = null;
        while (p.hasMore()) {
            int id = p.u8();
            switch (id) {
                case PARAM_FILE_ID:
                    fileId = p.u8();
                    break;
                case PARAM_PACKET_INDEX:
                    index = p.le32();
                    break;
                case PARAM_DATA:
                    data = p.rawParam16();
                    break;
                default:
                    p.rest();  // unknown id: stop the walk
                    break;
            }
        }
        if (data != null) {
            fileBuffer.write(data, 0, data.length);
        }
        // Ack this chunk so the band keeps streaming.
        listener.sendDebugMessage(buildTransferAck(fileId), "logdump-transfer-ack");

        if (index >= 0 && index >= expectedPackets - 1) {
            completeFile(fileId);
        }
    }

    private void completeFile(int fileId) {
        byte[] data = fileBuffer.toByteArray();
        fileBuffer.reset();
        LOG.info("Samsung Fit 3: log file {} complete ({} bytes)", fileId, data.length);
        listener.onLogFile(fileId, data);
        advanceOrFinish(fileId);
    }

    private void advanceOrFinish(int fileId) {
        int next = fileId + 1;
        if (next < totalFiles) {
            requestFile(next);
        } else {
            finish();
        }
    }

    private void requestFile(int fileId) {
        currentFileId = fileId;
        expectedPackets = 0;
        fileBuffer.reset();
        listener.sendDebugMessage(buildFileInitRequest(fileId), "logdump-file-init");
    }

    private void finish() {
        int count = totalFiles;
        reset();
        LOG.info("Samsung Fit 3: device log dump finished ({} file(s))", count);
        listener.onLogDumpFinished(count);
    }

    /** Drop all dump state; the listener is not notified. */
    private void reset() {
        active = false;
        totalFiles = 0;
        currentFileId = -1;
        expectedPackets = 0;
        lastActivityMs = 0;
        fileBuffer.reset();
    }

    /**
     * Length estimator for {@link SapReassembler}: the band streams each log
     * packet as a first fragment plus one continuation, and the total body size
     * is knowable from the first fragment alone (the DATA param carries a 2-byte
     * length). Returning it lets the reassembler finish a packet the instant its
     * continuation lands, instead of waiting for the next packet's first fragment
     * (which otherwise lags delivery by a packet and, if a first/continuation
     * pair is disturbed, hands up a truncated body). Only FILE_TRANSFER on
     * CH_DEBUG is length-delimited here; everything else returns -1 (unchanged).
     */
    public static int expectedBodyLength(int channel, byte[] soFar) {
        if (channel != SapProtocol.CH_DEBUG || soFar.length < 2) {
            return -1;
        }
        int hdr = soFar[0] & 0xFF;
        if ((hdr & 0x3F) != MSG_FILE_TRANSFER) {
            return -1;
        }
        int pos = 1;
        if ((hdr & 0x80) != 0) {
            pos++; // variable format: skip the param-count byte
        }
        // Walk the fixed-width params until DATA, whose 2-byte length closes the
        // body: total = (offset past the length field) + declared data bytes.
        while (pos < soFar.length) {
            int id = soFar[pos++] & 0xFF;
            switch (id) {
                case PARAM_FILE_ID:
                case PARAM_ACK:
                    pos += 1;
                    break;
                case PARAM_PACKET_INDEX:
                case PARAM_TOTAL_PACKET:
                    pos += 4;
                    break;
                case PARAM_DATA:
                    if (pos + 2 > soFar.length) {
                        return -1; // length field not fully arrived yet
                    }
                    int len = (soFar[pos] & 0xFF) | ((soFar[pos + 1] & 0xFF) << 8);
                    return pos + 2 + len;
                default:
                    return -1; // unknown width: give up, fall back to old behaviour
            }
        }
        return -1;
    }

    // ---- outgoing message builders ----

    /** Ask how many log files the band has. Bare header, no params. */
    static byte[] buildFileCountRequest() {
        return SapPayloadBuilder.fixed(MSG_FILE_COUNT).build();
    }

    /** Ask the band to start streaming one file: [REQ_TYPE=1][FILE_ID]. */
    static byte[] buildFileInitRequest(int fileId) {
        return SapPayloadBuilder.fixed(MSG_FILE_INIT)
                .raw(PARAM_REQ_TYPE).raw(1)
                .raw(PARAM_FILE_ID).raw(fileId)
                .build();
    }

    /** Ack a received chunk (response bit set): [FILE_ID][ACK=0]. */
    static byte[] buildTransferAck(int fileId) {
        return SapPayloadBuilder.fixedResponse(MSG_FILE_TRANSFER)
                .raw(PARAM_FILE_ID).raw(fileId)
                .raw(PARAM_ACK).raw(ACK_OK)
                .build();
    }
}
