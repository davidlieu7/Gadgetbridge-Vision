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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The shared TLV codec: {@link SapPayloadBuilder} against {@link SapPayloadParser}.
 */
public class SapPayloadCodecTest {

    @Test
    public void fixedHeaderHasNoParamCount() {
        byte[] body = SapPayloadBuilder.fixed(0x02).build();
        assertArrayEquals(new byte[]{0x02}, body);

        SapPayloadParser p = new SapPayloadParser(body);
        assertEquals(0x02, p.msgId);
        assertFalse(p.variable);
        assertFalse(p.response);
        assertEquals(-1, p.paramCount);
        assertFalse(p.hasMore());
    }

    @Test
    public void fixedResponseSetsOnlyTheTypeBit() {
        byte[] body = SapPayloadBuilder.fixedResponse(0x12).raw(0x15).raw(0x00).build();
        assertEquals(0x52, body[0] & 0xFF);   // 0x12 | 0x40

        SapPayloadParser p = new SapPayloadParser(body);
        assertEquals(0x12, p.msgId);
        assertTrue(p.response);
        assertFalse(p.variable);
        assertEquals(0x15, p.u8());
        assertEquals(0x00, p.u8());
    }

    @Test
    public void variableCountsItsParams() {
        byte[] body = SapPayloadBuilder.variable(0x0a)
                .addByte(0x01, 0x7f)
                .addShort(0x02, 0x1234)
                .addInt32(0x03, 0x0a0b0c0d)
                .build();
        assertEquals(0x8a, body[0] & 0xFF);   // 0x0a | 0x80

        SapPayloadParser p = new SapPayloadParser(body);
        assertTrue(p.variable);
        assertEquals(3, p.paramCount);
        assertEquals(0x01, p.u8());
        assertEquals(0x7f, p.u8());
        assertEquals(0x02, p.u8());
        assertEquals(0x1234, p.le16());
        assertEquals(0x03, p.u8());
        assertEquals(0x0a0b0c0d, p.le32());
        assertFalse(p.hasMore());
    }

    @Test
    public void variableNoCountOmitsTheCountByte() {
        byte[] body = SapPayloadBuilder.variableNoCount(0x01).addByte(0x05, 0x01).build();
        assertArrayEquals(new byte[]{(byte) 0x81, 0x05, 0x01}, body);

        // Inferring from the header would eat the first param id, so the caller
        // must say there is no count byte.
        SapPayloadParser p = new SapPayloadParser(body, false);
        assertEquals(-1, p.paramCount);
        assertEquals(0x05, p.u8());
        assertEquals(0x01, p.u8());
    }

    @Test
    public void longIsLittleEndianEightBytes() {
        byte[] body = SapPayloadBuilder.variable(0x08).addLong(0x0c, 0x0102030405060708L).build();
        SapPayloadParser p = new SapPayloadParser(body);
        assertEquals(0x0c, p.u8());
        assertEquals(0x0102030405060708L, p.le64());
    }

    @Test
    public void string8RoundTrip() {
        byte[] body = SapPayloadBuilder.variable(0x0a).addString(0x0A, "Gadgetbridge").build();
        SapPayloadParser p = new SapPayloadParser(body);
        assertEquals(0x0A, p.u8());
        assertEquals("Gadgetbridge", p.string8());
        assertFalse(p.hasMore());
    }

    @Test
    public void string16LengthIsTwoBytesLittleEndian() {
        // 300 characters: the low byte alone (0x2c) would decode as a 44-byte
        // string, so the high byte has to be there and has to come second.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('x');
        }
        byte[] body = SapPayloadBuilder.variable(0x0a).addString16(0x0B, sb.toString()).build();

        assertEquals(0x0B, body[2] & 0xFF);
        assertEquals(300 & 0xFF, body[3] & 0xFF);
        assertEquals(300 >> 8, body[4] & 0xFF);

        SapPayloadParser p = new SapPayloadParser(body);
        assertEquals(0x0B, p.u8());
        assertEquals(sb.toString(), p.string16());
        assertFalse(p.hasMore());
    }

    @Test
    public void utf8StringsAreLengthPrefixedInBytesNotCharacters() {
        byte[] body = SapPayloadBuilder.variable(0x0a).addString(0x0A, "é€").build();
        SapPayloadParser p = new SapPayloadParser(body);
        assertEquals(0x0A, p.u8());
        assertEquals(5, body[3] & 0xFF);   // 2 + 3 UTF-8 bytes
        assertEquals("é€", p.string8());
    }

    @Test
    public void rawParamRoundTrip() {
        byte[] data = new byte[]{0x00, (byte) 0xFF, 0x7f};
        byte[] body = SapPayloadBuilder.variable(0x0a).addRawParam(0x20, data).build();
        SapPayloadParser p = new SapPayloadParser(body);
        assertEquals(0x20, p.u8());
        assertArrayEquals(data, p.rawParam());
    }

    @Test
    public void rawParam16ClampsATruncatedBody() {
        // Declares 1000 bytes but only 3 are present - a frame cut short must
        // not blow up on the BLE callback thread.
        byte[] body = new byte[]{0x12, 0x04, (byte) 0xe8, 0x03, 0x01, 0x02, 0x03};
        SapPayloadParser p = new SapPayloadParser(body);
        assertEquals(0x04, p.u8());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, p.rawParam16());
        assertFalse(p.hasMore());
    }

    @Test
    public void restReturnsEverythingLeft() {
        byte[] body = new byte[]{0x12, 0x01, 0x02, 0x03};
        SapPayloadParser p = new SapPayloadParser(body);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, p.rest());
        assertFalse(p.hasMore());
        assertArrayEquals(new byte[0], p.rest());
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyBodyIsRejected() {
        new SapPayloadParser(new byte[0]);
    }
}
