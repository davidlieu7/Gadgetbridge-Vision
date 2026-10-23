package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages;

/**
 * Asks the watch to start streaming its LiveTrack data: FIT definition and data messages carrying
 * its position, speed, heart rate and battery. Garmin Connect sends it after the watch's
 * LIVE_TRACK_AUTO_START, with these values (taken from its LiveTrackDataHandler).
 *
 * The watch answers with one byte: bit 7 is set when data is available, and the low 3 bits are the
 * status (0 success, 1 not supported, 2 disabled, 3 format not supported, 4 transfer rate not
 * supported, 5 sample rate not supported, 6 not ready).
 */
public class LiveTrackStartMessage extends GFDIMessage {
    private static final int DATA_FORMAT = 0;
    private static final int TRANSFER_RATE = 10;
    private static final int SAMPLE_RATE = 2;

    private final int garminStartTime;

    /**
     * @param garminStartTime the start time in seconds since the Garmin epoch (1989-12-31)
     */
    public LiveTrackStartMessage(final int garminStartTime) {
        this.garminMessage = GarminMessage.LIVE_TRACK_TRACKING_REQUEST;
        this.garminStartTime = garminStartTime;
    }

    @Override
    protected boolean generateOutgoing() {
        final MessageWriter writer = new MessageWriter(response);
        writer.writeShort(0); // packet size will be filled below
        writer.writeShort(this.garminMessage.getId());
        writer.writeByte(DATA_FORMAT);
        writer.writeShort(TRANSFER_RATE);
        writer.writeShort(SAMPLE_RATE);
        writer.writeInt(garminStartTime);
        return true;
    }
}
