package nodomain.freeyourgadget.gadgetbridge.devices.nxwear

import de.greenrobot.dao.AbstractDao
import de.greenrobot.dao.Property
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractActivitySample
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.StressSample
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear.NxWearB8Support
import nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear.NxWearSportSession
import java.util.regex.Pattern

class NxWearB8Coordinator : AbstractBLEDeviceCoordinator() {
    fun getMtu(): Int = MTU

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("^B8-SW$")

    override fun getManufacturer(): String = "Nx Wear"

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> = NxWearB8Support::class.java

    override fun getDeviceNameResource(): Int = R.string.devicetype_nx_wear_b8

    override fun getBondingStyle(): Int = BONDING_STYLE_NONE

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec {
        val preferences = GBApplication.getDevicePrefs(device).preferences
        val key = DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT
        val stored = preferences.all[key]
        if (stored is Boolean || !preferences.contains(PREF_LIFT_ENABLED)) {
            val enabled = when (stored) {
                is Boolean -> stored
                is String -> stored != "off"
                else -> false
            }
            preferences.edit()
                .putString(key, if (enabled) "scheduled" else "off")
                .putBoolean(PREF_LIFT_ENABLED, enabled)
                .apply()
        }
        return deviceSettings {
            screen(
                key = "nx_wear_b8_health",
                title = R.string.pref_header_health,
                icon = R.drawable.ic_heartrate,
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
                    icon = R.drawable.ic_timer,
                    entries = getHeartRateMeasurementIntervals().map {
                        ListEntry.Res(it.getIntervalSeconds().toString(), it.getLabel())
                    },
                    defaultValue = HeartRateCapability.MeasurementInterval.MINUTES_30.getIntervalSeconds().toString(),
                )
                action(
                    key = ACTION_MEASURE_STRESS,
                    title = R.string.nx_wear_b8_measure_stress,
                    icon = R.drawable.ic_stress,
                ) { _, selectedDevice ->
                    if (selectedDevice != null) {
                        GBApplication.deviceService(selectedDevice).onSendConfiguration(ACTION_MEASURE_STRESS)
                    }
                    selectedDevice != null
                }
                action(
                    key = ACTION_MEASURE_SPO2,
                    title = R.string.nx_wear_b8_measure_spo2,
                    icon = R.drawable.ic_spo2,
                ) { _, selectedDevice ->
                    if (selectedDevice != null) {
                        GBApplication.deviceService(selectedDevice).onSendConfiguration(ACTION_MEASURE_SPO2)
                    }
                    selectedDevice != null
                }
            }
            screen(
                key = "nx_wear_b8_exercise",
                title = R.string.nx_wear_b8_exercise,
                icon = R.drawable.ic_activity_free_training,
            ) {
                list(
                    key = PREF_EXERCISE_SPORT,
                    title = R.string.nx_wear_b8_exercise_sport,
                    icon = R.drawable.ic_activity_free_training,
                    entries = NxWearSportSession.SPORTS.map {
                        ListEntry.Res(it.type.toString(), it.activityKind.getLabel())
                    },
                    defaultValue = NxWearSportSession.OUTDOOR_RUNNING.toString(),
                )
                action(
                    key = ACTION_EXERCISE_START,
                    title = R.string.nx_wear_b8_exercise_start,
                    icon = R.drawable.ic_play,
                    visibleWhen = { prefs -> !prefs.getBoolean(PREF_EXERCISE_RUNNING, false) },
                ) { _, selectedDevice ->
                    if (selectedDevice != null) {
                        GBApplication.deviceService(selectedDevice).onSendConfiguration(ACTION_EXERCISE_START)
                    }
                    selectedDevice != null
                }
                action(
                    key = ACTION_EXERCISE_STOP,
                    title = R.string.nx_wear_b8_exercise_stop,
                    icon = R.drawable.ic_stop,
                    visibleWhen = { prefs -> prefs.getBoolean(PREF_EXERCISE_RUNNING, false) },
                ) { _, selectedDevice ->
                    if (selectedDevice != null) {
                        GBApplication.deviceService(selectedDevice).onSendConfiguration(ACTION_EXERCISE_STOP)
                    }
                    selectedDevice != null
                }
            }
            screen(
                key = "nx_wear_b8_display",
                title = R.string.mi2_prefs_activate_display_on_lift,
                icon = R.drawable.ic_brightness_medium,
            ) {
                switchSetting(
                    key = PREF_LIFT_ENABLED,
                    title = R.string.mi2_prefs_activate_display_on_lift,
                    icon = R.drawable.ic_brightness_medium,
                    defaultValue = false,
                )
                list(
                    key = DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT,
                    title = R.string.mi2_prefs_activate_display_on_lift,
                    entries = listOf(ListEntry.Res("off", R.string.off), ListEntry.Res("scheduled", R.string.on)),
                    defaultValue = "off",
                    visibleWhen = { false },
                )
                time(
                    key = DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_START,
                    title = R.string.mi2_prefs_do_not_disturb_start,
                    icon = R.drawable.ic_wb_sunny,
                    defaultValue = "09:00",
                    dependency = PREF_LIFT_ENABLED,
                )
                time(
                    key = DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_END,
                    title = R.string.mi2_prefs_do_not_disturb_end,
                    icon = R.drawable.ic_nights_stay,
                    defaultValue = "23:59",
                    dependency = PREF_LIFT_ENABLED,
                )
                list(
                    key = DeviceSettingsPreferenceConst.PREF_NX_WEAR_B8_DISPLAY_ON_LIFT_DURATION,
                    title = R.string.nx_wear_b8_wrist_wake_duration,
                    icon = R.drawable.ic_timer,
                    entries = (3..10).map { ListEntry.Text(it.toString(), "$it s") },
                    defaultValue = "5",
                    dependency = PREF_LIFT_ENABLED,
                )
            }
            list(
                key = DeviceSettingsPreferenceConst.PREF_TIMEFORMAT,
                title = R.string.pref_title_timeformat,
                icon = R.drawable.ic_timer,
                entriesRes = R.array.pref_timeformat_entries,
                entryValuesRes = R.array.pref_timeformat_values,
                defaultValue = DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_AUTO,
            )
        }
    }

    override fun getHeartRateMeasurementIntervals(): List<HeartRateCapability.MeasurementInterval> = listOf(
        HeartRateCapability.MeasurementInterval.MINUTES_30,
        HeartRateCapability.MeasurementInterval.HOUR_1,
    )

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind = DeviceCoordinator.DeviceKind.WATCH

    override fun supportsPowerOff(device: GBDevice): Boolean = true

    override fun supportsHeartRateMeasurement(device: GBDevice): Boolean = true

    override fun supportsManualHeartRateMeasurement(device: GBDevice): Boolean = true

    override fun supportsRealtimeData(device: GBDevice): Boolean = true

    override fun supportsDataFetching(device: GBDevice): Boolean = true

    override fun supportsActivityTracking(device: GBDevice): Boolean = true

    override fun supportsActivityDistance(device: GBDevice): Boolean = true

    override fun supportsActiveCalories(device: GBDevice): Boolean = true

    override fun supportsStressMeasurement(device: GBDevice): Boolean = true

    override fun supportsSpo2(device: GBDevice): Boolean = true

    override fun getSampleProvider(device: GBDevice, session: DaoSession): SampleProvider<out AbstractActivitySample> =
        NxWearActivitySampleProvider(device, session)

    override fun getStressSampleProvider(device: GBDevice, session: DaoSession): TimeSampleProvider<out StressSample> =
        GenericStressSampleProvider(device, session)

    override fun getSpo2SampleProvider(device: GBDevice, session: DaoSession): TimeSampleProvider<out Spo2Sample> =
        GenericSpo2SampleProvider(device, session)

    override fun getAllDeviceDao(session: DaoSession): MutableMap<AbstractDao<*, *>, Property> =
        mutableMapOf<AbstractDao<*, *>, Property>(
            session.genericHeartRateSampleDao to GenericHeartRateSampleDao.Properties.DeviceId,
            session.genericSpo2SampleDao to GenericSpo2SampleDao.Properties.DeviceId,
            session.genericStressSampleDao to GenericStressSampleDao.Properties.DeviceId,
            session.nxWearSportSampleDao to nodomain.freeyourgadget.gadgetbridge.entities.NxWearSportSampleDao.Properties.DeviceId,
        )

    companion object {
        const val PREF_LIFT_ENABLED = "nx_wear_b8_lift_enabled"
        private const val ACTION_MEASURE_STRESS = "nx_wear_b8_measure_stress"
        private const val ACTION_MEASURE_SPO2 = "nx_wear_b8_measure_spo2"
        const val PREF_EXERCISE_SPORT = "nx_wear_b8_exercise_sport"
        const val ACTION_EXERCISE_START = "nx_wear_b8_exercise_start"
        const val ACTION_EXERCISE_STOP = "nx_wear_b8_exercise_stop"
        const val PREF_EXERCISE_RUNNING = "nx_wear_b8_exercise_running"
        private const val MTU = 517
    }

    override fun isExperimental(): Boolean = true
}
