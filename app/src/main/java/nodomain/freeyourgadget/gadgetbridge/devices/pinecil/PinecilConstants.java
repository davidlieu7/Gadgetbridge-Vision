/*  Copyright (C) 2023 Marc Nause

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.pinecil;

import java.util.UUID;

public final class PinecilConstants {

    // see https://github.com/Ralim/IronOS/blob/dev/source/Core/BSP/Pinecilv2/ble_characteristics.h

    public static final UUID UUID_SERVICE_BULK_DATA = UUID.fromString("9eae1adb-9d0d-48c5-a6e7-ae93f0ea37b0");
    public static final UUID UUID_SERVICE_SETTINGS_DATA = UUID.fromString("f6d75f91-5a10-4eba-a233-47d3f26a907f");
    public static final UUID UUID_SERVICE_LIVE_DATA = UUID.fromString("d85efab4-168e-4a71-affd-33e27f9bc533");

    // Bulk Data
    public static final UUID UUID_CHARACTERISTIC_BULK_LIVE_DATA = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_BULK_ACCEL_NAME = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_BULK_BUILD = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_BULK_DEV_ID = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb");

    // Live Data
    public static final UUID UUID_CHARACTERISTIC_LIVE_LIVE_TEMP = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_SETPOINT_TEMP = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_DC_INPUT = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_HANDLE_TEMP = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_POWER_LEVEL = UUID.fromString("00000005-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_POWER_SRC = UUID.fromString("00000006-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_TIP_RES = UUID.fromString("00000007-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_UPTIME = UUID.fromString("00000008-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_MOVEMENT = UUID.fromString("00000009-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_MAX_TEMP = UUID.fromString("0000000a-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_RAW_TIP = UUID.fromString("0000000b-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_HALL_SENSOR = UUID.fromString("0000000c-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_OP_MODE = UUID.fromString("0000000d-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_LIVE_EST_WATTS = UUID.fromString("0000000e-0000-1000-8000-00805f9b34fb");

    // Settings Data
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_SAVE = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_1 = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_2 = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_3 = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_4 = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_5 = UUID.fromString("00000005-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_6 = UUID.fromString("00000006-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_7 = UUID.fromString("00000007-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_8 = UUID.fromString("00000008-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_9 = UUID.fromString("00000009-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_10 = UUID.fromString("00000010-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_11 = UUID.fromString("00000011-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_12 = UUID.fromString("00000012-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_13 = UUID.fromString("00000013-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_14 = UUID.fromString("00000014-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_15 = UUID.fromString("00000015-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_16 = UUID.fromString("00000016-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_17 = UUID.fromString("00000017-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_18 = UUID.fromString("00000018-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_19 = UUID.fromString("00000019-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_20 = UUID.fromString("00000020-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_21 = UUID.fromString("00000021-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_22 = UUID.fromString("00000022-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_23 = UUID.fromString("00000023-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_24 = UUID.fromString("00000024-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_25 = UUID.fromString("00000025-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_26 = UUID.fromString("00000026-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_27 = UUID.fromString("00000027-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_28 = UUID.fromString("00000028-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_29 = UUID.fromString("00000029-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_30 = UUID.fromString("00000030-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_31 = UUID.fromString("00000031-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_32 = UUID.fromString("00000032-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_33 = UUID.fromString("00000033-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_34 = UUID.fromString("00000034-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_35 = UUID.fromString("00000035-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_36 = UUID.fromString("00000036-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_37 = UUID.fromString("00000037-0000-1000-8000-00805f9b34fb");
}
