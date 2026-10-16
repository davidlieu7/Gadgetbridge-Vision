package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun;

import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunPacketEncoder.joinPackets;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunResponseHandler.*;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.EarFunSettingsPreferenceConst.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.Interactions;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class EarFunProtocol extends GBDeviceProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(EarFunProtocol.class);

    @Override
    public GBDeviceEvent[] decodeResponse(byte[] data) {
        List<GBDeviceEvent> events = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data);

        while (buf.hasRemaining()) {
            EarFunPacket packet = EarFunPacket.decode(buf);

            if (packet == null) {
                // decode() has already skipped the byte it failed on: keep going so that the packets
                // following a malformed one are not lost as well
                continue;
            }

            EarFunPacket.Command command = packet.getCommand();
            byte[] payload = packet.getPayload();
            LOG.info("received {} {}", packet.getCommand().name(), packet);

            switch (command) {
                case REQUEST_RESPONSE_BATTERY_STATE_LEFT:
                    events.add(handleBatteryInfo(0, payload));
                    break;
                case REQUEST_RESPONSE_BATTERY_STATE_RIGHT:
                    events.add(handleBatteryInfo(1, payload));
                    break;
                case REQUEST_RESPONSE_BATTERY_STATE_CASE:
                    events.add(handleBatteryInfo(2, payload));
                    break;
                case REQUEST_RESPONSE_FIRMWARE_VERSION:
                    events.add(handleFirmwareVersionInfo(payload));
                    break;
                case REQUEST_RESPONSE_GAME_MODE:
                    events.add(handleGameModeInfo(payload));
                    break;
                case REQUEST_RESPONSE_AMBIENT_SOUND:
                    events.add(handleAmbientSoundInfo(payload));
                    break;
                case REQUEST_RESPONSE_ANC_MODE:
                    events.add(handleAncModeInfo(payload));
                    break;
                case REQUEST_RESPONSE_TRANSPARENCY_MODE:
                    events.add(handleTransparencyModeInfo(payload));
                    break;
                case REQUEST_RESPONSE_TOUCH_ACTION:
                    events.add(handleTouchActionInfo(payload));
                    break;
                // do nothing with these, they are returned after each EQ set operation and always return 01
                case RESPONSE_EQUALIZER_BAND:
                    break;
                case REQUEST_RESPONSE_DISABLE_IN_EAR_DETECTION:
                    events.add(handleInEarDetectionModeInfo(payload));
                    break;
                case REQUEST_RESPONSE_TOUCH_MODE:
                    events.add(handleTouchModeInfo(payload));
                    break;
                case REQUEST_RESPONSE_CONNECT_TWO_DEVICES:
                    events.add(handleConnectTwoDevicesModeInfo(payload));
                    break;
                case REQUEST_RESPONSE_ADVANCED_AUDIO_MODE:
                    events.add(handleAdvancedAudioModeInfo(payload));
                    break;
                case REQUEST_RESPONSE_MICROPHONE_MODE:
                    events.add(handleMicrophoneModeInfo(payload));
                    break;
                case REQUEST_RESPONSE_FIND_DEVICE:
                    events.add(handleFindDeviceInfo(payload));
                    break;
                case REQUEST_RESPONSE_VOICE_PROMPT_VOLUME:
                    events.add(handleVoicePromptVolumeInfo(payload));
                    break;
                case REQUEST_RESPONSE_AUDIO_CODEC:
                    events.add(handleAudioCodecInfo(payload));
                    break;
                case AC_NOTIFICATION_STATE_CHANGE:
                    // "noise processing active" flag; which mode is active is carried by 0x1081/0x1082
                    addEvent(events, handleNoiseProcessingPush(payload));
                    break;
                case AC_NOTIFICATION_MODE_CHANGE:
                case AC_NOTIFICATION_GAIN_CHANGE:
                    // ambient state push: arrives on its own, without any subscription, on every
                    // mode change — including when the user toggles it with a gesture on the earbud
                    addEvent(events, handleAmbientStatePush(payload));
                    break;
                default:
                    if (command.vendorId == EarFunPacket.VENDOR_ID_V3) {
                        // the 0x001D configuration layer answers with a lot of state dumps which are
                        // not interpreted yet; they arrive on every connection, so they are not errors
                        LOG.info("no handler for configuration packet {}", packet.getCommand().name());
                    } else {
                        LOG.error("no handler for packet type {}", packet.getCommand().name());
                    }
            }
        }
        return events.toArray(new GBDeviceEvent[0]);
    }

    private static void addEvent(final List<GBDeviceEvent> events, final GBDeviceEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    @Override
    public byte[] encodeSendConfiguration(String config) {
        Prefs prefs = getDevicePrefs();
        switch (config) {
            case PREF_EARFUN_AMBIENT_SOUND_CONTROL:
                int ambientSound = Integer.parseInt(prefs.getString(PREF_EARFUN_AMBIENT_SOUND_CONTROL, "0"));
                return new EarFunPacket(EarFunPacket.Command.SET_AMBIENT_SOUND, (byte) ambientSound).encode();
            case PREF_EARFUN_SINGLE_TAP_LEFT_ACTION:
                return EarFunPacketEncoder.encodeSetGesture(prefs, config, Interactions.InteractionType.SINGLE, Interactions.Position.LEFT);
            case PREF_EARFUN_SINGLE_TAP_RIGHT_ACTION:
                return EarFunPacketEncoder.encodeSetGesture(prefs, config, Interactions.InteractionType.SINGLE, Interactions.Position.RIGHT);
            case PREF_EARFUN_DOUBLE_TAP_LEFT_ACTION:
                return EarFunPacketEncoder.encodeSetGesture(prefs, config, Interactions.InteractionType.DOUBLE, Interactions.Position.LEFT);
            case PREF_EARFUN_DOUBLE_TAP_RIGHT_ACTION:
                return EarFunPacketEncoder.encodeSetGesture(prefs, config, Interactions.InteractionType.DOUBLE, Interactions.Position.RIGHT);
            case PREF_EARFUN_TRIPPLE_TAP_LEFT_ACTION:
                return EarFunPacketEncoder.encodeSetGesture(prefs, config, Interactions.InteractionType.TRIPLE, Interactions.Position.LEFT);
            case PREF_EARFUN_TRIPPLE_TAP_RIGHT_ACTION:
                return EarFunPacketEncoder.encodeSetGesture(prefs, config, Interactions.InteractionType.TRIPLE, Interactions.Position.RIGHT);
            case PREF_EARFUN_LONG_TAP_LEFT_ACTION:
                return EarFunPacketEncoder.encodeSetGesture(prefs, config, Interactions.InteractionType.LONG, Interactions.Position.LEFT);
            case PREF_EARFUN_LONG_TAP_RIGHT_ACTION:
                return EarFunPacketEncoder.encodeSetGesture(prefs, config, Interactions.InteractionType.LONG, Interactions.Position.RIGHT);
            case PREF_EARFUN_GAME_MODE:
                int gameMode = prefs.getBoolean(PREF_EARFUN_GAME_MODE, false) ? 1 : 0;
                return new EarFunPacket(EarFunPacket.Command.SET_GAME_MODE, (byte) gameMode).encode();
            case PREF_EARFUN_DEVICE_NAME:
                String deviceName = prefs.getString(PREF_EARFUN_DEVICE_NAME, "");
                byte[] utf8EncodedName = deviceName.getBytes(StandardCharsets.UTF_8);
                return new EarFunPacket(EarFunPacket.Command.SET_DEVICENAME, utf8EncodedName).encode();
            case PREF_EARFUN_ANC_MODE:
                byte ancMode = (byte) (Integer.parseInt(prefs.getString(PREF_EARFUN_ANC_MODE, "0")) & 0xFF);
                return new EarFunPacket(EarFunPacket.Command.SET_ANC_MODE, ancMode).encode();
            case PREF_EARFUN_TRANSPARENCY_MODE:
                byte transparencyMode = (byte) (Integer.parseInt(prefs.getString(PREF_EARFUN_TRANSPARENCY_MODE, "0")) & 0xFF);
                return new EarFunPacket(EarFunPacket.Command.SET_TRANSPARENCY_MODE, transparencyMode).encode();
            case PREF_EARFUN_IN_EAR_DETECTION_MODE:
                int disableInEarDetectionMode = prefs.getBoolean(PREF_EARFUN_IN_EAR_DETECTION_MODE, false) ? 0 : 1;
                return new EarFunPacket(EarFunPacket.Command.SET_DISABLE_IN_EAR_DETECTION, (byte) disableInEarDetectionMode).encode();
            case PREF_EARFUN_TOUCH_MODE:
                // 0 = both, 1 = none
                int touchMode = prefs.getBoolean(PREF_EARFUN_TOUCH_MODE, false) ? 0 : 1;
                return new EarFunPacket(EarFunPacket.Command.SET_TOUCH_MODE, (byte) touchMode).encode();
            case PREF_EARFUN_CONNECT_TWO_DEVICES_MODE:
                int connectTwoDevicesMode = prefs.getBoolean(PREF_EARFUN_CONNECT_TWO_DEVICES_MODE, false) ? 1 : 0;
                return joinPackets(
                        new EarFunPacket(EarFunPacket.Command.SET_CONNECT_TWO_DEVICES, (byte) connectTwoDevicesMode).encode(),
                        new EarFunPacket(EarFunPacket.Command.COMMAND_REBOOT).encode()
                );
            case PREF_EARFUN_ADVANCED_AUDIO_MODE:
                byte advancedAudioMode = (byte) (Integer.parseInt(prefs.getString(PREF_EARFUN_ADVANCED_AUDIO_MODE, "0")) & 0xFF);
                return new EarFunPacket(EarFunPacket.Command.SET_ADVANCED_AUDIO_MODE, advancedAudioMode).encode();
            case PREF_EARFUN_MICROPHONE_MODE:
                byte microphoneMode = (byte) (Integer.parseInt(prefs.getString(PREF_EARFUN_MICROPHONE_MODE, "0")) & 0xFF);
                return new EarFunPacket(EarFunPacket.Command.SET_MICROPHONE_MODE, microphoneMode).encode();
            case PREF_EARFUN_FIND_DEVICE:
                byte findDeviceMode = (byte) (Integer.parseInt(prefs.getString(PREF_EARFUN_FIND_DEVICE, "0")) & 0xFF);
                return new EarFunPacket(EarFunPacket.Command.SET_FIND_DEVICE, findDeviceMode).encode();
            case PREF_EARFUN_VOICE_PROMPT_VOLUME:
                // the volume has a scale from 4 to 0, where 0 is the highest volume and 4 is off,
                // to make it nicer for a slider, we reverse it
                int voicePromptVolume = prefs.getInt(PREF_EARFUN_VOICE_PROMPT_VOLUME, 0);
                voicePromptVolume = Math.max(0, 4 - voicePromptVolume);
                return new EarFunPacket(EarFunPacket.Command.SET_VOICE_PROMPT_VOLUME, (byte) voicePromptVolume).encode();
            case PREF_EARFUN_AUDIO_CODEC:
                byte audioCodec = (byte) (Integer.parseInt(prefs.getString(PREF_EARFUN_AUDIO_CODEC, "0")) & 0xFF);
                return new EarFunPacket(EarFunPacket.Command.SET_AUDIO_CODEC, audioCodec).encode();
            default:
                LOG.error("unhandled send configuration {}", config);
        }
        return null;
    }

    public byte[] encodeSettingsReq() {
        return EarFunPacketEncoder.encodeCommonSettingsReq();
    }

    public byte[] encodeSoundSettingsReq() {
        return EarFunPacketEncoder.encodeSoundSettingsReq();
    }

    @Override
    public byte[] encodeFirmwareVersionReq() {
        return EarFunPacketEncoder.encodeFirmwareVersionReq();
    }

    protected EarFunProtocol(GBDevice device) {
        super(device);
    }
}
