/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Framing and command bytes of the ring's GATT service. Every message in both directions is
 * {@code [command u8][length u8][payload]}, little endian; one notification can carry several.
 * Command {@link #CMD_EXTENDED} carries a sub-command as the first payload byte.
 */
public final class OuraProtocol {
    public static final UUID SERVICE = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b");
    public static final UUID CHARACTERISTIC_WRITE = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b");
    public static final UUID CHARACTERISTIC_NOTIFY = UUID.fromString("98ed0003-a541-11e4-b6a0-0002a5d5c51b");

    /** Request {@code 03 00 00 00}; reply holds four 3-byte versions, firmware second. */
    public static final int CMD_FIRMWARE = 0x08;
    public static final int RSP_FIRMWARE = 0x09;
    /** Reply {@code [percent][3 bytes][millivolts u16]}. */
    public static final int CMD_BATTERY = 0x0c;
    public static final int RSP_BATTERY = 0x0d;
    /**
     * Request {@code [cursor u32][max records u8, 0 for all][ff ff ff ff]}: the records from the
     * cursor's tick on follow, then {@link #RSP_HISTORY_DONE}. Reading frees nothing on the ring;
     * the cursor alone marks what the phone already holds.
     */
    public static final int CMD_HISTORY = 0x10;
    /**
     * {@code [records sent u8][analysis pending u8][bytes stored after the last record sent u32][2 bytes]}.
     * A non-zero second byte with nothing stored after means the ring is still writing records.
     */
    public static final int RSP_HISTORY_DONE = 0x11;
    /** Request {@code [unix seconds u64][utc offset in half hours i8]}; reply {@code [ring ticks u32][status]}. */
    public static final int CMD_SET_TIME = 0x12;
    public static final int RSP_SET_TIME = 0x13;
    /** One byte: {@link #RADIO_MODE_SYNC} while fetching history, {@link #RADIO_MODE_IDLE} after. */
    public static final int CMD_RADIO_MODE = 0x16;
    public static final int RSP_RADIO_MODE = 0x17;
    public static final int CMD_EVENT_FLAGS = 0x1c;
    public static final int RSP_EVENT_FLAGS = 0x1d;
    /** Unprompted event {@code [event u8][new state u8][previous state u8][u8]}; event 0x20 is the worn state of {@link OuraHistory#TAG_WORN}. */
    public static final int RSP_EVENT = 0x1f;
    /** Sent once before fetching history. */
    public static final int CMD_PREPARE_HISTORY = 0x28;
    public static final int RSP_PREPARE_HISTORY = 0x29;
    public static final int CMD_EXTENDED = 0x2f;

    public static final int EXT_CAPABILITIES = 0x01;
    public static final int EXT_BATCHING = 0x03;
    /** {@code [feature]}; reply {@code [feature][mode][3 bytes]}. */
    public static final int EXT_FEATURE_GET = 0x20;
    public static final int EXT_FEATURE_GET_REPLY = 0x21;
    /** {@code [feature][mode]}. */
    public static final int EXT_FEATURE_SET = 0x22;
    public static final int EXT_NONCE = 0x2b;
    public static final int EXT_NONCE_REPLY = 0x2c;
    public static final int EXT_PROOF = 0x2d;
    public static final int EXT_PROOF_REPLY = 0x2e;

    public static final int RADIO_MODE_IDLE = 0x00;
    public static final int RADIO_MODE_SYNC = 0x02;

    public static final int EVENT_FLAGS_ALL = 0xbf;

    /** Status byte of {@link #EXT_PROOF_REPLY}. */
    public static final int PROOF_ACCEPTED = 0x00;
    public static final int PROOF_WRONG_KEY = 0x01;
    public static final int PROOF_RING_RESET = 0x02;
    public static final int PROOF_OTHER_PHONE = 0x03;

    public static final int SWITCH_ALL_DAY_HR = 0x02;
    public static final int SWITCH_ACTIVITY_HR = 0x03;
    public static final int SWITCH_BLOOD_OXYGEN = 0x04;
    public static final int SWITCH_STEPS = 0x0b;

    public static final int FEATURE_OFF = 0x00;
    public static final int FEATURE_ON = 0x01;

    /** Ring clock rate: history timestamps, the history cursor and {@link #RSP_SET_TIME} count tenths of a second since boot. */
    public static final int TICKS_PER_SECOND = 10;

    /** A short first {@link #CMD_HISTORY}, whose reply tells how much is stored after it. */
    public static final int HISTORY_FIRST_RECORDS = 0xff;
    public static final int HISTORY_ALL_RECORDS = 0x00;

    public static byte[] frame(final int command, final byte... payload) {
        final byte[] out = new byte[2 + payload.length];
        out[0] = (byte) command;
        out[1] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 2, payload.length);
        return out;
    }

    public static byte[] extended(final int subCommand, final byte... payload) {
        final byte[] inner = new byte[1 + payload.length];
        inner[0] = (byte) subCommand;
        System.arraycopy(payload, 0, inner, 1, payload.length);
        return frame(CMD_EXTENDED, inner);
    }

    public static byte[] setTime(final long unixSeconds, final int utcOffsetMinutes) {
        final ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(unixSeconds);
        buf.put((byte) Math.floorDiv(utcOffsetMinutes, 30));
        return frame(CMD_SET_TIME, buf.array());
    }

    public static byte[] history(final long cursor, final int maxRecords) {
        final ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt((int) cursor);
        buf.put((byte) maxRecords);
        buf.putInt(-1);
        return frame(CMD_HISTORY, buf.array());
    }

    public static int u16(final byte[] buf, final int offset) {
        return (buf[offset] & 0xff) | (buf[offset + 1] & 0xff) << 8;
    }

    public static long u32(final byte[] buf, final int offset) {
        return (u16(buf, offset) | (long) u16(buf, offset + 2) << 16) & 0xffffffffL;
    }

    private OuraProtocol() {
    }
}
