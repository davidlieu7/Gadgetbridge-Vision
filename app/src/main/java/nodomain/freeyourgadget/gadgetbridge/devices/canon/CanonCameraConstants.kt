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
package nodomain.freeyourgadget.gadgetbridge.devices.canon

import java.util.UUID

object CanonCameraConstants {
    const val MANUFACTURER_ID = 0x01a9

    private const val BASE_UUID_FORMAT = "000%05x-0000-1000-0000-d8492fffa821"
    private fun uuid(prefix: Int): UUID {
        return UUID.fromString(String.format(BASE_UUID_FORMAT, prefix))
    }

    // Service 10000 - Main
    val UUID_SERVICE_MAIN: UUID = uuid(0x10000)
    val UUID_CHARACTERISTIC_MAIN_PAIRING: UUID = uuid(0x10006)
    val UUID_CHARACTERISTIC_MAIN_IDENTITY: UUID = uuid(0x1000a)

    // Main Pairing characteristic (0x10006)
    const val MAIN_PAIRING_REQUEST: Byte = 0x01
    const val MAIN_PAIRING_ACCEPT: Byte = 0x02

    // Main Identity characteristic (0x1000a)
    const val MAIN_IDENTITY_CMD_CONNECT: Byte = 0x01
    const val MAIN_IDENTITY_CMD_PHONE_UUID: Byte = 0x03
    const val MAIN_IDENTITY_CMD_PHONE_NAME: Byte = 0x04

    // Device preferences
    const val PREF_PHONE_UUID = "phone_uuid"
}
