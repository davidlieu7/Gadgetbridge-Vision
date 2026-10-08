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

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Phone alarms mirrored to the band on {@code CH_PHONE_ALARM} (0x0d). The
 * band's Alarm app lists the phone's alarms
 * and shows the alert when one rings. This is a different service from the
 * band's own alarms on 0x11, which the Fit3 rejects.
 *
 * <p>Fixed format, no param-count byte. Ints are 4 bytes and longs 8 bytes,
 * little-endian.
 *
 * <pre>
 *   phone -&gt; band
 *   08 14 [installed:1]                                   the phone has a clock app
 *   03 12 [enabled:1]                                     alarm alerts go to the band
 *   04 11 [count:1] { 01 [id:4] 03 [hhmm:4] 04 [active:1] 06 [next ring, ms:8] 10 [repeat:4] 15 [bedtime:4] }*
 *   05 ...same as 04...                                   add or update some alarms
 *   06 16 [len:1] [id:4]*                                 delete alarms
 *   00 01 [id:4] 02 [len:1] name 03 [hhmm:4] 05 [snooze:1] 06 [ring:8] 07 [snooze min:4]
 *      08 [snooze repeat:1] 09 [snooze count:4] 0a [now:8]  an alarm is ringing
 *   01                                                    it stopped ringing; the band ignores
 *                                                         a new alert until the last one is closed
 *   band -&gt; phone
 *   01 13 [snoozed:1]         snoozed (1) or dismissed (0) on the band
 *   04                        send the list again
 *   05 01 [id:4] 04 [on:1]    switched on or off on the band
 *   06 01 [id:4]              deleted on the band
 *   07 01 [id:4]              edit on the phone (-1: add)
 * </pre>
 *
 * <p>Two fields carry the phone clock app's own encodings. The time is
 * {@code hour * 100 + minute}. The repeat field has one nibble per weekday,
 * Sunday highest ({@code 0x10000000}) down to Saturday ({@code 0x10}), and a
 * low nibble of 1 for a one-off alarm or 4 for a weekly one.
 *
 * <p>Confidence: confirmed working against a live band from Gadgetbridge's
 * own logs.
 */
public final class Fit3PhoneAlarm {

    public static final int CHANNEL = SapProtocol.CH_PHONE_ALARM;

    /** Developer switch for the whole feature, registered by the settings customizer. */
    public static final String PREF_ENABLE = "samsung_fit3_phone_alarms";

    /** Alarm slots offered while the feature is on. */
    public static final int SLOT_COUNT = 10;

    public static final int MSG_ID_ALERT_START = 0;
    public static final int MSG_ID_ALERT_ACTION = 1;
    public static final int MSG_ID_NOTIFICATION_SETTING = 3;
    public static final int MSG_ID_SYNC_ALL = 4;
    public static final int MSG_ID_ADD_UPDATE = 5;
    public static final int MSG_ID_DELETE = 6;
    public static final int MSG_ID_EDIT_ON_PHONE = 7;
    public static final int MSG_ID_CLOCK_APP = 8;

    private static final int PARAM_ID = 1;
    private static final int PARAM_NAME = 2;
    private static final int PARAM_TIME = 3;
    private static final int PARAM_ACTIVE = 4;
    private static final int PARAM_SNOOZE = 5;
    private static final int PARAM_RING_TIME = 6;
    private static final int PARAM_SNOOZE_MINUTES = 7;
    private static final int PARAM_SNOOZE_REPEAT = 8;
    private static final int PARAM_SNOOZE_COUNT = 9;
    private static final int PARAM_NOW = 10;
    private static final int PARAM_REPEAT = 16;
    private static final int PARAM_COUNT = 17;
    private static final int PARAM_NOTIFICATION_ENABLED = 18;
    /** 1 = snoozed: a snooze on a live band sent {@code 01 13 01}. */
    private static final int PARAM_SNOOZED = 19;
    private static final int PARAM_INSTALLED = 20;
    private static final int PARAM_BEDTIME = 21;
    private static final int PARAM_ID_LIST = 22;

    private static final int REPEAT_ONCE = 0x1;
    private static final int REPEAT_WEEKLY = 0x4;
    /** Not a bedtime alarm. */
    private static final int NO_BEDTIME = -1;

    private static final int ENTRY_LENGTH = 5 + 5 + 2 + 9 + 5 + 5;

    /** The band's id for a Gadgetbridge alarm slot. Clock ids start at 1. */
    public static int idFor(Alarm alarm) {
        return alarm.getPosition() + 1;
    }

    /** Whether the phone has a clock app. Wire: {@code 08 14 xx}. */
    public static byte[] buildClockApp(boolean installed) {
        return new byte[]{MSG_ID_CLOCK_APP, PARAM_INSTALLED, (byte) (installed ? 1 : 0)};
    }

    /** Whether alarm alerts are forwarded to the band. Wire: {@code 03 12 xx}. */
    public static byte[] buildNotificationSetting(boolean enabled) {
        return new byte[]{MSG_ID_NOTIFICATION_SETTING, PARAM_NOTIFICATION_ENABLED, (byte) (enabled ? 1 : 0)};
    }

    /** The whole alarm list, replacing whatever the band holds. Unused slots are left out. */
    public static byte[] buildSyncAll(List<? extends Alarm> alarms, ZonedDateTime now) {
        List<Alarm> used = new ArrayList<>();
        for (Alarm a : alarms) {
            if (!a.getUnused()) {
                used.add(a);
            }
        }
        return buildList(MSG_ID_SYNC_ALL, used, now);
    }

    private static byte[] buildList(int msgId, List<? extends Alarm> alarms, ZonedDateTime now) {
        ByteBuffer b = le(3 + ENTRY_LENGTH * alarms.size());
        b.put((byte) msgId).put((byte) PARAM_COUNT).put((byte) alarms.size());
        for (Alarm a : alarms) {
            b.put((byte) PARAM_ID).putInt(idFor(a));
            b.put((byte) PARAM_TIME).putInt(clockTime(a.getHour(), a.getMinute()));
            b.put((byte) PARAM_ACTIVE).put((byte) (a.getEnabled() ? 1 : 0));
            b.put((byte) PARAM_RING_TIME).putLong(nextRing(a, now).toInstant().toEpochMilli());
            b.put((byte) PARAM_REPEAT).putInt(repeatType(a, now));
            b.put((byte) PARAM_BEDTIME).putInt(NO_BEDTIME);
        }
        return b.array();
    }

    /** Remove alarms by id. Wire: {@code 06 16 [len:1] [id:4]*}. */
    public static byte[] buildDelete(List<Integer> ids) {
        ByteBuffer b = le(3 + 4 * ids.size());
        b.put((byte) MSG_ID_DELETE).put((byte) PARAM_ID_LIST).put((byte) (4 * ids.size()));
        for (int id : ids) {
            b.putInt(id);
        }
        return b.array();
    }

    /** The alarm is ringing now: the band shows it until dismissed, snoozed or stopped. */
    public static byte[] buildAlertStart(Alarm alarm, String name, int snoozeMinutes, ZonedDateTime now) {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        int nameLength = Math.min(utf8.length, 255);
        ByteBuffer b = le(1 + 5 + 2 + nameLength + 5 + 2 + 9 + 5 + 2 + 5 + 9);
        b.put((byte) MSG_ID_ALERT_START);
        b.put((byte) PARAM_ID).putInt(idFor(alarm));
        b.put((byte) PARAM_NAME).put((byte) nameLength).put(utf8, 0, nameLength);
        b.put((byte) PARAM_TIME).putInt(clockTime(alarm.getHour(), alarm.getMinute()));
        b.put((byte) PARAM_SNOOZE).put((byte) (alarm.getSnooze() ? 1 : 0));
        b.put((byte) PARAM_RING_TIME).putLong(now.toInstant().toEpochMilli());
        b.put((byte) PARAM_SNOOZE_MINUTES).putInt(snoozeMinutes);
        b.put((byte) PARAM_SNOOZE_REPEAT).put((byte) 0);
        b.put((byte) PARAM_SNOOZE_COUNT).putInt(0);
        b.put((byte) PARAM_NOW).putLong(now.toInstant().toEpochMilli());
        return b.array();
    }

    /** The alarm stopped ringing. Wire: {@code 01}. */
    public static byte[] buildAlertStop() {
        return new byte[]{MSG_ID_ALERT_ACTION};
    }

    static int clockTime(int hour, int minute) {
        return hour * 100 + minute;
    }

    /** The clock app's repeat encoding, see the class comment. */
    static int repeatType(Alarm alarm, ZonedDateTime now) {
        if (!alarm.isRepetitive()) {
            return dayNibble(nextRing(alarm, now).getDayOfWeek()) | REPEAT_ONCE;
        }
        int days = 0;
        for (DayOfWeek d : DayOfWeek.values()) {
            if (alarm.getRepetition(gbDay(d))) {
                days |= dayNibble(d);
            }
        }
        return days | REPEAT_WEEKLY;
    }

    private static int dayNibble(DayOfWeek day) {
        // Sunday 0x10000000, Monday 0x01000000 ... Saturday 0x00000010.
        int fromSunday = day.getValue() % 7;
        return 0x10000000 >>> (4 * fromSunday);
    }

    private static int gbDay(DayOfWeek day) {
        return 1 << (day.getValue() - 1);  // Alarm.ALARM_MON = 1 ... ALARM_SUN = 64
    }

    /** When the alarm next rings, from {@code now}. */
    public static ZonedDateTime nextRing(Alarm alarm, ZonedDateTime now) {
        ZoneId zone = now.getZone();
        LocalDateTime today = now.toLocalDate().atTime(alarm.getHour(), alarm.getMinute());
        for (int i = 0; i < 8; i++) {
            ZonedDateTime candidate = today.plusDays(i).atZone(zone);
            if (!candidate.isAfter(now)) {
                continue;
            }
            if (!alarm.isRepetitive() || alarm.getRepetition(gbDay(candidate.getDayOfWeek()))) {
                return candidate;
            }
        }
        return today.plusDays(1).atZone(zone);
    }

    // ---- incoming ----

    public final int msgId;
    /** The alarm id, or {@code null} if the message has none. */
    @Nullable
    public final Integer id;
    /** Active (05) or snoozed (01), or {@code null} if absent. */
    @Nullable
    public final Boolean flag;

    private Fit3PhoneAlarm(int msgId, @Nullable Integer id, @Nullable Boolean flag) {
        this.msgId = msgId;
        this.id = id;
        this.flag = flag;
    }

    /** Decode a band request; {@code null} for anything this doesn't know. */
    @Nullable
    public static Fit3PhoneAlarm parse(byte[] body) {
        if (body == null || body.length < 1) {
            return null;
        }
        int msgId = body[0] & 0x3f;
        ByteBuffer b = ByteBuffer.wrap(body, 1, body.length - 1).order(ByteOrder.LITTLE_ENDIAN);
        Integer id = null;
        Boolean flag = null;
        switch (msgId) {
            case MSG_ID_SYNC_ALL:
                break;
            case MSG_ID_ALERT_ACTION:
                if (b.remaining() >= 2 && b.get() == PARAM_SNOOZED) {
                    flag = b.get() != 0;
                }
                break;
            case MSG_ID_ADD_UPDATE:
            case MSG_ID_DELETE:
            case MSG_ID_EDIT_ON_PHONE:
                // The vendor parser's table hints at a count byte on 05; skip
                // one if the first byte isn't a param we know.
                if (b.remaining() > 0 && b.get(b.position()) != PARAM_ID && b.get(b.position()) != PARAM_ACTIVE) {
                    b.get();
                }
                while (b.remaining() >= 2) {
                    int param = b.get() & 0xff;
                    if (param == PARAM_ID && b.remaining() >= 4) {
                        id = b.getInt();
                    } else if (param == PARAM_ACTIVE) {
                        flag = b.get() != 0;
                    } else {
                        break;
                    }
                }
                break;
            default:
                return null;
        }
        return new Fit3PhoneAlarm(msgId, id, flag);
    }

    @Override
    public String toString() {
        return "Fit3PhoneAlarm{msgId=" + msgId + ", id=" + id + ", flag=" + flag + "}";
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
