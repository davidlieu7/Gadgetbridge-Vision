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

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.LiveTrackStopMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTrackStopMessageTest {
    @Test
    fun theRequestIsEmptyLikeGarminConnectsOwn() {
        val bytes = LiveTrackStopMessage().outgoingMessage

        // 6 bytes in all: the size, message 5025 (0x13A1) and the checksum. The watch's own
        // stop message has the same shape (06 00 A1 13 + checksum).
        assertEquals(6, bytes.size)
        assertEquals("0600A113", bytes.copyOfRange(0, 4).joinToString("") { "%02X".format(it) })
    }
}
