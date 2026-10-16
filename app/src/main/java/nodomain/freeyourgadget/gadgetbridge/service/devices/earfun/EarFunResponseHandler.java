package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun;

import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_AMBIENT_SOUND_CONTROL;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_ANC_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_AUDIO_CODEC;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_CONNECT_TWO_DEVICES_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_GAME_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_ADVANCED_AUDIO_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_IN_EAR_DETECTION_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_MICROPHONE_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_FIND_DEVICE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_TOUCH_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_TRANSPARENCY_MODE;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.PREF_EARFUN_VOICE_PROMPT_VOLUME;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.Interactions.interactionPrefs;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;

public class EarFunResponseHandler {

    /// The device reports 0x00 for "unknown" and 0xFF for "no value available".
    /// Measured on the Free Pro 3 (TW400, firmware v0.3.3): the case answers `00 FF` while the
    /// earbuds are out of it, i.e. nothing is being charged in the case.
    private static final int BATTERY_UNAVAILABLE = 0xFF;

    /// First byte of the ambient state pushes (0x1081 / 0x1082) is a bitfield.
    /// Measured on the Free Pro 3 by switching modes: 0x01 = nothing active, 0x05 = noise
    /// cancelling, 0x09 = transparency level TOTAL, 0x0A = transparency level NATRUAL.
    private static final int AMBIENT_STATE_NOISE_CANCELLING = 0x04;
    private static final int AMBIENT_STATE_TRANSPARENCY = 0x08;
    private static final int AMBIENT_STATE_LEVEL_MASK = 0x03;

    /// Values of `pref_earfun_ambient_sound_control` (see `earfun_ambient_sound_control_values`)
    public static final String AMBIENT_CONTROL_OFF = "0";
    public static final String AMBIENT_CONTROL_NOISE_CANCELLING = "1";
    public static final String AMBIENT_CONTROL_TRANSPARENCY = "2";

    public static GBDeviceEvent handleBatteryInfo(int index, byte[] payload) {
        // payload[1] is a signed byte: mask it, otherwise 0xFF reads as -1 and the "no value"
        // case only works by accident
        final int batteryLevel = payload[1] & 0xFF;
        final boolean available = batteryLevel > 0 && batteryLevel != BATTERY_UNAVAILABLE;
        GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
        batteryInfo.batteryIndex = index;
        batteryInfo.level = available ? batteryLevel : GBDevice.BATTERY_UNKNOWN;
        batteryInfo.state = available ? BatteryState.BATTERY_NORMAL : BatteryState.UNKNOWN;
        return batteryInfo;
    }

    public static GBDeviceEvent handleFirmwareVersionInfo(byte[] payload) {
        final GBDeviceEventVersionInfo versionInfo = new GBDeviceEventVersionInfo();
        String[] versionParts = (new String(payload, StandardCharsets.UTF_8)).split("_");
        versionInfo.fwVersion = versionParts[versionParts.length - 1];
        if (versionParts.length > 1) {
            versionInfo.hwVersion = String.join(" ", Arrays.copyOf(versionParts, versionParts.length - 1));
        } else {
            versionInfo.hwVersion = GBApplication.getContext().getString(R.string.n_a);
        }
        return versionInfo;
    }

    public static GBDeviceEvent handleGameModeInfo(byte[] payload) {
        boolean gameMode = payload[1] == 0x01;
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_GAME_MODE, gameMode);
    }

    public static GBDeviceEvent handleInEarDetectionModeInfo(byte[] payload) {
        // "0" means in ear detection is enabled
        boolean inEarDetectionMode = payload[1] == 0x00;
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_IN_EAR_DETECTION_MODE, inEarDetectionMode);
    }

    public static GBDeviceEvent handleTouchModeInfo(byte[] payload) {
        // "0" means touch is enabled
        boolean touchMode = payload[1] == 0x00;
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_TOUCH_MODE, touchMode);
    }

    public static GBDeviceEvent handleConnectTwoDevicesModeInfo(byte[] payload) {
        boolean touchMode = payload[1] == 0x01;
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_CONNECT_TWO_DEVICES_MODE, touchMode);
    }

    public static GBDeviceEvent handleAdvancedAudioModeInfo(byte[] payload) {
        // 00 = Google Fast Pair, 01 = LE Audio
        String advancedAudioMode = Integer.toString(payload[1]);
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_ADVANCED_AUDIO_MODE, advancedAudioMode);
    }

    public static GBDeviceEvent handleAudioCodecInfo(byte[] payload) {
        String audioCodec = Integer.toString(payload[1]);
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_AUDIO_CODEC, audioCodec);
    }

    public static GBDeviceEvent handleAmbientSoundInfo(byte[] payload) {
        String ambientSoundMode = Integer.toString(payload[1]);
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_AMBIENT_SOUND_CONTROL, ambientSoundMode);
    }

    public static GBDeviceEvent handleAncModeInfo(byte[] payload) {
        String ancMode = Integer.toString(payload[1]);
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_ANC_MODE, ancMode);
    }

    public static GBDeviceEvent handleTransparencyModeInfo(byte[] payload) {
        String transparencyMode = Integer.toString(payload[1]);
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_TRANSPARENCY_MODE, transparencyMode);
    }

    public static GBDeviceEvent handleMicrophoneModeInfo(byte[] payload) {
        String microphoneMode = Integer.toString(payload[1]);
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_MICROPHONE_MODE, microphoneMode);
    }

    public static GBDeviceEvent handleFindDeviceInfo(byte[] payload) {
        String findDeviceMode = Integer.toString(payload[1]);
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_FIND_DEVICE, findDeviceMode);
    }

    public static GBDeviceEvent handleVoicePromptVolumeInfo(byte[] payload) {
        // the volume has a scale from 4 to 0, where 0 is the highest volume and 4 is off,
        // to make it nicer for a slider, we reverse it
        int voicePromptVolume = payload[1];
        voicePromptVolume = Math.max(0, 4 - voicePromptVolume);
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_VOICE_PROMPT_VOLUME, voicePromptVolume);
    }

    public static GBDeviceEvent handleTouchActionInfo(byte[] payload) {
        GBDeviceEventUpdatePreferences updateEvent = new GBDeviceEventUpdatePreferences();
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.get();
        Arrays.stream(interactionPrefs).forEach(interactionType -> {
            // only use the last byte - the first byte initially contains some non zero data
            int action = buf.getShort() & 0xFF;
            updateEvent.preferences.put(interactionType, Integer.toString(action));
        });
        return updateEvent;
    }

    /**
     * Ambient state pushes (vendor 0x001D, commands 0x1081 / 0x1082).
     * <p>
     * These arrive on their own, without any subscription, every time the ambient mode changes —
     * including when the user toggles it with a gesture on the earbud. The first payload byte is
     * a bitfield, measured on the Free Pro 3 (firmware v0.3.3) by switching modes one at a time:
     * <pre>
     *   0x01  nothing active (mode Normal)
     *   0x05  noise cancelling active
     *   0x09  transparency active, level 1 (TOTAL)
     *   0x0A  transparency active, level 2 (NATRUAL)
     * </pre>
     * bit 2 (0x04) = noise cancelling, bit 3 (0x08) = transparency, bits 0-1 = level, which is the
     * `AmbientModeEnum` byte of the protocol (0 = default, 1 = TOTAL, 2 = NATRUAL).
     * <p>
     * The remaining bytes are not decoded: 0x1082 carries two extra opaque bytes, one per earbud.
     * Returns {@code null} when the value is not one we understand, so that nothing is written to
     * the preferences on a guess.
     */
    public static GBDeviceEvent handleAmbientStatePush(byte[] payload) {
        if (payload.length == 0) {
            return null;
        }
        final int state = payload[0] & 0xFF;
        final int level = state & AMBIENT_STATE_LEVEL_MASK;
        final GBDeviceEventUpdatePreferences updateEvent = new GBDeviceEventUpdatePreferences();
        if ((state & AMBIENT_STATE_NOISE_CANCELLING) != 0) {
            updateEvent.withPreference(PREF_EARFUN_AMBIENT_SOUND_CONTROL, AMBIENT_CONTROL_NOISE_CANCELLING);
        } else if ((state & AMBIENT_STATE_TRANSPARENCY) != 0) {
            updateEvent.withPreference(PREF_EARFUN_AMBIENT_SOUND_CONTROL, AMBIENT_CONTROL_TRANSPARENCY);
            // both measured levels (1 = TOTAL, 2 = NATRUAL) are valid transparency modes; the
            // higher bit combinations are not, so keep the current preference in that case
            if (level >= 1 && level <= 2) {
                updateEvent.withPreference(PREF_EARFUN_TRANSPARENCY_MODE, Integer.toString(level));
            }
        } else if ((state & ~AMBIENT_STATE_LEVEL_MASK) == 0 && level <= 1) {
            // no bit 2 and no bit 3: nothing is processed, the device is in Normal mode (0x01 was
            // measured in that state); a level above 1 is not a valid mode, so nothing is written
            updateEvent.withPreference(PREF_EARFUN_AMBIENT_SOUND_CONTROL, AMBIENT_CONTROL_OFF);
        } else {
            return null;
        }
        return updateEvent;
    }

    /**
     * Noise processing push (vendor 0x001D, command 0x1080): the second payload byte is 0x00 while
     * the device is in Normal mode and 0x01 as soon as *some* mode is active (measured on the Free
     * Pro 3). Which mode that is comes from 0x1081 / 0x1082, so only the "nothing active" case is
     * turned into an event; see {@link #handleAmbientStatePush(byte[])}.
     */
    public static GBDeviceEvent handleNoiseProcessingPush(byte[] payload) {
        if (payload.length < 2 || payload[1] != 0x00) {
            return null;
        }
        return new GBDeviceEventUpdatePreferences(PREF_EARFUN_AMBIENT_SOUND_CONTROL, AMBIENT_CONTROL_OFF);
    }
}
