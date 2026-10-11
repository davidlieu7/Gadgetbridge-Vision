package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.liberty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.ButtonAction;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;

public class SoundcoreLibertyProtocol extends SoundcoreProtocolImplV1 {

    private static final Logger LOG = LoggerFactory.getLogger(SoundcoreLibertyProtocol.class);

    private static final short CMD_GET_UNKNOWN_DATA_8D01 = (short) 0x8d01;
    private static final short CMD_GET_UNKNOWN_DATA_8205 = (short) 0x8205;
    private static final int BATTERY_MULTIPLIER = 20;

    public SoundcoreLibertyProtocol(GBDevice device) {
        super(device);
    }

    @Override
    protected GBDeviceEvent[] decodeDeviceInfo(final byte[] payload) {
        if (payload.length < 32) {
            LOG.warn("CMD_GET_DEVICE_INFO payload too short: {} bytes", payload.length);
            return new GBDeviceEvent[0];
        }

        return decodeStandardEarbudDeviceInfo(payload);
    }

    @Override
    protected GBDeviceEvent[] decodeAudioMode(final byte[] payload) {
        decodeAdvancedAudioMode(payload);
        return new GBDeviceEvent[0];
    }

    @Override
    protected int getBatteryMultiplier() {
        return BATTERY_MULTIPLIER;
    }

    @Override
    protected GBDeviceEvent[] decodeExtendedInfo(final byte[] payload) {
        LOG.debug("Unknown incoming extended information, {} bytes", payload.length);
        return new GBDeviceEvent[0];
    }

    @Override
    protected GBDeviceEvent[] decodeCommand(final short command, final byte[] payload) {
        if (command == CMD_GET_UNKNOWN_DATA_8D01 || command == CMD_GET_UNKNOWN_DATA_8205) {
            LOG.debug("Unknown incoming message - command: {} ({} bytes)", command, payload.length);
            return new GBDeviceEvent[0];
        }

        LOG.debug("Unknown incoming message - command: {} ({} bytes)", command, payload.length);
        return new GBDeviceEvent[0];
    }

    @Override
    protected boolean appendAdvancedAudioModeStateByte() {
        return true;
    }

    @Override
    protected int getTapFunctionGroup(final ButtonAction buttonAction) {
        switch (buttonAction) {
            case SINGLE_TAP:
            case DOUBLE_TAP: return 6;
            case TRIPLE_TAP: return 4;
            case LONG_PRESS: return 5;
            default: throw new IllegalArgumentException("Unknown button action: " + buttonAction);
        }
    }

    byte[] encodeMysteryDataRequest1() {
        byte[] payload = new byte[]{0x00};
        return encodeCommand(CMD_GET_UNKNOWN_DATA_8D01, payload);
    }
    byte[] encodeMysteryDataRequest2() {
        return encodeRequest(CMD_GET_EXTENDED_INFO);
    }
    byte[] encodeMysteryDataRequest3() {
        byte[] payload = new byte[]{0x00};
        return encodeCommand(CMD_GET_UNKNOWN_DATA_8205, payload);
    }

}
