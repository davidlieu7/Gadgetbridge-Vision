package nodomain.freeyourgadget.gadgetbridge.devices.veepoo

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.util.Prefs

/**
 * Programmatic device settings for the VeePoo KR96 Pro. The monitor switches are applied to the
 * band in onSendConfiguration (0xB8 custom-setting frames, 0xB3 AllSet for SpO2); their state is
 * re-synced from the device after every connect. Each switch is hidden when the firmware does
 * not report that monitor as supported.
 */
private val monitorSupportKeys = listOf(
    VeepooConstants.CONFIG_AUTO_MEASURE_HR_SUPPORTED,
    VeepooConstants.CONFIG_AUTO_MEASURE_SPO2_SUPPORTED,
    VeepooConstants.CONFIG_AUTO_MEASURE_TEMPERATURE_SUPPORTED,
    VeepooConstants.CONFIG_AUTO_MEASURE_STRESS_SUPPORTED,
    VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_PRESSURE_SUPPORTED,
    VeepooConstants.CONFIG_AUTO_MEASURE_HRV_SUPPORTED,
    VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_COMPONENTS_SUPPORTED,
)

private val sectionVisible: (Prefs) -> Boolean = { prefs ->
    monitorSupportKeys.any { prefs.getBoolean(it, false) }
}

private fun visibleWhenSupported(key: String): (Prefs) -> Boolean = { prefs ->
    prefs.getBoolean(key, false)
}

fun veepooDeviceSettings(): DeviceSettingsSpec = deviceSettings {
    screen(
        key = "veepoo_auto_measure",
        title = R.string.veepoo_settings_auto_measure,
        icon = R.drawable.ic_health,
        visibleWhen = sectionVisible,
    ) {
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_HR,
            title = R.string.veepoo_auto_measure_hr,
            visibleWhen = visibleWhenSupported(VeepooConstants.CONFIG_AUTO_MEASURE_HR_SUPPORTED),
        )
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_SPO2,
            title = R.string.veepoo_auto_measure_spo2,
            visibleWhen = visibleWhenSupported(VeepooConstants.CONFIG_AUTO_MEASURE_SPO2_SUPPORTED),
        )
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_TEMPERATURE,
            title = R.string.veepoo_auto_measure_temperature,
            visibleWhen = visibleWhenSupported(VeepooConstants.CONFIG_AUTO_MEASURE_TEMPERATURE_SUPPORTED),
        )
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_STRESS,
            title = R.string.veepoo_auto_measure_stress,
            visibleWhen = visibleWhenSupported(VeepooConstants.CONFIG_AUTO_MEASURE_STRESS_SUPPORTED),
        )
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_PRESSURE,
            title = R.string.veepoo_auto_measure_blood_pressure,
            visibleWhen = visibleWhenSupported(VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_PRESSURE_SUPPORTED),
        )
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_HRV,
            title = R.string.veepoo_auto_measure_hrv,
            visibleWhen = visibleWhenSupported(VeepooConstants.CONFIG_AUTO_MEASURE_HRV_SUPPORTED),
        )
        switchSetting(
            key = VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_COMPONENTS,
            title = R.string.veepoo_auto_measure_blood_components,
            visibleWhen = visibleWhenSupported(VeepooConstants.CONFIG_AUTO_MEASURE_BLOOD_COMPONENTS_SUPPORTED),
        )
    }
}
