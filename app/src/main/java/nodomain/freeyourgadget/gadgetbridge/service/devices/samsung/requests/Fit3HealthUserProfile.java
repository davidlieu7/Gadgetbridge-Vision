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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * The user's profile - gender, birth date, height, weight, unit preferences -
 * pushed to the band as a {@code SYNC_DATA} (MSG_ID 2) on {@code CH_HEALTH}
 * (0x0a).
 *
 * <p>This is a <b>phone-to-band</b> message: the same MSG_ID the band uses to
 * push records at us ({@link Fit3HealthSyncData}), just in the other
 * direction, with the same {@code 0x82} header and record-count byte. The
 * band never sends a profile back - it was never observed doing so in either
 * direction of an edit - so the phone owns this data outright and the band
 * only ever receives it.
 *
 * <p>The profile is spread over three record types that must be sent
 * together, because each encodes its part differently:
 *
 * <pre>
 *   82 [record count] e0 [SEQUENCE_NUMBER:2 LE]
 *      05 [len:1] ...device info...
 *      0b [len:4 LE] ...gender, units, birth date...
 *      1c 05 [ts:4] a3 [height cm:FLOAT32] 97 [ver:4] 99 [ver:4]
 *      1d 05 [ts:4] a4 [weight kg:FLOAT32] 97 [ver:4] 99 [ver:4]
 * </pre>
 *
 * <p>Note the three different framings in there - {@code 0x0b} is
 * 4-byte-length-prefixed with <em>no</em> flag byte (the same exception
 * {@link Fit3HealthRecordType#MARKER_APP_PREFERENCES} makes), while
 * {@code 0x1c}/{@code 0x1d} carry no length at all and are a fixed 21-byte
 * field list. Height/weight live only in those two records; the
 * {@code 0x0b} block has slots for them that are never used.
 *
 * <p>What the real app sends is a <b>delta</b> - only the records whose
 * fields the user just changed, and inside {@code 0x0b} only the changed
 * sub-keys. This builds the full set every time instead: it is the shape the
 * initial profile sync uses (and so is equally wire-confirmed), and
 * Gadgetbridge has no per-field change tracking to build a delta from.
 *
 * <p>Field values are confirmed against a capture in which height, weight and
 * gender were edited seven times in a known order, each edit isolating its
 * own record. The row-version counters are the exception - see
 * {@link #ROW_VERSION}.
 */
public final class Fit3HealthUserProfile {

    public static final int CHANNEL = SapProtocol.CH_HEALTH;
    public static final int MSG_ID = Fit3HealthSyncData.MSG_ID;

    private static final int REQ_HEADER = 0x82;
    private static final int PARAM_SEQUENCE_NUMBER = 0xe0;

    private static final int MARKER_DEVICE_INFO = 0x05;
    private static final int MARKER_USER_PROFILE = 0x0b;
    private static final int MARKER_HEIGHT = 0x1c;
    private static final int MARKER_WEIGHT = 0x1d;
    private static final int RECORD_COUNT = 5; // sequence number + the four records above

    // Tags shared by the profile records.
    private static final int TAG_UPDATE_TIME = 0x05;
    private static final int TAG_CREATE_VERSION = 0x97;
    private static final int TAG_MODIFY_VERSION = 0x99;
    private static final int TAG_KEY_BLOCK = 0xa1;
    private static final int TAG_HEIGHT_VALUE = 0xa3;
    private static final int TAG_WEIGHT_VALUE = 0xa4;

    // Sub-keys of the 0xa1 block, in the order the initial sync sends them.
    private static final int KEY_GENDER = 1;
    private static final int KEY_WEIGHT_UNIT = 3;
    private static final int KEY_HEIGHT_UNIT = 5;
    private static final int KEY_BIRTH_DATE = 6;
    private static final int KEY_DISTANCE_UNIT = 7;
    private static final int KEY_TEMPERATURE_UNIT = 8;
    private static final int KEY_BLOOD_PRESSURE_UNIT = 10;
    private static final int KEY_WATER_UNIT = 9;
    /** Those eight sub-keys, tags included: 7 one-byte values plus the 4-byte date. */
    private static final int KEY_BLOCK_LEN = 19;
    /** Header, record count, sequence number, and the four records below. */
    private static final int MESSAGE_LEN = 118;

    /** Gender as the band wants it: 1 = male, 2 = female, no third value. */
    private static final int GENDER_MALE = 1;
    private static final int GENDER_FEMALE = 2;

    /** Unit sub-keys are uniformly 1 = metric, 2 = imperial. */
    private static final int UNIT_METRIC = 1;
    private static final int UNIT_IMPERIAL = 2;
    /** Except blood pressure, where 1 = mmHg and 2 = kPa - neither is "imperial". */
    private static final int BLOOD_PRESSURE_MMHG = 1;

    /**
     * The created/modified row-version counters that ride along with every
     * profile record. They are bookkeeping for the health-database rows on the
     * sending side, not anything derived from the profile, and this value is
     * replayed from a capture rather than computed - nothing observable
     * depends on it, and a plausible constant is a safer guess than zero for a
     * field the band may range-check. One value covers both tags: an edit
     * bumps only the modified counter, but a record written whole (which is
     * all this builds) carries the same number in both, as the initial profile
     * sync does.
     */
    private static final int ROW_VERSION = 0x042c21ae;

    private Fit3HealthUserProfile() {
    }

    /**
     * @param sequenceNumber the phone's own message counter; the band's ack
     *     echoes it back (see {@link Fit3HealthSyncData}).
     * @param updateTime seconds since the channel's 1990 epoch (see
     *     {@link Fit3HealthPedometerRecord#EPOCH_1990_OFFSET_SECONDS}) to
     *     stamp the records with - normally now.
     * @param gender {@link ActivityUser#GENDER_FEMALE} or one of its
     *     siblings. The wire has only male and female, and the app picks
     *     female only on an exact match, so anything else goes out as male.
     * @param dateOfBirth used for the birth-date sub-key. The band is told the
     *     date only here, once; what it gets on every later sync is the
     *     derived age, in a record this doesn't build.
     * @param heightCm centimetres, always - the height <em>unit</em> sub-key
     *     only picks the band's display unit, it doesn't change this field.
     * @param weightKg kilograms, likewise.
     * @param metric false to tell the band to display miles/pounds/inches/
     *     Fahrenheit/fluid ounces.
     */
    public static byte[] buildRequest(int sequenceNumber, int updateTime, int gender,
                                      LocalDate dateOfBirth, float heightCm, float weightKg,
                                      boolean metric) {
        boolean female = gender == ActivityUser.GENDER_FEMALE;
        int unit = metric ? UNIT_METRIC : UNIT_IMPERIAL;

        ByteBuffer keys = ByteBuffer.allocate(KEY_BLOCK_LEN).order(ByteOrder.LITTLE_ENDIAN);
        keys.put((byte) KEY_GENDER).put((byte) (female ? GENDER_FEMALE : GENDER_MALE));
        keys.put((byte) KEY_WEIGHT_UNIT).put((byte) unit);
        keys.put((byte) KEY_HEIGHT_UNIT).put((byte) unit);
        keys.put((byte) KEY_BIRTH_DATE)
                .put((byte) dateOfBirth.getDayOfMonth())
                .put((byte) dateOfBirth.getMonthValue())
                .putShort((short) dateOfBirth.getYear());
        keys.put((byte) KEY_DISTANCE_UNIT).put((byte) unit);
        keys.put((byte) KEY_TEMPERATURE_UNIT).put((byte) unit);
        keys.put((byte) KEY_BLOOD_PRESSURE_UNIT).put((byte) BLOOD_PRESSURE_MMHG);
        keys.put((byte) KEY_WATER_UNIT).put((byte) unit);

        ByteBuffer buf = ByteBuffer.allocate(MESSAGE_LEN).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) REQ_HEADER).put((byte) RECORD_COUNT);
        buf.put((byte) PARAM_SEQUENCE_NUMBER).putShort((short) sequenceNumber);
        putDeviceInfo(buf, updateTime);

        // 0x0b USER_PROFILE: [marker][length:4 LE][body], no flag byte.
        buf.put((byte) MARKER_USER_PROFILE).putInt(5 + 5 + 5 + 5 + KEY_BLOCK_LEN);
        buf.put((byte) TAG_UPDATE_TIME).putInt(updateTime);
        buf.put((byte) TAG_CREATE_VERSION).putInt(ROW_VERSION);
        buf.put((byte) TAG_MODIFY_VERSION).putInt(ROW_VERSION);
        buf.put((byte) TAG_KEY_BLOCK).putInt(KEY_BLOCK_LEN);
        buf.put(keys.array());

        putMeasurement(buf, MARKER_HEIGHT, TAG_HEIGHT_VALUE, heightCm, updateTime);
        putMeasurement(buf, MARKER_WEIGHT, TAG_WEIGHT_VALUE, weightKg, updateTime);
        return buf.array();
    }

    /**
     * The {@code 0x05} device-info record every push on this channel leads
     * with - same 25-byte field list as the one in
     * {@link Fit3HealthRequestData#buildRequest}, where its three timestamps
     * describe the range being synced. In a profile push there is no range;
     * the app fills them with the current sync window, so they get the push's
     * own timestamp here.
     */
    private static void putDeviceInfo(ByteBuffer buf, int updateTime) {
        buf.put((byte) MARKER_DEVICE_INFO).put((byte) 25);
        buf.put(new byte[]{
                0x01, 0x00,       // device (value ignored by the parser)
                0x02, 0x00, 0x00, // device type
                0x03, 0x04, 0x33, // version (4.51, replayed from the capture)
        });
        buf.put((byte) 0x04).putInt(updateTime); // last_sync_time
        buf.put((byte) 0x05).putInt(updateTime); // start_time
        buf.put((byte) 0x06).putInt(updateTime); // end_time
        buf.put((byte) 0x07).put((byte) 0x01);   // is_last_chunk
    }

    /** One of the two 21-byte height/weight records - no length prefix, fixed fields. */
    private static void putMeasurement(ByteBuffer buf, int marker, int valueTag, float value,
                                       int updateTime) {
        buf.put((byte) marker);
        buf.put((byte) TAG_UPDATE_TIME).putInt(updateTime);
        buf.put((byte) valueTag).putFloat(value);
        buf.put((byte) TAG_CREATE_VERSION).putInt(ROW_VERSION);
        buf.put((byte) TAG_MODIFY_VERSION).putInt(ROW_VERSION);
    }
}
