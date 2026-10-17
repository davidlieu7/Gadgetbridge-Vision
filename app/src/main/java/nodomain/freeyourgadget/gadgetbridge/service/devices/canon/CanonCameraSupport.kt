/*  Copyright (C) 2026 The Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.canon

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.devices.canon.CanonCameraConstants
import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationProviderType
import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationService
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getParcelableCompat
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

class CanonCameraSupport : AbstractBTLESingleDeviceSupport(LOG) {
    private var needsAuth = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceInfoProfile: DeviceInfoProfile<CanonCameraSupport>

    @Volatile private var gpsEnabled = false
    private var gpsStarted = false

    init {
        addSupportedService(CanonCameraConstants.UUID_SERVICE_MAIN)
        addSupportedService(CanonCameraConstants.UUID_SERVICE_GPS)

        addSupportedService(GattService.SERVICE_DEVICE_INFORMATION.uuid)
        deviceInfoProfile = DeviceInfoProfile<CanonCameraSupport>(this)
        deviceInfoProfile.addListener { intent: Intent? ->
            intent?.action?.let { action ->
                if (DeviceInfoProfile.ACTION_DEVICE_INFO == action) {
                    val info = intent.getParcelableCompat<DeviceInfo>(DeviceInfoProfile.EXTRA_DEVICE_INFO)
                    LOG.debug("Device info: {}", info)
                    for (event in DeviceInfoProfile.toDeviceEvents(info)) {
                        handleGBDeviceEvent(event)
                    }
                }
            }
        }
        addSupportedProfile(deviceInfoProfile)
    }

    override fun useAutoConnect(): Boolean {
        return false
    }

    override fun connectFirstTime(): Boolean {
        needsAuth = true
        return connect()
    }

    override fun connectFromScan(scanResult: ScanResult): Boolean {
        val data = scanResult.scanRecord?.getManufacturerSpecificData(CanonCameraConstants.MANUFACTURER_ID)
        if (data?.size == 6 && data[5] != CanonCameraConstants.ADV_POWER_STATE_ON) {
            LOG.debug("Not connecting to powered-off device")
            return false
        }
        return connect()
    }

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        builder.setDeviceState(GBDevice.State.INITIALIZING)
        setupGps(false)
        if (needsAuth) {
            builder.notify(CanonCameraConstants.UUID_CHARACTERISTIC_MAIN_PAIRING, true)
            builder.setDeviceState(GBDevice.State.AUTHENTICATING)
            builder.write(
                CanonCameraConstants.UUID_CHARACTERISTIC_MAIN_PAIRING,
                CanonCameraConstants.MAIN_PAIRING_REQUEST, *phoneName()
            )
            builder.write(
                CanonCameraConstants.UUID_CHARACTERISTIC_MAIN_IDENTITY,
                CanonCameraConstants.MAIN_IDENTITY_CMD_PHONE_UUID, *phoneUuid()
            )
            builder.write(
                CanonCameraConstants.UUID_CHARACTERISTIC_MAIN_IDENTITY,
                CanonCameraConstants.MAIN_IDENTITY_CMD_PHONE_NAME, *phoneName()
            )
            builder.write(
                CanonCameraConstants.UUID_CHARACTERISTIC_MAIN_IDENTITY,
                0x05, 0x02
            )
        } else {
            builder.notify(CanonCameraConstants.UUID_CHARACTERISTIC_GPS_NOTIFY, true)
            builder.write(
                CanonCameraConstants.UUID_CHARACTERISTIC_MAIN_IDENTITY,
                CanonCameraConstants.MAIN_IDENTITY_CMD_CONNECT
            )
            deviceInfoProfile.requestDeviceInfo(builder)
            builder.setDeviceState(GBDevice.State.INITIALIZED)
            builder.run(this::setupGps)
        }
        return builder
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            setupGps(false)
        }
    }

    override fun dispose() {
        synchronized (ConnectionMonitor) {
            setupGps(false)
            super.dispose()
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        when (characteristic.uuid) {
            CanonCameraConstants.UUID_CHARACTERISTIC_MAIN_PAIRING -> {
                if (value.isNotEmpty()) {
                    handlePairingResult(value)
                }
                return true
            }
            CanonCameraConstants.UUID_CHARACTERISTIC_GPS_NOTIFY -> {
                if (value.isNotEmpty()) {
                    handleGpsEvent(value)
                }
                return true
            }
        }
        return super.onCharacteristicChanged(gatt, characteristic, value)
    }

    private fun handlePairingResult(value: ByteArray) {
        if (value[0] == CanonCameraConstants.MAIN_PAIRING_ACCEPT) {
            LOG.info("Camera accepted pairing")
            needsAuth = false
            val builder = createTransactionBuilder("finishPairing")
            builder.notify(CanonCameraConstants.UUID_CHARACTERISTIC_MAIN_PAIRING, false)
            initializeDevice(builder)
            builder.queue()
        } else {
            LOG.warn("Camera rejected pairing")
            GB.toast("Pairing rejected by camera", Toast.LENGTH_SHORT, GB.WARN)
            createTransactionBuilder("pairingRejected")
                .setDeviceState(GBDevice.State.AUTHENTICATION_REQUIRED)
                .queue()
        }
    }

    private fun handleGpsEvent(value: ByteArray) {
        when (value[0]) {
            CanonCameraConstants.GPS_EVENT_DISABLE -> {
                LOG.info("Camera disabled GPS")
                setupGps(false)
            }
            CanonCameraConstants.GPS_EVENT_ENABLE -> {
                LOG.info("Camera enabled GPS")
                setupGps(true)
            }
            CanonCameraConstants.GPS_EVENT_REQUEST -> {
                LOG.info("GPS request received from camera")
                val response = if (GBLocationService.isGpsSupportedAndEnabled()) {
                    CanonCameraConstants.GPS_CMD_SUPPORTED
                } else {
                    CanonCameraConstants.GPS_CMD_UNSUPPORTED
                }
                writeGps(byteArrayOf(response))
            }
            else -> LOG.debug("Unhandled GPS event: {}", GB.hexdump(value))
        }
    }

    private fun setupGps(enable: Boolean = gpsEnabled) {
        gpsEnabled = enable
        mainHandler.post {
            if (gpsEnabled && !gpsStarted && !device.isInitialized) {
                // Called again at the end of initializeDevice
                LOG.debug("Delaying GPS start until initialized")
            } else if (gpsEnabled && !gpsStarted && GBLocationService.isGpsSupportedAndEnabled()) {
                val updateInterval = devicePrefs.getInt(
                    CanonCameraConstants.PREF_GPS_UPDATE_INTERVAL,
                    CanonCameraConstants.PREF_GPS_UPDATE_INTERVAL_DEFAULT,
                )
                val onlyUseNetwork = devicePrefs.getBoolean(
                    CanonCameraConstants.PREF_GPS_USE_NETWORK,
                    CanonCameraConstants.PREF_GPS_USE_NETWORK_DEFAULT,
                )
                val providerType = if (onlyUseNetwork) {
                    GBLocationProviderType.NETWORK
                } else {
                    GBLocationProviderType.GPS
                }
                LOG.info("Starting location service at interval {} s", updateInterval)
                GBLocationService.start(context, device, providerType, updateInterval * 1000)
                gpsStarted = true
            } else if (!gpsEnabled && gpsStarted) {
                LOG.info("Stopping location service")
                GBLocationService.stop(context, device)
                gpsStarted = false
            }
        }
    }

    override fun onSetGpsLocation(location: Location) {
        if (!gpsEnabled) {
            return
        }
        LOG.debug("Sending location: {}", location)
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(CanonCameraConstants.GPS_CMD_DATA)
        buffer.put((if (location.latitude >= 0) 'N' else 'S').code.toByte())
        buffer.putFloat(abs(location.latitude).toFloat())
        buffer.put((if (location.longitude >= 0) 'E' else 'W').code.toByte())
        buffer.putFloat(abs(location.longitude).toFloat())
        if (location.hasAltitude()) {
            buffer.put((if (location.altitude >= 0) '+' else '-').code.toByte())
            buffer.putFloat(abs(location.altitude).toFloat())
        } else {
            buffer.put(0)
            buffer.putFloat(0f)
        }
        buffer.putInt((location.time / 1000).toInt())
        writeGps(buffer.array(), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    private fun writeGps(data: ByteArray, writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
        createTransactionBuilder("gpsWrite")
            .write(getCharacteristic(CanonCameraConstants.UUID_CHARACTERISTIC_GPS_WRITE).also {
                it?.writeType = writeType
            }, *data)
            .queue()
    }

    private fun phoneUuid(): ByteArray {
        val prefs = devicePrefs.preferences
        val stored = prefs.getString(CanonCameraConstants.PREF_PHONE_UUID, null)
        val uuid = if (stored != null) {
            StringUtils.hexToBytes(stored)
        } else {
            Random.nextBytes(ByteArray(16)).also {
                prefs.edit {
                    putString(
                        CanonCameraConstants.PREF_PHONE_UUID,
                        StringUtils.bytesToHex(it)
                    )
                }
            }
        }
        return uuid
    }

    private fun phoneName(): ByteArray {
        val name = try {
            bluetoothAdapter?.name
        } catch (_: SecurityException) {
            null
        }?.takeUnless { it.isBlank() } ?: "Gadgetbridge"
        return name.toByteArray()
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(CanonCameraSupport::class.java)
    }
}
