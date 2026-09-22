/*  Copyright (C) 2026 Gadgetbridge Contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 or later;
    see the LICENSE file for details.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo;

import static org.junit.Assert.assertEquals;

import java.util.Calendar;

import org.junit.Test;

public class VeepooFetchWindowTest {

    private static long instant(int year, int month0, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month0, day, hour, minute);
        return calendar.getTimeInMillis();
    }

    @Test
    public void testMidnightOfStripsTheTime() {
        long midnight = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 21, 14, 37));
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(midnight);
        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, calendar.get(Calendar.MINUTE));
        assertEquals(0, calendar.get(Calendar.SECOND));
        assertEquals(0, calendar.get(Calendar.MILLISECOND));
    }

    @Test
    public void testMidnightOfKeepsTheDate() {
        long midnight = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 21, 14, 37));
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(midnight);
        assertEquals(2026, calendar.get(Calendar.YEAR));
        assertEquals(Calendar.SEPTEMBER, calendar.get(Calendar.MONTH));
        assertEquals(21, calendar.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void testFirstSyncRequestsFullHistory() {
        long today = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 21, 9, 0));
        assertEquals(-1, VeepooDeviceSupport.computeFetchEndDay(0L, today));
    }

    @Test
    public void testSameDayIsZero() {
        long today = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 21, 9, 0));
        long lastSync = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 21, 23, 59));
        assertEquals(0, VeepooDeviceSupport.computeFetchEndDay(lastSync, today));
    }

    @Test
    public void testDaysElapsedSinceLastSync() {
        long today = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 21, 9, 0));
        long lastSync = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 18, 2, 15));
        assertEquals(3, VeepooDeviceSupport.computeFetchEndDay(lastSync, today));
    }

    @Test
    public void testMonthBoundary() {
        long today = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 1, 9, 0));
        long lastSync = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.AUGUST, 30, 9, 0));
        assertEquals(2, VeepooDeviceSupport.computeFetchEndDay(lastSync, today));
    }

    @Test
    public void testYearBoundary() {
        long today = VeepooDeviceSupport.midnightOf(instant(2027, Calendar.JANUARY, 1, 9, 0));
        long lastSync = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.DECEMBER, 31, 9, 0));
        assertEquals(1, VeepooDeviceSupport.computeFetchEndDay(lastSync, today));
    }

    @Test
    public void testFutureLastSyncFallsBackToFullHistory() {
        long today = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 21, 9, 0));
        long lastSync = VeepooDeviceSupport.midnightOf(instant(2026, Calendar.SEPTEMBER, 22, 9, 0));
        assertEquals(-1, VeepooDeviceSupport.computeFetchEndDay(lastSync, today));
    }
}
