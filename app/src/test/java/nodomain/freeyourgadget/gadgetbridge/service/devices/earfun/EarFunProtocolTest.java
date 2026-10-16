package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_AMBIENT_SOUND_CONTROL;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_GAME_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_TRANSPARENCY_MODE;

public class EarFunProtocolTest {

    private EarFunProtocol protocol;
    private GBDevice device;

    @Before
    public void setUp() {
        protocol = new EarFunProtocol(device);
    }

    @Test
    public void testDecodeResponse() {
        byte[] data = {(byte) 0xFF, 0x03, 0x00, 0x02, 0x00, 0x0A, 0x03, 0x07, 0x00, 0x64};

        GBDeviceEvent[] events = protocol.decodeResponse(data);

        assertNotNull(events);
        assertEquals(1, events.length);
        assertTrue(events[0] instanceof GBDeviceEventBatteryInfo);
        GBDeviceEventBatteryInfo batteryInfo = (GBDeviceEventBatteryInfo) events[0];
        assertEquals(1, batteryInfo.batteryIndex);
        assertEquals(100, batteryInfo.level);
    }

    @Test
    public void testDecodeResponseSkipsGarbageByte() {
        // a single unexpected byte must not cost us the packet that follows it
        byte[] data = EarFunPacketEncoder.joinPackets(
                new byte[]{(byte) 0x99},
                new EarFunPacket(EarFunPacket.Command.REQUEST_RESPONSE_BATTERY_STATE_RIGHT,
                        new byte[]{0x00, 0x64}).encode());

        GBDeviceEvent[] events = protocol.decodeResponse(data);

        assertEquals(1, events.length);
        GBDeviceEventBatteryInfo batteryInfo = (GBDeviceEventBatteryInfo) events[0];
        assertEquals(1, batteryInfo.batteryIndex);
        assertEquals(100, batteryInfo.level);
    }

    @Test
    public void testDecodeResponseNoiseCancellingPush() {
        // measured on the Free Pro 3: 0x1081 with 05 01 00 00 01 while noise cancelling is active
        byte[] data = new EarFunPacket(EarFunPacket.Command.AC_NOTIFICATION_MODE_CHANGE,
                new byte[]{0x05, 0x01, 0x00, 0x00, 0x01}).encode();

        GBDeviceEvent[] events = protocol.decodeResponse(data);

        assertEquals(1, events.length);
        GBDeviceEventUpdatePreferences update = (GBDeviceEventUpdatePreferences) events[0];
        assertEquals(EarFunResponseHandler.AMBIENT_CONTROL_NOISE_CANCELLING,
                update.preferences.get(PREF_EARFUN_AMBIENT_SOUND_CONTROL));
        assertFalse("noise cancelling carries no transparency level",
                update.preferences.containsKey(PREF_EARFUN_TRANSPARENCY_MODE));
    }

    @Test
    public void testDecodeResponseTransparencyPush() {
        // 0A 01 00 00 01 was measured while transparency was set to NATRUAL (level 2)
        byte[] data = new EarFunPacket(EarFunPacket.Command.AC_NOTIFICATION_GAIN_CHANGE,
                new byte[]{0x0a, 0x01, 0x00, 0x00, 0x01}).encode();

        GBDeviceEvent[] events = protocol.decodeResponse(data);

        assertEquals(1, events.length);
        GBDeviceEventUpdatePreferences update = (GBDeviceEventUpdatePreferences) events[0];
        assertEquals(EarFunResponseHandler.AMBIENT_CONTROL_TRANSPARENCY,
                update.preferences.get(PREF_EARFUN_AMBIENT_SOUND_CONTROL));
        assertEquals("2", update.preferences.get(PREF_EARFUN_TRANSPARENCY_MODE));
    }

    @Test
    public void testDecodeResponseNoiseProcessingInactive() {
        // 0x1080 carries "some noise mode is active" in its second byte
        byte[] data = new EarFunPacket(EarFunPacket.Command.AC_NOTIFICATION_STATE_CHANGE,
                new byte[]{0x01, 0x00}).encode();

        GBDeviceEvent[] events = protocol.decodeResponse(data);

        assertEquals(1, events.length);
        GBDeviceEventUpdatePreferences update = (GBDeviceEventUpdatePreferences) events[0];
        assertEquals(EarFunResponseHandler.AMBIENT_CONTROL_OFF,
                update.preferences.get(PREF_EARFUN_AMBIENT_SOUND_CONTROL));
    }

    @Test
    public void testDecodeResponseMultiplePackets() {
        byte[] data = EarFunPacketEncoder.joinPackets(
                new EarFunPacket(EarFunPacket.Command.REQUEST_RESPONSE_BATTERY_STATE_LEFT,
                        new byte[]{0x00, 0x64}).encode(),
                new EarFunPacket(EarFunPacket.Command.AC_NOTIFICATION_MODE_CHANGE,
                        new byte[]{0x0a, 0x01, 0x00, 0x00, 0x01}).encode());

        GBDeviceEvent[] events = protocol.decodeResponse(data);

        assertEquals(2, events.length);
        assertTrue(events[0] instanceof GBDeviceEventBatteryInfo);
        assertTrue(events[1] instanceof GBDeviceEventUpdatePreferences);
    }
}
