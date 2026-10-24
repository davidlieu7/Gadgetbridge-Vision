package nodomain.freeyourgadget.gadgetbridge.adapter

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice

/**
 * Tints a connection status dot:
 * - red while busy
 * - green when initialized
 * - yellow while connecting
 * - blue while waiting for a reconnect or scal
 * - gray otherwise
 */
object DeviceStatusDot {
    @JvmStatic
    fun apply(dot: View, device: GBDevice) {
        dot.backgroundTintList = ColorStateList.valueOf(color(dot, device))
    }

    @JvmStatic
    fun color(view: View, device: GBDevice): Int = when (rank(device)) {
        4 -> ContextCompat.getColor(view.context, R.color.device_status_busy)
        3 -> ContextCompat.getColor(view.context, R.color.device_status_connected)
        2 -> ContextCompat.getColor(view.context, R.color.device_status_connecting)
        1 -> ContextCompat.getColor(view.context, R.color.device_status_waiting)
        else -> MaterialColors.getColor(view, R.attr.textColorSecondary)
    }

    @JvmStatic
    fun rank(device: GBDevice): Int = when {
        device.isBusy -> 4
        device.isInitialized -> 3
        device.state.equalsOrHigherThan(GBDevice.State.CONNECTING) -> 2
        device.state == GBDevice.State.WAITING_FOR_RECONNECT || device.state == GBDevice.State.WAITING_FOR_SCAN -> 1
        else -> 0
    }

    @JvmStatic
    fun apply(dot: View, connected: Boolean) {
        dot.backgroundTintList = ColorStateList.valueOf(
            if (connected) {
                ContextCompat.getColor(dot.context, R.color.device_status_connected)
            } else {
                MaterialColors.getColor(dot, R.attr.textColorSecondary)
            }
        )
    }
}
