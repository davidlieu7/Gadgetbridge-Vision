package nodomain.freeyourgadget.gadgetbridge.devices.cards

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice

/**
 * A single item rendered in the device list card's icon row.
 */
sealed interface DeviceCardItem

/**
 * A clickable icon that triggers an action.
 *
 * @param busyIndicator show a progress bar under the icon while [GBDevice.isBusy] is true.
 */
data class ActionCardItem(
    val action: DeviceCardAction,
    val busyIndicator: Boolean = false,
) : DeviceCardItem

/**
 * One battery slot. Displays the live values from the corresponding battery in [GBDevice].
 */
data class BatteryCardItem(val batteryIndex: Int) : DeviceCardItem

/**
 * A clickable color picker.
 */
data class ColorCardItem(
    val color: (GBDevice) -> Int,
    val description: (GBDevice, Context) -> String,
    val onClick: (GBDevice, Context) -> Unit,
) : DeviceCardItem

/**
 * The heart rate measurement. The label displays the current heart rate sample.
 */
data class HeartRateCardItem(
    val liveOnly: Boolean,
    val onClick: (GBDevice, Context) -> Unit,
) : DeviceCardItem
