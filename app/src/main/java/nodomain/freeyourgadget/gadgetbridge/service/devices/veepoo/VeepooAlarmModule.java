/*  Copyright (C) 2025 Gadgetbridge contributors

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo;

import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;

// ------------------------------------------------------------------------
// Text alarms (0xB9), mirroring the vendor TextAlarmOprate. Every op frame carries a CRC
// over the whole post-op alarm list; the band echoes it back in the ack and rejects the op
// on mismatch, so alarmList below must always mirror the watch exactly.
// ------------------------------------------------------------------------

/** Feature module for 0xB9 text alarms: keeps the tracked alarm list in sync with the band. */
class VeepooAlarmModule {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooAlarmModule.class);

    private VeepooDeviceSupport support;

    VeepooAlarmModule(VeepooDeviceSupport support) {
        this.support = support;
    }

    // Text alarms (0xB9): the band verifies a CRC over the whole alarm list on every op, so we
    // track its exact state and re-read whenever an op fails or the band edits alarms locally.
    private final Object alarmLock = new Object();
    private final List<VeepooAlarm> alarmList = new ArrayList<>();
    private int alarmCrc = 0;
    private boolean alarmReadOutstanding = false;
    private final List<byte[]> alarmReadPackets = new ArrayList<>();
    private final Runnable alarmResync = () -> sendAlarmRead();
    private final Runnable alarmReadWatchdog = () -> {
        synchronized (alarmLock) {
            if (alarmReadOutstanding) {
                LOG.warn("Alarm read timed out; resetting outstanding flag");
                alarmReadOutstanding = false;
            }
        }
    };

    /** One text alarm as stored on the band (vendor TextAlarm2Setting). */
    private static final class VeepooAlarm {
        int id;
        int hour;
        int minute;
        boolean open;
        int repeatInt;   // 7-bit weekday mask, bit6=Sunday ... bit0=Saturday; 0 = one-shot
        int yearOffset;  // year - 2000; 0 for repeating alarms
        int month;       // 1-12; 0 for repeating alarms
        int day;         // 1-31; 0 for repeating alarms
        String label = "";

        boolean sameTimeAs(VeepooAlarm other) {
            return hour == other.hour && minute == other.minute && repeatInt == other.repeatInt
                    && yearOffset == other.yearOffset && month == other.month && day == other.day;
        }

        boolean sameAs(VeepooAlarm other) {
            return sameTimeAs(other) && open == other.open && label.equals(other.label);
        }
    }

    /** Vendor Alarm2Util alarm CRC16 table (CCITT, init 0xFFFF, no final xor). */
    private static final int[] ALARM_CRC_TABLE = {
            0, 49345, 49537, 320, 49921, 960, 640, 49729, 50689, 1728, 1920, 51009, 1280, 50625, 50305, 1088,
            52225, 3264, 3456, 52545, 3840, 53185, 52865, 3648, 2560, 51905, 52097, 2880, 51457, 2496, 2176, 51265,
            55297, 6336, 6528, 55617, 6912, 56257, 55937, 6720, 7680, 57025, 57217, 8000, 56577, 7616, 7296, 56385,
            5120, 54465, 54657, 5440, 55041, 6080, 5760, 54849, 53761, 4800, 4992, 54081, 4352, 53697, 53377, 4160,
            61441, 12480, 12672, 61761, 13056, 62401, 62081, 12864, 13824, 63169, 63361, 14144, 62721, 13760, 13440, 62529,
            15360, 64705, 64897, 15680, 65281, 16320, 16000, 65089, 64001, 15040, 15232, 64321, 14592, 63937, 63617, 14400,
            10240, 59585, 59777, 10560, 60161, 11200, 10880, 59969, 60929, 11968, 12160, 61249, 11520, 60865, 60545, 11328,
            58369, 9408, 9600, 58689, 9984, 59329, 59009, 9792, 8704, 58049, 58241, 9024, 57601, 8640, 8320, 57409,
            40961, 24768, 24960, 41281, 25344, 41921, 41601, 25152, 26112, 42689, 42881, 26432, 42241, 26048, 25728, 42049,
            27648, 44225, 44417, 27968, 44801, 28608, 28288, 44609, 43521, 27328, 27520, 43841, 26880, 43457, 43137, 26688,
            30720, 47297, 47489, 31040, 47873, 31680, 31360, 47681, 48641, 32448, 32640, 48961, 32000, 48577, 48257, 31808,
            46081, 29888, 30080, 46401, 30464, 47041, 46721, 30272, 29184, 45761, 45953, 29504, 45313, 29120, 28800, 45121,
            20480, 37057, 37249, 20800, 37633, 21440, 21120, 37441, 38401, 22208, 22400, 38721, 21760, 38337, 38017, 21568,
            39937, 23744, 23936, 40257, 24320, 40897, 40577, 24128, 23040, 39617, 39809, 23360, 39169, 22976, 22656, 38977,
            34817, 18624, 18816, 35137, 19200, 35777, 35457, 19008, 19968, 36545, 36737, 20288, 36097, 19904, 19584, 35905,
            17408, 33985, 34177, 17728, 34561, 18368, 18048, 34369, 33281, 17088, 17280, 33601, 16640, 33217, 32897, 16448
    };

    void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        if (!alarmsSupported()) {
            LOG.warn("Watch does not support text alarms (no A7 alarm tag); ignoring onSetAlarms");
            return;
        }
        List<VeepooAlarm> desired = new ArrayList<>();
        Calendar now = Calendar.getInstance();
        for (Alarm alarm : alarms) {
            if (alarm == null || alarm.getUnused()) {
                continue;
            }
            VeepooAlarm watchAlarm = new VeepooAlarm();
            watchAlarm.hour = Math.min(alarm.getHour(), 23);
            watchAlarm.minute = Math.min(alarm.getMinute(), 59);
            watchAlarm.open = alarm.getEnabled();
            if (alarm.isRepetitive()) {
                watchAlarm.repeatInt = gbRepeatToVendorRepeat(alarm.getRepetition());
            } else {
                // One-shot: the band wants a concrete date; use today when the time is still ahead.
                Calendar when = (Calendar) now.clone();
                when.set(Calendar.HOUR_OF_DAY, watchAlarm.hour);
                when.set(Calendar.MINUTE, watchAlarm.minute);
                when.set(Calendar.SECOND, 0);
                when.set(Calendar.MILLISECOND, 0);
                if (!when.after(now)) {
                    when.add(Calendar.DAY_OF_MONTH, 1);
                }
                watchAlarm.yearOffset = when.get(Calendar.YEAR) - 2000;
                watchAlarm.month = when.get(Calendar.MONTH) + 1;
                watchAlarm.day = when.get(Calendar.DAY_OF_MONTH);
            }
            watchAlarm.label = truncateUtf8(alarm.getTitle(), 100);
            desired.add(watchAlarm);
        }
        if (desired.size() > VeepooConstants.ALARM_MAX_ALARMS) {
            LOG.error("Watch supports at most {} alarms; dropping the extras", VeepooConstants.ALARM_MAX_ALARMS);
            desired = new ArrayList<>(desired.subList(0, VeepooConstants.ALARM_MAX_ALARMS));
        }
        List<byte[]> frames;
        synchronized (alarmLock) {
            frames = buildAlarmSyncFrames(desired);
        }
        if (frames.isEmpty()) {
            LOG.info("Alarm list already in sync with watch");
            return;
        }
        LOG.info("Syncing {} alarm(s) to watch ({} frame(s))", desired.size(), frames.size());
        try {
            TransactionBuilder builder = support.performInitialized("sync alarms");
            for (byte[] frame : frames) {
                writeAlarmFrame(builder, frame);
            }
            builder.queue();
        } catch (IOException e) {
            LOG.error("Error syncing alarms to watch", e);
            return;
        }
        // Verify the push a few seconds later: the read rebuilds our state from ground truth.
        scheduleAlarmResync(3000L);
    }

    /** True once the A7 report says this band speaks text alarms (or a previous connect learned it). */
    private boolean alarmsSupported() {
        if (support.textAlarmsSupported) {
            return true;
        }
        return GBApplication.getDeviceSpecificSharedPrefs(support.getDevice().getAddress())
                .getBoolean(VeepooConstants.CONFIG_ALARMS_SUPPORTED, false);
    }

    /** Maps Gadgetbridge's MON=1..SUN=64 mask onto the band's weekday value (bit6=Sunday .. bit0=Saturday). */
    private static int gbRepeatToVendorRepeat(int gbRepetition) {
        int vendor = 0;
        if ((gbRepetition & Alarm.ALARM_SUN) != 0) vendor |= 0x40;
        if ((gbRepetition & Alarm.ALARM_MON) != 0) vendor |= 0x20;
        if ((gbRepetition & Alarm.ALARM_TUE) != 0) vendor |= 0x10;
        if ((gbRepetition & Alarm.ALARM_WED) != 0) vendor |= 0x08;
        if ((gbRepetition & Alarm.ALARM_THU) != 0) vendor |= 0x04;
        if ((gbRepetition & Alarm.ALARM_FRI) != 0) vendor |= 0x02;
        if ((gbRepetition & Alarm.ALARM_SAT) != 0) vendor |= 0x01;
        return vendor;
    }

    /**
     * Diffs the desired list against the tracked watch state and produces the 0xB9 frames that
     * reach it, in wire order. Each frame's CRC covers the simulated post-op list; the tracked
     * state is updated optimistically and a verification read follows the push.
     */
    private List<byte[]> buildAlarmSyncFrames(List<VeepooAlarm> desired) {
        List<VeepooAlarm> state = new ArrayList<>(alarmList);
        List<byte[]> frames = new ArrayList<>();
        // Match by time/repetition/date so re-sending an unchanged list stays a no-op.
        List<VeepooAlarm> matchedState = new ArrayList<>();
        for (VeepooAlarm want : desired) {
            for (VeepooAlarm have : state) {
                if (!matchedState.contains(have) && have.sameTimeAs(want)) {
                    want.id = have.id;
                    matchedState.add(have);
                    break;
                }
            }
        }
        // Delete watch alarms that are no longer wanted.
        for (VeepooAlarm have : new ArrayList<>(state)) {
            if (!matchedState.contains(have)) {
                state.remove(have);
                frames.add(alarmOpFrame(VeepooConstants.ALARM_OP_DELETE, have, computeAlarmCrc(state)));
            }
        }
        // Update matched entries whose content changed.
        for (VeepooAlarm want : desired) {
            int position = -1;
            for (int i = 0; i < state.size(); i++) {
                if (state.get(i).id == want.id) {
                    position = i;
                    break;
                }
            }
            if (position >= 0 && !state.get(position).sameAs(want)) {
                state.set(position, want);
                frames.add(alarmOpFrame(VeepooConstants.ALARM_OP_SET, want, computeAlarmCrc(state)));
            }
        }
        // Add new alarms with the lowest free ids.
        for (VeepooAlarm want : desired) {
            boolean present = false;
            for (VeepooAlarm have : state) {
                if (have.id == want.id) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                want.id = nextFreeAlarmId(state);
                if (want.id <= 0) {
                    LOG.error("Watch alarm list is full; dropping an alarm");
                    continue;
                }
                state.add(want);
                frames.add(alarmOpFrame(VeepooConstants.ALARM_OP_SET, want, computeAlarmCrc(state)));
            }
        }
        alarmList.clear();
        alarmList.addAll(state);
        alarmCrc = computeAlarmCrc(alarmList);
        return frames;
    }

    private static int nextFreeAlarmId(List<VeepooAlarm> state) {
        for (int id = 1; id <= VeepooConstants.ALARM_MAX_ALARMS; id++) {
            boolean used = false;
            for (VeepooAlarm have : state) {
                if (have.id == id) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return id;
            }
        }
        return -1;
    }

    /** Builds a 0xB9 frame carrying one alarm record plus the whole-list CRC. */
    private static byte[] alarmOpFrame(int op, VeepooAlarm alarm, int crc) {
        byte[] record = alarmRecord(alarm);
        byte[] frame = new byte[8 + record.length];
        frame[0] = VeepooConstants.OP_MULTI_ALARM;
        frame[1] = (byte) op;
        frame[2] = 1;
        frame[3] = 1;
        frame[4] = (byte) VeepooConstants.ALARM_CRC_MARKER;
        frame[5] = 2;
        frame[6] = (byte) (crc & 0xFF);
        frame[7] = (byte) ((crc >> 8) & 0xFF);
        System.arraycopy(record, 0, frame, 8, record.length);
        return frame;
    }

    /** One alarm record: [A1, len, B1, 8, id, open, repeat, h, m, y-2000, M, d, B2, textLen, text...]. */
    private static byte[] alarmRecord(VeepooAlarm alarm) {
        byte[] label = alarm.label.getBytes(StandardCharsets.UTF_8);
        byte[] record = new byte[label.length + 14];
        record[0] = (byte) 0xA1;
        record[1] = (byte) (label.length + 12);
        record[2] = (byte) 0xB1;
        record[3] = 8;
        record[4] = (byte) alarm.id;
        record[5] = (byte) (alarm.open ? 1 : 0);
        record[6] = (byte) alarm.repeatInt;
        record[7] = (byte) alarm.hour;
        record[8] = (byte) alarm.minute;
        record[9] = (byte) alarm.yearOffset;
        record[10] = (byte) alarm.month;
        record[11] = (byte) alarm.day;
        record[12] = (byte) 0xB2;
        record[13] = (byte) label.length;
        System.arraycopy(label, 0, record, 14, label.length);
        return record;
    }

    /** CRC over all records of the list in order (vendor Alarm2Util.getAlarmCrc16 semantics). */
    private static int computeAlarmCrc(List<VeepooAlarm> alarms) {
        if (alarms.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (VeepooAlarm alarm : alarms) {
            total += alarmRecord(alarm).length;
        }
        byte[] all = new byte[total];
        int offset = 0;
        for (VeepooAlarm alarm : alarms) {
            byte[] record = alarmRecord(alarm);
            System.arraycopy(record, 0, all, offset, record.length);
            offset += record.length;
        }
        return alarmCrc16(all);
    }

    private static int alarmCrc16(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        int crc = 0xFFFF;
        for (byte b : data) {
            crc = (((crc >> 8) & 0xFF) ^ ALARM_CRC_TABLE[(b & 0xFF) ^ (crc & 0xFF)]) & 0xFFFF;
        }
        return crc & 0xFFFF;
    }

    /** Sends a read request for the full alarm list; the response rebuilds the tracked state. */
    private void sendAlarmRead() {
        if (!alarmsSupported()) {
            return;
        }
        int crc;
        synchronized (alarmLock) {
            if (alarmReadOutstanding) {
                return;
            }
            alarmReadOutstanding = true;
            crc = alarmCrc;
        }
        byte[] frame = new byte[VeepooConstants.PACKET_SIZE];
        frame[0] = VeepooConstants.OP_MULTI_ALARM;
        frame[1] = VeepooConstants.ALARM_OP_READ;
        frame[4] = (byte) VeepooConstants.ALARM_CRC_MARKER;
        frame[5] = 2;
        frame[6] = (byte) (crc & 0xFF);
        frame[7] = (byte) ((crc >> 8) & 0xFF);
        try {
            TransactionBuilder builder = support.performInitialized("read alarms");
            support.writeFrame(builder, frame);
            builder.queue();
            support.mainHandler.postDelayed(alarmReadWatchdog, 10000L);
        } catch (IOException e) {
            LOG.error("Error reading alarms from watch", e);
            synchronized (alarmLock) {
                alarmReadOutstanding = false;
            }
        }
    }

    private void scheduleAlarmResync(long delayMillis) {
        support.mainHandler.removeCallbacks(alarmResync);
        support.mainHandler.postDelayed(alarmResync, delayMillis);
    }

    /** Connect hook: read the watch's alarm list once the A7 capability report has had time to arrive. */
    void scheduleConnectReadBack() {
        support.mainHandler.postDelayed(() -> sendAlarmRead(), 8000L);
    }

    /** Dispatches 0xB9 frames: our op acks, read responses, and band-initiated alarm changes. */
    void handleFrame(byte[] data) {
        if (data == null || data.length < 2) {
            return;
        }
        int op = data[1] & 0xFF;
        switch (op) {
            case VeepooConstants.ALARM_OP_DELETE:
            case VeepooConstants.ALARM_OP_SET:
                handleAlarmOpAck(data);
                break;
            case VeepooConstants.ALARM_OP_READ:
                handleAlarmReadPacket(data);
                break;
            case VeepooConstants.ALARM_OP_FIRE_REPORT:
                LOG.info("Band alarm fired: {}", VeepooDeviceSupport.hexBytes(data));
                scheduleAlarmResync(2000L);
                break;
            case VeepooConstants.ALARM_OP_DEVICE_ADD:
                LOG.info("Band added an alarm from its own UI; re-reading the list");
                scheduleAlarmResync(2000L);
                break;
            case VeepooConstants.ALARM_OP_DEVICE_DELETE:
                if (data.length >= 5) {
                    removeAlarmById(data[4] & 0xFF);
                }
                break;
            case VeepooConstants.ALARM_OP_DEVICE_TOGGLE:
                if (data.length >= 6) {
                    setAlarmOpen(data[4] & 0xFF, data[5] == 1);
                }
                break;
            default:
                LOG.debug("Unhandled alarm frame: {}", VeepooDeviceSupport.hexBytes(data));
        }
    }

    /** Ack for a delete/set op: [B7, op, x, x, A0, 3, result, crcLo, crcHi]. */
    private void handleAlarmOpAck(byte[] data) {
        if (data.length < 9 || (data[4] & 0xFF) != VeepooConstants.ALARM_CRC_MARKER || data[5] != 3) {
            LOG.debug("Unexpected alarm ack shape: {}", VeepooDeviceSupport.hexBytes(data));
            return;
        }
        int result = data[6] & 0xFF;
        int crcEcho = (data[7] & 0xFF) | ((data[8] & 0xFF) << 8);
        if (result != 1) {
            LOG.warn("Watch rejected an alarm op (crc {}); re-reading the list",
                    String.format("%04X", crcEcho));
            scheduleAlarmResync(2000L);
        } else {
            LOG.debug("Alarm op ack ok, crc={}", String.format("%04X", crcEcho));
        }
    }

    /** One packet of a (possibly multi-packet) read response; the last one rebuilds the state. */
    private void handleAlarmReadPacket(byte[] data) {
        if (data.length < 8) {
            return;
        }
        int total = data[2] & 0xFF;
        int index = data[3] & 0xFF;
        List<byte[]> packets;
        synchronized (alarmLock) {
            if (index == 1 || alarmReadPackets.isEmpty()) {
                alarmReadPackets.clear();
            }
            alarmReadPackets.add(data);
            if (!(total >= 1 && index == total)) {
                return;
            }
            packets = new ArrayList<>(alarmReadPackets);
            alarmReadPackets.clear();
            alarmReadOutstanding = false;
        }
        support.mainHandler.removeCallbacks(alarmReadWatchdog);
        int payloadLength = 0;
        for (byte[] packet : packets) {
            payloadLength += Math.max(0, packet.length - 4);
        }
        byte[] combined = new byte[payloadLength];
        int offset = 0;
        for (byte[] packet : packets) {
            if (packet.length > 4) {
                System.arraycopy(packet, 4, combined, offset, packet.length - 4);
                offset += packet.length - 4;
            }
        }
        List<VeepooAlarm> list = new ArrayList<>();
        // Records are A1-framed right after the first packet's A0 status section.
        int i = combined.length >= 2 ? (combined[1] & 0xFF) + 2 : 0;
        while (i + 3 <= combined.length && (combined[i] & 0xFF) == 0xA1) {
            int recordLength = combined[i + 1] & 0xFF;
            if (recordLength < 12 || i + 2 + recordLength > combined.length) {
                break;
            }
            VeepooAlarm alarm = parseAlarmRecord(combined, i + 2);
            if (alarm == null) {
                break;
            }
            list.add(alarm);
            i += 2 + recordLength;
        }
        synchronized (alarmLock) {
            alarmList.clear();
            alarmList.addAll(list);
            alarmCrc = computeAlarmCrc(alarmList);
        }
        LOG.info("Read {} alarm(s) from watch: {}", list.size(), describeAlarms(list));
    }

    /** Parses one alarm record starting at the 0xB1 marker byte. */
    private static VeepooAlarm parseAlarmRecord(byte[] buffer, int off) {
        if ((buffer[off] & 0xFF) != 0xB1) {
            return null;
        }
        VeepooAlarm alarm = new VeepooAlarm();
        alarm.id = buffer[off + 2] & 0xFF;
        alarm.open = (buffer[off + 3] & 0xFF) != 0;
        alarm.repeatInt = buffer[off + 4] & 0xFF;
        alarm.hour = buffer[off + 5] & 0xFF;
        alarm.minute = buffer[off + 6] & 0xFF;
        alarm.yearOffset = buffer[off + 7] & 0xFF;
        alarm.month = buffer[off + 8] & 0xFF;
        alarm.day = buffer[off + 9] & 0xFF;
        if ((buffer[off + 10] & 0xFF) == 0xB2) {
            int textLength = buffer[off + 11] & 0xFF;
            if (off + 12 + textLength <= buffer.length) {
                alarm.label = new String(buffer, off + 12, textLength, StandardCharsets.UTF_8);
            }
        }
        return alarm;
    }

    private void removeAlarmById(int id) {
        synchronized (alarmLock) {
            alarmList.removeIf(alarm -> alarm.id == id);
        }
        LOG.info("Band deleted alarm {} from its own UI", id);
    }

    private void setAlarmOpen(int id, boolean open) {
        synchronized (alarmLock) {
            for (VeepooAlarm alarm : alarmList) {
                if (alarm.id == id) {
                    alarm.open = open;
                }
            }
        }
        LOG.info("Band {} alarm {} from its own UI", open ? "enabled" : "disabled", id);
    }

    /** Writes an alarm frame (up to MTU-3 bytes, unlike the fixed 20-byte writeFrame). */
    private void writeAlarmFrame(TransactionBuilder builder, byte[] frame) {
        if (frame.length > VeepooConstants.MTU - 3) {
            throw new IllegalArgumentException("alarm frames must fit the negotiated MTU");
        }
        builder.write(support.getCharacteristic(VeepooConstants.UUID_CHARACTERISTIC_F008_CMD), frame);
    }

    private static String describeAlarms(List<VeepooAlarm> alarms) {
        StringBuilder sb = new StringBuilder();
        for (VeepooAlarm alarm : alarms) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('#').append(alarm.id).append(' ')
                    .append(String.format("%02d:%02d", alarm.hour, alarm.minute));
            if (alarm.repeatInt == 0) {
                sb.append(String.format(" once %04d-%02d-%02d",
                        alarm.yearOffset + 2000, alarm.month, alarm.day));
            } else {
                sb.append(String.format(" weekly(0x%02X)", alarm.repeatInt));
            }
            if (!alarm.open) {
                sb.append(" off");
            }
            if (!alarm.label.isEmpty()) {
                sb.append(" '").append(alarm.label).append('\'');
            }
        }
        return sb.toString();
    }

    /** Truncates to at most {@code maxBytes} UTF-8 bytes without splitting a multibyte character. */
    static String truncateUtf8(String value, int maxBytes) {
        if (value == null) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    void dispose() {
        support.mainHandler.removeCallbacks(alarmResync);
        support.mainHandler.removeCallbacks(alarmReadWatchdog);
    }
}
