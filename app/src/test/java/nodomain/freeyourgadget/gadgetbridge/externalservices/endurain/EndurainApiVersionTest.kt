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
package nodomain.freeyourgadget.gadgetbridge.externalservices.endurain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndurainApiVersionTest {
    @Test
    fun serversBefore020KeepTheOldApi() {
        assertFalse(EndurainApiVersion.usesActivitiesApiV2("v0.19.4"))
        assertFalse(EndurainApiVersion.usesActivitiesApiV2("0.19.4"))
        assertFalse(EndurainApiVersion.usesActivitiesApiV2("v0.9.0"))
    }

    @Test
    fun serversFrom020UseTheNewApi() {
        assertTrue(EndurainApiVersion.usesActivitiesApiV2("v0.20.0-beta1"))
        assertTrue(EndurainApiVersion.usesActivitiesApiV2("0.20.0"))
        assertTrue(EndurainApiVersion.usesActivitiesApiV2("v0.21.3"))
        assertTrue(EndurainApiVersion.usesActivitiesApiV2("v1.0.0"))
    }

    @Test
    fun anUnreadableVersionKeepsTheOldApi() {
        assertFalse(EndurainApiVersion.usesActivitiesApiV2(null))
        assertFalse(EndurainApiVersion.usesActivitiesApiV2(""))
        assertFalse(EndurainApiVersion.usesActivitiesApiV2("dev"))
    }
}
