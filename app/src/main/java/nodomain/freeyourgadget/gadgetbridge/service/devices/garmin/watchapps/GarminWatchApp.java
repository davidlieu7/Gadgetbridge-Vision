package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.watchapps;

import com.google.gson.JsonElement;

import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.GarminSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.communicator.GarminWatchAppCommunicator;

public abstract class GarminWatchApp {
    private final GarminSupport support;
    private final UUID uuid;

    protected GarminWatchApp(final GarminSupport support, final UUID uuid) {
        this.support = support;
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }

    public abstract void onMessage(JsonElement message);

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    protected boolean isAvailable() {
        return support.getWatchAppCommunicator() != null
            && support.getCoordinator().isAppInstalled(support.getDevice(), uuid);
    }

    /**
     * Queues a message to the watch app. If replace is set, the queued messages for this app are removed.
     */
    protected void sendMessage(final JsonElement message, final boolean replace) {
        final GarminWatchAppCommunicator communicator = support.getWatchAppCommunicator();
        if (communicator != null) {
            communicator.sendMessage(uuid, message, replace);
        }
    }
}
