package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.text.format.DateFormat
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.devices.nxwear.NxWearB8Coordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
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
    private enum class InitializationStep { WAIT_ACTIVITY_09, WAIT_ACTIVITY_04, WAIT_ACTIVITY_FA, COMPLETE }

    private var initializationStep = InitializationStep.WAIT_ACTIVITY_09
    private var initializationFragments = 0
    private var expectedInitializationFragments = 0
    private var nextInitializationSequence = 1
    private var deviceSyncPayload: ByteArray? = null
    private var deviceSyncLength = 0
    override fun getCoordinator(): NxWearB8Coordinator = super.getCoordinator() as NxWearB8Coordinator

    init {
        addSupportedService(UUID.fromString("0000f618-0000-1000-8000-00805f9b34fb"))
    }

    override fun useAutoConnect(): Boolean = true

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        initializationStep = InitializationStep.WAIT_ACTIVITY_09
        initializationFragments = 0
        expectedInitializationFragments = 0
        nextInitializationSequence = 1
        deviceSyncPayload = null
        deviceSyncLength = 0
        builder.setDeviceState(GBDevice.State.INITIALIZING)
        builder.requestMtu(getCoordinator().getMtu())
        builder.notify(RESPONSE_CHARACTERISTIC, true)
        getCharacteristic(WRITE_CHARACTERISTIC)?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        builder.write(WRITE_CHARACTERISTIC, *encodeInitializationBootstrap(buildBootstrap()))

        builder.setDeviceState(GBDevice.State.INITIALIZED)
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

    override fun onPowerOff() {
        createTransactionBuilder("Power off Nx Wear B8")
            .write(WRITE_CHARACTERISTIC, *encodePowerOff())
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
            } else if (packet.raw.contentEquals(INITIALIZATION_ACK)) {
                LOG.debug("Nx Wear B8 acknowledged its initialization request")
            } else if (packet.raw.contentEquals(POWER_OFF_ACK)) {
                LOG.debug("Nx Wear B8 acknowledged its power-off request")
            } else when {
                isBatteryNotification(packet) -> {
                    handleBatteryNotification(packet)
                    acknowledgeBatteryNotification(packet)
                }
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x31 && packet.dataType != null && packet.dataType in ACTIVITY_SUBTYPES -> {
                    handleInitializationActivityFrame(packet)
                }
                packet.kind == NxWearPacket.Kind.CONTINUATION -> handleInitializationContinuation(packet)
                isCandidateSettingsReply(packet) -> {
                    LOG.debug("Received candidate Nx Wear B8 settings reply with sequence {}", packet.sequence)
                }
                else -> LOG.debug("Unexpected Nx Wear B8 response: {}", packet.raw.joinToString("") { "%02x".format(it) })
            }
            return true
        }
        return super.onCharacteristicChanged(gatt, characteristic, value)
    }

    private fun handleInitializationActivityFrame(packet: NxWearPacket) {
        val subtype = requireNotNull(packet.dataType)
        val expectedSubtype = when (initializationStep) {
            InitializationStep.WAIT_ACTIVITY_09 -> 0x09
            InitializationStep.WAIT_ACTIVITY_04 -> 0x04
            InitializationStep.WAIT_ACTIVITY_FA -> 0xfa
            InitializationStep.COMPLETE -> null
        }
        if (subtype != expectedSubtype) {
            LOG.debug("Unexpected Nx Wear B8 activity subtype {} during initialization step {}", subtype, initializationStep)
            return
        }
        val declaredLength = packet.declaredLength
        if (declaredLength == null) {
            LOG.debug("Nx Wear B8 activity subtype {} has no declared length", subtype)
            return
        }
        expectedInitializationFragments = expectedActivityFragmentCount(declaredLength)
        initializationFragments = packet.pagesPresent
        if (subtype == 0x09) {
            deviceSyncPayload = packet.payload
            deviceSyncLength = declaredLength
        }
        advanceInitializationWhenComplete()
    }

    private fun handleInitializationContinuation(packet: NxWearPacket) {
        if (initializationStep == InitializationStep.COMPLETE || initializationFragments == 0) return
        if (packet.continuationIndex != initializationFragments) return
        initializationFragments += packet.pagesPresent
        if (initializationStep == InitializationStep.WAIT_ACTIVITY_09) {
            val current = deviceSyncPayload
            if (current != null) {
                deviceSyncPayload = (current + packet.payload).copyOfRange(0,
                    minOf(current.size + packet.payload.size, deviceSyncLength))
            }
        }
        advanceInitializationWhenComplete()
    }

    private fun advanceInitializationWhenComplete() {
        if (initializationFragments < expectedInitializationFragments) return

        if (initializationStep == InitializationStep.WAIT_ACTIVITY_09) {
            deviceSyncPayload?.takeIf { it.size == deviceSyncLength }
                ?.let { NxWearDeviceSync.parse(it)?.let(::applyDeviceSync) }
            deviceSyncPayload = null
            deviceSyncLength = 0
        }

        initializationFragments = 0
        expectedInitializationFragments = 0
        val (nextStep, request) = when (initializationStep) {
            InitializationStep.WAIT_ACTIVITY_09 -> InitializationStep.WAIT_ACTIVITY_04 to nextInitializationRead(0x09)
            InitializationStep.WAIT_ACTIVITY_04 -> InitializationStep.WAIT_ACTIVITY_FA to nextInitializationRead(0x04)
            InitializationStep.WAIT_ACTIVITY_FA -> InitializationStep.COMPLETE to nextInitializationRead(0xfa)
            InitializationStep.COMPLETE -> return
        }
        initializationStep = nextStep
        createTransactionBuilder("Continue Nx Wear B8 initialization")
            .write(WRITE_CHARACTERISTIC, *request)
            .queue()
    }

    private fun applyDeviceSync(sync: NxWearDeviceSync) {
        sync.firmwareVersion?.let {
            getDevice().setFirmwareVersion(it)
            getDevice().sendDeviceUpdateIntent(getContext())
        }
        if (sync.batteryLevel != null && sync.batteryStatus != null) {
            evaluateGBDeviceEvent(GBDeviceEventBatteryInfo().apply {
                level = sync.batteryLevel
                state = batteryStateForStatus(sync.batteryStatus)
            })
        }
        val editor = getDevicePrefs().getPreferences().edit()
        sync.heartRateEnabled?.let { editor.putBoolean(DeviceSettingsPreferenceConst.PREF_HEARTRATE_AUTOMATIC_ENABLE, it) }
        sync.heartRateIntervalMinutes?.let { editor.putString(DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL, (it * 60).toString()) }
        sync.spo2Enabled?.let { editor.putBoolean(DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING, it) }
        sync.wristWakeEnabled?.let { enabled ->
            if (!enabled || (sync.wristWakeStart != null && sync.wristWakeEnd != null)) {
                val mode = if (!enabled) "off" else if (sync.wristWakeStart == "00:01" && sync.wristWakeEnd == "23:59") "on" else "scheduled"
                editor.putString(DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT, mode)
            }
        }
        sync.wristWakeStart?.let { editor.putString(DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_START, it) }
        sync.wristWakeEnd?.let { editor.putString(DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_END, it) }
        sync.wristWakeDurationSeconds?.let { editor.putString(DeviceSettingsPreferenceConst.PREF_NX_WEAR_B8_DISPLAY_ON_LIFT_DURATION, it.toString()) }
        editor.apply()
    }

    private fun nextInitializationRead(subtype: Int): ByteArray {
        val sequence = nextInitializationSequence
        nextInitializationSequence = (sequence + 1) and 0xff
        return encodeInitializationRead(sequence, subtype)
    }

    private fun isBatteryNotification(packet: NxWearPacket): Boolean {
        val payload = packet.payload
        return packet.kind == NxWearPacket.Kind.FRAMED &&
            packet.command == 0x31 && packet.dataType == 0x03 && packet.isComplete &&
            payload.size == 2 && (payload[0].toInt() and 0xff) <= 100
    }

    private fun handleBatteryNotification(packet: NxWearPacket) {
        val payload = packet.payload
        val batteryInfo = GBDeviceEventBatteryInfo().apply {
            level = payload[0].toInt() and 0xff
            state = batteryStateForStatus(payload[1].toInt() and 0xff)
        }
        if (batteryInfo.state == BatteryState.UNKNOWN) {
            LOG.debug("Received Nx Wear B8 battery status {}, state mapping is unknown", payload[1].toInt() and 0xff)
        }
        evaluateGBDeviceEvent(batteryInfo)
    }

    private fun acknowledgeBatteryNotification(packet: NxWearPacket) {
        val sequence = requireNotNull(packet.sequence)
        val ack = encodeBatteryAck(sequence)
        if (initializationStep != InitializationStep.COMPLETE) {
            nextInitializationSequence = (sequence + 1) and 0xff
        }
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
        val now = System.currentTimeMillis()
        builder.write(WRITE_CHARACTERISTIC, *encodeClockSettings(now, TimeZone.getDefault().getOffset(now) / 1000, is24Hour()))
    }

    private fun is24Hour(): Boolean {
        val timeFormat = getDevicePrefs().getString(
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT,
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_AUTO,
        )
        return when (timeFormat) {
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H -> true
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_12H -> false
            else -> DateFormat.is24HourFormat(GBApplication.getContext())
        }
    }

    private fun buildBootstrap(): NxWearBootstrap {
        val user = ActivityUser()
        val now = System.currentTimeMillis()
        return NxWearBootstrap(
            userId = 0,
            isMale = user.gender == ActivityUser.GENDER_MALE,
            age = user.age.takeIf { it > 0 } ?: 20,
            heightCm = user.heightCm,
            weightKg = user.weightKg,
            epochSeconds = now / 1000L,
            timezoneOffsetSeconds = TimeZone.getDefault().getOffset(now) / 1000,
            is24Hour = is24Hour(),
            stepsGoal = user.stepsGoal,
            distanceGoalMeters = user.distanceGoalMeters,
            caloriesGoal = user.caloriesBurntGoal,
            sleepGoalSeconds = user.sleepDurationGoal * 60,
            activeTimeGoalSeconds = user.activeTimeGoalMinutes * 60,
        )
    }

    private fun writeWristWakeSettings(builder: TransactionBuilder) {
        val prefs = getDevicePrefs()
        val mode = prefs.getString(DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT, "off") ?: "off"
        val enabled = mode == "on" || mode == "scheduled"
        val start = if (mode == "on") "00:01" else prefs.getString(
            DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_START, "09:00",
        ) ?: "00:00"
        val end = if (mode == "on") "23:59" else prefs.getString(
            DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_END, "23:59",
        ) ?: "00:00"
        val duration = prefs.getString(
            DeviceSettingsPreferenceConst.PREF_NX_WEAR_B8_DISPLAY_ON_LIFT_DURATION, "5",
        )?.toIntOrNull()?.takeIf { it in 3..10 } ?: 5

        builder.write(WRITE_CHARACTERISTIC, *encodeWristWakeSettings(enabled, start, end, duration))
    }

    private fun isCandidateSettingsReply(packet: NxWearPacket): Boolean {
        val payload = packet.payload
        return packet.kind == NxWearPacket.Kind.FRAMED &&
            packet.command == 0x04 && packet.dataType == 0x04 && packet.isComplete &&
            payload.contentEquals(byteArrayOf(0x01))
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
        private val ACTIVITY_SUBTYPES = setOf(0x09, 0x04, 0xfa)
        private fun Boolean.toWireByte(): Byte = compareTo(false).toByte()

        internal fun batteryStateForStatus(status: Int): BatteryState = when (status) {
            0x00 -> BatteryState.BATTERY_NORMAL
            0x01 -> BatteryState.BATTERY_CHARGING
            0x02 -> BatteryState.BATTERY_CHARGING_FULL
            else -> BatteryState.UNKNOWN
        }

        internal fun expectedActivityFragmentCount(declaredLength: Int): Int {
            require(declaredLength >= 0)
            val continuationBytes = (declaredLength - 10).coerceAtLeast(0)
            val continuationCount = (continuationBytes + 18) / 19
            return continuationCount + 1
        }

        internal fun encodeInitializationBootstrap(settings: NxWearBootstrap): ByteArray = settings.encode()

        internal fun encodeInitializationRead(sequence: Int, dataType: Int): ByteArray =
            NxWearPacket.frame(sequence, 0x04, dataType, byteArrayOf(1))

        private val SETTINGS_ACK = NxWearPacket.frame(0, 0x34, 0x80, byteArrayOf(1))
        private val INITIALIZATION_ACK = NxWearPacket.frame(0, 0x34, 0x6e, byteArrayOf(1))
        private val POWER_OFF_ACK = NxWearPacket.frame(0, 0x34, 0x77, byteArrayOf(1))

        internal fun encodePowerOff(): ByteArray = NxWearPacket.frame(0, 0x01, 0x77, byteArrayOf(0))

        internal fun encodeRefreshActivity(): ByteArray = NxWearPacket.frame(0, 0x03, 0x09, byteArrayOf())

        internal fun encodeBatteryAck(sequence: Int): ByteArray = NxWearPacket.frame(sequence, 0x04, 0x03, byteArrayOf(1))

        internal fun encodeDetectionSettings(heartRateEnabled: Boolean, intervalMinutes: Int, spo2Enabled: Boolean): ByteArray {
            require(intervalMinutes == 30 || intervalMinutes == 60) { "Unsupported detection interval: $intervalMinutes" }
            return NxWearPacket.frame(
                0, 0x01, 0x80,
                byteArrayOf(
                    heartRateEnabled.toWireByte(), 0, intervalMinutes.toByte(), 0,
                    spo2Enabled.toWireByte(), 0, 0, 0,
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
                timeFields + byteArrayOf(0, 0, is24Hour.toWireByte()),
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
                    enabled.toWireByte(), startHour.toByte(), startMinute.toByte(),
                    endHour.toByte(), endMinute.toByte(), durationSeconds.toByte(),
                ),
            )
        }

        private val LOG = LoggerFactory.getLogger(NxWearB8Support::class.java)
    }
}
