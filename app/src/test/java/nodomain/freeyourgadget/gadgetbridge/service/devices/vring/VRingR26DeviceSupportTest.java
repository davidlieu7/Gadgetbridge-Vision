package nodomain.freeyourgadget.gadgetbridge.service.devices.vring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class VRingR26DeviceSupportTest {
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
