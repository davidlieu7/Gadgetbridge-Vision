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
package nodomain.freeyourgadget.gadgetbridge.externalservices

import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers which explanation of a failed request reaches the user. */
class ExternalServiceHttpTest : TestBase() {

    @Test
    fun aRefusedTokenIsExplainedAsSuchWhenTheCallerCanSaySo() {
        assertEquals(
            "bad key",
            ExternalServiceHttp.failureMessage(401, """{"message":"Unauthorized"}""", null, "bad key")
        )
        assertEquals(
            "Unauthorized",
            ExternalServiceHttp.failureMessage(401, """{"message":"Unauthorized"}""", null)
        )
    }

    @Test
    fun theServersOwnMessageComesBeforeTheNetworkReason() {
        assertEquals(
            "Invalid file",
            ExternalServiceHttp.failureMessage(400, """{"message":"Invalid file"}""", "offline")
        )
    }

    @Test
    fun aBodyWithoutAMessageFallsBackToTheReasonThenTheStatus() {
        assertEquals("offline", ExternalServiceHttp.failureMessage(null, null, "offline"))
        assertEquals("HTTP 502", ExternalServiceHttp.failureMessage(502, "<html>", null))
        assertEquals("HTTP 500", ExternalServiceHttp.failureMessage(500, """{"error":"x"}""", null))
        assertNull(ExternalServiceHttp.failureMessage(null, null, null))
    }
}
