/*  Copyright (C) 2026 Arnau Mora

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
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.LiveTrackStartMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTrackStartMessageTest {
    @Test
    fun theRequestIsTheOneGarminConnectSends() {
        val bytes = LiveTrackStartMessage(0x01020304).outgoingMessage

        // 15 bytes in all, message 5016 (0x1398), then format 0, transfer rate 10, sample rate 2
        // and the start time, all little endian; the last two bytes are the checksum.
        val header = bytes.copyOfRange(0, bytes.size - 2).joinToString("") { "%02X".format(it) }
        assertEquals("0F009813000A00020004030201", header)
        assertEquals(15, bytes.size)
    }
}
