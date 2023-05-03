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
    // The constants in this file are for IronOS v2.21.

    public static final UUID UUID_SERVICE_LIVE_DATA = UUID.fromString("d85ef000-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_SERVICE_SETTINGS_DATA = UUID.fromString("f6d80000-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_SERVICE_BULK_DATA = UUID.fromString("9eae1000-9d0d-48c5-AA55-33e27f9bc533");

    // Live Data
    public static final UUID UUID_CHARACTERISTIC_LIVE_LIVE_TEMP = UUID.fromString("d85ef001-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_SETPOINT_TEMP = UUID.fromString("d85ef002-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_DC_INPUT = UUID.fromString("d85ef003-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_HANDLE_TEMP = UUID.fromString("d85ef004-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_POWER_LEVEL = UUID.fromString("d85ef005-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_POWER_SRC = UUID.fromString("d85ef006-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_TIP_RES = UUID.fromString("d85ef007-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_UPTIME = UUID.fromString("d85ef008-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_MOVEMENT = UUID.fromString("d85ef009-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_MAX_TEMP = UUID.fromString("d85ef00A-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_RAW_TIP = UUID.fromString("d85ef00B-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_HALL_SENSOR = UUID.fromString("d85ef00C-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_OP_MODE = UUID.fromString("d85ef00D-168e-4a71-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_LIVE_EST_WATTS = UUID.fromString("d85ef00E-168e-4a71-AA55-33e27f9bc533");

    // Bulk Data
    public static final UUID UUID_CHARACTERISTIC_BULK_LIVE_DATA = UUID.fromString("9eae1001-9d0d-48c5-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_BULK_ACCEL_NAME = UUID.fromString("9eae1002-9d0d-48c5-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_BULK_BUILD = UUID.fromString("9eae1003-9d0d-48c5-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_BULK_DEV_SN = UUID.fromString("9eae1004-9d0d-48c5-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_BULK_DEV_ID = UUID.fromString("9eae1005-9d0d-48c5-AA55-33e27f9bc533");

    // Settings Data
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_SAVE = UUID.fromString("f6d7FFFF-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_RESET = UUID.fromString("f6d7FFFE-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_0 = UUID.fromString("f6d70000-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_1 = UUID.fromString("f6d70001-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_2 = UUID.fromString("f6d70002-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_3 = UUID.fromString("f6d70003-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_4 = UUID.fromString("f6d70004-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_5 = UUID.fromString("f6d70005-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_6 = UUID.fromString("f6d70006-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_7 = UUID.fromString("f6d70007-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_8 = UUID.fromString("f6d70008-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_9 = UUID.fromString("f6d70009-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_10 = UUID.fromString("f6d7000a-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_11 = UUID.fromString("f6d7000b-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_12 = UUID.fromString("f6d7000c-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_13 = UUID.fromString("f6d7000d-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_14 = UUID.fromString("f6d7000e-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_15 = UUID.fromString("f6d7000f-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_16 = UUID.fromString("f6d70010-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_17 = UUID.fromString("f6d70011-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_18 = UUID.fromString("f6d70012-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_19 = UUID.fromString("f6d70013-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_20 = UUID.fromString("f6d70014-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_21 = UUID.fromString("f6d70015-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_22 = UUID.fromString("f6d70016-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_23 = UUID.fromString("f6d70017-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_24 = UUID.fromString("f6d70018-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_25 = UUID.fromString("f6d70019-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_26 = UUID.fromString("f6d7001a-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_27 = UUID.fromString("f6d7001b-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_28 = UUID.fromString("f6d7001c-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_29 = UUID.fromString("f6d7001d-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_30 = UUID.fromString("f6d7001e-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_31 = UUID.fromString("f6d7001f-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_32 = UUID.fromString("f6d70020-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_33 = UUID.fromString("f6d70021-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_34 = UUID.fromString("f6d70022-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_35 = UUID.fromString("f6d70023-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_36 = UUID.fromString("f6d70024-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_37 = UUID.fromString("f6d70025-5a10-4eba-AA55-33e27f9bc533");
    public static final UUID UUID_CHARACTERISTIC_SETTINGS_VALUE_38 = UUID.fromString("f6d70026-5a10-4eba-AA55-33e27f9bc533");
}
