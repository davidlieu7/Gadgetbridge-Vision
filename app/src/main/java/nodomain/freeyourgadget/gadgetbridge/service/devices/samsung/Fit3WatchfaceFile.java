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

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.util.UriHelper;

/**
 * A Galaxy Fit3 watchface package, as installed onto the band verbatim.
 *
 * <p>The file is a flat archive: a 32-byte header ({@code "oppo"}, version,
 * payload size, entry count, header checksum), then a table of 74-byte
 * entries ({@code name[64]}, offset, size, CRC-16), then the entry payloads.
 * Every entry sits in one directory named after the face, e.g.
 * {@code ./SM-R390_00021_256x402/aod.bin} - model, face id and screen size -
 * and that directory name plus {@code .bin} is also the name the band expects
 * the file to arrive under.
 *
 * <p>Validation checks the magic, the declared size, that every entry lies
 * inside the file and that its CRC-16/CCITT-FALSE matches. The header's own
 * checksum isn't understood and isn't checked.
 */
public final class Fit3WatchfaceFile {
    private static final Logger LOG = LoggerFactory.getLogger(Fit3WatchfaceFile.class);

    private static final byte[] MAGIC = {'o', 'p', 'p', 'o'};
    private static final int HEADER_SIZE = 32;
    private static final int ENTRY_SIZE = 74;
    private static final int ENTRY_NAME_SIZE = 64;
    private static final int MAX_ENTRIES = 256;
    /** Far above any real face (~4 MB); just keeps a wrong pick from being read into memory. */
    private static final int MAX_FILE_SIZE = 16 * 1024 * 1024;

    /** The band model these packages are built for; the only one handled. */
    public static final String MODEL_FIT3 = "SM-R390";
    public static final int SCREEN_WIDTH = 256;
    public static final int SCREEN_HEIGHT = 402;

    private static final Pattern DIR_PATTERN = Pattern.compile("^\\./?(([A-Z]{2}-[A-Z0-9]+)_(\\d+)_(\\d+)x(\\d+))/");

    private final byte[] data;
    public final String model;
    public final int id;
    public final int width;
    public final int height;
    /** {@code SM-R390_00021_256x402.bin}: what the band is told the file is called. */
    public final String fileName;
    public final List<String> entryNames;

    private Fit3WatchfaceFile(byte[] data, String model, int id, int width, int height, String fileName,
                              List<String> entryNames) {
        this.data = data;
        this.model = model;
        this.id = id;
        this.width = width;
        this.height = height;
        this.fileName = fileName;
        this.entryNames = Collections.unmodifiableList(entryNames);
    }

    public byte[] getData() {
        return data;
    }

    /** Whether this face is for the Fit3 (model and screen size). */
    public boolean isForFit3() {
        return MODEL_FIT3.equals(model) && width == SCREEN_WIDTH && height == SCREEN_HEIGHT;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Fit3WatchfaceFile{%s, id=%d, %dx%d, %d bytes, %d entries}",
                model, id, width, height, data.length, entryNames.size());
    }

    /** Read and validate a package from a content URI; {@code null} if it isn't one. */
    @Nullable
    public static Fit3WatchfaceFile fromUri(Uri uri, Context context) {
        try {
            UriHelper uriHelper = UriHelper.get(uri, context);
            long size = uriHelper.getFileSize();
            // The size can be unknown (0) for some providers; the read is capped anyway.
            if ((size > 0 && size < HEADER_SIZE) || size > MAX_FILE_SIZE) {
                return null;
            }
            try (InputStream in = uriHelper.openInputStream()) {
                byte[] head = new byte[MAGIC.length];
                if (readFully(in, head) != head.length || !startsWithMagic(head)) {
                    return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(size > 0 ? (int) size : 64 * 1024);
                out.write(head);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_FILE_SIZE) {
                        return null;
                    }
                }
                return parse(out.toByteArray());
            }
        } catch (IOException | SecurityException e) {
            LOG.debug("Samsung Fit 3: not a watchface package: {}", e.getMessage());
            return null;
        }
    }

    /** Validate and describe a package already in memory; {@code null} if it isn't one. */
    @Nullable
    public static Fit3WatchfaceFile parse(byte[] data) {
        if (data == null || data.length < HEADER_SIZE || !startsWithMagic(data)) {
            return null;
        }
        long payloadSize = le32(data, 8);
        int count = (int) le32(data, 12);
        if (payloadSize != data.length - HEADER_SIZE) {
            LOG.debug("Samsung Fit 3: watchface size mismatch ({} declared, {} present)",
                    payloadSize, data.length - HEADER_SIZE);
            return null;
        }
        if (count <= 0 || count > MAX_ENTRIES || HEADER_SIZE + (long) count * ENTRY_SIZE > data.length) {
            return null;
        }

        List<String> names = new ArrayList<>(count);
        String dir = null;
        Matcher dirMatch = null;
        for (int i = 0; i < count; i++) {
            int e = HEADER_SIZE + i * ENTRY_SIZE;
            String name = cString(data, e, ENTRY_NAME_SIZE);
            long offset = le32(data, e + ENTRY_NAME_SIZE);
            long size = le32(data, e + ENTRY_NAME_SIZE + 4);
            int crc = le16(data, e + ENTRY_NAME_SIZE + 8);
            if (offset < HEADER_SIZE || offset + size > data.length) {
                LOG.debug("Samsung Fit 3: watchface entry {} out of bounds", name);
                return null;
            }
            int actual = crc16CcittFalse(data, (int) offset, (int) size);
            if (actual != crc) {
                LOG.debug("Samsung Fit 3: watchface entry {} CRC mismatch ({} != {})", name,
                        Integer.toHexString(actual), Integer.toHexString(crc));
                return null;
            }
            Matcher m = DIR_PATTERN.matcher(name);
            if (!m.find()) {
                return null;
            }
            if (dir == null) {
                dir = m.group(1);
                dirMatch = m;
            } else if (!dir.equals(m.group(1))) {
                return null;   // every entry belongs to the one face
            }
            names.add(name);
        }

        int id;
        int width;
        int height;
        try {
            id = Integer.parseInt(dirMatch.group(3));
            width = Integer.parseInt(dirMatch.group(4));
            height = Integer.parseInt(dirMatch.group(5));
        } catch (NumberFormatException e) {
            return null;
        }
        // Faces are addressed by a single byte on the wire.
        if (id < 0 || id > 0xFF) {
            return null;
        }
        return new Fit3WatchfaceFile(data, dirMatch.group(2), id, width, height, dir + ".bin", names);
    }

    private static boolean startsWithMagic(byte[] b) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (b[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    static int crc16CcittFalse(byte[] b, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
            }
            crc &= 0xFFFF;
        }
        return crc;
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static String cString(byte[] b, int off, int max) {
        int end = off;
        while (end < off + max && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.US_ASCII);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }
}
