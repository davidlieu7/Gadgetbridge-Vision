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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Calendar;

/**
 * The calendar channel, against a vendor capture syncing a test event.
 */
public class Fit3CalendarTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static String hexOf(String utf8) {
        StringBuilder sb = new StringBuilder();
        for (byte b : utf8.getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ---- calendar ----

    private static final TimeZone UTC8 = TimeZone.getTimeZone("GMT+08:00");

    /** The capture's test event, "Testing calendar 8", 1 hour on 8 July 2026. */
    private static Fit3Calendar.Event testEvent() {
        Fit3Calendar.Event e = new Fit3Calendar.Event();
        e.id = 2;
        e.title = "Testing calendar 8";
        e.beginMs = 1783468800000L;
        e.endMs = 1783472400000L;
        e.color = 0xff9fe1e7;
        e.timeZone = UTC8;
        return e;
    }

    @Test
    public void parsesBandRequests() {
        Fit3Calendar.Request sync = Fit3Calendar.parseRequest(hex("07 11 0315653b9f010000"));
        assertNotNull(sync);
        assertEquals(Fit3Calendar.MSG_ID_SYNC, sync.msgId);
        assertEquals(0x19f3b651503L, sync.lastSyncMs);

        Fit3Calendar.Request never = Fit3Calendar.parseRequest(hex("07 11 0000000000000000"));
        assertEquals(0, never.lastSyncMs);

        Fit3Calendar.Request detail = Fit3Calendar.parseRequest(hex("09 12 0200000000000000 06 00934d6a00000000"));
        assertNotNull(detail);
        assertEquals(2, detail.eventId);
        assertEquals("band's seconds become ms", 1783468800000L, detail.eventStartMs);
    }

    @Test
    public void eventListMatchesCapture() {
        byte[] expected = hex("c8 06 21 01 22 01 23 00 10 01 09 008070 11 0315653b9f010000"
                + " 01 00 12 0200000000000000 02 01 03 e7e19fff 04 12 " + hexOf("Testing calendar 8")
                + " 05 00 06 00934d6a00000000 07 008070 08 10a14d6a00000000 0e 00");
        List<byte[]> pages = Fit3Calendar.buildEventList(Collections.singletonList(testEvent()), 1, UTC8,
                0x19f3b651503L, 1783468800000L);
        assertEquals(1, pages.size());
        assertArrayEquals(expected, pages.get(0));
    }

    @Test
    public void emptyEventListIsStillOneMessage() {
        List<byte[]> pages = Fit3Calendar.buildEventList(Collections.emptyList(), 3, UTC8, 1000L, 0L);
        assertEquals(1, pages.size());
        assertArrayEquals(hex("c8 06 21 03 22 01 23 00 10 00 09 008070 11 e803000000000000"), pages.get(0));
    }

    @Test
    public void eventDetailMatchesCaptureShape() {
        Fit3Calendar.Event e = testEvent();
        e.calendarName = "Personal";
        e.reminderMinutes = Collections.singletonList(10);
        String tzName = UTC8.getDisplayName(false, TimeZone.LONG, Locale.ROOT);
        byte[] expected = hex("c9 10 12 0200000000000000 02 01 03 e7e19fff 04 12 " + hexOf("Testing calendar 8")
                + " 05 00 06 00934d6a00000000 07 008070 08 10a14d6a00000000 0e 00 13 00 14 00"
                + " 15 " + String.format("%02x", tzName.length()) + hexOf(tzName)
                + " 16 08 " + hexOf("Personal") + " 17 01 18 01 19 0a000000");
        assertArrayEquals(expected, Fit3Calendar.buildEventDetail(e, e.beginMs, Locale.ROOT));

        // Without reminders the count and list tags are sent bare, as the vendor does.
        e.reminderMinutes = Collections.emptyList();
        byte[] noReminders = Fit3Calendar.buildEventDetail(e, e.beginMs, Locale.ROOT);
        byte[] tail = Arrays.copyOfRange(noReminders, noReminders.length - 4, noReminders.length);
        assertArrayEquals(hex("17 00 18 19"), tail);
    }

    @Test
    public void settingsAndHolidays() {
        byte[] settings = Fit3Calendar.buildSettings(Calendar.MONDAY, UTC8, 1783468800000L, Locale.ROOT);
        assertArrayEquals("captured prefix: first day Monday, UTC+8",
                hex("4c 27 02 09 008070 28"), Arrays.copyOfRange(settings, 0, 8));
        assertArrayEquals("captured", hex("cd 24 00000000"), Fit3Calendar.buildNoHolidays());
        assertArrayEquals("captured", hex("43"), Fit3Calendar.buildNoData());
        assertArrayEquals("captured", hex("07"), Fit3Calendar.buildSyncPoke());

        byte[] west = Fit3Calendar.buildSettings(Calendar.SUNDAY, TimeZone.getTimeZone("GMT-05:00"), 0L, Locale.ROOT);
        assertArrayEquals("negative offset: sign byte 1", hex("4c 27 01 09 01 5046"), Arrays.copyOfRange(west, 0, 7));
    }

    @Test
    public void eventTypes() {
        Fit3Calendar.Event e = testEvent();
        assertEquals(Fit3Calendar.TYPE_SINGLE_DAY, Fit3Calendar.eventType(e, e.beginMs));
        e.allDay = true;
        assertEquals(Fit3Calendar.TYPE_ALL_DAY, Fit3Calendar.eventType(e, e.beginMs));
        e.allDay = false;
        e.endMs = e.beginMs + 3 * 86_400_000L;
        assertEquals(Fit3Calendar.TYPE_STARTS_TODAY, Fit3Calendar.eventType(e, e.beginMs));
        assertEquals(Fit3Calendar.TYPE_SPANS_TODAY, Fit3Calendar.eventType(e, e.beginMs + 86_400_000L));
        assertEquals(Fit3Calendar.TYPE_ENDS_TODAY, Fit3Calendar.eventType(e, e.endMs - 60_000L));
    }

    @Test
    public void longListsArePaged() {
        List<Fit3Calendar.Event> events = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Fit3Calendar.Event e = testEvent();
            e.id = i;
            events.add(e);
        }
        List<byte[]> pages = Fit3Calendar.buildEventList(events, 1, UTC8, 1L, 0L);
        assertEquals("at most 10 events a message", 3, pages.size());
        for (int p = 0; p < pages.size(); p++) {
            byte[] page = pages.get(p);
            assertEquals("total pages", 3, page[5]);
            assertEquals("page index", p, page[7]);
            assertEquals("events in this page", p < 2 ? 10 : 5, page[9]);
            assertTrue(page.length <= 980);
        }
    }
}
