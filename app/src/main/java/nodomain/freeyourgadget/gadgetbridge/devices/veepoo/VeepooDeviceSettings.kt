package nodomain.freeyourgadget.gadgetbridge.devices.veepoo

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings

/**
 * Programmatic device settings for the VeePoo KR96 Pro. The monitor switches are applied to the
 * band through VeepooConstants.CONFIG_AUTO_MEASURE_* in onSendConfiguration (0xB8 custom-setting
 * frames); their state is re-synced from the device after every connect. The whole section is
 * hidden until the device's 0xB8 report shows at least one configurable monitor.
 */
private val monitorVisible: (nodomain.freeyourgadget.gadgetbridge.util.Prefs) -> Boolean = {
    it.getBoolean(VeepooConstants.CONFIG_MONITOR_SUPPORTED, false)
}

fun veepooDeviceSettings(): DeviceSettingsSpec = deviceSettings {
    screen(
        key = "veepoo_auto_measure",
        title = R.string.veepoo_settings_auto_measure,
        icon = R.drawable.ic_health,
        visibleWhen = monitorVisible,
    ) {
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_HR,
            title = R.string.veepoo_auto_measure_hr,
            visibleWhen = monitorVisible,
        )
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_PRESSURE,
            title = R.string.veepoo_auto_measure_blood_pressure,
            visibleWhen = monitorVisible,
        )
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_TEMPERATURE,
            title = R.string.veepoo_auto_measure_temperature,
            visibleWhen = monitorVisible,
        )
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_STRESS,
            title = R.string.veepoo_auto_measure_stress,
            visibleWhen = monitorVisible,
        )
    }
}
