/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore

import android.bluetooth.BluetoothAdapter
import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.service.serial.AbstractHeadphoneSerialDeviceSupportV2
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol
import java.util.UUID

abstract class SoundcoreDeviceSupport<T : GBDeviceProtocol> : AbstractHeadphoneSerialDeviceSupportV2<T>() {
    override fun setContext(gbDevice: GBDevice, btAdapter: BluetoothAdapter, context: Context) {
        super.setContext(gbDevice, btAdapter, context)

        val coordinator = gbDevice.deviceCoordinator as? SoundcoreEarbudsCoordinator
            ?: throw IllegalArgumentException("Expected a SoundcoreEarbudsCoordinator for Soundcore earbuds support")

        addSupportedService(getServiceUUID(coordinator.getModelName()))
    }

    fun getServiceUUID(modelId: String): UUID {
        require(MODEL_ID_PATTERN.matches(modelId)) {
            "Invalid Soundcore model ID for control service UUID: $modelId"
        }
        val serviceSuffix = 0x10000 + modelId.toInt(radix = 16)
        return UUID.fromString("$UUID_DEVICE_CTRL_PREFIX${serviceSuffix.toString(16)}")
    }

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        // 1. Request device info (battery, firmware, serial, embedded control functions)
        builder.write(mDeviceProtocol.encodeDeviceInfoRequest());
        // 2. Request extended info (firmware details, serial, additional settings)
        builder.write(mDeviceProtocol.encodeExtendedInfoRequest());
        // 3. Session-init handshake - the device ACKs with an empty response
        builder.write(mDeviceProtocol.encodeSessionInitRequest());
        builder.setDeviceState(GBDevice.State.INITIALIZED)
        return builder
    }

    private companion object {
        const val UUID_DEVICE_CTRL_PREFIX = "0cf12d31-fac3-4553-bd80-d6832e7"
        val MODEL_ID_PATTERN = Regex("[0-9A-Fa-f]{5}")
    }
}
