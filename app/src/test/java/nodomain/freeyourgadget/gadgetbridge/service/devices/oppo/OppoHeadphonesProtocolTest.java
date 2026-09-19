package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo;

import org.junit.Assert;
import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class OppoHeadphonesProtocolTest extends TestBase {
    @Test
    public void testMultipleResponses() {
        final OppoHeadphonesProtocol protocol = new OppoHeadphonesProtocol(null);
        GBDeviceEvent[] events = protocol.decodeResponse(GB.hexStringToByteArray("AA4100000881013A00000E010100000101010101010205010103000101040C0101050001010600020100000201010102010206020103000201040B0201050002010600AA0F000006810208000003016402640346"));
        Assert.assertEquals(4, events.length);
        Assert.assertTrue(events[0] instanceof GBDeviceEventUpdatePreferences);
        Assert.assertTrue(events[1] instanceof GBDeviceEventBatteryInfo);
        Assert.assertTrue(events[2] instanceof GBDeviceEventBatteryInfo);
        Assert.assertTrue(events[3] instanceof GBDeviceEventBatteryInfo);
    }
}
