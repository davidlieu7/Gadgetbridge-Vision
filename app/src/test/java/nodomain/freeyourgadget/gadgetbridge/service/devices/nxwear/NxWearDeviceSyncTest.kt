package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NxWearDeviceSyncTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun parsesDeviceInfoBatteryAndSettingsFromSyncPayload() {
        val payload = bytes("4400061000020c0b0000050003418e005d57420500034b010b008001003c01000000000700162210e0081300170000000000000000000804000000000009007f010800172806")
        val sync = NxWearDeviceSync.parse(payload)

        assertEquals("779.0.0.5.0", sync?.firmwareVersion)
        assertEquals(75, sync?.batteryLevel)
        assertEquals(1, sync?.batteryStatus)
        assertEquals(true, sync?.heartRateEnabled)
        assertEquals(60, sync?.heartRateIntervalMinutes)
        assertEquals(true, sync?.spo2Enabled)
        assertEquals(true, sync?.wristWakeEnabled)
        assertEquals("08:00", sync?.wristWakeStart)
        assertEquals("23:40", sync?.wristWakeEnd)
        assertEquals(6, sync?.wristWakeDurationSeconds)
    }

    @Test
    fun rejectsTruncatedSyncPayload() {
        val payload = bytes("4400061000020c0b")
        assertNull(NxWearDeviceSync.parse(payload))
    }
}
