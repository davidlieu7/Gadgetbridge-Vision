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

import java.nio.charset.Charset;

/**
 * Reads the inner SAP message body carried inside a channel (the bytes after
 * [TYPE][SEQ][CHAN][CHAN]). This is the read side of {@link SapPayloadBuilder}.
 *
 * <pre>
 *   BODY = [ HDR:1 ] ...
 *   HDR bitfield:  bit7 Format (0 = fixed, 1 = variable)
 *                  bit6 Type   (0 = request, 1 = response)
 *                  bits5-0 MSG_ID  (meaning is channel-scoped)
 *
 *   Fixed    (bit7=0):  [HDR] then raw fields, schema-defined (e.g. [id][value]...)
 *   Variable (bit7=1):  [HDR][PARAM_COUNT][ param, param, ... ]
 * </pre>
 *
 * IMPORTANT: parameter value types/widths are schema-defined, NOT tagged on the
 * wire, and param ids are keyed on (channel, MSG_ID) — they are not globally
 * unique. This class only decodes the header (+ param count for variable) and
 * exposes typed cursor reads; the per-message decoder must know each field's
 * width. A blind generic walk desyncs on complex messages, so decode what you
 * know and stop (mirrors the dissector's best-effort behaviour).
 */
public final class SapPayloadParser {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final byte[] buf;
    private int pos;

    /** true = variable format (header bit7). */
    public final boolean variable;
    /** true = response (header bit6); false = request. */
    public final boolean response;
    /** MSG_ID, header bits 5-0. */
    public final int msgId;
    /** Declared parameter count for variable messages; -1 for fixed. */
    public final int paramCount;

    public SapPayloadParser(byte[] body) {
        this(body, null);
    }

    /**
     * @param hasParamCount override whether to consume a leading param-count
     *                      byte after the header; {@code null} infers it from
     *                      the header's format bit (the common case). Some
     *                      channels set the variable format bit but never
     *                      emit a count byte - e.g. OOBE (channel 0x01, see
     *                      {@link SapPayloadBuilder#variableNoCount}) - pass
     *                      {@code false} there.
     */
    public SapPayloadParser(byte[] body, Boolean hasParamCount) {
        if (body == null || body.length < 1) {
            throw new IllegalArgumentException("empty SAP body");
        }
        this.buf = body;
        int hdr = buf[0] & 0xFF;
        this.variable = (hdr & 0x80) != 0;
        this.response = (hdr & 0x40) != 0;
        this.msgId    = hdr & 0x3F;
        this.pos = 1;
        boolean consumeCount = (hasParamCount != null) ? hasParamCount : variable;
        this.paramCount = consumeCount ? (buf[pos++] & 0xFF) : -1;
    }

    // ---- cursor state ----

    public boolean hasMore() { return pos < buf.length; }
    public int remaining()   { return buf.length - pos; }
    public int position()    { return pos; }

    // ---- typed reads (little-endian, matching the builder) ----

    /** One unsigned byte. Used both for param ids and 1-byte values. */
    public int u8() {
        return buf[pos++] & 0xFF;
    }

    public int le16() {
        int v = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8);
        pos += 2;
        return v;
    }

    public int le32() {
        int v = (buf[pos] & 0xFF)
              | ((buf[pos + 1] & 0xFF) << 8)
              | ((buf[pos + 2] & 0xFF) << 16)
              | ((buf[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    public long le64() {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (buf[pos + i] & 0xFF) << (8 * i);
        }
        pos += 8;
        return v;
    }

    /** String value body: [len:1][utf8]. Call after consuming the param id. */
    public String string8() {
        int len = buf[pos++] & 0xFF;
        String s = new String(buf, pos, len, UTF8);
        pos += len;
        return s;
    }

    /** String value body: [len:2 LE][utf8]. Call after consuming the param id. */
    public String string16() {
        int len = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8);
        pos += 2;
        String s = new String(buf, pos, len, UTF8);
        pos += len;
        return s;
    }

    /** Custom/raw value body: [len:1][bytes]. Call after consuming the param id. */
    public byte[] rawParam() {
        int len = buf[pos++] & 0xFF;
        byte[] out = new byte[len];
        System.arraycopy(buf, pos, out, 0, len);
        pos += len;
        return out;
    }

    /**
     * Custom/raw value body: [len:2 LE][bytes]. Call after consuming the param id.
     * Defensive: if the declared length runs past the buffer (a truncated frame),
     * returns only the bytes actually present rather than throwing - a malformed
     * message must not crash the BLE callback thread.
     */
    public byte[] rawParam16() {
        int len = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8);
        pos += 2;
        int avail = Math.min(len, buf.length - pos);
        byte[] out = new byte[avail];
        System.arraycopy(buf, pos, out, 0, avail);
        pos += avail;
        return out;
    }

    /** Everything left, unparsed (e.g. to dump as "undecoded"). */
    public byte[] rest() {
        byte[] out = new byte[buf.length - pos];
        System.arraycopy(buf, pos, out, 0, out.length);
        pos = buf.length;
        return out;
    }
}
