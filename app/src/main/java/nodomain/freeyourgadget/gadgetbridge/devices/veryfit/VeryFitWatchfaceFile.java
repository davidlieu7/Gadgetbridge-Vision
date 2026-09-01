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
package nodomain.freeyourgadget.gadgetbridge.devices.veryfit;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.UriHelper;

/**
 * A watchface as it comes off the phone's storage.
 * <p>
 * The face is a small archive of the pictures and the layout the watch draws from, opening with a
 * directory of what it holds. The watch takes it packed and is told what it unpacks to, so a file
 * that arrives packed already is passed on as it is and one that does not is packed here.
 */
public class VeryFitWatchfaceFile {
    private static final Logger LOG = LoggerFactory.getLogger(VeryFitWatchfaceFile.class);

    private static final int MAX_FILE_LEN = 4 * 1024 * 1024;
    /** What every one of these archives opens with. */
    private static final byte[] ARCHIVE_MARKER = {'i', 'w', 'f', 0};
    private static final int ARCHIVE_HEADER_LEN = 8;
    private static final int ARCHIVE_COUNT_OFFSET = 6;
    private static final int ARCHIVE_ENTRY_LEN = 40;
    private static final int ARCHIVE_ENTRY_NAME_LEN = 32;
    /** The one member that describes the face rather than drawing it. */
    private static final String ARCHIVE_LAYOUT = "iwf.json";

    private byte[] packed;
    private int originalSize;
    private String name;
    private String model;

    public VeryFitWatchfaceFile(final Uri uri, final Context context) {
        final byte[] contents = read(uri, context);
        if (contents == null) {
            return;
        }

        final byte[] archive;
        if (isArchive(contents)) {
            archive = contents;
            packed = VeryFitPacker.pack(contents);
        } else {
            archive = VeryFitPacker.unpack(contents);
            packed = contents;
        }

        if (!isArchive(archive)) {
            LOG.warn("{} is not a watchface", uri);
            packed = null;
            return;
        }

        originalSize = archive.length;
        describe(archive);
    }

    public boolean isValid() {
        return packed != null;
    }

    /** The face as the watch is fed it. */
    public byte[] getPacked() {
        return packed;
    }

    /** How much of the watch's storage it takes unpacked, which is what the watch counts. */
    public int getOriginalSize() {
        return originalSize;
    }

    public String getName() {
        return name;
    }

    public String getModel() {
        return model;
    }

    private static byte[] read(final Uri uri, final Context context) {
        final UriHelper helper;
        try {
            helper = UriHelper.get(uri, context);
        } catch (final IOException e) {
            LOG.error("Failed to open {}", uri, e);
            return null;
        }

        if (helper.getFileSize() > MAX_FILE_LEN) {
            LOG.warn("{} is larger than the {} bytes a watchface takes", uri, MAX_FILE_LEN);
            return null;
        }

        try (InputStream in = new BufferedInputStream(helper.openInputStream())) {
            return FileUtils.readAll(in, MAX_FILE_LEN);
        } catch (final IOException e) {
            LOG.error("Failed to read {}", uri, e);
            return null;
        }
    }

    /** The marker, and a directory that fits inside what is holding it. */
    private static boolean isArchive(final byte[] data) {
        if (data == null || data.length < ARCHIVE_HEADER_LEN) {
            return false;
        }
        for (int i = 0; i < ARCHIVE_MARKER.length; i++) {
            if (data[i] != ARCHIVE_MARKER[i]) {
                return false;
            }
        }
        final int count = count(data);
        return count > 0 && ARCHIVE_HEADER_LEN + count * ARCHIVE_ENTRY_LEN <= data.length;
    }

    /** The layout member names the face and the model it was drawn for. */
    private void describe(final byte[] archive) {
        final int count = count(archive);
        for (int i = 0; i < count; i++) {
            final int entry = ARCHIVE_HEADER_LEN + i * ARCHIVE_ENTRY_LEN;
            final String member = new String(archive, entry, ARCHIVE_ENTRY_NAME_LEN,
                    StandardCharsets.UTF_8).split("\u0000", 2)[0];
            if (!ARCHIVE_LAYOUT.equals(member)) {
                continue;
            }

            final int offset = intAt(archive, entry + ARCHIVE_ENTRY_NAME_LEN);
            final int length = intAt(archive, entry + ARCHIVE_ENTRY_NAME_LEN + 4);
            if (offset < 0 || length < 0 || offset + length > archive.length) {
                return;
            }

            try {
                final JSONObject layout = new JSONObject(
                        new String(archive, offset, length, StandardCharsets.UTF_8));
                name = layout.optString("name", null);
                model = layout.optString("deviceId", null);
            } catch (final Exception e) {
                LOG.warn("Watchface does not say what it is", e);
            }
            return;
        }
    }

    private static int count(final byte[] archive) {
        return (archive[ARCHIVE_COUNT_OFFSET] & 0xff)
                | ((archive[ARCHIVE_COUNT_OFFSET + 1] & 0xff) << 8);
    }

    private static int intAt(final byte[] data, final int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16) | ((data[offset + 3] & 0xff) << 24);
    }
}
