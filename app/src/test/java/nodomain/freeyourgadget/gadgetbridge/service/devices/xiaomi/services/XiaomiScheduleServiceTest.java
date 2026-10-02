package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AlarmDetails;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AlarmInfo;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Alarms;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.WearCommon;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiServiceTestBase;

/**
 * Tests use captured Smart Band 10 alarm messages and synthetic edge cases.
 * Device replies are simulated. These tests do not establish hardware compatibility.
 * Tests for other models preserve existing assumptions until device evidence is available.
 */
public class XiaomiScheduleServiceTest extends XiaomiServiceTestBase {
    private XiaomiScheduleService service;

    @Override
    protected void useDevice(final DeviceType type) {
        super.useDevice(type);
        service = new XiaomiScheduleService(support);
    }

    private AlarmInfo watchAlarm(final int id, final int minute, final boolean enabled) {
        return AlarmInfo.newBuilder().setId(id).setAlarmDetails(AlarmDetails.newBuilder()
                .setTime(WearCommon.HourMinute.newBuilder().setHour(7).setMinute(minute))
                .setRepeatMode(0).setEnabled(enabled).setSmart(2)).build();
    }

    private void receive(final AlarmInfo... alarms) {
        service.handleAlarms(Alarms.newBuilder().setMaxAlarms(10).addAllAlarm(List.of(alarms)).build());
    }

    private ArrayList<Alarm> localAlarms() {
        return new ArrayList<>(DBHelper.getAlarmsWithDefaults(device));
    }

    private void acknowledge(final int subtype) {
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(subtype).build());
    }

    private void complete(final int subtype, final AlarmInfo... alarms) {
        sent.clear();
        acknowledge(subtype);
        onlyCommand(0);
        receive(alarms);
        sent.clear();
    }

    private XiaomiProto.Command onlyCommand(final int subtype) {
        assertEquals(1, sent.size());
        assertEquals(17, sent.get(0).getType());
        assertEquals(subtype, sent.get(0).getSubtype());
        return sent.get(0);
    }

    @Test
    public void band10ImportsEditsTogglesAndDeletesAlarmZero() {
        support.onFetchRecordedData(0);
        assertTrue(sent.stream().anyMatch(cmd -> cmd.getType() == 17 && cmd.getSubtype() == 0));
        assertTrue(sent.stream().anyMatch(cmd -> cmd.getType() == 8 && cmd.getSubtype() == 1));
        sent.clear();
        receive(watchAlarm(0, 13, true));
        ArrayList<Alarm> alarms = localAlarms();
        assertEquals(10, alarms.size());
        assertEquals(13, alarms.get(0).getMinute());
        assertFalse(alarms.get(0).getUnused());
        assertTrue(alarms.subList(1, alarms.size()).stream().allMatch(Alarm::getUnused));
        service.onSetAlarms(alarms);
        assertTrue("Unchanged alarms must not create default entries", sent.isEmpty());

        alarms.get(0).setMinute(17);
        service.onSetAlarms(alarms);
        assertEquals(0, onlyCommand(2).getSchedule().getEditAlarm().getId());

        complete(2, watchAlarm(0, 17, true));
        // Disabling was captured on Band 10. Enabling with 17:5 remains unverified on hardware.
        for (final boolean enabled : new boolean[]{false, true}) {
            alarms = localAlarms();
            alarms.get(0).setEnabled(enabled);
            service.onSetAlarms(alarms);
            assertEquals(0, onlyCommand(5).getSchedule().getEditAlarm().getId());
            assertEquals(enabled, sent.get(0).getSchedule().getEditAlarm().getAlarmDetails().getEnabled());
            complete(5, watchAlarm(0, 17, enabled));
        }
        alarms = localAlarms();
        alarms.get(0).setUnused(true);
        alarms.get(0).setEnabled(false);
        service.onSetAlarms(alarms);
        assertEquals(List.of(0), onlyCommand(4).getSchedule().getDeleteAlarm().getIdList());

        complete(4);
        service.onSetAlarms(localAlarms());
        assertTrue("Deleted alarms must not return on the next sync", sent.isEmpty());
        assertTrue(localAlarms().stream().allMatch(Alarm::getUnused));
    }

    @Test
    public void otherBandsRetainTheUnverifiedLegacyAlarmMapping() {
        for (final DeviceType type : new DeviceType[]{DeviceType.MIBAND8, DeviceType.MIBAND9}) {
            useDevice(type);
            daoSession.getAlarmDao().deleteAll();
            support.onFetchRecordedData(0);
            assertFalse(sent.stream().anyMatch(cmd -> cmd.getType() == 17));
            sent.clear();
            receive(watchAlarm(1, 13, true));
            assertTrue("Legacy readback must not create database slots", DBHelper.getAlarms(device).isEmpty());
            localAlarms().forEach(DBHelper::store);
            receive(watchAlarm(1, 13, true));
            final ArrayList<Alarm> alarms = localAlarms();
            assertEquals(type.name(), 13, alarms.get(0).getMinute());
            alarms.get(0).setEnabled(false);
            service.onSetAlarms(alarms);
            assertEquals(type.name(), 1, onlyCommand(2).getSchedule().getEditAlarm().getId());
            sent.clear();
            acknowledge(2);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
            assertTrue("Legacy edits must not add readback or recovery requests", sent.isEmpty());
        }
    }

    @Test
    public void creationBatchWaitsForRepliesAndRecoversFromMissingReplies() {
        for (final boolean repliesArrive : new boolean[]{true, false}) {
            useDevice(DeviceType.MIBAND10);
            receive();
            final ArrayList<Alarm> alarms = localAlarms();
            for (int i = 0; i < 2; i++) {
                alarms.get(i).setUnused(false);
                alarms.get(i).setEnabled(true);
                alarms.get(i).setHour(7);
                alarms.get(i).setMinute(13 + i);
            }
            service.onSetAlarms(alarms);
            service.onSetAlarms(alarms);
            service.requestAlarms();
            assertEquals(2, sent.size());
            assertTrue(sent.stream().allMatch(cmd -> cmd.getSubtype() == 1));

            sent.clear();
            if (repliesArrive) {
                acknowledge(1);
                assertTrue(sent.isEmpty());
                acknowledge(1);
            } else {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
            }
            onlyCommand(0);
            sent.clear();
            receive(watchAlarm(0, 13, true), watchAlarm(1, 14, true));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
            assertTrue("Readback must not create duplicate alarms or leave a timer active", sent.isEmpty());
            alarms.get(0).setMinute(17);
            service.onSetAlarms(alarms);
            onlyCommand(2);
            service.dispose();
            sent.clear();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
            assertTrue("Disposal must cancel pending recovery", sent.isEmpty());
        }
    }

    @Test
    public void pendingSnapshotsRemainCompleteAndIndependentOfTheCaller() {
        for (final boolean replyDuringCopy : new boolean[]{false, true}) {
            useDevice(DeviceType.MIBAND10);
            final Alarm alarm = new Alarm() {
                @Override
                public int getPosition() {
                    // Simulate a device reply before the caller's snapshot is complete.
                    if (replyDuringCopy) {
                        receive(watchAlarm(0, 13, true));
                    }
                    return 0;
                }
            };
            alarm.setHour(7);
            alarm.setMinute(17);
            alarm.setEnabled(true);
            service.onSetAlarms(new ArrayList<>(List.of(alarm)));
            alarm.setMinute(59);
            if (!replyDuringCopy) {
                assertTrue(sent.isEmpty());
                receive(watchAlarm(0, 13, true));
            }
            assertEquals(17, onlyCommand(2).getSchedule().getEditAlarm().getAlarmDetails().getTime().getMinute());
            service.dispose();
        }
    }

    @Test
    public void rejectedDeletionIsReconciledWithTheBand() {
        receive(watchAlarm(0, 13, true));
        final ArrayList<Alarm> alarms = localAlarms();
        alarms.get(0).setUnused(true);
        alarms.get(0).setEnabled(false);
        service.onSetAlarms(alarms);
        sent.clear();
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(4).setStatus(1).build());
        onlyCommand(0);
        receive(watchAlarm(0, 13, true));
        assertFalse(localAlarms().get(0).getUnused());
    }

    @Test
    public void readbackPersistsDefaultsAndClearsDeletedAlarmState() {
        service.handleAlarms(Alarms.newBuilder().setMaxAlarms(10).addAlarm(
                AlarmInfo.newBuilder().setId(0).setAlarmDetails(AlarmDetails.newBuilder()
                        .setTime(WearCommon.HourMinute.newBuilder().setHour(6).setMinute(30))
                        .setEnabled(false).setSmart(2).setRepeatMode(0))).build());
        assertEquals(10, DBHelper.getAlarms(device).size());
        assertFalse(localAlarms().get(0).getUnused());

        final Alarm alarm = localAlarms().get(0);
        alarm.setEnabled(true);
        alarm.setUnused(true);
        DBHelper.store(alarm);
        receive();
        assertTrue(localAlarms().stream().allMatch(a -> a.getUnused() && !a.getEnabled()));

        // The unchanged external receiver enables an unused slot without clearing its flag.
        final ArrayList<Alarm> alarms = localAlarms();
        alarms.get(0).setEnabled(true);
        service.onSetAlarms(alarms);
        onlyCommand(1);
        service.dispose();
    }

    @Test
    public void partialUpdatesPreserveOtherQueuedSlots() {
        receive(watchAlarm(0, 10, true), watchAlarm(1, 20, true), watchAlarm(2, 30, true));
        final ArrayList<Alarm> alarms = localAlarms();
        alarms.get(0).setMinute(11);
        service.onSetAlarms(alarms);
        alarms.get(1).setMinute(21);
        service.onSetAlarms(alarms);
        alarms.get(2).setMinute(31);
        service.onSetAlarms(new ArrayList<>(List.of(alarms.get(2))));
        onlyCommand(2);

        sent.clear();
        acknowledge(2);
        onlyCommand(0);
        sent.clear();
        receive(watchAlarm(0, 11, true), watchAlarm(1, 20, true), watchAlarm(2, 30, true));
        assertEquals(2, sent.size());
        for (int i = 0; i < 2; i++) {
            assertEquals(2, sent.get(i).getSubtype());
            assertEquals(i + 1, sent.get(i).getSchedule().getEditAlarm().getId());
            assertEquals(21 + i * 10, sent.get(i).getSchedule().getEditAlarm().getAlarmDetails().getTime().getMinute());
        }
        service.dispose();
    }
}
