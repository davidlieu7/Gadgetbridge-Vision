package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.a30i;

import static nodomain.freeyourgadget.gadgetbridge.util.GB.hexdump;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.AncMode;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.AmbientSoundMode;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcorePacket;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class SoundcoreA30iProtocol extends SoundcoreProtocolImplV1 {

    private static final Logger LOG = LoggerFactory.getLogger(SoundcoreA30iProtocol.class);

    private static final int battery_earphone_left = 0;
    private static final int battery_earphone_right = 1;

    public SoundcoreA30iProtocol(GBDevice device) {
        super(device);
    }

    @Nullable
    @Override
    public GBDeviceEvent[] decodeResponse(byte[] responseData) {
        SoundcorePacket packet = decodePacket(responseData);

        if (packet == null)
            return null;

        List<GBDeviceEvent> devEvts = new ArrayList<>();

        byte[] payload = packet.getPayload();
        short cmd = packet.getCommand();

        if (cmd == CMD_GET_DEVICE_INFO) {
            // Firmware Version and Serial Number
            String firmware1 = readString(payload, 6, 5);
            String firmware2 = readString(payload, 11, 5);
            String serialNumber = readString(payload, 16, 16);
            devEvts.add(buildVersionInfo(firmware1, firmware2, serialNumber));

            // Battery Info
            handleBatteryInfo(devEvts, payload[2], payload[3]);

            //-------------------------------------------------------------------------
            // Active Noise Cancelling [63 to 70 (7 bytes)]
            final AmbientSoundMode ambientSoundControl = getAmbientSoundControl(payload[64], payload[67]);
            final AncMode ancMode = AncMode.fromByte(payload[70]);
            int noiseCancellingLevel = getNoiseCancellingLevel(payload[65]);
            boolean wind_noise_reduction = payload[68] == 0x01;
            //-------------------------------------------------------------------------
            boolean touchTone = payload[72] == 0x01;
            boolean surround3d = payload[74] == 0x01;
            boolean low_battery = payload[77] == 0x01;
            // AutoPowerOff duration=0 means never (disabled); 1=10min, 2=20min, 3=30min, 4=60min.
            final boolean autoPowerEnabled = payload[75] == 0x01;
            final int autoPowerDuration = autoPowerEnabled ? (payload[76] & 0xFF) + 1 : 0;

            getDevicePrefs().getPreferences().edit()
                    .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL, ambientSoundControl != null ? ambientSoundControl.toString() : "Unknown Mode")
                    .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ANC_MODE, ancMode != null ? ancMode.toString() : "Unknown Mode")
                    .putInt(DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL, noiseCancellingLevel)
                    .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION, wind_noise_reduction)

                    .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_3D_SURROUND, surround3d)
                    .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_BATTERY_LOW_TONE, low_battery)
                    .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE, touchTone)
                    .putString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AUTO_POWER_OFF, String.valueOf(autoPowerDuration))
                    .apply();

            LOG.debug("Message: CMD_GET_DEVICE_INFO");

        }else if(cmd == CMD_SESSION_INIT) {
            LOG.debug("Message: Initialisation finished!");
        }else if(cmd == CMD_NOTIFY_BATTERY_INFO){
            handleBatteryInfo(devEvts, payload[0], payload[1]);
            LOG.debug("Message: CMD_NOTIFY_BATTERY_INFO");
        }else {
            LOG.debug("cmd          :" + cmd);
            LOG.debug("Payload      :" + hexdump(payload));
            LOG.debug("Respond Data:" + hexdump(responseData));
        }
        return devEvts.toArray(new GBDeviceEvent[devEvts.size()]);
    }

    /**
     * It returns the current ANC level.
     */
    private int getNoiseCancellingLevel(byte noise_level){
        return switch (noise_level) {
            case 0x51 -> 5;
            case 0x45 -> 4;
            case 0x35 -> 3;
            case 0x25 -> 2;
            case 0x15 -> 1;
            default -> 1;
        };
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
     * It calculates the Battery Level for both In-Ear speaker.
     */
    private void handleBatteryInfo(List<GBDeviceEvent> devEvts, byte batteryLeft, byte batteryRight) {
        int batteryLeftLevel = (batteryLeft + 1) * 10;
        int batteryRightLevel = (batteryRight + 1) * 10;
        devEvts.add(buildBatteryInfo(battery_earphone_left, batteryLeftLevel));
        devEvts.add(buildBatteryInfo(battery_earphone_right, batteryRightLevel));
    }

    @Nullable
    @Override
    public byte[] encodeSendConfiguration(String config) {
        Prefs prefs = getDevicePrefs();

        switch (config) {
            // Control
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE:
                // It enables/disables the sound if the user touches the device button.
                final boolean touchTone = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE, false);
                return encodeBooleanCommand(CMD_SET_TOUCH_TONE, touchTone);

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_3D_SURROUND:
                // It enables/disables the 3D-surround sound.
                final boolean surround3d = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_3D_SURROUND, false);
                return encodeBooleanCommand(CMD_SET_3D_SURROUND, surround3d);

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_BATTERY_LOW_TONE:
                // It enables/disables the low battery tone of the device at 20% and 10%.
                final boolean low_battery = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_BATTERY_LOW_TONE, false);
                return encodeBooleanCommand(CMD_SET_BATTERY_LOW_TONE, low_battery);

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ANC_MODE, DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL,
                 DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL, DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION:
                // It sets the ANC mode. It always sends the same command (with different values),
                // independent of which part of the ANC setting was changed.
                final AmbientSoundMode ambientSoundControl = AmbientSoundMode.fromPreference(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL, AmbientSoundMode.OFF.toString()));
                final AncMode ancMode = AncMode.fromPreference(
                        prefs.getString(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_ANC_MODE, AncMode.TRANSPORT.toString()));
                int noiseCancellingLevel = prefs.getInt(DeviceSettingsPreferenceConst.PREF_SONY_AMBIENT_SOUND_LEVEL, 0);
                boolean wind_noise_reduction = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_WIND_NOISE_REDUCTION, false);

                byte[] audioModeCommand = encodeAudioMode(ambientSoundControl, ancMode, noiseCancellingLevel, wind_noise_reduction);
                if (audioModeCommand != null) {
                    return audioModeCommand;
                }
                return super.encodeSendConfiguration(config);

            default:
                LOG.debug("Unsupported CONFIG: " + config);
        }
        return super.encodeSendConfiguration(config);
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
        ByteBuffer buf = ByteBuffer.allocate(7);
        // A30i ambient sound is an ANC subtype, not its transparency mode.
        final byte mode = ambientSoundControl == AmbientSoundMode.AMBIENT_SOUND
                ? AmbientSoundMode.NOISE_CANCELLING.toByte()
                : ambientSoundControl.toByte();

        buf.put(mode);

        switch (noiseCancellingLevel){
            case 5 -> buf.put((byte) 0x51);
            case 4 -> buf.put((byte) 0x45);
            case 3 -> buf.put((byte) 0x35);
            case 2 -> buf.put((byte) 0x25);
            case 1 -> buf.put((byte) 0x15);
            default -> {
                return null;
            }
        }

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


    /** Sent after CMD_GET_DEVICE_INFO, to finalise the session with the device. */
    public byte[] encodeSessionInitRequest() {
        return encodeRequest(CMD_SESSION_INIT);
    }

    /** Request more device info during init; currently not used because
     * it is unclear which additional information are in the returned message. */
    public byte[] encodeSessionInitMoreInfoRequest() {
        return encodeRequest(CMD_GET_EXTENDED_INFO);
    }
}
