package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages;

/**
 * Tells the watch the phone ended the LiveTrack, so it stops streaming and takes the "stop live
 * track" option off its screen. Garmin Connect sends it with no payload (its
 * LiveTrackDataHandler.sendStopTrackingRequest).
 */
public class LiveTrackStopMessage extends GFDIMessage {
    public LiveTrackStopMessage() {
        this.garminMessage = GarminMessage.LIVE_TRACK_STOP_TRACKING;
    }

    @Override
    protected boolean generateOutgoing() {
        final MessageWriter writer = new MessageWriter(response);
        writer.writeShort(0); // packet size will be filled below
        writer.writeShort(this.garminMessage.getId());
        return true;
    }
}
