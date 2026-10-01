package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.text.format.DateFormat
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryIncrementalInfo
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.TimeZone

/** BLE support for the Nx Wear B8. */
class NxWearB8Support : AbstractBTLESingleDeviceSupport(LOG) {
    init {
        // Discovered from nx-wear-1.txt: primary vendor services 0x0614 and 0xF615.
        addSupportedService(UUID.fromString("00000614-0000-1000-8000-00805f9b34fb"))
        addSupportedService(UUID.fromString("0000f615-0000-1000-8000-00805f9b34fb"))
    }

    override fun useAutoConnect(): Boolean = true

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        // The capture maps value handle 0x0011 to 0xB001 (notify) and 0x0014 to 0xB002 (write).
        // These UUIDs are derived from the ATT characteristic declarations, rather than relying
        // on the captured handles remaining stable across firmware versions.
        builder.notify(RESPONSE_CHARACTERISTIC, true)
        getCharacteristic(WRITE_CHARACTERISTIC)?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        writeDetectionSettings(builder)
        writeWristWakeSettings(builder)
        return builder
    }

    override fun onSendConfiguration(config: String) {
        super.onSendConfiguration(config)
        if (config !in CONFIG_KEYS) return

        val builder = createTransactionBuilder("Set Nx Wear B8 settings")
        if (config == ACTION_REFRESH_ACTIVITY) {
            builder.write(WRITE_CHARACTERISTIC, *REFRESH_ACTIVITY_COMMAND)
            builder.queue()
            return
        }
        if (config in HEALTH_CONFIG_KEYS) writeDetectionSettings(builder)
        if (config in WRIST_WAKE_CONFIG_KEYS) writeWristWakeSettings(builder)
        if (config == DeviceSettingsPreferenceConst.PREF_TIMEFORMAT) {
            writeClockSettings(builder)
        }
        builder.queue()
    }

    override fun onSetTime() {
        if (!GBApplication.getPrefs().syncTime() || !isConnected()) return

        createTransactionBuilder("Synchronize Nx Wear B8 clock")
            .also { writeClockSettings(it) }
            .queue()
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        if (characteristic.uuid == RESPONSE_CHARACTERISTIC) {
            if (value.contentEquals(ACK)) {
                // This fixed response only acknowledges the write; it does not report stored settings.
                LOG.debug("Nx Wear B8 acknowledged a settings write")
            } else if (isBatteryNotification(value)) {
                handleBatteryNotification(value)
                acknowledgeBatteryNotification(value)
            } else if (isActivityDataFrame(value)) {
                // Refresh is confirmed, but activity fragment boundaries and metric fields are not decoded yet.
                LOG.debug("Received Nx Wear B8 activity data frame; payload interpretation is not implemented")
            } else if (isPotentialActivityFragment(value)) {
                // Continuation packets observed after the 0x31/0x09 activity frame start with 0x01..0x04.
                // Preserve them in diagnostics only; the capture does not establish their field layout.
                LOG.debug("Received Nx Wear B8 activity continuation frame; payload interpretation is not implemented")
            } else if (isCandidateSettingsReply(value)) {
                // Observed after a repeated 0x6d request: 00 0b 00 <changing byte> 04 04 00 00 01 00 01 ...
                // The changing 16-bit field may be a sequence number, but its meaning and the payload semantics
                // have not been established, so do not treat this frame as settings readback.
                val sequenceCandidate = ((value[2].toInt() and 0xff) shl 8) or (value[3].toInt() and 0xff)
                LOG.debug("Received candidate Nx Wear B8 settings reply with sequence candidate {}", sequenceCandidate)
            } else {
                LOG.debug("Unexpected Nx Wear B8 response: {}", value.joinToString("") { "%02x".format(it) })
            }
            return true
        }
        return super.onCharacteristicChanged(gatt, characteristic, value)
    }

    /**
     * Battery values are interpreted from the supplied capture: payload byte 10 is the percentage,
     * and byte 11 value 1 denotes charging. Other status values remain intentionally unmapped.
     */
    private fun isBatteryNotification(value: ByteArray): Boolean =
        value.size == 20 &&
            value[0] == 0x00.toByte() && value[1] == 0x0b.toByte() &&
            value[4] == 0x31.toByte() && value[5] == 0x03.toByte() &&
            value[6] == 0x00.toByte() && value[7] == 0x00.toByte() &&
            value[8] == 0x02.toByte() && value[9] == 0x00.toByte() &&
            (value[10].toInt() and 0xff) <= 100

    private fun isActivityDataFrame(value: ByteArray): Boolean =
        value.size >= 6 &&
            value[0] == 0x00.toByte() && value[1] == 0x0b.toByte() &&
            value[4] == 0x31.toByte() && value[5] == 0x09.toByte()

    private fun isPotentialActivityFragment(value: ByteArray): Boolean =
        value.size == 20 && (value[0].toInt() and 0xff) in 0x01..0x04

    private fun handleBatteryNotification(value: ByteArray) {
        val level = value[10].toInt() and 0xff
        evaluateGBDeviceEvent(GBDeviceEventBatteryIncrementalInfo(0, level))
        if (value[11] == 0x01.toByte()) {
            evaluateGBDeviceEvent(GBDeviceEventBatteryIncrementalInfo(0, BatteryState.BATTERY_CHARGING))
        } else {
            LOG.debug("Received Nx Wear B8 battery status {}, state mapping is not confirmed", value[11].toInt() and 0xff)
        }
    }

    private fun acknowledgeBatteryNotification(value: ByteArray) {
        // Echo the observed 16-bit header field; evidence does not support incrementing it.
        val ack = byteArrayOf(
            value[0], value[1], value[2], value[3],
            0x04, 0x03, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        createTransactionBuilder("Acknowledge Nx Wear B8 battery status")
            .write(WRITE_CHARACTERISTIC, *ack)
            .queue()
    }

    private fun writeDetectionSettings(builder: TransactionBuilder) {
        val prefs = getDevicePrefs()
        val heartRateEnabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HEARTRATE_AUTOMATIC_ENABLE, false)
        val spo2Enabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING, false)
        val intervalSeconds = prefs.getString(
            DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL,
            HeartRateCapability.MeasurementInterval.MINUTES_30.getIntervalSeconds().toString(),
        )?.toIntOrNull()
            ?.takeIf {
                it == HeartRateCapability.MeasurementInterval.MINUTES_30.getIntervalSeconds() ||
                    it == HeartRateCapability.MeasurementInterval.HOUR_1.getIntervalSeconds()
            }
            ?: HeartRateCapability.MeasurementInterval.MINUTES_30.getIntervalSeconds()
        val intervalMinutes = intervalSeconds / 60

        builder.write(
            WRITE_CHARACTERISTIC,
            *encodeDetectionSettings(heartRateEnabled, intervalMinutes, spo2Enabled),
        )
    }

    private fun writeClockSettings(builder: TransactionBuilder) {
        val prefs = getDevicePrefs()
        val timeFormat = prefs.getString(
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT,
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_AUTO,
        )
        val is24Hour = when (timeFormat) {
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H -> true
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_12H -> false
            else -> DateFormat.is24HourFormat(GBApplication.getContext())
        }
        val now = System.currentTimeMillis()
        builder.write(WRITE_CHARACTERISTIC, *encodeClockSettings(now, TimeZone.getDefault().getOffset(now) / 1000, is24Hour))
    }

    private fun writeWristWakeSettings(builder: TransactionBuilder) {
        val prefs = getDevicePrefs()
        val mode = prefs.getString(DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT, "off") ?: "off"
        val enabled = mode == "on" || mode == "scheduled"
        val start = if (mode == "on") "00:01" else prefs.getString(
            DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_START, "00:00",
        ) ?: "00:00"
        val end = if (mode == "on") "23:59" else prefs.getString(
            DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_END, "00:00",
        ) ?: "00:00"
        val duration = prefs.getString(
            DeviceSettingsPreferenceConst.PREF_NX_WEAR_B8_DISPLAY_ON_LIFT_DURATION, "5",
        )?.toIntOrNull()?.takeIf { it in 3..10 } ?: 5

        builder.write(WRITE_CHARACTERISTIC, *encodeWristWakeSettings(enabled, start, end, duration))
    }

    /** Matches the observed 20-byte 0x04/0x04 frame without assigning unverified semantics to it. */
    private fun isCandidateSettingsReply(value: ByteArray): Boolean =
        value.size == 20 &&
            value[0] == 0x00.toByte() &&
            value[1] == 0x0b.toByte() &&
            value[4] == 0x04.toByte() &&
            value[5] == 0x04.toByte() &&
            value[6] == 0x00.toByte() &&
            value[7] == 0x00.toByte() &&
            value[8] == 0x01.toByte() &&
            value[9] == 0x00.toByte() &&
            value[10] == 0x01.toByte() &&
            value.drop(11).all { it == 0x00.toByte() }

    companion object {
        private val WRITE_CHARACTERISTIC = UUID.fromString("0000b002-0000-1000-8000-00805f9b34fb")
        private val RESPONSE_CHARACTERISTIC = UUID.fromString("0000b001-0000-1000-8000-00805f9b34fb")
        private val HEALTH_CONFIG_KEYS = setOf(
            DeviceSettingsPreferenceConst.PREF_HEARTRATE_AUTOMATIC_ENABLE,
            DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING,
            DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL,
        )

        private val WRIST_WAKE_CONFIG_KEYS = setOf(
            DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT,
            DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_START,
            DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_END,
            DeviceSettingsPreferenceConst.PREF_NX_WEAR_B8_DISPLAY_ON_LIFT_DURATION,
        )

        private val CONFIG_KEYS = HEALTH_CONFIG_KEYS + setOf(
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT,
            ACTION_REFRESH_ACTIVITY,
        ) + WRIST_WAKE_CONFIG_KEYS

        private const val ACTION_REFRESH_ACTIVITY = "nx_wear_b8_refresh_activity"
        private val REFRESH_ACTIVITY_COMMAND = byteArrayOf(
            0x00, 0x0b, 0x00, 0x00, 0x03, 0x09, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        private val ACK = byteArrayOf(
            0x00, 0x0b, 0x00, 0x00, 0x34, 0x80.toByte(), 0x00, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        internal fun encodeDetectionSettings(heartRateEnabled: Boolean, intervalMinutes: Int, spo2Enabled: Boolean): ByteArray {
            require(intervalMinutes == 30 || intervalMinutes == 60) { "Unsupported detection interval: $intervalMinutes" }
            return byteArrayOf(
                0x00, 0x0b, 0x00, 0x00, 0x01, 0x80.toByte(), 0x00, 0x00, 0x08, 0x00,
                if (heartRateEnabled) 0x01 else 0x00,
                0x00,
                intervalMinutes.toByte(),
                0x00,
                if (spo2Enabled) 0x01 else 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00,
            )
        }

        internal fun encodeClockSettings(timeMillis: Long, timezoneOffsetSeconds: Int, is24Hour: Boolean): ByteArray {
            val epochSeconds = timeMillis / 1000L
            val timezoneOffset = timezoneOffsetSeconds.toShort().toInt()
            return byteArrayOf(
                0x00, 0x0b, 0x00, 0x00, 0x01, 0x68, 0x00, 0x00, 0x09, 0x00,
                epochSeconds.toByte(), (epochSeconds shr 8).toByte(), (epochSeconds shr 16).toByte(), (epochSeconds shr 24).toByte(),
                timezoneOffset.toByte(), (timezoneOffset shr 8).toByte(),
                0x00, 0x00, if (is24Hour) 0x01 else 0x00, 0x00,
            )
        }

        internal fun encodeWristWakeSettings(enabled: Boolean, start: String, end: String, durationSeconds: Int): ByteArray {
            require(durationSeconds in 3..10) { "Unsupported wrist-wake duration: $durationSeconds" }
            fun parseTime(value: String): Pair<Int, Int> {
                val match = Regex("^(\\d{2}):(\\d{2})$").matchEntire(value)
                    ?: throw IllegalArgumentException("Invalid wrist-wake time: $value")
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                require(hour in 0..23 && minute in 0..59) { "Invalid wrist-wake time: $value" }
                return hour to minute
            }
            val (startHour, startMinute) = parseTime(start)
            val (endHour, endMinute) = parseTime(end)
            return byteArrayOf(
                0x00, 0x0b, 0x00, 0x00, 0x01, 0x7f, 0x00, 0x00, 0x06, 0x00,
                if (enabled) 0x01 else 0x00,
                startHour.toByte(), startMinute.toByte(), endHour.toByte(), endMinute.toByte(), durationSeconds.toByte(),
                0x00, 0x00, 0x00, 0x00,
            )
        }

        private val LOG = LoggerFactory.getLogger(NxWearB8Support::class.java)
    }
}
