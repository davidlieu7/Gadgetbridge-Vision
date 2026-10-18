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

    // Advertised power state (last byte of 6-byte manufacturer-specific data)
    const val ADV_POWER_STATE_OFF: Byte = 0x05
    const val ADV_POWER_STATE_ON: Byte = 0x02
    const val ADV_POWER_STATE_SLEEP: Byte = 0x01

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

    // Service 30000 - Remote control
    val UUID_SERVICE_CONTROL: UUID = uuid(0x30000)
    val UUID_CHARACTERISTIC_CONTROL_SHUTTER: UUID = uuid(0x30030)

    // Remote control Shutter characteristic (0x30030)
    val CONTROL_SHUTTER_PRESS = byteArrayOf(0x00, 0x01)
    val CONTROL_SHUTTER_RELEASE = byteArrayOf(0x00, 0x02)

    // Service 40000 - GPS
    val UUID_SERVICE_GPS: UUID = uuid(0x40000)
    val UUID_CHARACTERISTIC_GPS_WRITE: UUID = uuid(0x40002)
    val UUID_CHARACTERISTIC_GPS_NOTIFY: UUID = uuid(0x40003)

    // GPS Write characteristic (0x40002)
    const val GPS_CMD_SUPPORTED: Byte = 0x01
    const val GPS_CMD_UNSUPPORTED: Byte = 0x02
    const val GPS_CMD_DATA: Byte = 0x04

    // GPS Notify characteristic (00040003)
    const val GPS_EVENT_DISABLE: Byte = 0x01
    const val GPS_EVENT_ENABLE: Byte = 0x02
    const val GPS_EVENT_REQUEST: Byte = 0x03

    // Device preferences
    const val PREF_PHONE_UUID = "phone_uuid"
    const val PREF_GPS_UPDATE_INTERVAL = "gps_update_interval"
    const val PREF_GPS_UPDATE_INTERVAL_DEFAULT = 10
    const val PREF_GPS_USE_NETWORK = "gps_use_network"
    const val PREF_GPS_USE_NETWORK_DEFAULT = false

    // Device actions (sent via onSendConfiguration)
    const val ACTION_CONTROL_SHUTTER_PRESS = "control_shutter_press"
    const val ACTION_CONTROL_SHUTTER_RELEASE = "control_shutter_release"
}
