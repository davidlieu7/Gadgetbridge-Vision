package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.devices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;

public class SoundcoreAeroFitProtocol extends SoundcoreProtocolImplV1 {

    private static final Logger LOG = LoggerFactory.getLogger(SoundcoreAeroFitProtocol.class);

    public SoundcoreAeroFitProtocol(GBDevice device) {
        super(device);
    }

    @Override
    protected GBDeviceEvent[] decodePairedDevices(final byte[] payload) {
        LOG.debug("Incoming information about connected devices, {} bytes", payload.length);
        return new GBDeviceEvent[0];
    }
}
