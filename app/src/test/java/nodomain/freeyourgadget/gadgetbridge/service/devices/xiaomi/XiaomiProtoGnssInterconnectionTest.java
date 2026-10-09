package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Gnss;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.GnssLocation;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Holiday;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Schedule;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Commands captured from a Xiaomi Smart Band 10 Pro and Smart Band 8 Pro.
 */
public class XiaomiProtoGnssInterconnectionTest {
    private static XiaomiProto.Command parse(final String hex) throws Exception {
        return XiaomiProto.Command.parseFrom(GB.hexStringToByteArray(hex));
    }

    @Test
    public void gnssOfflineRequest() throws Exception {
        final XiaomiProto.Command cmd = parse("0810100192011312110801120B6169726F68615F656C706F1803");
        assertEquals(16, cmd.getType());
        assertEquals(1, cmd.getSubtype());
        assertEquals(1, cmd.getGnss().getAssistRequest().getType());
        assertEquals("airoha_elpo", cmd.getGnss().getAssistRequest().getSource());
        assertEquals(3, cmd.getGnss().getAssistRequest().getDays());
        assertFalse(cmd.getGnss().getAssistRequest().getNeedLocation());
    }

    @Test
    public void gnssOfflineRequestWithLocation() throws Exception {
        final XiaomiProto.Command cmd = parse("08101001920110120E080112066169726F686118032001");
        assertEquals("airoha", cmd.getGnss().getAssistRequest().getSource());
        assertTrue(cmd.getGnss().getAssistRequest().getNeedLocation());
    }

    @Test
    public void gnssLocationRequest() throws Exception {
        final XiaomiProto.Command cmd = parse("08101003920100");
        assertEquals(16, cmd.getType());
        assertEquals(3, cmd.getSubtype());
        assertTrue(cmd.hasGnss());
    }

    @Test
    public void gnssLocationResponse_usesDoubleFieldsOfField18() {
        final XiaomiProto.Command cmd = XiaomiProto.Command.newBuilder()
                .setType(16)
                .setSubtype(2)
                .setGnss(Gnss.newBuilder().setLocation(GnssLocation.newBuilder()
                        .setTimestamp(1)
                        .setLongitude(1.0)
                        .setLatitude(2.0)))
                .build();
        // gnss = field 18 -> location = field 3 -> timestamp varint, longitude/latitude fixed64
        assertEquals(
                "08101002" + "9201" + "16" + "1A" + "14" + "0801"
                        + "11000000000000F03F" + "190000000000000040",
                GB.hexdump(cmd.toByteArray()).toUpperCase()
        );
    }

    @Test
    public void interconnectionCommands() throws Exception {
        XiaomiProto.Command cmd = parse("08171000CA01040A020800");
        assertEquals(23, cmd.getType());
        assertEquals(0, cmd.getSubtype());
        assertTrue(cmd.getInterconnection().hasPhoneStatus());

        cmd = parse("08171011CA0109820106080010001800");
        assertEquals(17, cmd.getSubtype());
        assertTrue(cmd.getInterconnection().hasPhoneUsage());
        assertFalse(cmd.getInterconnection().getPhoneUsage().getHold());

        cmd = parse("08171002CA010462020801");
        assertEquals(2, cmd.getSubtype());
        assertTrue(cmd.getInterconnection().getRequestInfo().getOnlyAudio());
    }

    @Test
    public void holidayRequestAndResponse() throws Exception {
        final XiaomiProto.Command request = parse("081110079A01053A0308EA0F");
        assertEquals(17, request.getType());
        assertEquals(7, request.getSubtype());
        assertEquals(2026, request.getSchedule().getHoliday().getYear());

        final XiaomiProto.Command response = XiaomiProto.Command.newBuilder()
                .setType(17)
                .setSubtype(6)
                .setSchedule(Schedule.newBuilder().setHoliday(Holiday.newBuilder().setYear(2026)))
                .build();
        assertEquals("081110069A01053A0308EA0F", GB.hexdump(response.toByteArray()).toUpperCase());
    }
}
