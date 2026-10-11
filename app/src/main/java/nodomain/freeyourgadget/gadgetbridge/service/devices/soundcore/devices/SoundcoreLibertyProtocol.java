package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.devices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.ButtonAction;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;

public class SoundcoreLibertyProtocol extends SoundcoreProtocolImplV1 {

    private static final Logger LOG = LoggerFactory.getLogger(SoundcoreLibertyProtocol.class);

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
}
