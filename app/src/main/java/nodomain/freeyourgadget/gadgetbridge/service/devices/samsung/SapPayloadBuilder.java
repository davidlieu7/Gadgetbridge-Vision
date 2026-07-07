package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * Builds the inner SAP message body carried inside a channel (the bytes after
 * [TYPE][SEQ][CHAN][CHAN]).
 *
 * <pre>
 *   BODY = [ HDR ] ...
 *   HDR bitfield:  bit7 Format (0 = fixed, 1 = variable)
 *                  bit6 Type   (0 = request, 1 = response)
 *                  bits5-0 MSG_ID  (meaning is channel-scoped)
 *
 *   Fixed    (bit7=0):  [HDR] then raw setting fields, e.g. [id][value].
 *   Variable (bit7=1):  [HDR][PARAM_COUNT][ param, param, ... ]
 * </pre>
 *
 * Parameter value types are schema-defined (they are NOT tagged on the wire), so
 * the caller chooses the right {@code addX} method per parameter. Integers are
 * little-endian; strings are UTF-8 with a 1-byte length prefix.
 *
 * Examples (confirmed on captures):
 * <ul>
 *   <li>Settings write (fixed): {@code fixed(group).raw(id).raw(value).build()}
 *       e.g. group 7, id 0x0e, value 30 -> display timeout 30 s.</li>
 *   <li>Notification reply (variable, MSG_ID 10 on channel 0x07):
 *       {@code variable(10).addInt32(0x01, notifId).addString(0x0A, text).build()}.</li>
 * </ul>
 */
public final class SapPayloadBuilder {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final boolean variable;
    private final int header;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int paramCount = 0;

    private SapPayloadBuilder(int msgId, boolean variable, boolean response) {
        this.variable = variable;
        int h = msgId & 0x3F;
        if (variable) {
            h |= 0x80;
        }
        if (response) {
            h |= 0x40;
        }
        this.header = h;
    }

    public static SapPayloadBuilder fixed(int msgId) {
        return new SapPayloadBuilder(msgId, false, false);
    }

    public static SapPayloadBuilder variable(int msgId) {
        return new SapPayloadBuilder(msgId, true, false);
    }

    // ---- fixed-format helpers: append raw bytes after the header ----

    public SapPayloadBuilder raw(int b) {
        out.write(b & 0xFF);
        return this;
    }

    public SapPayloadBuilder rawLe16(int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        return this;
    }

    public SapPayloadBuilder rawBytes(byte[] data) {
        out.write(data, 0, data.length);
        return this;
    }

    // ---- variable-format parameters (each increments the count) ----

    public SapPayloadBuilder addByte(int id, int value) {
        out.write(id & 0xFF);
        out.write(value & 0xFF);
        paramCount++;
        return this;
    }

    public SapPayloadBuilder addInt32(int id, int value) {
        out.write(id & 0xFF);
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
        paramCount++;
        return this;
    }

    public SapPayloadBuilder addLong(int id, long value) {
        out.write(id & 0xFF);
        for (int i = 0; i < 8; i++) {
            out.write((int) ((value >> (8 * i)) & 0xFF));
        }
        paramCount++;
        return this;
    }

    /** String parameter: [id][len:1][utf8]. */
    public SapPayloadBuilder addString(int id, String value) {
        byte[] utf8 = value.getBytes(UTF8);
        out.write(id & 0xFF);
        out.write(utf8.length & 0xFF);           // 1-byte length (see class note)
        out.write(utf8, 0, utf8.length);
        paramCount++;
        return this;
    }

    /** Raw/custom parameter: [id][len:1][bytes]. */
    public SapPayloadBuilder addRawParam(int id, byte[] data) {
        out.write(id & 0xFF);
        out.write(data.length & 0xFF);
        out.write(data, 0, data.length);
        paramCount++;
        return this;
    }

    public byte[] build() {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        msg.write(header);
        if (variable) {
            msg.write(paramCount & 0xFF);
        }
        byte[] payload = out.toByteArray();
        msg.write(payload, 0, payload.length);
        return msg.toByteArray();
    }
}
