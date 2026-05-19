/*  Copyright (C) 2026 Ariel Saghiv

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.vring;

import java.nio.ByteBuffer;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.vring.VRingR26Constants;

/**
 * Builds wire packets for the VRing R26 protocol.
 *
 * <pre>
 *   FD DA 10 &lt;total_len&gt; &lt;category&gt; &lt;command&gt; &lt;payload...&gt;
 * </pre>
 * {@code total_len} = 6 + payload.length (entire packet length including 6-byte header).
 */
public final class VRingR26Packet {

    private final int category;
    private final int command;
    private final byte[] payload;

    public VRingR26Packet(int category, int command, byte[] payload) {
        this.category = category;
        this.command = command;
        this.payload = payload != null ? payload : new byte[0];
    }

    public byte[] encode() {
        int total = VRingR26Constants.HEADER_LEN + payload.length;
        ByteBuffer bb = ByteBuffer.allocate(total);
        bb.put(VRingR26Constants.MAGIC_0);
        bb.put(VRingR26Constants.MAGIC_1);
        bb.put(VRingR26Constants.MAGIC_2);
        bb.put((byte) total);
        bb.put((byte) category);
        bb.put((byte) command);
        bb.put(payload);
        return bb.array();
    }

    /** Returns the GATT characteristic UUID to write this packet to, per cat routing. */
    public UUID writeCharacteristic() {
        if (category == 0xF1) return VRingR26Constants.UUID_CHARACTERISTIC_WRITE_F1;
        if (category == 0xF2) return VRingR26Constants.UUID_CHARACTERISTIC_WRITE_F2;
        return VRingR26Constants.UUID_CHARACTERISTIC_WRITE_DEFAULT;
    }

    public int getCategory() { return category; }
    public int getCommand()  { return command; }
    public byte[] getPayload() { return payload; }

    // --- Static helpers for common packets ---

    public static VRingR26Packet findDevice(boolean enable) {
        return new VRingR26Packet(VRingR26Constants.CAT_FIND,
                VRingR26Constants.CMD_FIND_DEVICE,
                new byte[]{(byte) (enable ? 1 : 0)});
    }

    public static VRingR26Packet queryDeviceInfo() {
        return new VRingR26Packet(VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_DEVICE_INFO, null);
    }

    public static VRingR26Packet queryFirmware() {
        return new VRingR26Packet(VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_FIRMWARE_TABLE, null);
    }

    public static VRingR26Packet queryStepRecordToday() {
        return new VRingR26Packet(VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_STEP_RECORD, new byte[]{0});
    }

    /** cat=2 cmd=13: queryHistorySteps(day). day=0..-6 (today, -1d, ...). */
    public static VRingR26Packet queryHistoryStepsDay(int day) {
        return new VRingR26Packet(VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_HISTORY_STEPS, new byte[]{(byte) day});
    }

    /** cat=2 cmd=18: queryHistoryStepsDetails(day) - per-bucket array. */
    public static VRingR26Packet queryHistoryStepsDetailsDay(int day) {
        return new VRingR26Packet(VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_HISTORY_STEPS_DETAIL, new byte[]{(byte) day});
    }

    /** cat=2 cmd=9: queryHistoryHeartRate(day). */
    public static VRingR26Packet queryHistoryHeartRate(int day) {
        return new VRingR26Packet(VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_HR_LATEST, new byte[]{(byte) day});
    }

    /** cat=2 cmd=15: queryTimingHeartRate(day, pageIndex). */
    public static VRingR26Packet queryTimingHeartRate(int day, int pageIndex) {
        return new VRingR26Packet(VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_TIMING_HR, new byte[]{(byte) day, (byte) pageIndex});
    }

    public static VRingR26Packet queryHistoryDay(int day) {
        return new VRingR26Packet(VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_HISTORY_DAY, new byte[]{(byte) day});
    }

    public static VRingR26Packet querySleep() {
        return new VRingR26Packet(VRingR26Constants.CAT_QUERY,
                VRingR26Constants.CMD_QUERY_SLEEP, null);
    }

    public static VRingR26Packet startHeartRateMeasurement(boolean start) {
        return new VRingR26Packet(VRingR26Constants.CAT_SET,
                VRingR26Constants.CMD_START_HR_MEASURE,
                new byte[]{(byte) (start ? 1 : 0)});
    }

    public static VRingR26Packet startSpo2Measurement(boolean start) {
        return new VRingR26Packet(VRingR26Constants.CAT_SET,
                VRingR26Constants.CMD_START_SPO2_MEASURE,
                new byte[]{(byte) (start ? 1 : 0)});
    }

    public static VRingR26Packet setTime(long epochSeconds) {
        // Vendor formats current wall time as "yyyy-MM-dd HH:mm:ss" then re-parses it as GMT+8,
        // so the ring sees its internal GMT+8 reference clock matching the user's local wall time.
        // local_wall = epoch + localOffset;  ring_wall = adjusted + gmt8Offset
        // We want ring_wall == local_wall  =>  adjusted = epoch + (localOffset - gmt8Offset)
        java.util.TimeZone local = java.util.TimeZone.getDefault();
        long localOffsetMs = local.getOffset(epochSeconds * 1000L);
        long gmt8OffsetMs = 8L * 3600L * 1000L;
        long adjusted = epochSeconds + (localOffsetMs - gmt8OffsetMs) / 1000L;
        ByteBuffer p = ByteBuffer.allocate(5).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        p.putInt((int) adjusted);   // little-endian (z1.a.k)
        p.put((byte) 0x08);
        return new VRingR26Packet(VRingR26Constants.CAT_SET,
                VRingR26Constants.CMD_SET_TIME, p.array());
    }

    public static VRingR26Packet sendNotification(int code) {
        return new VRingR26Packet(VRingR26Constants.CAT_SET,
                VRingR26Constants.CMD_SET_NOTIFY_MESSAGE,
                new byte[]{(byte) code});
    }

    /** Parses a notification frame. Returns null if magic does not match or frame is truncated. */
    public static VRingR26Packet decode(byte[] data) {
        if (data == null || data.length < VRingR26Constants.HEADER_LEN) return null;
        if (data[0] != VRingR26Constants.MAGIC_0 ||
            data[1] != VRingR26Constants.MAGIC_1 ||
            data[2] != VRingR26Constants.MAGIC_2) {
            return null;
        }
        int totalLen = data[3] & 0xFF;
        if (totalLen < VRingR26Constants.HEADER_LEN) return null;
        if (totalLen > data.length) {
            // Frame is truncated or fragmented - refuse to parse rather than silently drop bytes.
            return null;
        }
        int cat = data[4] & 0xFF;
        int cmd = data[5] & 0xFF;
        int payloadLen = totalLen - VRingR26Constants.HEADER_LEN;
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            System.arraycopy(data, VRingR26Constants.HEADER_LEN, payload, 0, payloadLen);
        }
        return new VRingR26Packet(cat, cmd, payload);
    }
}
