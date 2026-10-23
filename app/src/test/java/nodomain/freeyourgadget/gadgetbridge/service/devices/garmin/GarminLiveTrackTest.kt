package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import nodomain.freeyourgadget.gadgetbridge.devices.garmin.GarminCapability
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class GarminLiveTrackTest {
    private val device = mock(GBDevice::class.java)
    private val events = mutableListOf<String>()
    private val listener = object : GarminLiveTrack.Listener {
        override fun onLiveTrackStarted(device: GBDevice) {
            events += "started"
        }

        override fun onLiveTrackStopped(device: GBDevice) {
            events += "stopped"
        }
    }

    @After
    fun tearDown() {
        GarminLiveTrack.removeListener(listener)
        GarminCapability.setAdvertiseLiveTrack(false)
    }

    @Test
    fun listenersAreToldWhenALiveTrackStartsAndStops() {
        GarminLiveTrack.addListener(listener)
        GarminLiveTrack.dispatchStarted(device)
        GarminLiveTrack.dispatchStopped(device)
        assertEquals(listOf("started", "stopped"), events)
    }

    @Test
    fun aRemovedListenerHearsNothing() {
        GarminLiveTrack.addListener(listener)
        GarminLiveTrack.removeListener(listener)
        GarminLiveTrack.dispatchStarted(device)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aFailingListenerDoesNotStopTheOthers() {
        val failing = object : GarminLiveTrack.Listener {
            override fun onLiveTrackStarted(device: GBDevice) = throw IllegalStateException("boom")
        }
        GarminLiveTrack.addListener(failing)
        GarminLiveTrack.addListener(listener)
        try {
            GarminLiveTrack.dispatchStarted(device)
        } finally {
            GarminLiveTrack.removeListener(failing)
        }
        assertEquals(listOf("started"), events)
    }

    @Test
    fun liveTrackIsOnlyAdvertisedWhenAsked() {
        assertFalse(GarminCapability.getOurCapabilities().contains(GarminCapability.LIVETRACK))
        GarminCapability.setAdvertiseLiveTrack(true)
        val capabilities = GarminCapability.getOurCapabilities()
        assertTrue(capabilities.contains(GarminCapability.LIVETRACK))
        assertTrue(capabilities.contains(GarminCapability.LIVETRACK_AUTO_START))
        // The shared default set must not be changed by asking.
        GarminCapability.setAdvertiseLiveTrack(false)
        assertFalse(GarminCapability.getOurCapabilities().contains(GarminCapability.LIVETRACK))
    }
}
