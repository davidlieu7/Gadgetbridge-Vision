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
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.concurrent.CopyOnWriteArraySet
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitRecord
import org.slf4j.LoggerFactory

/**
 * The part of Garmin's LiveTrack that other components can use.
 *
 * A watch with a LiveTrack session announces it to the phone (`LIVE_TRACK_AUTO_START`), and the
 * phone asks it to stream its data (`LIVE_TRACK_TRACKING_REQUEST`); the watch then sends FIT
 * records with its own position, speed, heart rate and so on, every few seconds, until either side
 * stops it. This object lets something that publishes that data (to a tracking server, for example)
 * follow the stream, and end it from the phone, without knowing anything about the Garmin protocol.
 *
 * Streaming only happens while the "Advertise LiveTrack" device setting is on, because it is the only
 * way the watch offers a session at all.
 */
object GarminLiveTrack {
    private val LOG = LoggerFactory.getLogger(GarminLiveTrack::class.java)

    /** Local broadcast asking the connection to end the watch's LiveTrack stream; see [end]. */
    const val ACTION_END = "nodomain.freeyourgadget.gadgetbridge.garmin.livetrack.END"
    const val EXTRA_ADDRESS = "address"

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Receives what the watch does with its LiveTrack. Called from the device connection's thread. */
    interface Listener {
        /** The watch started a LiveTrack and is being asked to stream. */
        fun onLiveTrackStarted(device: GBDevice) {}

        /** One record of the watch's stream, with at least a position when it has a fix. */
        fun onLiveTrackRecord(device: GBDevice, record: FitRecord) {}

        /** The LiveTrack ended, from the watch or (with [end]) from the phone. */
        fun onLiveTrackStopped(device: GBDevice) {}
    }

    @JvmStatic
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Ends the watch's LiveTrack from the phone: the watch stops streaming and drops the "stop live
     * track" option from its screen. Does nothing when the device is not streaming.
     */
    @JvmStatic
    fun end(context: Context, device: GBDevice) {
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(ACTION_END).putExtra(EXTRA_ADDRESS, device.address))
    }

    // For the device connection (GarminSupport), not for listeners. A failing listener must not affect the others.

    @JvmStatic
    fun dispatchStarted(device: GBDevice) = dispatch("start") { it.onLiveTrackStarted(device) }

    @JvmStatic
    fun dispatchRecord(device: GBDevice, record: FitRecord) = dispatch("a record") { it.onLiveTrackRecord(device, record) }

    @JvmStatic
    fun dispatchStopped(device: GBDevice) = dispatch("stop") { it.onLiveTrackStopped(device) }

    private inline fun dispatch(what: String, call: (Listener) -> Unit) {
        for (listener in listeners) {
            try {
                call(listener)
            } catch (e: RuntimeException) {
                LOG.error("LiveTrack listener failed on {}", what, e)
            }
        }
    }
}
