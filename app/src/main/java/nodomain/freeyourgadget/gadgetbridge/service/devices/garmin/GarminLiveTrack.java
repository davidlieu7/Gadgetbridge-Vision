/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArraySet;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitRecord;

/**
 * The part of Garmin's LiveTrack that other components can use.
 * <p>
 * A watch with a LiveTrack session announces it to the phone ({@code LIVE_TRACK_AUTO_START}), and the
 * phone asks it to stream its data ({@code LIVE_TRACK_TRACKING_REQUEST}); the watch then sends FIT
 * records with its own position, speed, heart rate and so on, every few seconds, until either side
 * stops it. This class lets something that publishes that data (to a tracking server, for example)
 * follow the stream, and end it from the phone, without knowing anything about the Garmin protocol.
 * <p>
 * Streaming only happens while the "Advertise LiveTrack" device setting is on, because it is the only
 * way the watch offers a session at all.
 */
public final class GarminLiveTrack {
    private static final Logger LOG = LoggerFactory.getLogger(GarminLiveTrack.class);

    /** Local broadcast asking the connection to end the watch's LiveTrack stream; see {@link #end}. */
    static final String ACTION_END = "nodomain.freeyourgadget.gadgetbridge.garmin.livetrack.END";
    static final String EXTRA_ADDRESS = "address";

    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private GarminLiveTrack() {
    }

    /** Receives what the watch does with its LiveTrack. Called from the device connection's thread. */
    public interface Listener {
        /** The watch started a LiveTrack and is being asked to stream. */
        default void onLiveTrackStarted(@NonNull final GBDevice device) {
        }

        /** One record of the watch's stream, with at least a position when it has a fix. */
        default void onLiveTrackRecord(@NonNull final GBDevice device, @NonNull final FitRecord record) {
        }

        /** The LiveTrack ended, from the watch or (with {@link #end}) from the phone. */
        default void onLiveTrackStopped(@NonNull final GBDevice device) {
        }
    }

    public static void addListener(@NonNull final Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(@NonNull final Listener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Ends the watch's LiveTrack from the phone: the watch stops streaming and drops the "stop live
     * track" option from its screen. Does nothing when the device is not streaming.
     */
    public static void end(@NonNull final Context context, @NonNull final GBDevice device) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(
                new Intent(ACTION_END).putExtra(EXTRA_ADDRESS, device.getAddress()));
    }

    static void dispatchStarted(final GBDevice device) {
        for (final Listener listener : LISTENERS) {
            try {
                listener.onLiveTrackStarted(device);
            } catch (final RuntimeException e) {
                LOG.error("LiveTrack listener failed on start", e);
            }
        }
    }

    static void dispatchRecord(final GBDevice device, final FitRecord record) {
        for (final Listener listener : LISTENERS) {
            try {
                listener.onLiveTrackRecord(device, record);
            } catch (final RuntimeException e) {
                LOG.error("LiveTrack listener failed on a record", e);
            }
        }
    }

    static void dispatchStopped(final GBDevice device) {
        for (final Listener listener : LISTENERS) {
            try {
                listener.onLiveTrackStopped(device);
            } catch (final RuntimeException e) {
                LOG.error("LiveTrack listener failed on stop", e);
            }
        }
    }
}
