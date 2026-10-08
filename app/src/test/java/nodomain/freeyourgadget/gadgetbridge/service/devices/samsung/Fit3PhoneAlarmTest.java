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

import org.junit.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3PhoneAlarm;

public class Fit3PhoneAlarmTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /** Thursday 2026-10-08 12:00 UTC. */
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, ZoneOffset.UTC);

    private static Alarm alarm(int position, int hour, int minute, int repetition, boolean enabled) {
        Alarm a = new Alarm();
        a.setPosition(position);
        a.setHour(hour);
        a.setMinute(minute);
        a.setRepetition(repetition);
        a.setEnabled(enabled);
        a.setUnused(false);
        return a;
    }

    @Test
    public void weekdayAlarmEntry() {
        int weekdays = Alarm.ALARM_MON | Alarm.ALARM_TUE | Alarm.ALARM_WED | Alarm.ALARM_THU | Alarm.ALARM_FRI;
        byte[] msg = Fit3PhoneAlarm.buildSyncAll(
                Collections.singletonList(alarm(0, 7, 30, weekdays, true)), NOW);
        // Next ring: Friday 2026-10-09 07:30 UTC = 1791531000000 ms.
        assertArrayEquals(hex("04 11 01"
                + "01 01000000"
                + "03 da020000"            // 730
                + "04 01"
                + "06 c0a0911fa1010000"
                + "10 04111101"            // 0x01111104: Mon-Fri, weekly
                + "15 ffffffff"), msg);
    }

    @Test
    public void oneOffAlarmCarriesItsDayAndOnce() {
        // 11:00 has passed today (Thursday), so it rings Friday: 0x100 | 1.
        byte[] msg = Fit3PhoneAlarm.buildSyncAll(
                Collections.singletonList(alarm(2, 11, 0, Alarm.ALARM_ONCE, false)), NOW);
        assertArrayEquals(hex("10 01010000"), Arrays.copyOfRange(msg, 24, 29));
        assertArrayEquals(hex("01 03000000"), Arrays.copyOfRange(msg, 3, 8));
    }

    @Test
    public void unusedSlotsAreLeftOut() {
        Alarm unused = alarm(1, 8, 0, Alarm.ALARM_DAILY, false);
        unused.setUnused(true);
        byte[] msg = Fit3PhoneAlarm.buildSyncAll(Arrays.asList(alarm(0, 6, 0, Alarm.ALARM_DAILY, true), unused), NOW);
        assertEquals(1, msg[2]);
        assertArrayEquals(hex("10 14111111"), Arrays.copyOfRange(msg, 24, 29));  // every day: 0x11111114
    }

    @Test
    public void setupMessages() {
        assertArrayEquals(hex("08 14 01"), Fit3PhoneAlarm.buildClockApp(true));
        assertArrayEquals(hex("03 12 01"), Fit3PhoneAlarm.buildNotificationSetting(true));
        assertArrayEquals(hex("06 16 08 01000000 02000000"), Fit3PhoneAlarm.buildDelete(Arrays.asList(1, 2)));
    }

    @Test
    public void bandRequestsAreDecoded() {
        Fit3PhoneAlarm toggle = Fit3PhoneAlarm.parse(hex("05 01 03000000 04 00"));
        assertNotNull(toggle);
        assertEquals(Integer.valueOf(3), toggle.id);
        assertEquals(Boolean.FALSE, toggle.flag);

        Fit3PhoneAlarm counted = Fit3PhoneAlarm.parse(hex("05 02 01 03000000 04 01"));
        assertNotNull(counted);
        assertEquals(Integer.valueOf(3), counted.id);
        assertEquals(Boolean.TRUE, counted.flag);

        // Captured: a snooze on the band.
        Fit3PhoneAlarm snooze = Fit3PhoneAlarm.parse(hex("01 13 01"));
        assertNotNull(snooze);
        assertEquals(Boolean.TRUE, snooze.flag);

        Fit3PhoneAlarm add = Fit3PhoneAlarm.parse(hex("07 01 ffffffff"));
        assertNotNull(add);
        assertEquals(Integer.valueOf(-1), add.id);
    }
}
