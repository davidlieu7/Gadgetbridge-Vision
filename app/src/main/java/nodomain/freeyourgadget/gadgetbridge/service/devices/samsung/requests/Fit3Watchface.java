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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Watchface management on {@code CH_WATCHFACE} (0x06): list the installed
 * faces, pick the active one, delete one, and announce an install whose
 * bytes then travel over the file-transfer path (see {@link Fit3FileTransfer}).
 *
 * <p>A watchface is addressed by a pair: its numeric id (one byte, the
 * {@code 00021} in a {@code SM-R390_00021_256x402.bin} file name) and a
 * variant id, the style within that face. Every request that names
 * a face carries both, as {@code [0x04 id][0x1d variant]}.
 *
 * <p>All requests are fixed format with no param-count byte. The list reply
 * sets the variable format bit but also carries no count byte - its first
 * byte after the header is the list param id - so it is parsed with the count
 * explicitly disabled.
 *
 * <p>Wire-confirmed against a vendor capture of one install: the list
 * request/reply (before and after), the install request and both of its
 * replies (one when accepted, one when installed), and the order push that
 * follows. Activate and delete are not in that capture and follow the
 * same request shape.
 */
public final class Fit3Watchface {

    public static final int CHANNEL = SapProtocol.CH_WATCHFACE;

    public static final int MSG_ID_GET_LIST = 0x01;
    public static final int MSG_ID_GET_ACTIVE = 0x02;
    public static final int MSG_ID_ACTIVATE = 0x03;
    public static final int MSG_ID_INSTALL = 0x04;
    public static final int MSG_ID_DELETE = 0x05;
    public static final int MSG_ID_REORDER = 0x0a;

    // Param ids. Widths are schema-defined (not tagged on the wire), so every
    // id the list walker may meet has to be listed with its width below.
    private static final int PARAM_FACES = 0x03;
    private static final int PARAM_ID = 0x04;
    private static final int PARAM_VERSION = 0x05;          // string8
    private static final int PARAM_NAME = 0x06;             // string8, NUL-terminated
    private static final int PARAM_DESCRIPTION = 0x07;      // string8
    private static final int PARAM_LISTED_VARIANT = 0x08;         // same value as the variant id
    private static final int PARAM_POSITION = 0x09;
    private static final int PARAM_STYLE_INDEX = 0x0a;
    private static final int PARAM_ACTIVE = 0x0b;
    private static final int PARAM_CUSTOMIZABLE = 0x0c;
    private static final int PARAM_CLOCK_TYPE = 0x0d;
    private static final int PARAM_HOUR_INDEX = 0x0e;
    private static final int PARAM_MINUTE_INDEX = 0x0f;
    private static final int PARAM_SECOND_INDEX = 0x10;
    private static final int PARAM_WIDGET_INDEX = 0x11;     // [count][count bytes]
    private static final int PARAM_BACKGROUND = 0x13;       // string8
    private static final int PARAM_INSTALL_STATUS = 0x16;
    private static final int PARAM_ENTRY = 0x18;            // [param count] opens one list entry
    private static final int PARAM_DELETE_OK = 0x1a;
    private static final int PARAM_VARIANT = 0x1d;
    private static final int PARAM_CLOCK_COLOR = 0x1e;

    /** Install outcome codes carried in {@code INSTALL_RESULT}. */
    public static final int INSTALL_SUCCESS = 1;
    public static final int INSTALL_FAILED_LOW_BATTERY = 2;
    public static final int INSTALL_FAILED_WRONG_PATH = 3;
    public static final int INSTALL_FAILED_WRONG_FORMAT = 4;
    public static final int INSTALL_FAILED_LIST_FULL = 5;

    /**
     * Most-significant half of the {@link UUID}s Gadgetbridge's app manager
     * uses for these faces. Arbitrary but fixed; the face itself is encoded
     * in the low half (see {@link #toUuid}).
     */
    private static final long UUID_MSB = 0x5346495433574600L;

    private Fit3Watchface() {
    }

    // ---- requests ----

    /** Ask for the installed faces; answered with a {@link #parseList list}. */
    public static byte[] buildListRequest() {
        return SapPayloadBuilder.fixed(MSG_ID_GET_LIST).build();
    }

    /**
     * Announce an install. The band answers once straight away (accepted or
     * not), and a second time with the same message once the file has been
     * transferred and installed - see {@link #parseInstallResult}.
     */
    public static byte[] buildInstallRequest(int id, int variant) {
        return idPair(MSG_ID_INSTALL, id, variant);
    }

    public static byte[] buildActivateRequest(int id, int variant) {
        return idPair(MSG_ID_ACTIVATE, id, variant);
    }

    public static byte[] buildDeleteRequest(int id, int variant) {
        return idPair(MSG_ID_DELETE, id, variant);
    }

    private static byte[] idPair(int msgId, int id, int variant) {
        return SapPayloadBuilder.fixed(msgId)
                .addByte(PARAM_ID, id)
                .addByte(PARAM_VARIANT, variant)
                .build();
    }

    // ---- replies ----

    /** One installed face, as the band lists it. */
    public static final class Entry {
        public int id = -1;
        public int variant;
        public int position = -1;
        public boolean active;
        public boolean editable;
        @Nullable public String version;
        @Nullable public String name;
        @Nullable public String description;

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "Entry{id=%d, variant=%d, pos=%d, active=%b, version=%s, name=%s}",
                    id, variant, position, active, version, name);
        }
    }

    /**
     * Decode the {@code GET_LIST} reply:
     * {@code [hdr][0x03][count]} then {@code count} entries, each
     * {@code [0x18][param count][param]...}.
     *
     * <p>An entry param whose id isn't known ends the walk - its width can't
     * be guessed - and everything decoded up to that point is returned.
     *
     * @return the faces in list order, or {@code null} if this isn't a list reply
     */
    @Nullable
    public static List<Entry> parseList(byte[] body) {
        if (body == null || body.length < 3) {
            return null;
        }
        SapPayloadParser p = new SapPayloadParser(body, false);
        if (p.msgId != MSG_ID_GET_LIST || !p.response) {
            return null;
        }
        if (p.u8() != PARAM_FACES) {
            return null;
        }
        int count = p.u8();
        List<Entry> entries = new ArrayList<>(count);
        try {
            for (int i = 0; i < count && p.remaining() >= 2; i++) {
                if (p.u8() != PARAM_ENTRY) {
                    break;
                }
                int params = p.u8();
                Entry entry = new Entry();
                boolean complete = readEntry(p, params, entry);
                if (entry.id >= 0) {
                    entries.add(entry);
                }
                if (!complete) {
                    break;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            // Truncated reply: keep the entries decoded so far.
        }
        return entries;
    }

    /** @return false if the walk had to stop early on an unknown param id. */
    private static boolean readEntry(SapPayloadParser p, int params, Entry entry) {
        for (int i = 0; i < params; i++) {
            int id = p.u8();
            switch (id) {
                case PARAM_ID:
                    entry.id = p.u8();
                    break;
                case PARAM_VERSION:
                    entry.version = trimNul(p.string8());
                    break;
                case PARAM_NAME:
                    entry.name = trimNul(p.string8());
                    break;
                case PARAM_DESCRIPTION:
                    entry.description = trimNul(p.string8());
                    break;
                case PARAM_LISTED_VARIANT:
                    entry.variant = p.u8();
                    break;
                case PARAM_POSITION:
                    entry.position = p.u8();
                    break;
                case PARAM_ACTIVE:
                    entry.active = p.u8() == 1;
                    break;
                case PARAM_CUSTOMIZABLE:
                    entry.editable = p.u8() == 1;
                    break;
                case PARAM_STYLE_INDEX:
                case PARAM_CLOCK_TYPE:
                case PARAM_HOUR_INDEX:
                case PARAM_MINUTE_INDEX:
                case PARAM_SECOND_INDEX:
                case PARAM_CLOCK_COLOR:
                    p.u8();
                    break;
                case PARAM_BACKGROUND:
                    p.string8();
                    break;
                case PARAM_WIDGET_INDEX:
                    p.rawParam();
                    break;
                default:
                    return false;
            }
        }
        return true;
    }

    private static String trimNul(String s) {
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    /** Outcome of an install, from the band's {@code INSTALL} reply. */
    public static final class InstallResult {
        public final int id;
        public final int variant;
        public final int status;

        InstallResult(int id, int variant, int status) {
            this.id = id;
            this.variant = variant;
            this.status = status;
        }

        public boolean isSuccess() {
            return status == INSTALL_SUCCESS;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "InstallResult{id=%d, variant=%d, status=%d}", id, variant, status);
        }
    }

    /** {@code [hdr][0x16 result][0x04 id][0x1d variant]}; {@code null} if not that shape. */
    @Nullable
    public static InstallResult parseInstallResult(byte[] body) {
        if (body == null || body.length < 7) {
            return null;
        }
        SapPayloadParser p = new SapPayloadParser(body, false);
        if (p.msgId != MSG_ID_INSTALL || !p.response) {
            return null;
        }
        if (p.u8() != PARAM_INSTALL_STATUS) {
            return null;
        }
        int status = p.u8();
        if (p.u8() != PARAM_ID) {
            return null;
        }
        int id = p.u8();
        if (p.u8() != PARAM_VARIANT) {
            return null;
        }
        return new InstallResult(id, p.u8(), status);
    }

    /**
     * A face reference, from an activate reply or a band-initiated
     * activate/delete request ({@code [hdr][0x04 id][0x1d variant]}).
     *
     * @return {@code {id, variant}}, or {@code null} if not that shape
     */
    @Nullable
    public static int[] parseIdPair(byte[] body) {
        if (body == null || body.length < 5) {
            return null;
        }
        SapPayloadParser p = new SapPayloadParser(body, false);
        if (p.u8() != PARAM_ID) {
            return null;
        }
        int id = p.u8();
        if (p.u8() != PARAM_VARIANT) {
            return null;
        }
        return new int[]{id, p.u8()};
    }

    /**
     * Whether a delete reply ({@code [hdr][0x04 id][0x1d variant][0x1a ok]})
     * reports success. {@code null} if the reply isn't that shape.
     */
    @Nullable
    public static Boolean parseDeleteResult(byte[] body) {
        if (parseIdPair(body) == null || body.length < 7) {
            return null;
        }
        if ((body[5] & 0xFF) != PARAM_DELETE_OK) {
            return null;
        }
        return body[6] != 0;
    }

    // ---- app-manager identity ----

    /** The {@link UUID} Gadgetbridge's app manager shows a face under. */
    public static UUID toUuid(int id, int variant) {
        return new UUID(UUID_MSB, ((long) (variant & 0xFF) << 8) | (id & 0xFF));
    }

    /** {@code {id, variant}} for a {@link #toUuid} UUID, or {@code null} if it isn't one. */
    @Nullable
    public static int[] fromUuid(UUID uuid) {
        if (uuid == null || uuid.getMostSignificantBits() != UUID_MSB) {
            return null;
        }
        long low = uuid.getLeastSignificantBits();
        if ((low & ~0xFFFFL) != 0) {
            return null;
        }
        return new int[]{(int) (low & 0xFF), (int) ((low >> 8) & 0xFF)};
    }

    /** Faces sorted by the band's own position, which is the order it cycles through them. */
    public static List<Entry> sortedByPosition(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.position, b.position));
        return sorted;
    }
}
