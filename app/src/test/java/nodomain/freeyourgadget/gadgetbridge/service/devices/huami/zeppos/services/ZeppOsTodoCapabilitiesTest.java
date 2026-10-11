/* Copyright 2026 Gadgetbridge Contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.services;

import android.content.SharedPreferences;
import org.junit.Test;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.Reminder;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import java.util.Date;
import nodomain.freeyourgadget.gadgetbridge.devices.huami.zeppos.watches.AmazfitBalanceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ZeppOsTodoCapabilitiesTest extends TestBase {
    @Test public void reportedTodoCapacityUsesNoCalendarReservationAndLegacyDevicesKeepTheirLimits() {
        final GBDevice device = mock(GBDevice.class);
        final AmazfitBalanceCoordinator coordinator = new AmazfitBalanceCoordinator();
        when(device.getAddress()).thenReturn("00:00:00:00:12:02");
        when(device.getDeviceCoordinator()).thenReturn(coordinator);
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        prefs.edit().clear().putInt("huami_2021_capability_reminders", 22)
                .putBoolean("sync_calendar", true).putInt("reserve_reminders_calendar", 9).commit();
        assertEquals(22, coordinator.getReminderSlotCount(device));
        assertEquals(16, coordinator.getMaximumReminderMessageLength(device));
        assertFalse(coordinator.getReminderMessageRequired(device));
        assertEquals(9, GBApplication.getDevicePrefs(device).getReservedReminderCalendarSlots());
        prefs.edit().putInt(ZeppOsTodoService.PREF_VERSION, 3).putInt(ZeppOsTodoService.PREF_SLOT_COUNT, 20).commit();
        assertEquals(20, coordinator.getReminderSlotCount(device));
        assertEquals(150, coordinator.getMaximumReminderMessageLength(device));
        assertEquals(150, coordinator.getMaximumReminderMessageBytes(device));
        assertTrue(coordinator.getReminderMessageRequired(device));
        assertEquals(0, GBApplication.getDevicePrefs(device).getReservedReminderCalendarSlots());
        prefs.edit().putInt(ZeppOsTodoService.PREF_VERSION, 4).commit();
        assertEquals(0, coordinator.getReminderSlotCount(device));
        assertEquals(0, GBApplication.getDevicePrefs(device).getReservedReminderCalendarSlots());
        prefs.edit().putInt(ZeppOsTodoService.PREF_VERSION, -1).commit();
        assertEquals(0, coordinator.getReminderSlotCount(device));
        prefs.edit().putInt(ZeppOsTodoService.PREF_VERSION, 0).commit();
        assertEquals(22, coordinator.getReminderSlotCount(device));
        assertEquals(16, coordinator.getMaximumReminderMessageLength(device));
        assertEquals(9, GBApplication.getDevicePrefs(device).getReservedReminderCalendarSlots());
    }

    @Test public void reducedTodoCapacityCannotHideSavedRemindersWhileLegacyQueriesKeepTheirLimit() {
        final GBDevice device = mock(GBDevice.class);
        when(device.getAddress()).thenReturn("00:00:00:00:12:04");
        when(device.getName()).thenReturn("Balance");
        when(device.getType()).thenReturn(DeviceType.AMAZFITBALANCE);
        when(device.getDeviceCoordinator()).thenReturn(new AmazfitBalanceCoordinator());
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        prefs.edit().clear().putInt("huami_2021_capability_reminders", 1).commit();
        for (int i = 0; i < 3; i++) {
            final Reminder reminder = new Reminder();
            reminder.setReminderId("saved-" + i); reminder.setMessage("saved");
            reminder.setDate(new Date(System.currentTimeMillis() + 86400000)); reminder.setRepetition(Reminder.ONCE);
            reminder.setDeviceId(DBHelper.getDevice(device, daoSession).getId());
            reminder.setUserId(DBHelper.getUser(daoSession).getId());
            daoSession.getReminderDao().insert(reminder);
        }
        assertEquals(1, DBHelper.getReminders(device).size());
        prefs.edit().putInt(ZeppOsTodoService.PREF_VERSION, 3).putInt(ZeppOsTodoService.PREF_SLOT_COUNT, 1).commit();
        assertEquals(3, DBHelper.getReminders(device).size());
        prefs.edit().putInt(ZeppOsTodoService.PREF_SLOT_COUNT, 0).commit();
        assertEquals(3, DBHelper.getReminders(device).size());
        assertEquals(3, daoSession.getReminderDao().count());
    }
}
