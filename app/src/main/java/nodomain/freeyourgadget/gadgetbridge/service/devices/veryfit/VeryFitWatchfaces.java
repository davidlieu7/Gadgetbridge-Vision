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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceApp;

/**
 * The faces the watch is holding.
 * <p>
 * It reports them as a storage report followed by one record per face, and takes a new one by the
 * file name those records carry. Nothing else names a face, so the list has to have been read
 * before one can be picked.
 */
class VeryFitWatchfaces {
    private static final Logger LOG = LoggerFactory.getLogger(VeryFitWatchfaces.class);

    private final Map<UUID, String> files = new HashMap<>();

    /** Turns the reported list into apps, keeping the file name each of them stands for. */
    List<GBDeviceApp> parse(final byte[] payload) {
        final List<GBDeviceApp> faces = new ArrayList<>();
        if (payload.length < VeryFitConstants.WATCHFACE_LIST_HEADER_LEN) {
            return faces;
        }

        final String current = text(payload, VeryFitConstants.WATCHFACE_CURRENT_OFFSET);
        final int count = payload[VeryFitConstants.WATCHFACE_COUNT_OFFSET] & 0xff;
        LOG.info("Watch holds {} faces, showing {}, {} of {} bytes used", count, current,
                intAt(payload, VeryFitConstants.WATCHFACE_USED_OFFSET),
                intAt(payload, VeryFitConstants.WATCHFACE_TOTAL_OFFSET));

        files.clear();
        for (int i = 0; i < count; i++) {
            final int offset = VeryFitConstants.WATCHFACE_LIST_HEADER_LEN
                    + i * VeryFitConstants.WATCHFACE_RECORD_LEN;
            if (offset + VeryFitConstants.WATCHFACE_RECORD_LEN > payload.length) {
                break;
            }

            final String file = text(payload, offset + VeryFitConstants.WATCHFACE_RECORD_NAME);
            if (file.isEmpty()) {
                continue;
            }

            final UUID uuid = uuidOf(file);
            files.put(uuid, file);
            faces.add(new GBDeviceApp(uuid, label(file), "", file.equals(current)
                    ? GBApplication.getContext().getString(R.string.appmanager_watchface_active) : "",
                    GBDeviceApp.Type.WATCHFACE));
        }

        return faces;
    }

    /** The file the given face is stored as, or null when the list it came from was never read. */
    String fileOf(final UUID uuid) {
        return files.get(uuid);
    }

    /** Nothing the watch reports is a stable id, so the file name is folded into one. */
    private static UUID uuidOf(final String file) {
        return UUID.nameUUIDFromBytes(file.getBytes(StandardCharsets.UTF_8));
    }

    private static String label(final String file) {
        return file.endsWith(VeryFitConstants.WATCHFACE_SUFFIX)
                ? file.substring(0, file.length() - VeryFitConstants.WATCHFACE_SUFFIX.length()) : file;
    }

    private static String text(final byte[] payload, final int offset) {
        final int length = Math.min(VeryFitConstants.WATCHFACE_NAME_LEN, payload.length - offset);
        return new String(payload, offset, length, StandardCharsets.UTF_8).split("\u0000", 2)[0];
    }

    private static long intAt(final byte[] payload, final int offset) {
        return (payload[offset] & 0xffL) | ((payload[offset + 1] & 0xffL) << 8)
                | ((payload[offset + 2] & 0xffL) << 16) | ((payload[offset + 3] & 0xffL) << 24);
    }
}
