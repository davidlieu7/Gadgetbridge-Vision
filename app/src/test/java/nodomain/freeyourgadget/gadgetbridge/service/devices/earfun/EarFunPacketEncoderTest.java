package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.Equalizer;
import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.Interactions;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class EarFunPacketEncoderTest {
    Prefs mockPrefs = mock(Prefs.class);

    @Test
    public void testEncodeSetGesture() {
        when(mockPrefs.getString(anyString(), anyString())).thenReturn("1");

        byte[] expected = new EarFunPacket(EarFunPacket.Command.SET_TOUCH_ACTION, new byte[]{1, 1, 0, 0}).encode();
        assertArrayEquals(expected, EarFunPacketEncoder.encodeSetGesture(mockPrefs, "anyKey",
                Interactions.InteractionType.SINGLE,
                Interactions.Position.LEFT)
        );
    }

    @Test
    public void testEncodeSetEqualizerSixBands() {
        for (Equalizer.BandConfig bandConfig : Equalizer.SixBandEqualizer) {
            if (bandConfig.key != null) {
                when(mockPrefs.getInt(bandConfig.key, 0)).thenReturn(5);
            }
        }

        byte[] result = EarFunPacketEncoder.encodeSetEqualizerSixBands(mockPrefs);
        assertNotNull(result);
        assertEquals(170, result.length);
    }

    @Test
    public void testEncodeSetEqualizerTenBands() {
        for (Equalizer.BandConfig bandConfig : Equalizer.TenBandEqualizer) {
            if (bandConfig.key != null) {
                when(mockPrefs.getInt(bandConfig.key, 0)).thenReturn(-5);
            }
        }

        byte[] result = EarFunPacketEncoder.encodeSetEqualizerTenBands(mockPrefs);
        assertNotNull(result);
        assertEquals(170, result.length);
    }

    @Test
    public void testEncodeFirmwareVersionReq() {
        byte[] encoded = EarFunPacketEncoder.encodeFirmwareVersionReq();

        assertNotNull(encoded);
        assertEquals(8, encoded.length);
    }

    @Test
    public void testEncodeFreePro3SettingsReq() {
        List<EarFunPacket.Command> commands = decodeCommands(EarFunPacketEncoder.encodeFreePro3SettingsReq());

        assertEquals(10, commands.size());
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_BATTERY_STATE_LEFT));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_BATTERY_STATE_RIGHT));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_BATTERY_STATE_CASE));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_TOUCH_ACTION));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_TOUCH_MODE));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_CONNECT_TWO_DEVICES));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_MICROPHONE_MODE));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_FIND_DEVICE));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_VOICE_PROMPT_VOLUME));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_AUDIO_CODEC));
        // measured to never answer on the Free Pro 3 (firmware v0.3.3), so they are not polled
        assertFalse(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_DISABLE_IN_EAR_DETECTION));
        assertFalse(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_ADVANCED_AUDIO_MODE));
        // the sound settings are not part of this request: they are covered by
        // encodeSoundSettingsReq(), written when the settings screen is opened
        assertFalse(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_AMBIENT_SOUND));
        assertFalse(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_ANC_MODE));
        assertFalse(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_TRANSPARENCY_MODE));
        assertFalse(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_GAME_MODE));
    }

    @Test
    public void testEncodeAirPro4SettingsReqKeepsUnsupportedCommands() {
        // documents why the Free Pro 3 has its own request: the Air Pro 4 template polls both
        List<EarFunPacket.Command> commands = decodeCommands(EarFunPacketEncoder.encodeAirPro4SettingsReq());

        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_DISABLE_IN_EAR_DETECTION));
        assertTrue(commands.contains(EarFunPacket.Command.REQUEST_RESPONSE_ADVANCED_AUDIO_MODE));
    }

    private static List<EarFunPacket.Command> decodeCommands(byte[] data) {
        List<EarFunPacket.Command> commands = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            EarFunPacket packet = EarFunPacket.decode(buf);
            if (packet == null) {
                break;
            }
            commands.add(packet.getCommand());
        }
        return commands;
    }

    @Test
    public void testJoinPacketsMultipleArrays() {
        byte[] array1 = {1, 2, 3};
        byte[] array2 = {4, 5, 6};
        byte[] array3 = {7, 8, 9};
        byte[] expected = {1, 2, 3, 4, 5, 6, 7, 8, 9};

        byte[] result = EarFunPacketEncoder.joinPackets(array1, array2, array3);
        assertArrayEquals(expected, result);
    }

    @Test
    public void testJoinPacketsEmptyArray() {
        byte[] array1 = {};
        byte[] array2 = {1, 2, 3};
        byte[] expected = {1, 2, 3};

        byte[] result = EarFunPacketEncoder.joinPackets(array1, array2);
        assertArrayEquals(expected, result);
    }

    @Test
    public void testJoinPacketsSingleArray() {
        byte[] array1 = {1, 2, 3};
        byte[] expected = {1, 2, 3};

        byte[] result = EarFunPacketEncoder.joinPackets(array1);
        assertArrayEquals(expected, result);
    }

    @Test
    public void testJoinPacketsNoArrays() {
        byte[] expected = {};

        byte[] result = EarFunPacketEncoder.joinPackets();
        assertArrayEquals(expected, result);
    }

    @Test
    public void testJoinPacketsDifferentLengths() {
        byte[] array1 = {1, 2};
        byte[] array2 = {3, 4, 5, 6};
        byte[] array3 = {7};
        byte[] expected = {1, 2, 3, 4, 5, 6, 7};

        byte[] result = EarFunPacketEncoder.joinPackets(array1, array2, array3);
        assertArrayEquals(expected, result);
    }

    @Test
    public void testJoinPacketsListInput() {
        List<byte[]> arrays = Arrays.asList(new byte[]{1, 2}, new byte[]{3, 4, 5}, new byte[]{6, 7, 8, 9});
        byte[] expected = {1, 2, 3, 4, 5, 6, 7, 8, 9};

        byte[] result = EarFunPacketEncoder.joinPackets(arrays);
        assertArrayEquals(expected, result);
    }
}
