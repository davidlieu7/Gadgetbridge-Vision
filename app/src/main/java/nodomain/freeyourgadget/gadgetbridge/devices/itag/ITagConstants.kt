/*  Copyright (C) 2020-2024 Taavi Eomäe

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
package nodomain.freeyourgadget.gadgetbridge.devices.itag

import java.util.UUID

object ITagConstants {
    /** Contains information about the button state  */
    @JvmField
    val UUID_SERVICE_BUTTON_CHARACTERISTIC: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    // Controls what happens to the tag on disconnect
    @JvmField
    val UUID_LINK_LOSS_CHARACTERISTIC: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

    const val PREF_ITAG_ALERT_FORCE_MILD: String = "itag_alert_force_mild";

    const val PREF_ITAG_ALERT_LINK_LOSS: String = "itag_alert_link_loss";

    // I don't know if this is a case of 1 -> mild beep 2 -> scream because on my iTag alert level 2 does not appear to work
    enum class LinkLossBehaviour(val value: Byte) {
        DO_NOTHING(0),
        BEEP(1)
    }
}
