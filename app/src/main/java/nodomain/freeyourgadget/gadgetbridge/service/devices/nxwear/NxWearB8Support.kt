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
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            builder.write(WRITE_CHARACTERISTIC, *encodeRefreshActivity())
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
            val packet = NxWearPacket.parse(value)
            if (packet.raw.contentEquals(SETTINGS_ACK)) {
                // This fixed response only acknowledges a settings write; it does not report stored settings.
                LOG.debug("Nx Wear B8 acknowledged a settings write")
            } else when {
                isBatteryNotification(packet) -> {
                    handleBatteryNotification(packet)
                    acknowledgeBatteryNotification(packet)
                }
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x31 && packet.subtype == 0x09 -> {
                    // Refresh is confirmed, but activity fragment boundaries and metric fields are not decoded yet.
                    LOG.debug("Received Nx Wear B8 activity data frame; payload interpretation is not implemented")
                }
                packet.kind == NxWearPacket.Kind.CONTINUATION -> {
                    // Continuation bytes are retained opaque; their field layout is unknown.
                    LOG.debug("Received Nx Wear B8 activity continuation frame; payload interpretation is not implemented")
                }
                isCandidateSettingsReply(packet) -> {
                    // The changing header ID may be a sequence number; payload semantics remain unknown.
                    LOG.debug("Received candidate Nx Wear B8 settings reply with sequence candidate {}", packet.notificationId)
                }
                else -> LOG.debug("Unexpected Nx Wear B8 response: {}", packet.raw.joinToString("") { "%02x".format(it) })
            }
            return true
        }
        return super.onCharacteristicChanged(gatt, characteristic, value)
    }

    /** Battery payload offsets 4 and 5 contain the observed level and charging status. */
    private fun isBatteryNotification(packet: NxWearPacket): Boolean {
        val payload = packet.payload
        return packet.kind == NxWearPacket.Kind.FRAMED &&
            packet.command == 0x31 && packet.subtype == 0x03 &&
            payload.size == 14 && payload[0] == 0x00.toByte() && payload[1] == 0x00.toByte() &&
            payload[2] == 0x02.toByte() && payload[3] == 0x00.toByte() &&
            (payload[4].toInt() and 0xff) <= 100
    }

    private fun handleBatteryNotification(packet: NxWearPacket) {
        val payload = packet.payload
        val level = payload[4].toInt() and 0xff
        evaluateGBDeviceEvent(GBDeviceEventBatteryIncrementalInfo(0, level))
        if (payload[5] == 0x01.toByte()) {
            evaluateGBDeviceEvent(GBDeviceEventBatteryIncrementalInfo(0, BatteryState.BATTERY_CHARGING))
        } else {
            LOG.debug("Received Nx Wear B8 battery status {}, state mapping is not confirmed", payload[5].toInt() and 0xff)
        }
    }

    private fun acknowledgeBatteryNotification(packet: NxWearPacket) {
        // Echo the observed 16-bit header field; evidence does not support incrementing it.
        val ack = encodeBatteryAck(requireNotNull(packet.notificationId))
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

    /** Matches the observed 0x04/0x04 frame without assigning unverified payload semantics. */
    private fun isCandidateSettingsReply(packet: NxWearPacket): Boolean {
        val payload = packet.payload
        return packet.kind == NxWearPacket.Kind.FRAMED &&
            packet.command == 0x04 && packet.subtype == 0x04 &&
            payload.size == 14 && payload[0] == 0x00.toByte() && payload[1] == 0x00.toByte() &&
            payload[2] == 0x01.toByte() && payload[3] == 0x00.toByte() && payload[4] == 0x01.toByte() &&
            payload.drop(5).all { it == 0x00.toByte() }
    }

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
        private fun Boolean.toWireByte(): Byte = compareTo(false).toByte()

        private val SETTINGS_ACK = NxWearPacket.frame(
            0, 0x34, 0x80,
            byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0, 0),
        )

        internal fun encodeRefreshActivity(): ByteArray = NxWearPacket.frame(
            0, 0x03, 0x09, ByteArray(14),
        )

        internal fun encodeBatteryAck(notificationId: Int): ByteArray = NxWearPacket.frame(
            notificationId, 0x04, 0x03,
            byteArrayOf(0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )

        internal fun encodeDetectionSettings(heartRateEnabled: Boolean, intervalMinutes: Int, spo2Enabled: Boolean): ByteArray {
            require(intervalMinutes == 30 || intervalMinutes == 60) { "Unsupported detection interval: $intervalMinutes" }
            return NxWearPacket.frame(
                0, 0x01, 0x80,
                byteArrayOf(
                    0, 0, 8, 0, heartRateEnabled.toWireByte(), 0, intervalMinutes.toByte(), 0,
                    spo2Enabled.toWireByte(), 0, 0, 0, 0, 0,
                ),
            )
        }

        internal fun encodeClockSettings(timeMillis: Long, timezoneOffsetSeconds: Int, is24Hour: Boolean): ByteArray {
            val epochSeconds = timeMillis / 1000L
            val timezoneOffset = timezoneOffsetSeconds.toShort().toInt()
            // Captured packets store both values least-significant byte first.
            val timeFields = ByteBuffer.allocate(6)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(epochSeconds.toInt())
                .putShort(timezoneOffset.toShort())
                .array()
            return NxWearPacket.frame(
                0, 0x01, 0x68,
                byteArrayOf(0, 0, 9, 0) + timeFields + byteArrayOf(0, 0, is24Hour.toWireByte(), 0),
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
            return NxWearPacket.frame(
                0, 0x01, 0x7f,
                byteArrayOf(
                    0, 0, 6, 0, enabled.toWireByte(), startHour.toByte(), startMinute.toByte(),
                    endHour.toByte(), endMinute.toByte(), durationSeconds.toByte(), 0, 0, 0, 0,
                ),
            )
        }

        private val LOG = LoggerFactory.getLogger(NxWearB8Support::class.java)
    }
}
