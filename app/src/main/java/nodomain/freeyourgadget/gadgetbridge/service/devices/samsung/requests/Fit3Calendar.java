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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Calendar and calendar settings on {@code CH_TIME_CAL} (0x08). This channel
 * has its own encoding: header byte, then {@code [id][value]} pairs with a
 * count byte only on the list/detail messages.
 *
 * <p>The band drives it. On connect it asks for the calendar settings
 * (MSG_ID 12), holidays (13) and any events changed since its last sync (7,
 * carrying that sync time); later it asks for one event's details (9). The
 * phone can prompt a new sync with an empty MSG_ID 7 when its calendar
 * changes. The sync time the phone sends back with a list is the one the
 * band repeats next time, so it doubles as a version stamp.
 *
 * <pre>
 *   settings  P-&gt;B: 4c 27 [first day] 09 [tz] 28 [len][tz name]
 *   holidays  P-&gt;B: cd 24 [count:4 LE]                     (always none here)
 *   list      P-&gt;B: c8 06 21 [seq] 22 [pages] 23 [page] 10 [n] 09 [tz] 11 [stamp:8]
 *                   then per event: 01 [index] 12 [id:8] 02 [has colour] 03 [colour:4]
 *                   04 [title] 05 [type] 06 [start:8] 07 [tz] 08 [end:8] 0e [declined]
 *   detail    P-&gt;B: c9 10 12 [id:8] ... 0e [declined] 13 [description] 14 [location]
 *                   15 [tz name] 16 [calendar] 17 [has reminders] 18 [n] 19 n x [minutes:4]
 *   no data   P-&gt;B: 43                                     (answer to "what's on now")
 *   poke      P-&gt;B: 07
 *   [tz] = [sign: 0 +, 1 -][|offset| seconds:2 LE]; times are epoch seconds,
 *   the stamp is epoch milliseconds; strings are [len:1][utf8], at most 100 bytes.
 * </pre>
 *
 * Every shape here is confirmed against a vendor capture syncing a known
 * test event, list and detail included.
 */
public final class Fit3Calendar {

    public static final int CHANNEL = SapProtocol.CH_TIME_CAL;

    public static final int MSG_ID_NOW = 0x00;
    public static final int MSG_ID_UPCOMING = 0x01;
    public static final int MSG_ID_NO_DATA = 0x03;
    public static final int MSG_ID_SYNC = 0x07;
    public static final int MSG_ID_EVENT_LIST = 0x08;
    public static final int MSG_ID_EVENT_DETAIL = 0x09;
    public static final int MSG_ID_SETTINGS = 0x0c;
    public static final int MSG_ID_HOLIDAYS = 0x0d;
    public static final int MSG_ID_SYNC_WITH_DETAIL = 0x0e;

    private static final int P_INDEX = 0x01;
    private static final int P_HAS_COLOR = 0x02;
    private static final int P_COLOR = 0x03;
    private static final int P_TITLE = 0x04;
    private static final int P_TYPE = 0x05;
    private static final int P_START = 0x06;
    private static final int P_TZ = 0x07;
    private static final int P_END = 0x08;
    private static final int P_LOCK_TZ = 0x09;
    private static final int P_DECLINED = 0x0e;
    private static final int P_EVENTS_IN_MSG = 0x10;
    private static final int P_LAST_SYNC = 0x11;
    private static final int P_EVENT_ID = 0x12;
    private static final int P_DESCRIPTION = 0x13;
    private static final int P_LOCATION = 0x14;
    private static final int P_TZ_NAME = 0x15;
    private static final int P_CALENDAR_NAME = 0x16;
    private static final int P_HAS_REMINDER = 0x17;
    private static final int P_REMINDER_COUNT = 0x18;
    private static final int P_REMINDERS = 0x19;
    private static final int P_PACKET_SEQ = 0x21;
    private static final int P_PACKET_TOTAL = 0x22;
    private static final int P_PACKET_INDEX = 0x23;
    private static final int P_HOLIDAY_COUNT = 0x24;
    private static final int P_FIRST_DAY = 0x27;
    private static final int P_LOCK_TZ_NAME = 0x28;

    public static final int TYPE_SINGLE_DAY = 0;
    public static final int TYPE_ALL_DAY = 1;
    public static final int TYPE_STARTS_TODAY = 2;
    public static final int TYPE_ENDS_TODAY = 3;
    public static final int TYPE_SPANS_TODAY = 4;

    private static final int MAX_STRING = 100;
    /** Message budget the band accepts, and the per-message / per-event estimates used to fill it. */
    private static final int MESSAGE_BUDGET = 980;
    private static final int MESSAGE_OVERHEAD = 30;
    private static final int EVENT_OVERHEAD = 46;
    private static final int MAX_EVENTS_PER_MESSAGE = 10;
    private static final int MAX_EVENTS = 70;

    private Fit3Calendar() {
    }

    /** One event, as far as the band cares. */
    public static final class Event {
        public long id;
        public String title = "";
        public long beginMs;
        public long endMs;
        public boolean allDay;
        public int color;
        public boolean declined;
        public String description = "";
        public String location = "";
        public String calendarName = "";
        public TimeZone timeZone = TimeZone.getDefault();
        public List<Integer> reminderMinutes = Collections.emptyList();

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "Event{%d, %s, %d-%d}", id, title, beginMs, endMs);
        }
    }

    /** A request from the band. */
    public static final class Request {
        public final int msgId;
        /** MSG_ID 7/14: the stamp of the band's last sync, in ms; 0 = never. */
        public final long lastSyncMs;
        /** MSG_ID 9: which event. */
        public final long eventId;
        public final long eventStartMs;

        Request(int msgId, long lastSyncMs, long eventId, long eventStartMs) {
            this.msgId = msgId;
            this.lastSyncMs = lastSyncMs;
            this.eventId = eventId;
            this.eventStartMs = eventStartMs;
        }
    }

    /**
     * @return the band's request, or {@code null} if the body is too short for
     *         what its MSG_ID needs. Values are read from fixed offsets, as the
     *         band's own peer does.
     */
    @Nullable
    public static Request parseRequest(byte[] body) {
        if (body == null || body.length < 1) {
            return null;
        }
        int msgId = body[0] & 0x3f;
        switch (msgId) {
            case MSG_ID_SYNC:
            case MSG_ID_SYNC_WITH_DETAIL:
                // 07 11 [stamp:8]; a bare 07 is the phone's own poke and never arrives here.
                return new Request(msgId, body.length >= 10 ? toMs(le64(body, 2)) : 0, 0, 0);
            case MSG_ID_EVENT_DETAIL:
                // 09 12 [id:8] 06 [start:8]
                if (body.length < 19) {
                    return null;
                }
                return new Request(msgId, 0, le64(body, 2), toMs(le64(body, 11)));
            default:
                return new Request(msgId, 0, 0, 0);
        }
    }

    /** The band sends seconds or milliseconds; tell them apart by size, as its peer does. */
    private static long toMs(long t) {
        return t >= 1_000_000_000_000L ? t : t * 1000;
    }

    // ---- replies ----

    /** @param firstDayOfWeek {@link Calendar#SUNDAY} .. {@link Calendar#SATURDAY} */
    public static byte[] buildSettings(int firstDayOfWeek, TimeZone tz, long nowMs, Locale locale) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x40 | MSG_ID_SETTINGS);
        out.write(P_FIRST_DAY);
        out.write(firstDayOfWeek);
        out.write(P_LOCK_TZ);
        writeTz(out, tz, nowMs);
        writeString(out, P_LOCK_TZ_NAME, tzName(tz, nowMs, locale));
        return out.toByteArray();
    }

    public static byte[] buildNoHolidays() {
        return new byte[]{(byte) (0xc0 | MSG_ID_HOLIDAYS), P_HOLIDAY_COUNT, 0, 0, 0, 0};
    }

    public static byte[] buildNoData() {
        return new byte[]{(byte) (0x40 | MSG_ID_NO_DATA)};
    }

    /** Tell the band the calendar changed; it answers with a sync request. */
    public static byte[] buildSyncPoke() {
        return new byte[]{MSG_ID_SYNC};
    }

    /**
     * The event list in as many messages as it takes (at least one, even with
     * no events).
     *
     * @param seq     a sync counter the band can tell syncs apart by
     * @param stampMs the version stamp the band will report back next time
     */
    public static List<byte[]> buildEventList(List<Event> events, int seq, TimeZone lockTz, long stampMs,
                                              long nowMs) {
        List<List<Event>> pages = paginate(events);
        List<byte[]> out = new ArrayList<>(pages.size());
        for (int p = 0; p < pages.size(); p++) {
            List<Event> page = pages.get(p);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(0xc0 | MSG_ID_EVENT_LIST);
            b.write(6);
            b.write(P_PACKET_SEQ);
            b.write(seq);
            b.write(P_PACKET_TOTAL);
            b.write(pages.size());
            b.write(P_PACKET_INDEX);
            b.write(p);
            b.write(P_EVENTS_IN_MSG);
            b.write(page.size());
            b.write(P_LOCK_TZ);
            writeTz(b, lockTz, nowMs);
            b.write(P_LAST_SYNC);
            writeLe64(b, stampMs);
            for (int i = 0; i < page.size(); i++) {
                Event e = page.get(i);
                b.write(P_INDEX);
                b.write(i);
                b.write(P_EVENT_ID);
                writeLe64(b, e.id);
                writeEventInfo(b, e, nowMs);
                b.write(P_DECLINED);
                b.write(e.declined ? 1 : 0);
            }
            out.add(b.toByteArray());
        }
        return out;
    }

    /** One event in full, answering the band's MSG_ID 9. */
    public static byte[] buildEventDetail(Event e, long nowMs, Locale locale) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0xc0 | MSG_ID_EVENT_DETAIL);
        b.write(16);
        b.write(P_EVENT_ID);
        writeLe64(b, e.id);
        writeEventInfo(b, e, nowMs);
        b.write(P_DECLINED);
        b.write(e.declined ? 1 : 0);
        writeString(b, P_DESCRIPTION, e.description);
        writeString(b, P_LOCATION, e.location);
        writeString(b, P_TZ_NAME, tzName(e.timeZone, e.beginMs, locale));
        writeString(b, P_CALENDAR_NAME, e.calendarName);
        boolean hasReminders = !e.reminderMinutes.isEmpty();
        b.write(P_HAS_REMINDER);
        b.write(hasReminders ? 1 : 0);
        // Without reminders the count and list tags still go out, bare.
        b.write(P_REMINDER_COUNT);
        if (hasReminders) {
            b.write(e.reminderMinutes.size());
        }
        b.write(P_REMINDERS);
        if (hasReminders) {
            for (Integer minutes : e.reminderMinutes) {
                writeLe32(b, minutes);
            }
        }
        return b.toByteArray();
    }

    /** {@code 02 .. 08}: colour, title, type, start, timezone, end. */
    private static void writeEventInfo(ByteArrayOutputStream b, Event e, long nowMs) {
        b.write(P_HAS_COLOR);
        b.write(e.color != 0 ? 1 : 0);
        b.write(P_COLOR);
        writeLe32(b, e.color);
        writeString(b, P_TITLE, e.title);
        b.write(P_TYPE);
        b.write(eventType(e, nowMs));
        b.write(P_START);
        writeLe64(b, e.beginMs / 1000);
        b.write(P_TZ);
        writeTz(b, e.timeZone, e.beginMs);
        b.write(P_END);
        writeLe64(b, e.endMs / 1000);
    }

    /** How the event sits against today, which decides how the band shows its times. */
    public static int eventType(Event e, long nowMs) {
        if (e.allDay) {
            return TYPE_ALL_DAY;
        }
        long startDay = dayIndex(e.beginMs, e.timeZone);
        // An event ending exactly at midnight belongs to the day before.
        long endDay = dayIndex(Math.max(e.beginMs, e.endMs - 1), e.timeZone);
        if (startDay == endDay) {
            return TYPE_SINGLE_DAY;
        }
        long today = dayIndex(nowMs, e.timeZone);
        if (startDay == today) {
            return TYPE_STARTS_TODAY;
        }
        if (endDay == today) {
            return TYPE_ENDS_TODAY;
        }
        return TYPE_SPANS_TODAY;
    }

    private static long dayIndex(long ms, TimeZone tz) {
        return Math.floorDiv(ms + tz.getOffset(ms), 86_400_000L);
    }

    /** Split into messages the way the vendor does, by an estimate of each event's size. */
    static List<List<Event>> paginate(List<Event> events) {
        List<List<Event>> pages = new ArrayList<>();
        List<Event> page = new ArrayList<>();
        int size = MESSAGE_OVERHEAD;
        int total = 0;
        for (Event e : events) {
            if (total >= MAX_EVENTS) {
                break;
            }
            int eventSize = EVENT_OVERHEAD + Math.min(utf8(e.title).length, MAX_STRING);
            if (!page.isEmpty() && (size + eventSize > MESSAGE_BUDGET || page.size() >= MAX_EVENTS_PER_MESSAGE)) {
                pages.add(page);
                page = new ArrayList<>();
                size = MESSAGE_OVERHEAD;
            }
            page.add(e);
            size += eventSize;
            total++;
        }
        pages.add(page);
        return pages;
    }

    // ---- encoding ----

    private static String tzName(TimeZone tz, long atMs, Locale locale) {
        String name = tz.getDisplayName(tz.inDaylightTime(new java.util.Date(atMs)), TimeZone.LONG, locale);
        return name == null || name.isEmpty() ? tz.getID() : name;
    }

    private static void writeTz(ByteArrayOutputStream out, TimeZone tz, long atMs) {
        int offset = tz.getOffset(atMs) / 1000;
        out.write(offset < 0 ? 1 : 0);
        int abs = Math.abs(offset);
        out.write(abs & 0xff);
        out.write((abs >> 8) & 0xff);
    }

    private static void writeString(ByteArrayOutputStream out, int id, @Nullable String s) {
        byte[] bytes = utf8(s);
        int len = Math.min(bytes.length, MAX_STRING);
        // Don't cut a UTF-8 sequence in half.
        while (len < bytes.length && len > 0 && (bytes[len] & 0xc0) == 0x80) {
            len--;
        }
        out.write(id);
        out.write(len);
        out.write(bytes, 0, len);
    }

    private static byte[] utf8(@Nullable String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        for (int i = 0; i < 4; i++) {
            out.write((v >> (8 * i)) & 0xff);
        }
    }

    private static void writeLe64(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 8; i++) {
            out.write((int) (v >> (8 * i)) & 0xff);
        }
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (b[off + i] & 0xffL) << (8 * i);
        }
        return v;
    }
}
