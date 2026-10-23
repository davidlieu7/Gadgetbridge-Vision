package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages;

import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * A LiveTrack message from the watch: tracking request (5016), stop (5025), auto start (5046),
 * auto start cancel (5047) or auto start failure (5048). A watch only sends them once the phone has
 * said it supports LiveTrack (see the "Advertise LiveTrack" setting). The ones seen so far carry no
 * payload, so they are only acknowledged and logged; what they mean is decided by the caller.
 */
public class LiveTrackMessage extends GFDIMessage {
    public LiveTrackMessage(final GarminMessage garminMessage, final byte[] payload) {
        this.garminMessage = garminMessage;
        this.statusMessage = super.getStatusMessage();

        LOG.info("LiveTrack message {} from the watch, {} byte(s) of payload: {}",
                garminMessage, payload.length, GB.hexdump(payload));
    }

    public static LiveTrackMessage parseIncoming(final MessageReader reader, final GarminMessage garminMessage) {
        return new LiveTrackMessage(garminMessage, reader.readBytes(reader.remaining()));
    }

    @Override
    protected boolean generateOutgoing() {
        return false;
    }
}
