package nodomain.freeyourgadget.gadgetbridge.service.devices.vring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothGattCharacteristic;

import org.junit.Test;
import org.mockito.InOrder;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;

public class VRingR26DeviceSupportTest {
    @Test
    public void initializationSpacesEveryCommandWrite() {
        final BluetoothGattCharacteristic characteristic = mock(BluetoothGattCharacteristic.class);
        final VRingR26DeviceSupport support = new VRingR26DeviceSupport() {
            @Override
            public BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
                return characteristic;
            }
        };
        final TransactionBuilder builder = mock(TransactionBuilder.class, RETURNS_SELF);

        support.initializeDevice(builder);

        final InOrder writes = inOrder(builder);
        for (int command = 0; command < 3; command++) {
            writes.verify(builder).write(eq(characteristic), any(byte[].class));
            writes.verify(builder).sleep(100);
        }
        verify(builder, times(3)).write(eq(characteristic), any(byte[].class));
        verify(builder, times(3)).sleep(100);
    }

    @Test
    public void missingCommandCharacteristicDoesNotAddDelays() {
        final VRingR26DeviceSupport support = new VRingR26DeviceSupport() {
            @Override
            public BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
                return null;
            }
        };
        final TransactionBuilder builder = mock(TransactionBuilder.class, RETURNS_SELF);

        support.initializeDevice(builder);

        verify(builder, never()).write(any(BluetoothGattCharacteristic.class), any(byte[].class));
        verify(builder, never()).sleep(100);
    }

    @Test
    public void stepsPerMinuteUsesBucketDuration() {
        assertEquals(0, VRingR26DeviceSupport.stepsPerMinute(0, 1800));
        assertEquals(0, VRingR26DeviceSupport.stepsPerMinute(100, 0));
        assertEquals(80, VRingR26DeviceSupport.stepsPerMinute(2400, 1800));
        assertEquals(135, VRingR26DeviceSupport.stepsPerMinute(4050, 1800));
    }

    @Test
    public void prorateTotalPreservesShare() {
        assertEquals(Integer.valueOf(250),
                VRingR26DeviceSupport.prorateTotal(1000, 25, 100));
        assertNull(VRingR26DeviceSupport.prorateTotal(0, 25, 100));
        assertNull(VRingR26DeviceSupport.prorateTotal(1000, 0, 100));
        assertNull(VRingR26DeviceSupport.prorateTotal(1000, 25, 0));
    }

    @Test
    public void historyDaysAreBoundedByLatestSample() {
        final TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            final Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            now.set(2026, Calendar.OCTOBER, 25, 12, 0, 0);
            now.set(Calendar.MILLISECOND, 0);

            assertEquals(7, VRingR26DeviceSupport.calculateHistoryDaysToFetch(
                    0, now.getTimeInMillis()));
            assertEquals(1, VRingR26DeviceSupport.calculateHistoryDaysToFetch(
                    timestamp(2026, Calendar.OCTOBER, 25, 1), now.getTimeInMillis()));
            assertEquals(2, VRingR26DeviceSupport.calculateHistoryDaysToFetch(
                    timestamp(2026, Calendar.OCTOBER, 24, 23), now.getTimeInMillis()));
            assertEquals(7, VRingR26DeviceSupport.calculateHistoryDaysToFetch(
                    timestamp(2026, Calendar.OCTOBER, 10, 12), now.getTimeInMillis()));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    private static int timestamp(final int year, final int month, final int day,
                                 final int hour) {
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.set(year, month, day, hour, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return (int) (calendar.getTimeInMillis() / 1000L);
    }
}
