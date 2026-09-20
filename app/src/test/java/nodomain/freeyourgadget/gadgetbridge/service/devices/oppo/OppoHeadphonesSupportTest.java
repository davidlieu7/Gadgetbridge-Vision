package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo;

import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.junit.Assert;
import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.OppoCommand;

public class OppoHeadphonesSupportTest extends TestBase {
    @Test
    public void testMultipleResponses() {
        final OppoHeadphonesSupport realSupport = new OppoHeadphonesSupport();
        final OppoHeadphonesSupport spySupport = Mockito.spy(realSupport);

        Mockito.doNothing().when(spySupport).handleCommand(Mockito.any(), Mockito.any());

        spySupport.onSocketRead(GB.hexStringToByteArray("AA4100000881013A00000E010100000101010101010205010103000101040C0101050001010600020100000201010102010206020103000201040B0201050002010600AA0F000006810208000003016402640346"));

        final InOrder inOrder = Mockito.inOrder(spySupport);
        inOrder.verify(spySupport).handleCommand(
                ArgumentMatchers.eq(OppoCommand.TOUCH_CONFIG_RET),
                ArgumentMatchers.eq(GB.hexStringToByteArray("000E010100000101010101010205010103000101040C0101050001010600020100000201010102010206020103000201040B0201050002010600")));
        inOrder.verify(spySupport).handleCommand(
                ArgumentMatchers.eq(OppoCommand.BATTERY_RET),
                ArgumentMatchers.eq(GB.hexStringToByteArray("0003016402640346")));
        inOrder.verifyNoMoreInteractions();
    }
}
