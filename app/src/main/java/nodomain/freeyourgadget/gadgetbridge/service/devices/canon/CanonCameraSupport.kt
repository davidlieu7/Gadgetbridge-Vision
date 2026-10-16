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
import android.content.Intent
import android.widget.Toast
import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.devices.canon.CanonCameraConstants
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
import kotlin.random.Random

class CanonCameraSupport : AbstractBTLESingleDeviceSupport(LOG) {
    private var needsAuth = false

    private val deviceInfoProfile: DeviceInfoProfile<CanonCameraSupport>

    init {
        addSupportedService(CanonCameraConstants.UUID_SERVICE_MAIN)

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

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        builder.setDeviceState(GBDevice.State.INITIALIZING)
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
            builder.write(
                CanonCameraConstants.UUID_CHARACTERISTIC_MAIN_IDENTITY,
                CanonCameraConstants.MAIN_IDENTITY_CMD_CONNECT
            )
            deviceInfoProfile.requestDeviceInfo(builder)
            builder.setDeviceState(GBDevice.State.INITIALIZED)
        }
        return builder
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
