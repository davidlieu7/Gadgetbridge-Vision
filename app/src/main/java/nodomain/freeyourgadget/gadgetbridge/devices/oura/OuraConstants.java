/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.devices.oura;

public final class OuraConstants {
    /** Ring tick after the newest history record stored. Ticks keep counting across ring restarts and start over only rarely, as after a reset. */
    public static final String PREF_HISTORY_CURSOR = "oura_history_cursor";
    /** Wall time in ms at which the ring's ticks were zero ("boot time"), mapping its ticks to the same time on every connection. */
    public static final String PREF_BOOT_MILLIS = "oura_boot_millis";
    /** Wall time in ms since which the ring is off the finger as of the history stored, -1 when worn. */
    public static final String PREF_OFF_SINCE = "oura_off_since";

    /** Step counting switch. */
    public static final String PREF_STEPS = "oura_steps";

    private OuraConstants() {
    }
}
