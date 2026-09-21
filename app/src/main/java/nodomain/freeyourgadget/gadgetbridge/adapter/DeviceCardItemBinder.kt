package nodomain.freeyourgadget.gadgetbridge.adapter

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayout
import java.util.Locale
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.BatteryInfoActivity
import nodomain.freeyourgadget.gadgetbridge.devices.cards.ActionCardItem
import nodomain.freeyourgadget.gadgetbridge.devices.cards.BatteryCardItem
import nodomain.freeyourgadget.gadgetbridge.devices.cards.DeviceCardItem
import nodomain.freeyourgadget.gadgetbridge.devices.cards.HeartRateCardItem
import nodomain.freeyourgadget.gadgetbridge.devices.cards.StatusCardItem
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils

/**
 * Renders a device card's icon row to [R.id.device_info_icons] from a list of [DeviceCardItem]s.
 * Every item binds onto the same [R.layout.device_card_item] (icon, optional label, optional busy indicator),
 * so reuse is purely index-based: existing children are rebound in place, extra items are inflated and appended,
 * and surplus children are trimmed from the tail.
 */
object DeviceCardItemBinder {
    /**
     * Cached child view lookups for one inflated [R.layout.device_card_item], stashed in the
     * root's [View.getTag] so a reused view (the common case after the first bind) skips
     * [View.findViewById] entirely instead of re-walking the same 3-view hierarchy every bind.
     */
    private class ChildViews(val icon: ImageView, val label: TextView, val busy: ProgressBar)

    @JvmStatic
    fun bind(
        container: FlexboxLayout,
        items: List<DeviceCardItem>,
        device: GBDevice,
        context: Context,
        hrSampleText: String?,
    ) {
        for (i in items.indices) {
            val existing = container.getChildAt(i)
            val view: View
            val views: ChildViews
            if (existing != null) {
                view = existing
                views = existing.tag as ChildViews
            } else {
                view = LayoutInflater.from(context).inflate(R.layout.device_card_item, container, false)
                views = ChildViews(
                    icon = view.findViewById(R.id.card_item_icon),
                    label = view.findViewById(R.id.card_item_label),
                    busy = view.findViewById(R.id.card_item_busy),
                )
                view.tag = views
                container.addView(view)
            }
            bindItem(view, views, items[i], device, context, hrSampleText)
        }
        if (container.childCount > items.size) {
            container.removeViews(items.size, container.childCount - items.size)
        }
    }

    private fun bindItem(
        view: View,
        views: ChildViews,
        item: DeviceCardItem,
        device: GBDevice,
        context: Context,
        hrSampleText: String?
    ) {
        val icon = views.icon
        val label = views.label
        val busy = views.busy

        // Reset per-bind state that not every branch below touches.
        view.visibility = View.VISIBLE
        busy.visibility = View.GONE

        when (item) {
            is ActionCardItem -> bindAction(view, icon, label, busy, item, device, context)
            is BatteryCardItem -> bindBattery(view, icon, label, item, device, context)
            is StatusCardItem -> bindStatus(view, icon, label, item, device, context)
            is HeartRateCardItem -> bindHeartRate(view, icon, label, item, device, context, hrSampleText)
        }
    }

    private fun setDescription(view: View, description: String) {
        view.contentDescription = description
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.tooltipText = description
        }
    }

    private fun bindAction(
        view: View,
        icon: ImageView,
        label: TextView,
        busy: ProgressBar,
        item: ActionCardItem,
        device: GBDevice,
        context: Context,
    ) {
        val action = item.action
        icon.setImageResource(action.getIcon(device))
        setDescription(view, action.getDescription(device, context))

        val actionLabel = action.getLabel(device, context)
        if (!StringUtils.isNullOrEmpty(actionLabel)) {
            label.visibility = View.VISIBLE
            label.text = actionLabel
        } else {
            label.visibility = View.GONE
        }

        if (item.busyIndicator) {
            busy.visibility = if (device.isBusy) View.VISIBLE else View.GONE
        }

        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { action.onClick(device, context) }
    }

    private fun bindBattery(
        view: View,
        icon: ImageView,
        label: TextView,
        item: BatteryCardItem,
        device: GBDevice,
        context: Context
    ) {
        val batteryIndex = item.batteryIndex
        val batteryLevel = device.getBatteryLevel(batteryIndex)
        val batteryVoltage = device.getBatteryVoltage(batteryIndex)
        val batteryState = device.getBatteryState(batteryIndex)
        val batteryIcon = device.getBatteryIcon(batteryIndex)

        setDescription(view, context.getString(R.string.battery_detail_activity_title))

        icon.setImageResource(R.drawable.level_list_battery)
        label.alpha = 1.0f

        if (batteryIcon != GBDevice.BATTERY_ICON_DEFAULT.toInt()) {
            icon.setImageResource(batteryIcon)
        }

        if (batteryLevel != GBDevice.BATTERY_UNKNOWN.toInt()) {
            label.text = context.getString(R.string.battery_percentage_str, batteryLevel.toString())
            if (BatteryState.BATTERY_CHARGING == batteryState || BatteryState.BATTERY_CHARGING_FULL == batteryState) {
                icon.setImageLevel(batteryLevel + 100)
            } else if (BatteryState.NO_BATTERY == batteryState) {
                label.alpha = 0.3f
                icon.setImageLevel(300)
            } else {
                icon.setImageLevel(batteryLevel)
            }
        } else if (BatteryState.NO_BATTERY == batteryState && batteryVoltage != GBDevice.BATTERY_UNKNOWN.toFloat()) {
            label.text = String.format(Locale.getDefault(), "%.2f", batteryVoltage)
            icon.setImageLevel(200)
        } else {
            // The "default" status, shown when the device is not connected
            label.text = ""
            icon.setImageLevel(300)
        }

        label.visibility = if (TextUtils.isEmpty(label.text)) View.GONE else View.VISIBLE

        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener {
            val startIntent = Intent(context, BatteryInfoActivity::class.java)
            startIntent.putExtra(GBDevice.EXTRA_DEVICE, device)
            startIntent.putExtra(GBDevice.BATTERY_INDEX, batteryIndex)
            context.startActivity(startIntent)
        }
    }

    private fun bindStatus(
        view: View,
        icon: ImageView,
        label: TextView,
        item: StatusCardItem,
        device: GBDevice,
        context: Context
    ) {
        icon.setImageResource(item.icon)
        item.color?.invoke(device)?.let { color ->
            (icon.drawable.mutate() as GradientDrawable).setColor(color)
        }
        setDescription(view, item.description(device, context))

        val value = item.value(device, context)
        if (!TextUtils.isEmpty(value)) {
            label.visibility = View.VISIBLE
            label.text = value
        } else {
            label.visibility = View.GONE
        }

        if (item.onClick != null) {
            view.isClickable = true
            view.isFocusable = true
            view.setOnClickListener { item.onClick.invoke(device, context) }
        } else {
            view.isClickable = false
            view.isFocusable = false
            view.setOnClickListener(null)
        }
    }

    private fun bindHeartRate(
        view: View,
        icon: ImageView,
        label: TextView,
        item: HeartRateCardItem,
        device: GBDevice,
        context: Context,
        hrSampleText: String?,
    ) {
        icon.setImageResource(R.drawable.ic_heart)
        setDescription(view, context.getString(R.string.controlcenter_get_heartrate_measurement))

        // For live-only devices (e.g. Withings), a live HR sample overrides the base
        // isInitialized()/supportsRealtimeData() visibility: shown only while a sample
        // is currently available.
        view.visibility = if (item.liveOnly && hrSampleText == null) View.GONE else View.VISIBLE

        if (!TextUtils.isEmpty(hrSampleText)) {
            label.visibility = View.VISIBLE
            label.text = hrSampleText
        } else {
            label.visibility = View.GONE
        }

        if (item.liveOnly) {
            view.setOnClickListener(null)
            view.isClickable = false
            view.isFocusable = false
        } else {
            view.setOnClickListener { item.onClick(device, context) }
            view.isClickable = true
            view.isFocusable = true
        }
    }
}
