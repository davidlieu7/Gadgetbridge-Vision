package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.devices;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.devices.sony.headphones.prefs.AmbientSoundControlButtonMode;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.AncMode;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.AmbientSoundMode;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class SoundcoreA30iProtocol extends SoundcoreProtocolImplV1 {

    private static final int battery_earphone_left = 0;
    private static final int battery_earphone_right = 1;

    public SoundcoreA30iProtocol(GBDevice device) {
        super(device);
    }

    @Override
    protected void decodeAudioMode(final byte[] payload) {
        decodeAmbientSoundControl(payload, 0);
    }

    @Override
    protected GBDeviceEvent[] decodeDeviceInfo(final byte[] payload) {
        if (payload == null || payload.length < 78) {
            return new GBDeviceEvent[0];
        }

        int DEVICE_INFO_CONTROL_OFFSET = 55;
        final SoundcoreEarbudsCoordinator.TapFunction lSingle = decodeConfiguredTapFunction(payload[DEVICE_INFO_CONTROL_OFFSET]     & 0x0f);
        final SoundcoreEarbudsCoordinator.TapFunction rSingle = decodeConfiguredTapFunction(payload[DEVICE_INFO_CONTROL_OFFSET + 1] & 0x0f);
        final SoundcoreEarbudsCoordinator.TapFunction lDouble = decodeConfiguredTapFunction(payload[DEVICE_INFO_CONTROL_OFFSET + 2] & 0x0f);
        final SoundcoreEarbudsCoordinator.TapFunction rDouble = decodeConfiguredTapFunction(payload[DEVICE_INFO_CONTROL_OFFSET + 3] & 0x0f);
        final SoundcoreEarbudsCoordinator.TapFunction lTriple = decodeConfiguredTapFunction(payload[DEVICE_INFO_CONTROL_OFFSET + 4] & 0x0f);
        final SoundcoreEarbudsCoordinator.TapFunction rTriple = decodeConfiguredTapFunction(payload[DEVICE_INFO_CONTROL_OFFSET + 5] & 0x0f);
        final SoundcoreEarbudsCoordinator.TapFunction lLong   = decodeConfiguredTapFunction(payload[DEVICE_INFO_CONTROL_OFFSET + 6] & 0x0f);
        final SoundcoreEarbudsCoordinator.TapFunction rLong   = decodeConfiguredTapFunction(payload[DEVICE_INFO_CONTROL_OFFSET + 7] & 0x0f);

        final AmbientSoundControlButtonMode controlButtonMode = decodeControlAmbientMode(payload[DEVICE_INFO_CONTROL_OFFSET + 8] & 0x0f);

        String controlButtonModeString = controlButtonMode.name().toLowerCase(Locale.ROOT);

        //-------------------------------------------------------------------------
        // Active Noise Cancelling [64 to 70 (7 bytes)]
        decodeAmbientSoundControl(payload, 64);
        //-------------------------------------------------------------------------
        boolean touchTone = payload[72] == 0x01;
        boolean surround3d = payload[74] == 0x01;
        boolean low_battery = payload[77] == 0x01;
        // AutoPowerOff duration=0 means never (disabled); 1=10min, 2=20min, 3=30min, 4=60min.
        final boolean autoPowerEnabled = payload[75] == 0x01;
        final int autoPowerDuration = autoPowerEnabled ? (payload[76] & 0xFF) + 1 : 0;

        getDevicePrefs().getPreferences().edit()
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_LEFT,  lSingle.name())
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_SINGLE_TAP_ACTION_RIGHT, rSingle.name())
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT,  lDouble.name())
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT, rDouble.name())
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_LEFT,  lTriple.name())
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_RIGHT, rTriple.name())
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT,  lLong.name())
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT, rLong.name())
            .putString(DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_CONTROL_BUTTON_MODE, controlButtonModeString)

            .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_3D_SURROUND, surround3d)
            .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_BATTERY_LOW_TONE, low_battery)
            .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE, touchTone)
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AUTO_POWER_OFF, String.valueOf(autoPowerDuration))
            .apply();

        return decodeStandardEarbudDeviceInfo(payload);
    }

    private void decodeAmbientSoundControl(byte[] payload, int offset){
        final AmbientSoundMode ambientSoundControl = getAmbientSoundControl(payload[offset], payload[offset + 3]);
        final AncMode ancMode = AncMode.fromByte(payload[offset + 6]);
        int noiseCancellingLevel = decodeNoiseCancellationLevel(payload[offset + 1]);
        boolean wind_noise_reduction = payload[offset + 4] == 0x01;

        getDevicePrefs().getPreferences().edit()
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL, ambientSoundControl != null ? ambientSoundControl.toString() : "Unknown Mode")
            .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ANC_MODE, ancMode != null ? ancMode.toString() : "Unknown Mode")
            .putInt(DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL, noiseCancellingLevel)
            .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION, wind_noise_reduction)
            .apply();
    }

    @Override
    protected int getAmbientFunctionGroup(){
        return 3;
    }

    @Override
    protected int getTapFunctionGroup(final SoundcoreEarbudsCoordinator.ButtonAction buttonAction) {
        switch (buttonAction) {
            case SINGLE_TAP: return 15;
            case TRIPLE_TAP: //Only the device specific part is identical with DOUBLE_TAP, the second half of the
                             //byte is not model specific and will be set in SoundcoreProtocolImplV1
            case DOUBLE_TAP: return 6;
            case LONG_PRESS: return 4;
            default: throw new IllegalArgumentException("Unknown button action: " + buttonAction);
        }
    }

    @Override
    protected int[] getBatteryIndexes(final int payloadLength) {
        return new int[]{battery_earphone_left, battery_earphone_right};
    }

    @Nullable
    @Override
    public byte[] encodeSendConfiguration(final String config) {
        switch (config) {
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ANC_MODE:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION:
            case DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL:
                final Prefs prefs = getDevicePrefs();
                final AmbientSoundMode ambientSoundControl = AmbientSoundMode.fromPreference(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL, AmbientSoundMode.OFF.toString()));
                final AncMode ancMode = AncMode.fromPreference(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ANC_MODE, AncMode.TRANSPORT.toString()));
                final int noiseCancellingLevel = prefs.getInt(DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL, 1);
                final boolean windNoiseReduction = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION, false);

                final byte[] command = encodeAudioMode(ambientSoundControl, ancMode, noiseCancellingLevel, windNoiseReduction);
                return command != null ? command : super.encodeSendConfiguration(config);
            default:
                return super.encodeSendConfiguration(config);
        }
    }

    /**
     * It returns the current ambient sound control.
     */
    private AmbientSoundMode getAmbientSoundControl(byte ambientSoundControlSelection, byte mode) {
        return switch (ambientSoundControlSelection) {
            case 0x00 -> switch (mode) {
                case 0x00 -> AmbientSoundMode.NOISE_CANCELLING;
                case 0x01 -> AmbientSoundMode.ADAPTIVE_ANC;
                case 0x02 -> AmbientSoundMode.AMBIENT_SOUND;
                default -> null;
            };
            case 0x01 -> AmbientSoundMode.TRANSPARENCY;
            case 0x02 -> AmbientSoundMode.OFF;
            default -> null;
        };
    }

    /**
     * It builds the content for the ANC command.
     * It always needs all information, independent of the mode.
     * @return The content for the ANC command; it returns null if an unknown value was requested.
     */
    private byte[] encodeAudioMode(AmbientSoundMode ambientSoundControl, AncMode ancMode, int noiseCancellingLevel, boolean wind_noise_reduction) {
        if (ambientSoundControl == null || ancMode == null) {
            return null;
        }
        final Byte ancStrength = encodeNoiseCancellationLevel(noiseCancellingLevel);
        if (ancStrength == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(7);
        // A30i ambient sound is an ANC subtype, not its transparency mode.
        final byte mode = ambientSoundControl == AmbientSoundMode.AMBIENT_SOUND
                ? AmbientSoundMode.NOISE_CANCELLING.toByte()
                : ambientSoundControl.toByte();

        buf.put(mode);

        buf.put(ancStrength.byteValue());

        buf.put(mode); // Probably a duplicated value cached from before.

        switch (ambientSoundControl) {
            case ADAPTIVE_ANC -> buf.put((byte) 0x01);
            case AMBIENT_SOUND -> buf.put((byte) 0x02);
            case NOISE_CANCELLING, OFF, TRANSPARENCY -> buf.put((byte) 0x00);
            default -> {
                return null;
            }
        }

        buf.put(encodeBoolean(wind_noise_reduction));

        buf.put((byte) 0x00); // Byte seems to be unused.

        buf.put(ancMode.toByte());
        return encodeCommand(CMD_SET_AUDIO_MODE, buf.array());
    }
}
