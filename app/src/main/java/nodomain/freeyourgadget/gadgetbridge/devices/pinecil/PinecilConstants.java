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

    public static final UUID UUID_SERVICE_BULK_DATA = UUID.fromString("9eae1adb-9d0d-48c5-a6e7-ae93f0ea37b0");
    public static final UUID UUID_SERVICE_SETTINGS_DATA = UUID.fromString("f6d75f91-5a10-4eba-a233-47d3f26a907f");
    public static final UUID UUID_SERVICE_LIVE_DATA = UUID.fromString("d85efab4-168e-4a71-affd-33e27f9bc533");


    public static final UUID UUID_SERVICE_GENERIC_ATTRIBUTE = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_SERVICE_GENERIC_ACCESS = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
}
