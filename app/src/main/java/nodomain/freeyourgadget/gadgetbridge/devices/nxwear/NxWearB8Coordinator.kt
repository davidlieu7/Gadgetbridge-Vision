package nodomain.freeyourgadget.gadgetbridge.devices.nxwear

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear.NxWearB8Support
import java.util.regex.Pattern

class NxWearB8Coordinator : AbstractBLEDeviceCoordinator() {
    override fun getSupportedDeviceName(): Pattern = Pattern.compile("^B8-SW$")

    override fun getManufacturer(): String = "Nx Wear"

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> = NxWearB8Support::class.java

    override fun getDeviceNameResource(): Int = R.string.devicetype_nx_wear_b8

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
        screen(
            key = "nx_wear_b8_health",
            title = R.string.pref_header_health,
        ) {
            switchSetting(
                key = DeviceSettingsPreferenceConst.PREF_HEARTRATE_AUTOMATIC_ENABLE,
                title = R.string.pref_heartrate_automatic_enable,
                icon = R.drawable.ic_heartrate,
                defaultValue = false,
            )
            switchSetting(
                key = DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING,
                title = R.string.prefs_spo2_monitoring_title,
                icon = R.drawable.ic_spo2,
                defaultValue = false,
            )
            list(
                key = DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL,
                title = R.string.pref_title_time_interval,
                entries = getHeartRateMeasurementIntervals().map {
                    ListEntry.Res(it.getIntervalSeconds().toString(), it.getLabel())
                },
                defaultValue = HeartRateCapability.MeasurementInterval.MINUTES_30.getIntervalSeconds().toString(),
            )
        }
        screen(
            key = "nx_wear_b8_clock",
            title = R.string.pref_header_datetime,
        ) {
            list(
                key = DeviceSettingsPreferenceConst.PREF_TIMEFORMAT,
                title = R.string.pref_title_timeformat,
                entriesRes = R.array.pref_timeformat_entries,
                entryValuesRes = R.array.pref_timeformat_values,
                defaultValue = DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_AUTO,
            )
        }
        screen(
            key = "nx_wear_b8_display",
            title = R.string.mi2_prefs_activate_display_on_lift,
        ) {
            list(
                key = DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT,
                title = R.string.mi2_prefs_activate_display_on_lift,
                entriesRes = R.array.activate_display_on_lift,
                entryValuesRes = R.array.activate_display_on_lift_values,
                defaultValue = "off",
            )
            time(
                key = DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_START,
                title = R.string.mi2_prefs_do_not_disturb_start,
                defaultValue = "00:00",
                dependency = DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT,
            )
            time(
                key = DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_END,
                title = R.string.mi2_prefs_do_not_disturb_end,
                defaultValue = "00:00",
                dependency = DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT,
            )
            list(
                key = DeviceSettingsPreferenceConst.PREF_NX_WEAR_B8_DISPLAY_ON_LIFT_DURATION,
                title = R.string.nx_wear_b8_wrist_wake_duration,
                entries = (3..10).map { ListEntry(it.toString(), "$it s") },
                defaultValue = "5",
                dependency = DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT,
            )
        }
        screen(
            key = "nx_wear_b8_activity",
            title = R.string.nx_wear_b8_activity,
        ) {
            action(
                key = ACTION_REFRESH_ACTIVITY,
                title = R.string.nx_wear_b8_refresh_activity,
                icon = R.drawable.ic_refresh,
            ) { _, selectedDevice ->
                if (selectedDevice != null) {
                    GBApplication.deviceService(selectedDevice).onSendConfiguration(ACTION_REFRESH_ACTIVITY)
                }
                selectedDevice != null
            }
        }
    }

    override fun getHeartRateMeasurementIntervals(): List<HeartRateCapability.MeasurementInterval> = listOf(
        HeartRateCapability.MeasurementInterval.MINUTES_30,
        HeartRateCapability.MeasurementInterval.HOUR_1,
    )

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind = DeviceCoordinator.DeviceKind.WATCH

    companion object {
        private const val ACTION_REFRESH_ACTIVITY = "nx_wear_b8_refresh_activity"
    }

    override fun isExperimental(): Boolean = true
}
