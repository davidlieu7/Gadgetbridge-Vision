package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NxWearHeartRateTimeoutTest : TestBase() {
    private fun runTimeout(active: Boolean, continuous: Boolean): List<Intent> {
        val device = GBDevice("00:11:22:33:44:55", "B8-SW", null, null, DeviceType.NX_WEAR_B8)
        val support = NxWearB8Support()
        support.setContext(device, BluetoothAdapter.getDefaultAdapter(), context)
        val errors = mutableListOf<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { errors.add(intent) }
        }
        val broadcasts = LocalBroadcastManager.getInstance(context)
        broadcasts.registerReceiver(receiver, IntentFilter(DeviceService.ACTION_HEARTRATE_MEASUREMENT_ERROR))
        try {
            for ((name, value) in listOf("manualHeartRateActive" to active, "continuousHeartRate" to continuous)) {
                NxWearB8Support::class.java.getDeclaredField(name).apply { isAccessible = true }.setBoolean(support, value)
            }
            val timeout = NxWearB8Support::class.java.getDeclaredField("heartRateTimeout")
                .apply { isAccessible = true }.get(support) as Runnable
            timeout.run()
            timeout.run()
            broadcasts.executePendingBroadcasts()
            if (active && !continuous) {
                assertFalse(NxWearB8Support::class.java.getDeclaredField("manualHeartRateActive")
                    .apply { isAccessible = true }.getBoolean(support))
            }
            return errors
        } finally {
            broadcasts.unregisterReceiver(receiver)
            support.dispose()
        }
    }

    @Test
    fun oneShotTimeoutReportsMissingMeasurementOnce() {
        val errors = runTimeout(active = true, continuous = false)
        assertEquals(1, errors.size)
        assertEquals(context.getString(R.string.heart_rate_measurement_timeout),
            errors.single().getStringExtra(DeviceService.EXTRA_MEASUREMENT_ERROR))
        assertEquals("00:11:22:33:44:55", errors.single().getParcelableExtra<GBDevice>(GBDevice.EXTRA_DEVICE)?.address)
    }

    @Test
    fun completedMeasurementDoesNotReportTimeout() {
        assertEquals(0, runTimeout(active = false, continuous = false).size)
    }

    @Test
    fun continuousMeasurementDoesNotReportOneShotTimeout() {
        assertEquals(0, runTimeout(active = true, continuous = true).size)
    }
}
