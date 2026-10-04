package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.nxwear.NxWearB8Coordinator
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample
import nodomain.freeyourgadget.gadgetbridge.entities.NxWearSportSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.TimeZone

/** BLE support for the Nx Wear B8. */
class NxWearB8Support : AbstractBTLESingleDeviceSupport(LOG) {
    private enum class InitializationStep {
        WAIT_ACTIVITY_09, WAIT_ACTIVITY_04, WAIT_HISTORY_DATA, COMPLETE,
    }

    private var initializationStep = InitializationStep.WAIT_ACTIVITY_09
    private var initializationFragments = 0
    private var expectedInitializationFragments = 0
    private var nextInitializationSequence = 1
    private var currentHistorySubtype: Int? = null
    private var deviceSyncPayload: ByteArray? = null
    private var deviceSyncLength = 0
    private var sportPacket: NxWearPacket? = null
    private var historyPacket: NxWearPacket? = null
    private var pendingLogSequence: Int? = null
    private var pendingLogPages = 0
    private var expectedLogPages = 0
    private val heartRateTimeoutHandler = Handler(Looper.getMainLooper())
    private val heartRateTimeout = Runnable { stopManualHeartRate() }
    private val historyTimeoutHandler = Handler(Looper.getMainLooper())
    private val historyTimeout = Runnable { finishHistoryFetch() }
    private var historyFetchActive = false
    @Volatile
    private var manualHeartRateActive = false
    private var exerciseStartedAt: Long? = null
    private var exerciseSportType = NxWearSportSession.OUTDOOR_RUNNING
    override fun getCoordinator(): NxWearB8Coordinator = super.getCoordinator() as NxWearB8Coordinator

    init {
        addSupportedService(UUID.fromString("0000f618-0000-1000-8000-00805f9b34fb"))
    }

    override fun useAutoConnect(): Boolean = true

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        finishHistoryFetch()
        initializationStep = InitializationStep.WAIT_ACTIVITY_09
        initializationFragments = 0
        expectedInitializationFragments = 0
        nextInitializationSequence = 1
        currentHistorySubtype = null
        deviceSyncPayload = null
        deviceSyncLength = 0
        sportPacket = null
        historyPacket = null
        pendingLogSequence = null
        pendingLogPages = 0
        expectedLogPages = 0
        manualHeartRateActive = false
        heartRateTimeoutHandler.removeCallbacks(heartRateTimeout)
        builder.setDeviceState(GBDevice.State.INITIALIZING)
        builder.requestMtu(getCoordinator().getMtu())
        builder.notify(RESPONSE_CHARACTERISTIC, true)
        getCharacteristic(WRITE_CHARACTERISTIC)?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        builder.write(WRITE_CHARACTERISTIC, *encodeInitializationBootstrap(buildBootstrap()))

        builder.setDeviceState(GBDevice.State.INITIALIZED)
        startHistoryFetch()
        return builder
    }

    override fun onSendConfiguration(config: String) {
        super.onSendConfiguration(config)
        if (config !in CONFIG_KEYS) return

        if (config == ACTION_MEASURE_STRESS) {
            if (isConnected()) {
                createTransactionBuilder("Start Nx Wear B8 stress measurement")
                    .write(WRITE_CHARACTERISTIC, *encodeStressMeasurementStart())
                    .queue()
            }
            return
        }
        if (config == ACTION_MEASURE_SPO2) {
            if (isConnected()) {
                createTransactionBuilder("Start Nx Wear B8 blood oxygen measurement")
                    .write(WRITE_CHARACTERISTIC, *encodeSpo2MeasurementStart())
                    .queue()
            }
            return
        }
        if (config == NxWearB8Coordinator.ACTION_EXERCISE_START || config == NxWearB8Coordinator.ACTION_EXERCISE_STOP) {
            if (!isConnected()) return
            val starting = config == NxWearB8Coordinator.ACTION_EXERCISE_START
            val prefs = getDevicePrefs().preferences
            if (starting) {
                exerciseSportType = getDevicePrefs()
                    .getString(NxWearB8Coordinator.PREF_EXERCISE_SPORT, NxWearSportSession.OUTDOOR_RUNNING.toString())
                    ?.toIntOrNull()
                    ?.takeIf { selectedType -> NxWearSportSession.SPORTS.any { it.type == selectedType } }
                    ?: NxWearSportSession.OUTDOOR_RUNNING
                exerciseStartedAt = SystemClock.elapsedRealtime()
                prefs.edit().putBoolean(NxWearB8Coordinator.PREF_EXERCISE_RUNNING, true).apply()
            }
            val elapsed = if (starting) 0L else exerciseStartedAt?.let { (SystemClock.elapsedRealtime() - it) / 1000L } ?: 0L
            createTransactionBuilder(if (starting) "Start Nx Wear B8 exercise" else "Stop Nx Wear B8 exercise")
                .write(WRITE_CHARACTERISTIC, *NxWearSportSession.encode(exerciseSportType, if (starting) NxWearSportSession.START else NxWearSportSession.STOP, elapsed))
                .queue()
            if (!starting) {
                exerciseStartedAt = null
                prefs.edit().putBoolean(NxWearB8Coordinator.PREF_EXERCISE_RUNNING, false).apply()
            }
            return
        }
        val builder = createTransactionBuilder("Set Nx Wear B8 settings")
        if (config in HEALTH_CONFIG_KEYS) writeDetectionSettings(builder)
        if (config in WRIST_WAKE_CONFIG_KEYS) {
            if (config == NxWearB8Coordinator.PREF_LIFT_ENABLED) {
                val enabled = getDevicePrefs().getBoolean(NxWearB8Coordinator.PREF_LIFT_ENABLED, false)
                getDevicePrefs().preferences.edit()
                    .putString(DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT, if (enabled) "scheduled" else "off")
                    .apply()
            }
            writeWristWakeSettings(builder)
        }
        if (config == DeviceSettingsPreferenceConst.PREF_TIMEFORMAT) {
            writeClockSettings(builder)
        }
        builder.queue()
    }

    override fun onFetchRecordedData(dataTypes: Int) {
        if (!isConnected() || dataTypes and RecordedDataTypes.TYPE_ACTIVITY == 0 ||
            initializationStep != InitializationStep.COMPLETE || historyFetchActive) return
        initializationStep = InitializationStep.WAIT_ACTIVITY_09
        initializationFragments = 0
        expectedInitializationFragments = 0
        nextInitializationSequence = 1
        currentHistorySubtype = null
        historyPacket = null
        startHistoryFetch()
        // The history stream advances only after a complete data frame is acknowledged.
        createTransactionBuilder("Synchronize Nx Wear B8 history")
            .write(WRITE_CHARACTERISTIC, *encodeInitializationBootstrap(buildBootstrap()))
            .queue()
    }

    private fun startHistoryFetch() {
        historyFetchActive = true
        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext())
        getDevice().sendDeviceUpdateIntent(getContext())
        historyTimeoutHandler.postDelayed(historyTimeout, HISTORY_TIMEOUT_MS)
    }

    @Synchronized
    private fun finishHistoryFetch() {
        historyTimeoutHandler.removeCallbacks(historyTimeout)
        if (!historyFetchActive) return
        historyFetchActive = false
        initializationStep = InitializationStep.COMPLETE
        initializationFragments = 0
        expectedInitializationFragments = 0
        currentHistorySubtype = null
        deviceSyncPayload = null
        sportPacket = null
        historyPacket = null
        pendingLogSequence = null
        pendingLogPages = 0
        expectedLogPages = 0
        getDevice().unsetBusyTask()
        getDevice().sendDeviceUpdateIntent(getContext())
        GB.signalActivityDataFinish(getDevice())
    }

    override fun disconnect() {
        finishHistoryFetch()
        super.disconnect()
    }

    override fun dispose() {
        heartRateTimeoutHandler.removeCallbacks(heartRateTimeout)
        finishHistoryFetch()
        super.dispose()
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

    override fun onSetCallState(callSpec: CallSpec) {
        if (!isConnected()) return
        val packet = when (callSpec.command) {
            CallSpec.CALL_INCOMING -> encodeIncomingCall(System.currentTimeMillis(), callSpec.name, callSpec.number)
            CallSpec.CALL_REJECT, CallSpec.CALL_END -> encodeCallEnded()
            else -> return
        }
        createTransactionBuilder("Update Nx Wear B8 call state")
            .write(WRITE_CHARACTERISTIC, *packet)
            .queue()
    }

    override fun onHeartRateTest() {
        startManualHeartRate(false)
    }

    override fun onEnableRealtimeHeartRateMeasurement(enable: Boolean) {
        if (enable) startManualHeartRate(true) else stopManualHeartRate()
    }

    @Synchronized
    private fun startManualHeartRate(continuous: Boolean) {
        if (!isConnected()) return
        heartRateTimeoutHandler.removeCallbacks(heartRateTimeout)
        if (manualHeartRateActive) {
            if (!continuous) heartRateTimeoutHandler.postDelayed(heartRateTimeout, HEART_RATE_TIMEOUT_MS)
            return
        }
        manualHeartRateActive = true
        if (!continuous) heartRateTimeoutHandler.postDelayed(heartRateTimeout, HEART_RATE_TIMEOUT_MS)
        createTransactionBuilder("Start Nx Wear B8 heart rate reading")
            .write(WRITE_CHARACTERISTIC, *encodeManualHeartRate(true))
            .queue()
    }

    @Synchronized
    private fun stopManualHeartRate() {
        if (!manualHeartRateActive) return
        manualHeartRateActive = false
        heartRateTimeoutHandler.removeCallbacks(heartRateTimeout)
        if (!isConnected()) return
        createTransactionBuilder("Stop Nx Wear B8 heart rate reading")
            .write(WRITE_CHARACTERISTIC, *encodeManualHeartRate(false))
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
                disconnect()
            } else when {
                isBatteryNotification(packet) -> {
                    handleBatteryNotification(packet)
                    acknowledgeBatteryNotification(packet)
                }
                NxWearHeartRateReading.parse(packet) != null &&
                    initializationStep == InitializationStep.COMPLETE -> handleManualHeartRate(packet)
                NxWearSpo2Reading.parse(packet) != null &&
                    initializationStep == InitializationStep.COMPLETE ->
                    handleSpo2Reading(packet, notifyResult = packet.dataType == NxWearDataType.SPO2)
                NxWearStressReading.parse(packet) != null -> handleStressReading(packet)
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x31 && packet.dataType == NxWearDataType.HEART_RATE_CONTROL &&
                    packet.isComplete && packet.payload.size == 1 -> {
                    acknowledgeMeasurementPacket(requireNotNull(packet.sequence), NxWearDataType.HEART_RATE_CONTROL)
                }
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x34 && packet.dataType == NxWearDataType.SPO2 -> {
                    LOG.debug("Nx Wear B8 acknowledged blood oxygen measurement command")
                }
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x34 && packet.dataType == NxWearDataType.STRESS -> {
                    LOG.debug("Nx Wear B8 acknowledged stress measurement command")
                }
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x34 && packet.dataType == NxWearDataType.MANUAL_HEART_RATE -> {
                    LOG.debug("Nx Wear B8 acknowledged heart rate reading command")
                }
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x34 &&
                    packet.dataType in setOf(NxWearDataType.MESSAGE_NOTICE, NxWearDataType.CALL_CONTROL) -> {
                    LOG.debug("Nx Wear B8 acknowledged call state")
                }
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x31 && packet.dataType == NxWearDataType.ACTIVITY_FA &&
                    initializationStep == InitializationStep.COMPLETE -> handleLogFrame(packet)
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x31 && packet.dataType == NxWearDataType.ACTIVITY_04 &&
                    initializationStep == InitializationStep.COMPLETE -> handleSportFrame(packet)
                packet.kind == NxWearPacket.Kind.FRAMED && packet.command == 0x31 && packet.dataType != null && packet.dataType in ACTIVITY_SUBTYPES -> {
                    handleInitializationActivityFrame(packet)
                }
                packet.kind == NxWearPacket.Kind.CONTINUATION -> {
                    if (pendingLogSequence != null) handleLogContinuation(packet)
                    else if (sportPacket != null && initializationStep == InitializationStep.COMPLETE) handleSportContinuation(packet)
                    else handleInitializationContinuation(packet)
                }
                isCandidateSettingsReply(packet) -> {
                    LOG.debug("Received candidate Nx Wear B8 settings reply with sequence {}", packet.sequence)
                }
                else -> LOG.debug("Unexpected Nx Wear B8 response: {}", packet.raw.joinToString("") { "%02x".format(it) })
            }
            return true
        }
        return super.onCharacteristicChanged(gatt, characteristic, value)
    }

    private fun handleStressReading(packet: NxWearPacket) {
        val readings = requireNotNull(NxWearStressReading.parse(packet))
        acknowledgeMeasurementPacket(requireNotNull(packet.sequence), NxWearDataType.STRESS)
        val valid = readings.filter { it.stress in 1..100 }
        if (valid.isEmpty()) return
        try {
            GBApplication.acquireDB().use { db ->
                val session = db.daoSession
                val deviceId = requireNotNull(DBHelper.getDevice(getDevice(), session).id)
                val userId = requireNotNull(DBHelper.getUser(session).id)
                val samples = valid.map { GenericStressSample(it.timestampMillis, deviceId, userId, it.stress) }
                GenericStressSampleProvider(getDevice(), session).addSamples(samples)
            }
            GB.signalActivityDataFinish(getDevice())
            GB.toast(getContext(), getContext().getString(R.string.nx_wear_b8_stress_result, valid.last().stress),
                Toast.LENGTH_LONG, GB.INFO)
        } catch (e: Exception) {
            LOG.error("Unable to save Nx Wear B8 stress reading", e)
        }
    }

    private fun handleSpo2Reading(packet: NxWearPacket, acknowledge: Boolean = true, notifyResult: Boolean = true) {
        val readings = requireNotNull(NxWearSpo2Reading.parse(packet))
        if (acknowledge) acknowledgeMeasurementPacket(requireNotNull(packet.sequence), requireNotNull(packet.dataType))
        val valid = readings.filter { it.value in 1..100 }
        if (valid.isEmpty()) return
        try {
            GBApplication.acquireDB().use { db ->
                val session = db.daoSession
                val deviceId = requireNotNull(DBHelper.getDevice(getDevice(), session).id)
                val userId = requireNotNull(DBHelper.getUser(session).id)
                val samples = valid.map { GenericSpo2Sample(it.timestampMillis, deviceId, userId, it.value) }
                GenericSpo2SampleProvider(getDevice(), session).addSamples(samples)
            }
            GB.signalActivityDataFinish(getDevice())
            if (notifyResult) {
                GB.toast(getContext(), getContext().getString(R.string.nx_wear_b8_spo2_result, valid.last().value),
                    Toast.LENGTH_LONG, GB.INFO)
            }
        } catch (e: Exception) {
            LOG.error("Unable to save Nx Wear B8 blood oxygen reading", e)
        }
    }

    private fun acknowledgeMeasurementPacket(sequence: Int, dataType: Int) {
        if (initializationStep != InitializationStep.COMPLETE) {
            nextInitializationSequence = (sequence + 1) and 0xff
        }
        if (historyFetchActive && dataType == NxWearDataType.ACTIVITY_FA &&
            initializationStep == InitializationStep.COMPLETE) {
            historyTimeoutHandler.removeCallbacks(historyTimeout)
            historyTimeoutHandler.postDelayed(historyTimeout, HISTORY_QUIET_TIMEOUT_MS)
        }
        createTransactionBuilder("Acknowledge Nx Wear B8 measurement data")
            .write(WRITE_CHARACTERISTIC, *encodeMeasurementAck(sequence, dataType))
            .queue()
    }

    private fun handleLogFrame(packet: NxWearPacket) {
        val declaredLength = requireNotNull(packet.declaredLength)
        pendingLogSequence = packet.sequence
        pendingLogPages = packet.pagesPresent
        expectedLogPages = expectedActivityFragmentCount(declaredLength)
        finishLogIfComplete()
    }

    private fun handleLogContinuation(packet: NxWearPacket) {
        if (packet.continuationIndex != pendingLogPages) return
        pendingLogPages += packet.pagesPresent
        finishLogIfComplete()
    }

    private fun finishLogIfComplete() {
        if (pendingLogPages < expectedLogPages) return
        val sequence = pendingLogSequence ?: return
        pendingLogSequence = null
        pendingLogPages = 0
        expectedLogPages = 0
        acknowledgeMeasurementPacket(sequence, NxWearDataType.ACTIVITY_FA)
    }

    private fun handleManualHeartRate(packet: NxWearPacket, acknowledge: Boolean = true, realtime: Boolean = true) {
        val readings = requireNotNull(NxWearHeartRateReading.parse(packet))
        val sequence = requireNotNull(packet.sequence)
        if (acknowledge && initializationStep != InitializationStep.COMPLETE) {
            nextInitializationSequence = (sequence + 1) and 0xff
        }
        if (acknowledge) {
            createTransactionBuilder("Acknowledge Nx Wear B8 heart rate reading")
                .write(WRITE_CHARACTERISTIC, *encodeMeasurementAck(sequence, requireNotNull(packet.dataType)))
                .queue()
        }
        val valid = readings.filter { HeartRateUtils.getInstance().isValidHeartRateValue(it.bpm) }
        if (valid.isNotEmpty()) {
            try {
                GBApplication.acquireDB().use { db ->
                    val session = db.daoSession
                    val deviceId = requireNotNull(DBHelper.getDevice(getDevice(), session).id)
                    val userId = requireNotNull(DBHelper.getUser(session).id)
                    val samples = valid.map { GenericHeartRateSample(it.timestampMillis, deviceId, userId, it.bpm) }
                    GenericHeartRateSampleProvider(getDevice(), session).addSamples(samples)
                }
                GB.signalActivityDataFinish(getDevice())
            } catch (e: Exception) {
                LOG.error("Unable to save Nx Wear B8 heart rate reading", e)
            }
        }
        if (!realtime || packet.dataType != NxWearDataType.HEART_RATE) return
        // These notifications stream throughout the measurement; they are not completion events.
        // Keep the sensor running until the one-shot timeout or an explicit realtime stop.
        for (reading in valid) {
            val intent = Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                .putExtra(GBDevice.EXTRA_DEVICE, getDevice())
                .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, reading as java.io.Serializable)
                .putExtra(DeviceService.EXTRA_TIMESTAMP, reading.timestampMillis)
            LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent)
        }
    }

    private fun handleInitializationActivityFrame(packet: NxWearPacket) {
        val subtype = requireNotNull(packet.dataType)
        val expected = when (initializationStep) {
            InitializationStep.WAIT_ACTIVITY_09 -> subtype == NxWearDataType.ACTIVITY_09
            InitializationStep.WAIT_ACTIVITY_04 -> subtype == NxWearDataType.ACTIVITY_04
            InitializationStep.WAIT_HISTORY_DATA -> isHistoryDataType(subtype)
            InitializationStep.COMPLETE -> false
        }
        if (!expected) {
            LOG.debug("Unexpected Nx Wear B8 activity subtype {} during initialization step {}", subtype, initializationStep)
            return
        }
        historyTimeoutHandler.removeCallbacks(historyTimeout)
        historyTimeoutHandler.postDelayed(historyTimeout, HISTORY_TIMEOUT_MS)
        currentHistorySubtype = subtype
        nextInitializationSequence = requireNotNull(packet.sequence)
        val declaredLength = packet.declaredLength
        if (declaredLength == null) {
            LOG.debug("Nx Wear B8 activity subtype {} has no declared length", subtype)
            return
        }
        expectedInitializationFragments = expectedActivityFragmentCount(declaredLength)
        initializationFragments = packet.pagesPresent
        if (subtype == NxWearDataType.ACTIVITY_09) {
            deviceSyncPayload = packet.payload
            deviceSyncLength = declaredLength
        }
        if (subtype == NxWearDataType.ACTIVITY_04) sportPacket = packet
        if (initializationStep == InitializationStep.WAIT_HISTORY_DATA) historyPacket = packet
        advanceInitializationWhenComplete()
    }

    private fun handleSportFrame(packet: NxWearPacket) {
        sportPacket = packet
        if (packet.isComplete) {
            persistSportPacket(packet)
            sportPacket = null
            acknowledgeMeasurementPacket(requireNotNull(packet.sequence), NxWearDataType.ACTIVITY_04)
        }
    }

    private fun handleSportContinuation(continuation: NxWearPacket) {
        val first = sportPacket ?: return
        val complete = first.append(continuation) ?: return
        sportPacket = complete
        if (complete.isComplete && initializationStep == InitializationStep.COMPLETE) {
            persistSportPacket(complete)
            sportPacket = null
            acknowledgeMeasurementPacket(requireNotNull(complete.sequence), NxWearDataType.ACTIVITY_04)
        }
    }

    private fun persistSportPacket(packet: NxWearPacket) {
        val readings = NxWearSportReading.parse(packet) ?: return
        if (readings.isEmpty()) return
        try {
            GBApplication.acquireDB().use { db ->
                val session = db.daoSession
                val deviceId = requireNotNull(DBHelper.getDevice(getDevice(), session).id)
                val userId = requireNotNull(DBHelper.getUser(session).id)
                for (reading in readings) {
                    session.nxWearSportSampleDao.insertOrReplace(NxWearSportSample(
                        reading.timestampMillis, deviceId, userId, reading.steps, reading.distanceMeters,
                        reading.caloriesMilliKcal, reading.durationSeconds,
                    ))
                }
            }
            GB.signalActivityDataFinish(getDevice())
        } catch (e: Exception) {
            LOG.error("Failed to persist Nx Wear B8 walking activity", e)
        }
    }

    private fun persistSleepPacket(packet: NxWearPacket) {
        val readings = NxWearSleepReading.parse(packet) ?: return
        if (readings.isEmpty()) return
        try {
            GBApplication.acquireDB().use { db ->
                val session = db.daoSession
                val deviceId = requireNotNull(DBHelper.getDevice(getDevice(), session).id)
                val userId = requireNotNull(DBHelper.getUser(session).id)
                val samples = readings.map {
                    GenericSleepStageSample(it.timestampMillis, deviceId, userId, it.durationMinutes, it.kind.code)
                }
                GenericSleepStageSampleProvider(getDevice(), session).addSamples(samples)
            }
        } catch (e: Exception) {
            LOG.error("Unable to save Nx Wear B8 sleep history", e)
        }
    }

    private fun handleInitializationContinuation(packet: NxWearPacket) {
        if (initializationStep == InitializationStep.COMPLETE || initializationFragments == 0) return
        if (packet.continuationIndex != initializationFragments) return
        if (initializationStep == InitializationStep.WAIT_HISTORY_DATA) {
            historyPacket = historyPacket?.append(packet) ?: return
        }
        historyTimeoutHandler.removeCallbacks(historyTimeout)
        historyTimeoutHandler.postDelayed(historyTimeout, HISTORY_TIMEOUT_MS)
        initializationFragments += packet.pagesPresent
        if (initializationStep == InitializationStep.WAIT_ACTIVITY_09) {
            val current = deviceSyncPayload
            if (current != null) {
                deviceSyncPayload = (current + packet.payload).copyOfRange(0,
                    minOf(current.size + packet.payload.size, deviceSyncLength))
            }
        }
        if (initializationStep == InitializationStep.WAIT_ACTIVITY_04) handleSportContinuation(packet)
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
        if (initializationStep == InitializationStep.WAIT_ACTIVITY_04) {
            sportPacket?.takeIf { it.isComplete }?.let(::persistSportPacket)
            sportPacket = null
        }

        if (initializationStep == InitializationStep.WAIT_HISTORY_DATA) {
            val packet = historyPacket?.takeIf { it.isComplete } ?: return
            if (NxWearHeartRateReading.parse(packet) != null) {
                handleManualHeartRate(packet, acknowledge = false, realtime = false)
            } else if (NxWearSpo2Reading.parse(packet) != null) {
                handleSpo2Reading(packet, acknowledge = false, notifyResult = false)
            } else if (packet.dataType == NxWearDataType.SLEEP) {
                persistSleepPacket(packet)
            } else if (packet.dataType == NxWearDataType.ACTIVITY_05) {
                persistSportPacket(packet)
            }
            historyPacket = null
        }
        initializationFragments = 0
        expectedInitializationFragments = 0
        val (nextStep, request) = when (initializationStep) {
            InitializationStep.WAIT_ACTIVITY_09 -> InitializationStep.WAIT_ACTIVITY_04 to nextInitializationRead(NxWearDataType.ACTIVITY_09)
            InitializationStep.WAIT_ACTIVITY_04 -> InitializationStep.WAIT_HISTORY_DATA to nextInitializationRead(NxWearDataType.ACTIVITY_04)
            InitializationStep.WAIT_HISTORY_DATA -> {
                val subtype = currentHistorySubtype ?: return
                InitializationStep.WAIT_HISTORY_DATA to
                    nextInitializationRead(subtype)
            }
            InitializationStep.COMPLETE -> return
        }
        currentHistorySubtype = null
        initializationStep = nextStep
        createTransactionBuilder("Continue Nx Wear B8 initialization")
            .write(WRITE_CHARACTERISTIC, *request)
            .queue()
        if (historyFetchActive) {
            historyTimeoutHandler.removeCallbacks(historyTimeout)
            historyTimeoutHandler.postDelayed(
                historyTimeout,
                historyTimeoutForSubtype(NxWearPacket.parse(request).dataType),
            )
        }
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
        sync.wristWakeEnabled?.let {
            editor.putString(DeviceSettingsPreferenceConst.PREF_ACTIVATE_DISPLAY_ON_LIFT, if (it) "scheduled" else "off")
            editor.putBoolean(NxWearB8Coordinator.PREF_LIFT_ENABLED, it)
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
            packet.command == 0x31 && packet.dataType == NxWearDataType.BATTERY && packet.isComplete &&
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
        val enabled = prefs.getBoolean(NxWearB8Coordinator.PREF_LIFT_ENABLED, false)
        val start = prefs.getString(
            DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_START, "09:00",
        ) ?: "00:00"
        val end = prefs.getString(
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
            packet.command == 0x04 && packet.dataType == NxWearDataType.ACTIVITY_04 && packet.isComplete &&
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
            NxWearB8Coordinator.PREF_LIFT_ENABLED,
            DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_START,
            DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_LIFT_END,
            DeviceSettingsPreferenceConst.PREF_NX_WEAR_B8_DISPLAY_ON_LIFT_DURATION,
        )

        private val CONFIG_KEYS = HEALTH_CONFIG_KEYS + setOf(
            DeviceSettingsPreferenceConst.PREF_TIMEFORMAT,
            ACTION_MEASURE_STRESS,
            ACTION_MEASURE_SPO2,
            NxWearB8Coordinator.ACTION_EXERCISE_START,
            NxWearB8Coordinator.ACTION_EXERCISE_STOP,
        ) + WRIST_WAKE_CONFIG_KEYS

        private const val ACTION_MEASURE_STRESS = "nx_wear_b8_measure_stress"
        private const val ACTION_MEASURE_SPO2 = "nx_wear_b8_measure_spo2"
        private const val HEART_RATE_TIMEOUT_MS = 60_000L
        private const val HISTORY_TIMEOUT_MS = 60_000L
        private const val HISTORY_QUIET_TIMEOUT_MS = 5_000L
        private val ACTIVITY_SUBTYPES = setOf(
            NxWearDataType.ACTIVITY_09, NxWearDataType.ACTIVITY_04, NxWearDataType.ACTIVITY_05, NxWearDataType.SLEEP,
            NxWearDataType.HEART_RATE, NxWearDataType.EXERCISE_HEART_RATE, NxWearDataType.HISTORY_TEMPERATURE,
            NxWearDataType.ACTIVITY_08, NxWearDataType.HISTORY_SPO2, NxWearDataType.SPO2, NxWearDataType.ACTIVITY_2A,
            NxWearDataType.ACTIVITY_FA,
        )
        internal fun historyTimeoutForSubtype(dataType: Int?): Long =
            if (dataType == NxWearDataType.ACTIVITY_FA) HISTORY_QUIET_TIMEOUT_MS else HISTORY_TIMEOUT_MS

        private val HISTORY_DATA_TYPES = ACTIVITY_SUBTYPES - setOf(NxWearDataType.ACTIVITY_09, NxWearDataType.ACTIVITY_04)
        internal fun isHistoryDataType(dataType: Int): Boolean = dataType in HISTORY_DATA_TYPES
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

        private val SETTINGS_ACK = NxWearPacket.frame(0, 0x34, NxWearDataType.DETECTION_SETTINGS, byteArrayOf(1))
        private val INITIALIZATION_ACK = NxWearPacket.frame(0, 0x34, NxWearDataType.BOOTSTRAP, byteArrayOf(1))
        private val POWER_OFF_ACK = NxWearPacket.frame(0, 0x34, NxWearDataType.POWER_OFF, byteArrayOf(1))

        internal fun encodePowerOff(): ByteArray = NxWearPacket.frame(0, 0x01, NxWearDataType.POWER_OFF, byteArrayOf(0))

        internal fun encodeIncomingCall(timeMillis: Long, name: String?, number: String?): ByteArray {
            val seconds = timeMillis / 1000L
            require(seconds in 0..0xffff_ffffL)
            val callerName = name?.takeIf { it.isNotBlank() } ?: "contact"
            val callerNumber = number?.takeIf { it.isNotBlank() } ?: callerName
            val nameBytes = StringUtils.truncateToBytes(callerName, 52)
            val numberBytes = StringUtils.truncateToBytes(callerNumber, 220 - nameBytes.size)
            val timestamp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seconds.toInt()).array()
            val payload = timestamp + byteArrayOf(1, 2, 1, nameBytes.size.toByte()) + nameBytes +
                byteArrayOf(2, numberBytes.size.toByte()) + numberBytes
            return NxWearPacket.frame(0, 0x01, NxWearDataType.MESSAGE_NOTICE, payload)
        }

        internal fun encodeCallEnded(): ByteArray =
            NxWearPacket.frame(0, 0x01, NxWearDataType.CALL_CONTROL, byteArrayOf(2))

        internal fun encodeBatteryAck(sequence: Int): ByteArray = NxWearPacket.frame(sequence, 0x04, NxWearDataType.BATTERY, byteArrayOf(1))

        internal fun encodeManualHeartRate(start: Boolean): ByteArray =
            NxWearPacket.frame(0, 0x01, NxWearDataType.MANUAL_HEART_RATE, byteArrayOf((if (start) 1 else 0).toByte()))

        internal fun encodeHeartRateAck(sequence: Int): ByteArray =
            NxWearPacket.frame(sequence, 0x04, NxWearDataType.HEART_RATE, byteArrayOf(1))

        internal fun encodeStressMeasurementStart(): ByteArray = NxWearPacket.frame(0, 0x01, NxWearDataType.STRESS, byteArrayOf(1))

        internal fun encodeSpo2MeasurementStart(): ByteArray =
            NxWearPacket.frame(0, 0x01, NxWearDataType.SPO2, byteArrayOf(1, 0, 0, 0, 0))

        internal fun encodeMeasurementAck(sequence: Int, dataType: Int): ByteArray =
            NxWearPacket.frame(sequence, 0x04, dataType, byteArrayOf(1))

        internal fun encodeDetectionSettings(heartRateEnabled: Boolean, intervalMinutes: Int, spo2Enabled: Boolean): ByteArray {
            require(intervalMinutes == 30 || intervalMinutes == 60) { "Unsupported detection interval: $intervalMinutes" }
            return NxWearPacket.frame(
                0, 0x01, NxWearDataType.DETECTION_SETTINGS,
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
                0, 0x01, NxWearDataType.CLOCK,
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
                0, 0x01, NxWearDataType.WRIST_WAKE,
                byteArrayOf(
                    enabled.toWireByte(), startHour.toByte(), startMinute.toByte(),
                    endHour.toByte(), endMinute.toByte(), durationSeconds.toByte(),
                ),
            )
        }

        private val LOG = LoggerFactory.getLogger(NxWearB8Support::class.java)
    }
}
