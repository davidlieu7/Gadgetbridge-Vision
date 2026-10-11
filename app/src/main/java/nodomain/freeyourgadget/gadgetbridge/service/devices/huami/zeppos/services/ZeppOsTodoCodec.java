/* Copyright 2026 Gadgetbridge Contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.services;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** To-do endpoint 0x0012, independent of calendar events and legacy reminder slots. */
final class ZeppOsTodoCodec {
    static final byte CAPABILITIES_REQUEST = 1;
    static final byte CAPABILITIES_RESPONSE = 2;
    static final byte LIST_REQUEST = 3;
    static final byte LIST_RESPONSE = 4;
    static final byte UPSERT = 5;
    static final byte UPSERT_ACK = 6;
    static final byte DELETE = 7;
    static final byte DELETE_ACK = 8;
    static final byte CHANGED = 13;
    static final int MAX_TEXT_BYTES = 150;
    static final long UINT32_MAX = 0xffffffffL;

    static boolean supportedVersion(final int version) { return version >= 1 && version <= 3; }

    static final class Item {
        final long id;
        final String text;
        final int source;
        final long firstRemind;
        final long date;
        final long time;
        final int repeat;
        final long completeTime;

        Item(final long id, final String text, final int source, final long firstRemind,
             final long date, final long time, final int repeat, final long completeTime) {
            this.id = id; this.text = text; this.source = source; this.firstRemind = firstRemind;
            this.date = date; this.time = time; this.repeat = repeat; this.completeTime = completeTime;
        }

        @Override public boolean equals(final Object other) {
            if (!(other instanceof Item)) { return false; }
            final Item that = (Item) other;
            return id == that.id && text.equals(that.text) && source == that.source
                    && firstRemind == that.firstRemind && date == that.date && time == that.time
                    && repeat == that.repeat && completeTime == that.completeTime;
        }
        @Override public int hashCode() {
            return Objects.hash(id, text, source, firstRemind, date, time, repeat, completeTime);
        }
    }

    static byte[] upsert(final Item item, final int version) {
        requireVersion(version);
        if (item.text == null || item.text.isEmpty()
                || item.text.indexOf('\0') >= 0) { throw new IllegalArgumentException("Invalid To-do text"); }
        final byte[] text;
        try {
            final ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(item.text));
            text = new byte[encoded.remaining()];
            encoded.get(text);
        } catch (final CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid To-do UTF-8", e);
        }
        if (text.length > MAX_TEXT_BYTES) { throw new IllegalArgumentException("To-do text exceeds 150 UTF-8 bytes"); }
        final ByteBuffer out = ByteBuffer.allocate(1 + text.length + (version >= 2 ? 26 : 22))
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(UPSERT);
        putUint32(out, item.id);
        out.put(text).put((byte) 0).put((byte) item.source);
        if (version >= 2) { putUint32(out, item.firstRemind); }
        putUint32(out, item.date);
        putUint32(out, item.time);
        out.putInt(item.repeat);
        putUint32(out, item.completeTime);
        return out.array();
    }

    static byte[] delete(final long id) {
        final ByteBuffer out = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(DELETE);
        putUint32(out, id);
        return out.array();
    }

    static Map<Long, Item> list(final byte[] payload, final int version, final int maximum) {
        requireVersion(version);
        if (payload.length < 3 || payload[0] != LIST_RESPONSE) { throw new IllegalArgumentException("Short To-do list"); }
        final ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        in.get();
        final int count = in.getShort() & 0xffff;
        if (count > maximum) { throw new IllegalArgumentException("To-do count exceeds capacity"); }
        final Map<Long, Item> result = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            if (in.remaining() < (version >= 2 ? 26 : 22)) { throw new IllegalArgumentException("Truncated To-do item"); }
            final long id = in.getInt() & UINT32_MAX;
            final int textStart = in.position();
            int textEnd = textStart;
            while (textEnd < in.limit() && in.get(textEnd) != 0) { textEnd++; }
            if (textEnd == in.limit()) { throw new IllegalArgumentException("Unterminated To-do text"); }
            final String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(payload, textStart, textEnd - textStart)).toString();
            } catch (final CharacterCodingException e) { throw new IllegalArgumentException("Invalid To-do UTF-8", e); }
            in.position(textEnd + 1);
            if (in.remaining() < (version >= 2 ? 21 : 17)) { throw new IllegalArgumentException("Truncated To-do fields"); }
            final int source = in.get() & 0xff;
            final long firstRemind = version >= 2 ? in.getInt() & UINT32_MAX : 0;
            final Item item = new Item(id, text, source, firstRemind, in.getInt() & UINT32_MAX,
                    in.getInt() & UINT32_MAX, in.getInt(), in.getInt() & UINT32_MAX);
            if (result.put(id, item) != null) { throw new IllegalArgumentException("Duplicate To-do ID"); }
        }
        if (in.hasRemaining()) { throw new IllegalArgumentException("Unexpected To-do list trailer"); }
        return result;
    }

    private static void requireVersion(final int version) {
        if (!supportedVersion(version)) { throw new IllegalArgumentException("Unsupported To-do version " + version); }
    }

    private static void putUint32(final ByteBuffer out, final long value) {
        if (value < 0 || value > UINT32_MAX) { throw new IllegalArgumentException("To-do value exceeds uint32"); }
        out.putInt((int) value);
    }
}
