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
package nodomain.freeyourgadget.gadgetbridge.externalservices.endurain

import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bodies are the ones Endurain v0.19.3 and v0.20.0 answer. */
class EndurainApiClientTest : TestBase() {
    @Test
    fun theRoutesFollowTheVersionTheServerReports() {
        assertFalse(EndurainApiClient.usesResourceRoutes("v0.19.3"))
        assertFalse(EndurainApiClient.usesResourceRoutes("0.19.4"))
        assertFalse(EndurainApiClient.usesResourceRoutes("v0.9.0"))
        assertTrue(EndurainApiClient.usesResourceRoutes("v0.20.0-beta1"))
        assertTrue(EndurainApiClient.usesResourceRoutes("v0.20.0"))
        assertTrue(EndurainApiClient.usesResourceRoutes("v0.21.0"))
        assertTrue(EndurainApiClient.usesResourceRoutes("v1.0.0"))
        assertFalse(EndurainApiClient.usesResourceRoutes("unknown"))
        assertFalse(EndurainApiClient.usesResourceRoutes(""))
    }

    @Test
    fun anUploadJobNamesTheActivityOnceItCompletes() {
        fun outcome(json: String) = EndurainApiClient.jobOutcomeOf(JSONObject(json))
        assertEquals(EndurainJobOutcome.Running, outcome("""{"status":"pending","activity_ids":[]}"""))
        assertEquals(EndurainJobOutcome.Running, outcome("""{"status":"processing","activity_ids":[]}"""))
        assertEquals(
            EndurainJobOutcome.Created(12),
            outcome("""{"status":"completed","error_code":null,"activity_ids":[12]}""")
        )
        assertEquals(
            EndurainJobOutcome.Failed("processing_failed"),
            outcome("""{"status":"failed","error_code":"processing_failed","activity_ids":[]}""")
        )
        assertEquals(
            EndurainJobOutcome.Failed("no_activities_found"),
            outcome("""{"status":"completed","activity_ids":[]}""")
        )
    }

    @Test
    fun anUploadAfterADeletedActivityGetsAKeyOfItsOwn() {
        val fingerprint = "ab".repeat(32)
        assertEquals("gadgetbridge-$fingerprint", EndurainApiClient.idempotencyKeyFor(fingerprint, null))
        assertEquals("gadgetbridge-$fingerprint-after-2", EndurainApiClient.idempotencyKeyFor(fingerprint, 2))
        assertTrue(EndurainApiClient.idempotencyKeyFor(fingerprint, Int.MAX_VALUE).length <= 255)
    }

    @Test
    fun theMediaListIsAPageFrom020() {
        val page = """{"items":[{"id":1,"activity_id":7,"media_type":1,"url":"/api/v1/activities/7/media/1/file?t=x"}],""" +
            """"total":1,"page":1,"num_records":200,"next":null}"""
        assertEquals(listOf(EndurainActivityMedia(1, 7)), EndurainApiClient.parseMediaList(page, true))
        assertEquals(
            emptyList<EndurainActivityMedia>(),
            EndurainApiClient.parseMediaList("""{"items":[],"total":0,"page":1,"num_records":200,"next":null}""", true)
        )
    }

    @Test
    fun theMediaListIsAnArrayOrNullUpTo019() {
        val array = """[{"id":3,"activity_id":7,"media_path":"/app/backend/data/activity_media/7_1.png"}]"""
        assertEquals(listOf(EndurainActivityMedia(3, 7)), EndurainApiClient.parseMediaList(array, false))
        assertEquals(emptyList<EndurainActivityMedia>(), EndurainApiClient.parseMediaList("null", false))
    }
}
