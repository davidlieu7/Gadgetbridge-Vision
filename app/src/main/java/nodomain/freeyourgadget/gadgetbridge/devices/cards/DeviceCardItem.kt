package nodomain.freeyourgadget.gadgetbridge.devices.cards

import android.content.Context
import androidx.annotation.DrawableRes
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice

/**
 * A single item rendered in the device list card's icon row.
 */
sealed interface DeviceCardItem

/**
 * A clickable icon action.
 *
 * @param busyIndicator when true, a progress bar is shown under the icon while [GBDevice.isBusy]
 * is true (used by the fetch/sync action).
 */
data class ActionCardItem(
    val action: DeviceCardAction,
    val busyIndicator: Boolean = false,
) : DeviceCardItem

/**
 * One battery slot. Live values come from [GBDevice.getBatteryLevel] and others.
 */
data class BatteryCardItem(val batteryIndex: Int) : DeviceCardItem

/**
 * A generic icon(+optional color swatch)/value item, for things like FM frequency or LED color
 * that are driven by an ad-hoc [GBDevice.getExtraInfo] entry rather than a coordinator capability
 * method.
 */
data class StatusCardItem(
    @DrawableRes val icon: Int,
    /**
     * Non-null renders a tinted color swatch instead of a plain icon, e.g. for LED color.
     */
    val color: ((GBDevice) -> Int?)? = null,
    val value: (GBDevice, Context) -> CharSequence?,
    val description: (GBDevice, Context) -> String,
    val onClick: ((GBDevice, Context) -> Unit)? = null,
) : DeviceCardItem

/**
 * The heart-rate status box. Kept distinct from [StatusCardItem] because its live label/final
 * visibility depend on the currently displayed HR sample, an activity-scoped value the
 * coordinator/device layer can't see -- the adapter resolves it and passes it into the binder.
 */
data class HeartRateCardItem(
    val liveOnly: Boolean,
    val onClick: (GBDevice, Context) -> Unit,
) : DeviceCardItem
