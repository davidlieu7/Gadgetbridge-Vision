/* Copyright 2026 Gadgetbridge Contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.services;

import android.content.SharedPreferences;
import android.os.Looper;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Shadows;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.entities.Reminder;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.HuamiDevicePrefs;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.ZeppOsSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.ZeppOsTransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ZeppOsTodoServiceTest extends TestBase {
    private ZeppOsTodoService service;
    private ZeppOsSupport support;
    private ZeppOsTransactionBuilder builder;
    private SharedPreferences prefs;
    private final List<byte[]> sent = new ArrayList<>();
    private HuamiDevicePrefs devicePrefs;
    private GBDevice device;

    @Before public void service() {
        device = mock(GBDevice.class);
        when(device.getAddress()).thenReturn("00:00:00:00:12:01");
        when(device.getName()).thenReturn("Balance");
        when(device.getType()).thenReturn(DeviceType.AMAZFITBALANCE);
        final DeviceCoordinator coordinator = mock(DeviceCoordinator.class);
        when(coordinator.getManufacturer()).thenReturn("Amazfit");
        when(device.getDeviceCoordinator()).thenReturn(coordinator);
        prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()); prefs.edit().clear().commit();
        devicePrefs = new HuamiDevicePrefs(prefs, device);
        support = mock(ZeppOsSupport.class); builder = mock(ZeppOsTransactionBuilder.class);
        when(support.getDevice()).thenReturn(device); when(support.getDevicePrefs()).thenReturn(devicePrefs);
        doAnswer(inv -> { ((GBDeviceEvent) inv.getArgument(0)).evaluate(getContext(), device); return null; })
                .when(support).evaluateGBDeviceEvent(any());
        doAnswer(inv -> { sent.add(((byte[]) inv.getArgument(2)).clone()); return null; })
                .when(support).writeToChunked2021(anyString(), eq(ZeppOsTodoService.ENDPOINT), any(byte[].class), eq(false));
        doAnswer(inv -> { sent.add(((byte[]) inv.getArgument(2)).clone()); return null; })
                .when(support).writeToChunked2021(eq(builder), eq(ZeppOsTodoService.ENDPOINT), any(byte[].class), eq(false));
        service = new ZeppOsTodoService(support);
        DBHelper.getDevice(device, daoSession);
    }

    private void ready(final int maximum, final ZeppOsTodoCodec.Item... items) throws Exception {
        final org.json.JSONObject owned = new org.json.JSONObject(prefs.getString("zepp_os_todo_reminder_ids", "{}"));
        final java.util.Iterator<String> keys = owned.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            for (final ZeppOsTodoCodec.Item item : items) {
                if (item.id != owned.getLong(key)) { continue; }
                final Reminder saved = reminder(key);
                saved.setMessage(item.text);
                saved.setDate(new Date(item.firstRemind * 1000L));
                saved.setRepetition(item.repeat == 2 ? Reminder.EVERY_DAY : Reminder.ONCE);
                store(saved);
            }
        }
        service.initialize(builder);
        service.handlePayload(new byte[]{2, 3, (byte) maximum, (byte) (maximum >> 8)});
        service.handlePayload(list(items)); sent.clear();
    }

    private void store(final Reminder reminder) {
        reminder.setDeviceId(DBHelper.getDevice(device, daoSession).getId());
        reminder.setUserId(DBHelper.getUser(daoSession).getId());
        daoSession.getReminderDao().insertOrReplace(reminder);
    }

    private static byte[] list(final ZeppOsTodoCodec.Item... items) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{4, (byte) items.length, (byte) (items.length >> 8)});
        for (final ZeppOsTodoCodec.Item item : items) {
            final byte[] packet = ZeppOsTodoCodec.upsert(item, 3); bytes.write(packet, 1, packet.length - 1);
        }
        return bytes.toByteArray();
    }

    private Reminder reminder(final String key) {
        final Reminder reminder = new Reminder(); reminder.setReminderId(key); reminder.setMessage("buy bread");
        reminder.setDate(new Date((System.currentTimeMillis() / 1000L + 86400) * 1000L)); reminder.setRepetition(Reminder.ONCE);
        return reminder;
    }

    private ZeppOsTodoCodec.Item requested() throws Exception {
        final byte[] upsert = sent.get(sent.size() - 1);
        assertEquals(5, upsert[0]);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(new byte[]{4, 1, 0});
        bytes.write(upsert, 1, upsert.length - 1);
        return ZeppOsTodoCodec.list(bytes.toByteArray(), 3, 10).values().iterator().next();
    }

    @Test public void initializationWithoutSavedRemindersReadsOnlyAndWaitsForAValidListBeforePublishingCapacity() throws Exception {
        service.initialize(builder); assertArrayEquals(new byte[]{1}, sent.get(0));
        service.handlePayload(new byte[]{2, 3, 44, 1}); // 300 slots, two-byte count.
        assertEquals(0, ZeppOsTodoService.getSlotCount(devicePrefs));
        assertArrayEquals(new byte[]{3}, sent.get(1));
        service.handlePayload(list()); assertEquals(300, ZeppOsTodoService.getSlotCount(devicePrefs));
        assertEquals(2, sent.size());
    }

    @Test public void createPreservesUnmanagedItemsAndWaitsForAckAndReadback() throws Exception {
        final ZeppOsTodoCodec.Item foreign = new ZeppOsTodoCodec.Item(60, "Zepp item", 1, 1, 1, 1, 1, 0);
        ready(2, foreign); assertEquals(1, ZeppOsTodoService.getSlotCount(devicePrefs));
        final Reminder reminder = reminder("ours");
        service.sendReminders(builder, Collections.singletonList(reminder));
        assertArrayEquals(new byte[]{3}, sent.get(0));
        service.handlePayload(list(foreign)); final ZeppOsTodoCodec.Item ours = requested();
        assertNotEquals(foreign.id, ours.id); assertEquals(1, ours.repeat); assertEquals(0, ours.source);
        assertTrue(prefs.getString("zepp_os_todo_reminder_ids", "").contains("ours"));
        service.handlePayload(new byte[]{8, 1}); assertEquals(2, sent.size()); // Unrelated delete ACK.
        service.handlePayload(new byte[]{6, 1}); assertArrayEquals(new byte[]{3}, sent.get(2));
        service.handlePayload(list(foreign, ours)); assertEquals(3, sent.size());
        service.dispose();
        service = new ZeppOsTodoService(support); ready(2, foreign, ours);
        service.sendReminders(builder, Collections.singletonList(reminder));
        service.handlePayload(list(foreign, ours)); assertEquals(1, sent.size()); // Same stable ID; no repeated create.
    }

    @Test public void onlyManagedIdsCanBeDeletedAndMappingIsRemovedAfterVerification() throws Exception {
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"ours\":50}").commit();
        final ZeppOsTodoCodec.Item ours = new ZeppOsTodoCodec.Item(50, "ours", 0, 1, 1, 1, 1, 0);
        final ZeppOsTodoCodec.Item foreign = new ZeppOsTodoCodec.Item(60, "foreign", 0, 1, 1, 1, 1, 0);
        ready(2, ours, foreign); service.sendReminders(builder, Collections.emptyList());
        service.handlePayload(list(ours, foreign)); assertArrayEquals(new byte[]{7, 50, 0, 0, 0}, sent.get(1));
        assertTrue(prefs.getString("zepp_os_todo_reminder_ids", "").contains("ours"));
        service.handlePayload(new byte[]{8, 1}); service.handlePayload(list(foreign));
        assertEquals("{}", prefs.getString("zepp_os_todo_reminder_ids", "")); assertEquals(3, sent.size());
    }

    @Test public void fullOrMalformedWatchListsCannotCauseAnyMutations() throws Exception {
        final ZeppOsTodoCodec.Item foreign = new ZeppOsTodoCodec.Item(60, "foreign", 0, 1, 1, 1, 1, 0);
        ready(1, foreign); service.sendReminders(builder, Collections.singletonList(reminder("ours")));
        service.handlePayload(list(foreign)); assertEquals(1, sent.size());
        assertEquals("{}", prefs.getString("zepp_os_todo_reminder_ids", "{}"));
        service.sendReminders(builder, Collections.singletonList(reminder("ours")));
        service.handlePayload(new byte[]{4, 1, 0}); assertEquals(2, sent.size());
    }

    @Test public void rejectedOrTimedOutWritesAreNotRetriedAndIdSurvivesLostAck() throws Exception {
        ready(2); service.sendReminders(builder, Collections.singletonList(reminder("ours")));
        service.handlePayload(list()); final ZeppOsTodoCodec.Item ours = requested();
        final String ids = prefs.getString("zepp_os_todo_reminder_ids", "");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        service.handlePayload(new byte[]{6, 1}); assertEquals(2, sent.size()); assertEquals(ids, prefs.getString("zepp_os_todo_reminder_ids", ""));
        service.sendReminders(builder, Collections.singletonList(reminder("ours")));
        service.handlePayload(list()); assertEquals(ours.id, requested().id);
        service.handlePayload(new byte[]{6, 0}); assertEquals(4, sent.size());
    }

    @Test public void watchChangeNotificationsRefreshOnlyAndCorruptOwnershipStopsSynchronization() throws Exception {
        ready(2); service.handlePayload(new byte[]{13, 1}); assertArrayEquals(new byte[]{3}, sent.get(0));
        service.handlePayload(list()); assertEquals(1, sent.size());
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"one\":50,\"two\":50}").commit();
        service.sendReminders(builder, Collections.singletonList(reminder("ours")));
        service.handlePayload(list()); assertEquals(2, sent.size());
    }

    @Test public void editingUsesTheSameIdAndFailedReadbackStopsTheRestOfTheBatch() throws Exception {
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"ours\":50}").commit();
        final ZeppOsTodoCodec.Item old = new ZeppOsTodoCodec.Item(50, "old text", 0, 1, 1, 1, 1, 0);
        ready(3, old);
        service.sendReminders(builder, java.util.Arrays.asList(reminder("ours"), reminder("second")));
        service.handlePayload(list(old)); assertEquals(50, requested().id);
        service.handlePayload(new byte[]{6, 1}); service.handlePayload(list(old));
        assertEquals(3, sent.size()); // The second upsert must not follow mismatching readback.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertEquals(3, sent.size());
    }

    @Test public void unchangedCompletedOrAdvancedRecurringItemsAreNotResurrected() throws Exception {
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"ours\":50}").commit();
        final Reminder reminder = reminder("ours"); reminder.setRepetition(Reminder.EVERY_DAY);
        final long original = reminder.getDate().getTime() / 1000L;
        final ZeppOsTodoCodec.Item completed = new ZeppOsTodoCodec.Item(50, reminder.getMessage(), 0,
                original, original + 86400, original + 86400, 2, original);
        ready(2, completed); service.sendReminders(builder, Collections.singletonList(reminder));
        service.handlePayload(list(completed)); assertEquals(1, sent.size());
    }

    @Test public void unsupportedVersionOrInvalidEntireBatchNeverSendsAMutation() throws Exception {
        service.initialize(builder); sent.clear();
        service.handlePayload(new byte[]{2, 4, 10, 0});
        service.sendReminders(builder, Collections.singletonList(reminder("ours"))); assertTrue(sent.isEmpty());
        ready(2);
        final Reminder tooLong = reminder("invalid"); tooLong.setMessage("é".repeat(76));
        service.sendReminders(builder, java.util.Arrays.asList(reminder("ours"), tooLong));
        assertTrue(sent.isEmpty());
    }

    @Test public void syncDuringInitializationUsesTheLatestRequestAndTheInitialList() throws Exception {
        service.initialize(builder);
        service.sendReminders(builder, Collections.singletonList(reminder("old")));
        service.sendReminders(builder, Collections.singletonList(reminder("latest")));
        assertEquals(1, sent.size());
        service.handlePayload(new byte[]{2, 3, 2, 0});
        service.handlePayload(list());
        final ZeppOsTodoCodec.Item ours = requested();
        assertEquals(3, sent.size());
        assertFalse(prefs.getString("zepp_os_todo_reminder_ids", "").contains("old"));
        assertTrue(prefs.getString("zepp_os_todo_reminder_ids", "").contains("latest"));
        service.handlePayload(new byte[]{6, 1});
        service.handlePayload(list(ours));
        assertEquals(4, sent.size());
    }

    @Test public void explicitSyncCanRetryInitializationButTimeoutDoesNotReplayQueuedWrites() throws Exception {
        service.initialize(builder);
        service.sendReminders(builder, Collections.singletonList(reminder("ours")));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        service.handlePayload(new byte[]{2, 3, 2, 0});
        assertEquals(1, sent.size());
        assertEquals(0, ZeppOsTodoService.getSlotCount(devicePrefs));
        service.sendReminders(builder, Collections.singletonList(reminder("ours")));
        assertArrayEquals(new byte[]{1}, sent.get(1));
        service.handlePayload(new byte[]{2, 3, 2, 0});
        service.handlePayload(list());
        requested();
        assertEquals(4, sent.size());
    }

    @Test public void duplicateCapabilitiesCannotInterruptAnAcknowledgementOrVerification() throws Exception {
        ready(2);
        service.sendReminders(builder, Collections.singletonList(reminder("ours")));
        service.handlePayload(list());
        final ZeppOsTodoCodec.Item ours = requested();
        service.handlePayload(new byte[]{2, 3, 2, 0});
        assertEquals(2, sent.size());
        service.handlePayload(new byte[]{6, 1});
        service.handlePayload(new byte[]{2, 4, 2, 0});
        service.handlePayload(list(ours));
        assertEquals(3, sent.size());
        assertEquals(3, prefs.getInt(ZeppOsTodoService.PREF_VERSION, 0));
    }

    @Test public void queuedEditRunsAfterVerificationAndKeepsTheSameId() throws Exception {
        ready(2);
        final Reminder reminder = reminder("ours");
        service.sendReminders(builder, Collections.singletonList(reminder));
        service.handlePayload(list());
        final ZeppOsTodoCodec.Item original = requested();
        reminder.setMessage("buy milk");
        service.sendReminders(builder, Collections.singletonList(reminder));
        service.handlePayload(new byte[]{6, 1});
        service.handlePayload(list(original));
        assertArrayEquals(new byte[]{3}, sent.get(sent.size() - 1));
        service.handlePayload(list(original));
        final ZeppOsTodoCodec.Item edited = requested();
        assertEquals(original.id, edited.id);
        assertEquals("buy milk", edited.text);
        service.handlePayload(new byte[]{6, 1});
        service.handlePayload(list(edited));
        assertEquals(6, sent.size());
    }

    @Test public void replacingAManagedItemAtCapacityDeletesItBeforeCreatingTheReplacement() throws Exception {
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"old\":50}").commit();
        final ZeppOsTodoCodec.Item old = new ZeppOsTodoCodec.Item(50, "old", 0, 1, 1, 1, 1, 0);
        final ZeppOsTodoCodec.Item foreign = new ZeppOsTodoCodec.Item(60, "foreign", 1, 1, 1, 1, 1, 0);
        ready(2, old, foreign);
        service.sendReminders(builder, Collections.singletonList(reminder("new")));
        service.handlePayload(list(old, foreign));
        assertArrayEquals(new byte[]{7, 50, 0, 0, 0}, sent.get(1));
        service.handlePayload(new byte[]{8, 1});
        service.handlePayload(list(foreign));
        final ZeppOsTodoCodec.Item replacement = requested();
        assertNotEquals(50, replacement.id);
        assertNotEquals(60, replacement.id);
        service.handlePayload(new byte[]{6, 1});
        service.handlePayload(list(foreign, replacement));
        assertEquals(5, sent.size());
        assertFalse(prefs.getString("zepp_os_todo_reminder_ids", "").contains("old"));
    }

    @Test public void versionOnePreservesAdvancedRecurringItemsAcrossReconnectAndStillAllowsTimeEdits() throws Exception {
        final Reminder reminder = reminder("ours");
        reminder.setRepetition(Reminder.EVERY_DAY);
        service.initialize(builder);
        service.handlePayload(new byte[]{2, 1, 2, 0});
        service.handlePayload(new byte[]{4, 0, 0});
        sent.clear();
        service.sendReminders(builder, Collections.singletonList(reminder));
        service.handlePayload(new byte[]{4, 0, 0});
        final byte[] create = sent.get(1);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{4, 1, 0}); bytes.write(create, 1, create.length - 1);
        final byte[] original = bytes.toByteArray();
        final ZeppOsTodoCodec.Item ours = ZeppOsTodoCodec.list(original, 1, 2).values().iterator().next();
        service.handlePayload(new byte[]{6, 1});
        service.handlePayload(original);
        store(reminder);
        final ZeppOsTodoCodec.Item advanced = new ZeppOsTodoCodec.Item(ours.id, ours.text, 0, 0,
                ours.date + 86400, ours.time + 86400, ours.repeat, ours.time);
        bytes.reset(); bytes.write(new byte[]{4, 1, 0});
        final byte[] encoded = ZeppOsTodoCodec.upsert(advanced, 1);
        bytes.write(encoded, 1, encoded.length - 1);
        final byte[] advancedList = bytes.toByteArray();
        service.dispose(); service = new ZeppOsTodoService(support);
        service.initialize(builder);
        service.handlePayload(new byte[]{2, 1, 2, 0});
        service.handlePayload(advancedList); sent.clear();
        service.sendReminders(builder, Collections.singletonList(reminder));
        service.handlePayload(advancedList);
        assertEquals(1, sent.size());
        reminder.setDate(new Date(reminder.getDate().getTime() + 60000));
        service.sendReminders(builder, Collections.singletonList(reminder));
        service.handlePayload(advancedList);
        assertEquals(5, sent.get(sent.size() - 1)[0]);
    }

    @Test public void invalidDatesAndChangedOwnershipCannotCausePartialWrites() throws Exception {
        ready(2);
        final Reminder invalid = reminder("invalid"); invalid.setDate(null);
        service.sendReminders(builder, java.util.Arrays.asList(reminder("valid"), invalid));
        service.sendReminders(builder, Collections.singletonList(null));
        assertTrue(sent.isEmpty());
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"ours\":50}").commit();
        service.sendReminders(builder, Collections.emptyList());
        service.handlePayload(list(new ZeppOsTodoCodec.Item(50, "another source", 1, 1, 1, 1, 1, 0)));
        assertEquals(1, sent.size());
    }

    @Test public void allRepetitionsUseTheLocalCalendarAndUnsignedTimestampRange() throws Exception {
        ready(10);
        final java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Australia/Melbourne"));
            final java.util.Calendar date = java.util.Calendar.getInstance();
            date.clear(); date.set(2040, java.util.Calendar.JANUARY, 2, 9, 15);
            final int[] repetitions = {Reminder.ONCE, Reminder.EVERY_DAY, Reminder.EVERY_WEEK, Reminder.EVERY_MONTH, Reminder.EVERY_YEAR};
            final int[] flags = {1, 2, 0x24, 8, 16};
            for (int i = 0; i < repetitions.length; i++) {
                final Reminder reminder = reminder("repeat");
                reminder.setDate(date.getTime()); reminder.setRepetition(repetitions[i]);
                service.sendReminders(builder, Collections.singletonList(reminder));
                service.handlePayload(list());
                final ZeppOsTodoCodec.Item item = requested();
                assertEquals(flags[i], item.repeat);
                assertEquals(date.getTimeInMillis() / 1000L, item.time);
                assertEquals(item.time, item.firstRemind);
                assertEquals(java.time.Instant.parse("2040-01-01T13:00:00Z").getEpochSecond(), item.date);
                service.handlePayload(new byte[]{6, 1}); service.handlePayload(list(item));
            }
        } finally { java.util.TimeZone.setDefault(original); }
    }

    @Test public void initializationSyncsOfflineCreationsAndPreservesWatchOnlyItems() throws Exception {
        store(reminder("offline"));
        service.initialize(builder);
        service.handlePayload(new byte[]{2, 3, 2, 0});
        final ZeppOsTodoCodec.Item foreign = new ZeppOsTodoCodec.Item(60, "watch-only", 1, 1, 1, 1, 1, 0);
        service.handlePayload(list(foreign));
        final ZeppOsTodoCodec.Item created = requested();
        assertNotEquals(foreign.id, created.id);
        assertTrue(prefs.getString("zepp_os_todo_reminder_ids", "").contains("offline"));
        service.handlePayload(new byte[]{6, 1});
        service.handlePayload(list(created, foreign));
        assertEquals(4, sent.size());
    }

    @Test public void reconnectSyncsOfflineEditsAndDeletionsWithStableIds() throws Exception {
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"ours\":50}").commit();
        final ZeppOsTodoCodec.Item original = new ZeppOsTodoCodec.Item(50, "old", 0, 1, 1, 1, 1, 0);
        ready(2, original);
        final Reminder edited = reminder("ours"); edited.setMessage("edited offline"); store(edited);
        service.dispose(); service = new ZeppOsTodoService(support);
        service.initialize(builder); service.handlePayload(new byte[]{2, 3, 2, 0});
        service.handlePayload(list(original));
        final ZeppOsTodoCodec.Item updated = requested();
        assertEquals(50, updated.id); assertEquals("edited offline", updated.text);
        service.handlePayload(new byte[]{6, 1}); service.handlePayload(list(updated));
        daoSession.getReminderDao().deleteAll();
        service.dispose(); service = new ZeppOsTodoService(support);
        sent.clear(); service.initialize(builder); service.handlePayload(new byte[]{2, 3, 2, 0});
        service.handlePayload(list(updated));
        assertArrayEquals(new byte[]{7, 50, 0, 0, 0}, sent.get(2));
        service.handlePayload(new byte[]{8, 1}); service.handlePayload(list());
        assertEquals("{}", prefs.getString("zepp_os_todo_reminder_ids", ""));
    }

    @Test public void missingDatabaseCannotBeInterpretedAsDeletionOfAllManagedItems() throws Exception {
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"ours\":50}").commit();
        daoSession.getDeviceDao().deleteAll();
        service.initialize(builder); service.handlePayload(new byte[]{2, 3, 2, 0});
        service.handlePayload(list(new ZeppOsTodoCodec.Item(50, "ours", 0, 1, 1, 1, 1, 0)));
        assertEquals(2, sent.size());
        assertEquals("{\"ours\":50}", prefs.getString("zepp_os_todo_reminder_ids", ""));
    }

    @Test public void textOnlyEditsKeepCompletionAndAdvancedDates() throws Exception {
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"ours\":50}").commit();
        final Reminder reminder = reminder("ours"); reminder.setRepetition(Reminder.EVERY_DAY);
        final long time = reminder.getDate().getTime() / 1000L;
        final ZeppOsTodoCodec.Item completed = new ZeppOsTodoCodec.Item(50, "old", 0,
                time, time + 86400, time + 86400, 2, time);
        ready(2, completed);
        reminder.setMessage("new text");
        service.sendReminders(builder, Collections.singletonList(reminder));
        service.handlePayload(list(completed));
        final ZeppOsTodoCodec.Item updated = requested();
        assertEquals("new text", updated.text);
        assertEquals(completed.date, updated.date); assertEquals(completed.time, updated.time);
        assertEquals(completed.completeTime, updated.completeTime);
    }

    @Test public void expiredOneOffRemindersAreNotRecreatedButRecurringRemindersRemainSupported() throws Exception {
        final Reminder expired = reminder("expired"); expired.setDate(new Date(System.currentTimeMillis() - 86400000));
        final Reminder recurring = reminder("recurring"); recurring.setDate(expired.getDate());
        recurring.setRepetition(Reminder.EVERY_DAY);
        store(expired); store(recurring);
        service.initialize(builder); service.handlePayload(new byte[]{2, 3, 2, 0});
        service.handlePayload(list());
        assertEquals(2, requested().repeat);
        assertFalse(prefs.getString("zepp_os_todo_reminder_ids", "").contains("expired"));
    }

    @Test public void overCapacityOfflineStateCannotDeleteSavedItemsToMakeRoom() throws Exception {
        prefs.edit().putString("zepp_os_todo_reminder_ids", "{\"first\":50,\"second\":51}").commit();
        store(reminder("first")); store(reminder("second")); store(reminder("third"));
        service.initialize(builder); service.handlePayload(new byte[]{2, 3, 3, 0});
        service.handlePayload(list(
                new ZeppOsTodoCodec.Item(50, "old first", 0, 1, 1, 1, 1, 0),
                new ZeppOsTodoCodec.Item(51, "old second", 0, 1, 1, 1, 1, 0),
                new ZeppOsTodoCodec.Item(60, "watch-only", 1, 1, 1, 1, 1, 0)));
        assertEquals(2, sent.size());
        assertEquals(3, daoSession.getReminderDao().count());
        assertFalse(prefs.getString("zepp_os_todo_reminder_ids", "").contains("third"));
    }
}
