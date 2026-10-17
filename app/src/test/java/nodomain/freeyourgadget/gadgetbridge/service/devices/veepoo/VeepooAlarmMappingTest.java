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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;

public class VeepooAlarmMappingTest {

    private static VeepooAlarmModule.VeepooAlarm bandAlarm(int hour, int minute, int repeatInt) {
        VeepooAlarmModule.VeepooAlarm alarm = new VeepooAlarmModule.VeepooAlarm();
        alarm.hour = hour;
        alarm.minute = minute;
        alarm.repeatInt = repeatInt;
        return alarm;
    }

    private static nodomain.freeyourgadget.gadgetbridge.entities.Alarm gbAlarm(int hour, int minute, int repetition) {
        return new nodomain.freeyourgadget.gadgetbridge.entities.Alarm(
                1, 1, 0, true, false, null, false, repetition, hour, minute, false, null, null, 0, true);
    }

    @Test
    public void weekdayMasksRoundTrip() {
        for (int gbMask = 0; gbMask < 128; gbMask++) {
            int vendor = VeepooAlarmModule.gbRepeatToVendorRepeat(gbMask);
            assertEquals("round trip failed for GB mask " + gbMask, gbMask,
                    VeepooAlarmModule.vendorRepeatToGbRepeat(vendor));
        }
    }

    @Test
    public void mapsKnownMasksOntoTheVendorBitOrder() {
        // Vendor bit6=Sunday ... bit0=Saturday; Gadgetbridge MON=1 .. SUN=64.
        assertEquals(0x01, VeepooAlarmModule.gbRepeatToVendorRepeat(Alarm.ALARM_SAT));
        assertEquals(0x28, VeepooAlarmModule.gbRepeatToVendorRepeat(Alarm.ALARM_MON | Alarm.ALARM_WED));
        assertEquals(0x40, VeepooAlarmModule.gbRepeatToVendorRepeat(Alarm.ALARM_SUN));
        assertEquals(0x7F, VeepooAlarmModule.gbRepeatToVendorRepeat(Alarm.ALARM_SUN | Alarm.ALARM_MON
                | Alarm.ALARM_TUE | Alarm.ALARM_WED | Alarm.ALARM_THU | Alarm.ALARM_FRI | Alarm.ALARM_SAT));
    }

    @Test
    public void matchesWeeklyAlarms() {
        int monWedVendor = 0x28;
        assertTrue(VeepooAlarmModule.matchesGbAlarm(bandAlarm(7, 30, monWedVendor),
                gbAlarm(7, 30, Alarm.ALARM_MON | Alarm.ALARM_WED)));
        assertFalse(VeepooAlarmModule.matchesGbAlarm(bandAlarm(7, 30, monWedVendor),
                gbAlarm(7, 31, Alarm.ALARM_MON | Alarm.ALARM_WED)));
        assertFalse(VeepooAlarmModule.matchesGbAlarm(bandAlarm(7, 30, monWedVendor),
                gbAlarm(7, 30, Alarm.ALARM_MON | Alarm.ALARM_THU)));
    }

    @Test
    public void matchesOneShotAlarmsAgainstNonRepetitiveOnes() {
        assertTrue(VeepooAlarmModule.matchesGbAlarm(bandAlarm(6, 15, 0), gbAlarm(6, 15, Alarm.ALARM_ONCE)));
        assertFalse(VeepooAlarmModule.matchesGbAlarm(bandAlarm(6, 15, 0),
                gbAlarm(6, 15, Alarm.ALARM_MON)));
        assertFalse(VeepooAlarmModule.matchesGbAlarm(bandAlarm(6, 15, 0x40),
                gbAlarm(6, 15, Alarm.ALARM_ONCE)));
    }

    @Test
    public void picksTheLowestFreeSlot() {
        Set<Integer> used = new HashSet<>();
        assertEquals(0, VeepooAlarmModule.nextFreeAlarmPosition(VeepooConstants.ALARM_MAX_ALARMS, used));
        used.add(0);
        used.add(1);
        assertEquals(2, VeepooAlarmModule.nextFreeAlarmPosition(VeepooConstants.ALARM_MAX_ALARMS, used));
    }

    @Test
    public void reportsNoFreeSlotWhenAllSlotsAreTaken() {
        Set<Integer> used = new HashSet<>();
        for (int position = 0; position < VeepooConstants.ALARM_MAX_ALARMS; position++) {
            used.add(position);
        }
        assertEquals(-1, VeepooAlarmModule.nextFreeAlarmPosition(VeepooConstants.ALARM_MAX_ALARMS, used));
    }
}
