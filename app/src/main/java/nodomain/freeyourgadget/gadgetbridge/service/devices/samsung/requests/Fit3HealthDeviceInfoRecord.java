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

/**
 * The device-info record (outer marker {@code 0x05}) that both sides of
 * {@code CH_HEALTH} (0x0a) append to almost every message they send. Its
 * {@code last_sync_time} tag is the channel's flow control: it is how the
 * phone tells the band "everything up to this instant has been consumed,
 * don't send it again".
 *
 * <p>This is the piece whose absence stalls a sync. The band re-sends an
 * object it believes the phone has not taken - roughly every two minutes,
 * about four times - and then stops syncing altogether for the rest of the
 * connection, which is why a reconnect used to be the only way to get new
 * data. Confirmed from a capture where four consecutive 15872-byte pushes
 * were byte-identical apart from the SAP sequence number and this record's
 * own timestamps.
 *
 * <p>Both directions use the same 25-byte body with fixed field widths and a
 * fixed tag order, so it is read and written positionally:
 *
 * <pre>
 *   [0x05][0x19]                 marker + body length (always 25)
 *     [0x01][ device:1 ]
 *     [0x02][ device_type:2 ]
 *     [0x03][ version:2 ]
 *     [0x04][ last_sync_time:4 LE ]
 *     [0x05][ start_time:4 LE ]
 *     [0x06][ end_time:4 LE ]
 *     [0x07][ is_last_chunk:1 ]
 * </pre>
 *
 * <p>All three timestamps are seconds since 1990-01-01T00:00:00Z (see
 * {@link Fit3HealthPedometerRecord#EPOCH_1990_OFFSET_SECONDS}). In the band's
 * own records {@code start_time}/{@code end_time} both carry its current
 * clock, and {@code last_sync_time} echoes back whatever the phone last
 * claimed - which is how {@link #findTrailingLastSync} can be used to verify
 * the band accepted a watermark.
 */
public final class Fit3HealthDeviceInfoRecord {

    public static final int MARKER = 0x05;

    /** Body length, fixed by the schema. */
    private static final int BODY_LEN = 0x19;
    /** Marker + length byte + body. */
    public static final int TOTAL_LEN = 2 + BODY_LEN;

    // Byte offsets of each tag inside the body, in the order they always appear.
    private static final int OFF_DEVICE = 0;
    private static final int OFF_DEVICE_TYPE = 2;
    private static final int OFF_VERSION = 5;
    private static final int OFF_LAST_SYNC = 8;
    private static final int OFF_START = 13;
    private static final int OFF_END = 18;
    private static final int OFF_IS_LAST_CHUNK = 23;

    private static final int TAG_DEVICE = 0x01;
    private static final int TAG_DEVICE_TYPE = 0x02;
    private static final int TAG_VERSION = 0x03;
    private static final int TAG_LAST_SYNC = 0x04;
    private static final int TAG_START = 0x05;
    private static final int TAG_END = 0x06;
    private static final int TAG_IS_LAST_CHUNK = 0x07;

    private Fit3HealthDeviceInfoRecord() {
    }

    /**
     * Build the phone's own device-info record. The {@code device} /
     * {@code device_type} / {@code version} fields are replayed from a live
     * vendor capture - the band does not appear to act on them, but every
     * phone-sent copy in the captures carries exactly these values.
     *
     * @param lastSyncTime seconds since the 1990 epoch of the newest data the
     *     phone has taken; 0 means "I have nothing, send everything".
     * @param startTime range start, or 0. Every phone-sent ack in the captures
     *     leaves this and {@code endTime} zero and puts the real value in
     *     {@code lastSyncTime}; only the phone's own periodic status push
     *     fills them in.
     * @param endTime range end, or 0.
     */
    public static byte[] build(int lastSyncTime, int startTime, int endTime) {
        return new byte[]{
                (byte) MARKER, (byte) BODY_LEN,
                TAG_DEVICE, 0x00,
                TAG_DEVICE_TYPE, 0x00, 0x00,
                TAG_VERSION, 0x04, 0x33,
                TAG_LAST_SYNC,
                (byte) lastSyncTime, (byte) (lastSyncTime >> 8),
                (byte) (lastSyncTime >> 16), (byte) (lastSyncTime >> 24),
                TAG_START,
                (byte) startTime, (byte) (startTime >> 8),
                (byte) (startTime >> 16), (byte) (startTime >> 24),
                TAG_END,
                (byte) endTime, (byte) (endTime >> 8),
                (byte) (endTime >> 16), (byte) (endTime >> 24),
                TAG_IS_LAST_CHUNK, 0x01,
        };
    }

    /**
     * Read the band's clock out of the device-info record the band appends to
     * the end of every message it sends, which is the value the phone should
     * claim as its new watermark once it has consumed that message.
     *
     * <p>Takes the record from the end of the payload rather than scanning for
     * a {@code 0x05} byte: the trailing copy is the one describing this
     * message, and a scan would happily match a {@code 0x05} inside the
     * records in front of it.
     *
     * @return {@code end_time} in seconds since the 1990 epoch, or {@code -1}
     *     if the payload doesn't end in a well-formed device-info record.
     */
    public static int findTrailingBandTime(byte[] payload) {
        int body = trailingBodyOffset(payload);
        if (body < 0) {
            return -1;
        }
        return readU32(payload, body + OFF_END + 1);
    }

    /**
     * The {@code last_sync_time} of the trailing device-info record - in a
     * band-sent message, the watermark the band currently believes the phone
     * is at. Useful for confirming from a log that an ack was accepted.
     *
     * @return the value, or {@code -1} if there is no trailing record.
     */
    public static int findTrailingLastSync(byte[] payload) {
        int body = trailingBodyOffset(payload);
        if (body < 0) {
            return -1;
        }
        return readU32(payload, body + OFF_LAST_SYNC + 1);
    }

    /** @return offset of the trailing record's body, or -1 if it isn't there. */
    private static int trailingBodyOffset(byte[] payload) {
        if (payload == null || payload.length < TOTAL_LEN) {
            return -1;
        }
        int start = payload.length - TOTAL_LEN;
        if ((payload[start] & 0xFF) != MARKER || (payload[start + 1] & 0xFF) != BODY_LEN) {
            return -1;
        }
        int body = start + 2;
        // The tag order is fixed; checking it rejects a payload that merely
        // happens to end in the right two bytes.
        return (payload[body + OFF_DEVICE] & 0xFF) == TAG_DEVICE
                && (payload[body + OFF_DEVICE_TYPE] & 0xFF) == TAG_DEVICE_TYPE
                && (payload[body + OFF_VERSION] & 0xFF) == TAG_VERSION
                && (payload[body + OFF_LAST_SYNC] & 0xFF) == TAG_LAST_SYNC
                && (payload[body + OFF_START] & 0xFF) == TAG_START
                && (payload[body + OFF_END] & 0xFF) == TAG_END
                && (payload[body + OFF_IS_LAST_CHUNK] & 0xFF) == TAG_IS_LAST_CHUNK
                ? body : -1;
    }

    private static int readU32(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16) | ((data[off + 3] & 0xFF) << 24);
    }
}
