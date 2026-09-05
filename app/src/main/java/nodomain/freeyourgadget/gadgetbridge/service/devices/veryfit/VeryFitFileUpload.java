/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.veryfit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitConstants;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * One file on its way to the watch.
 * <p>
 * The channel is the watch's own, beside the command packets rather than inside them: the size is
 * announced, the transfer opened, and the data then goes out in batches, the watch counting what
 * it has taken after each of them. It is closed with a sum and a digest of everything sent, which
 * is what the watch checks the file against before keeping it.
 */
class VeryFitFileUpload {
    private static final Logger LOG = LoggerFactory.getLogger(VeryFitFileUpload.class);

    private final VeryFitSupport support;
    private String file;
    private byte[] data;
    private byte type;
    private int label;
    private int sent;
    private boolean running;

    VeryFitFileUpload(final VeryFitSupport support) {
        this.support = support;
    }

    boolean isRunning() {
        return running;
    }

    /** Forgets a transfer the connection dropped in the middle of. */
    void reset() {
        running = false;
        data = null;
    }

    /** Announces the file, from which the watch works out how much room it has to make. */
    void start(final String name, final byte[] contents, final int originalSize) {
        take(name, contents, VeryFitConstants.FILE_ANY_TYPE, R.string.uploading_watchface);
        LOG.info("Sending {} as {} bytes of {}", name, contents.length, originalSize);
        support.sendFile("veryfit file declare", Collections.singletonList(
                open(VeryFitConstants.FILE_DECLARE, originalSize, name)), 0, label);
    }

    /**
     * A file the watch already keeps a place of its own for, so there is no room for it to make
     * and nothing to announce: the transfer opens straight away.
     */
    void start(final String name, final byte[] contents, final byte fileType, final int busy) {
        take(name, contents, fileType, busy);
        LOG.info("Sending {} as {} bytes", name, contents.length);
        support.sendFile("veryfit file begin", Collections.singletonList(
                open(VeryFitConstants.FILE_BEGIN, contents.length, name)), 0, label);
    }

    private void take(final String name, final byte[] contents, final byte fileType, final int busy) {
        file = name;
        data = contents;
        type = fileType;
        label = busy;
        sent = 0;
        running = true;
    }

    void onPacket(final byte[] value) {
        if (!running) {
            return;
        }
        if (value.length < 3 || value[2] != VeryFitConstants.FILE_OK) {
            LOG.error("Watch refused the transfer: {}", GB.hexdump(value));
            finish(false);
            return;
        }

        switch (value[1]) {
            case VeryFitConstants.FILE_DECLARE:
                support.sendFile("veryfit file begin", Collections.singletonList(
                        open(VeryFitConstants.FILE_BEGIN, data.length, file)), 0, label);
                break;
            case VeryFitConstants.FILE_BEGIN:
                support.sendFile("veryfit file interval", Collections.singletonList(new byte[]{
                        VeryFitConstants.FILE_MARKER, VeryFitConstants.FILE_ACK_EVERY,
                        (byte) VeryFitConstants.FILE_ACK_CHUNKS}), 0, label);
                break;
            case VeryFitConstants.FILE_ACK_EVERY:
                sendBatch();
                break;
            case VeryFitConstants.FILE_DATA:
                // The last batch is closed off as it is sent, so its own count arrives too late.
                if (sent < data.length) {
                    sendBatch();
                }
                break;
            case VeryFitConstants.FILE_END:
                finish(true);
                break;
            default:
                LOG.debug("Unhandled file channel answer {}", Integer.toHexString(value[1] & 0xff));
        }
    }

    /** As many chunks as the watch counts between two of its answers, and the close behind them. */
    private void sendBatch() {
        final int chunk = support.getFileChunkLen();
        final List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < VeryFitConstants.FILE_ACK_CHUNKS && sent < data.length; i++) {
            final int size = Math.min(chunk, data.length - sent);
            final byte[] packet = new byte[3 + size];
            packet[0] = VeryFitConstants.FILE_MARKER;
            packet[1] = VeryFitConstants.FILE_DATA;
            System.arraycopy(data, sent, packet, 3, size);
            packets.add(packet);
            sent += size;
        }

        if (sent >= data.length) {
            packets.add(close());
        }
        support.sendFile("veryfit file data", packets, 100 * sent / data.length, label);
    }

    private void finish(final boolean success) {
        running = false;
        data = null;
        support.onFileUploadFinished(success);
    }

    /** Names the file and says how long it is, either as it is stored or as it arrives. */
    private byte[] open(final byte operation, final int size, final String file) {
        final byte[] name = file.getBytes(StandardCharsets.UTF_8);
        final byte[] packet = new byte[8 + name.length];
        packet[0] = VeryFitConstants.FILE_MARKER;
        packet[1] = operation;
        packet[2] = type;
        packet[3] = (byte) size;
        packet[4] = (byte) (size >> 8);
        packet[5] = (byte) (size >> 16);
        packet[6] = (byte) (size >> 24);
        packet[7] = VeryFitConstants.FILE_PACKED;
        System.arraycopy(name, 0, packet, 8, name.length);
        return packet;
    }

    /** The sum of everything sent, and its digest as text in a slot of its own. */
    private byte[] close() {
        int sum = 0;
        for (final byte b : data) {
            sum += b & 0xff;
        }

        final byte[] packet = new byte[6 + VeryFitConstants.FILE_DIGEST_LEN];
        packet[0] = VeryFitConstants.FILE_MARKER;
        packet[1] = VeryFitConstants.FILE_END;
        packet[2] = (byte) sum;
        packet[3] = (byte) (sum >> 8);
        packet[4] = (byte) (sum >> 16);
        packet[5] = (byte) (sum >> 24);

        final byte[] digest = digest().getBytes(StandardCharsets.UTF_8);
        System.arraycopy(digest, 0, packet, 6,
                Math.min(digest.length, VeryFitConstants.FILE_DIGEST_LEN));
        return packet;
    }

    private String digest() {
        try {
            final MessageDigest md5 = MessageDigest.getInstance("MD5");
            final StringBuilder text = new StringBuilder();
            for (final byte b : md5.digest(data)) {
                text.append(String.format(Locale.ROOT, "%02x", b));
            }
            return text.toString();
        } catch (final NoSuchAlgorithmException e) {
            LOG.error("Cannot close a transfer without a digest", e);
            return "";
        }
    }
}
