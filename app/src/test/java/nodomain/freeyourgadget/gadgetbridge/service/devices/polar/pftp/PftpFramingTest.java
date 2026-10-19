/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar.pftp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Frames are taken from a BLE capture of Polar Flow talking to a Polar Ignite 3. */
public class PftpFramingTest {
    private static final int FRAME_SIZE = 229; // negotiated ATT MTU 232 - 3

    @Test
    public void requestMatchesCapture() {
        final byte[] message = PftpFraming.request(PolarProto.PbPFtpOperation.Command.GET, "/DEVICE.BPB", null);
        final List<byte[]> frames = PftpFraming.frames(message, FRAME_SIZE);
        assertEquals(1, frames.size());
        assertArrayEquals(GB.hexStringToByteArray("020f000800120b2f4445564943452e425042"), frames.get(0));
    }

    @Test
    public void queryMatchesCapture() {
        // SET_LOCAL_TIME 2026-10-19 23:22:48.361, UTC+120 minutes
        final PolarProto.PbPFtpSetLocalTimeParams params = PolarProto.PbPFtpSetLocalTimeParams.newBuilder()
                .setDate(PolarProto.PbDate.newBuilder().setYear(2026).setMonth(10).setDay(19))
                .setTime(PolarProto.PbTime.newBuilder().setHour(23).setMinute(22).setSeconds(48).setMillis(361))
                .setTzOffset(120)
                .build();
        final byte[] message = PftpFraming.query(PftpConstants.QUERY_SET_LOCAL_TIME, params.toByteArray());
        final List<byte[]> frames = PftpFraming.frames(message, FRAME_SIZE);
        assertEquals(1, frames.size());
        assertArrayEquals(GB.hexStringToByteArray("0203800a0708ea0f100a1813120908171016183020e9021878"), frames.get(0));
    }

    @Test
    public void reassembleMultiFrameResponse() throws Exception {
        final PftpFraming.Reassembler reassembler = new PftpFraming.Reassembler();
        assertNull(reassembler.add(GB.hexStringToByteArray(
                "060a0608031002180112060800100918051a06080610001812320831314241334433453a0b506f6c61725f494e57354a42"
                        + "0c3030373130363036342e30394a0542726f776e5206556e697365785a103234414341434646464531314241334"
                        + "46214cd013e1c74ee443d6d2580e84d975c06b8d6149d6a06083d1000180072100a06426c654170701a060803100518"
                        + "0172170a0d426c65426f6f746c6f616465721a0608041003180072170a034750531a100801100018002208617630305"
                        + "f32323272140a05546f7563681a0b080210141801220372383078018201180a0608041001180012")));
        final PftpFraming.Message message = reassembler.add(GB.hexStringToByteArray(
                "13060814100018001a060801100018008a010d0a095a485f4a412e524157100292010f73706f72745f69636f6e305f61"
                        + "766492010b73796e63616c6c2e74677a92011273796e63706172745f77726974652e74677a920108736167726663"
                        + "393892010f6d756c7469706c655f616c61726d7392010b7270655f67726164696e6792011570736d74705f666c6f"
                        + "775f636f6e74726f6c3d31309a0106474c4f42414c"));
        assertNotNull(message);
        assertNotNull(message.payload);

        final PolarProto.PbDeviceInfo info = PolarProto.PbDeviceInfo.parseFrom(message.payload);
        assertEquals(6, info.getDeviceVersion().getMajor());
        assertEquals(0, info.getDeviceVersion().getMinor());
        assertEquals(18, info.getDeviceVersion().getPatch());
        assertEquals("11BA3D3E", info.getDeviceID());
        assertEquals("Polar_INW5J", info.getModelName());
    }

    @Test
    public void statusFrame() {
        final PftpFraming.Message ok = new PftpFraming.Reassembler().add(new byte[]{0x00, 0x00, 0x00});
        assertNotNull(ok);
        assertTrue(ok.isStatus());
        assertEquals(PftpConstants.ERROR_OPERATION_SUCCEEDED, ok.error);

        final PftpFraming.Message notFound = new PftpFraming.Reassembler().add(new byte[]{0x00, 0x67, 0x00});
        assertNotNull(notFound);
        assertEquals(PftpConstants.ERROR_NO_SUCH_FILE_OR_DIRECTORY, notFound.error);
    }

    @Test
    public void framesRoundTrip() {
        final byte[] message = new byte[1000];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) i;
        }
        final List<byte[]> frames = PftpFraming.frames(message, FRAME_SIZE);
        assertEquals(5, frames.size());

        final PftpFraming.Reassembler reassembler = new PftpFraming.Reassembler();
        PftpFraming.Message result = null;
        for (final byte[] frame : frames) {
            assertNull(result);
            result = reassembler.add(frame);
        }
        assertNotNull(result);
        assertArrayEquals(message, result.payload);
    }

    @Test
    public void sequenceGapIsRejected() {
        final List<byte[]> frames = PftpFraming.frames(new byte[600], FRAME_SIZE);
        final PftpFraming.Reassembler reassembler = new PftpFraming.Reassembler();
        reassembler.add(frames.get(0));
        assertThrows(IllegalStateException.class, () -> reassembler.add(frames.get(2)));
    }
}
