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
package nodomain.freeyourgadget.gadgetbridge.externalservices.dreeve

import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteIdResolution
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date

/**
 * Covers how Dreeve's answers are read: which import an upload became, which activity a skipped
 * file duplicates, and which sport a workout kind is sent as.
 */
class DreeveServiceTest : TestBase() {

    private fun import(status: String, activityId: String? = null, errorMessage: String? = null) =
        DreeveImport("gadgetbridge-1-abc.fit", status, activityId, errorMessage)

    private fun resolve(import: DreeveImport?, skipped: String? = null) =
        DreeveService.resolutionOf(import, "no reason") { skipped }

    @Test
    fun theUploadNameIsStableAndFollowsTheTrack() {
        val name = DreeveService.uploadFileName(42L, "0123456789abcdef")
        assertEquals("gadgetbridge-42-0123456789ab.fit", name)
        assertEquals(name, DreeveService.uploadFileName(42L, "0123456789abcdef"))
        assertNotEquals(name, DreeveService.uploadFileName(42L, "fedcba9876543210"))
    }

    @Test
    fun eachImportStatusResolvesToWhatItSays() {
        assertEquals(RemoteIdResolution.Unknown, resolve(null))
        assertEquals(RemoteIdResolution.Pending, resolve(import("queued")))
        assertEquals(RemoteIdResolution.Resolved("activity-1"), resolve(import("success", "activity-1")))
        assertEquals(RemoteIdResolution.Rejected("bad file"), resolve(import("failed", errorMessage = "bad file")))
        assertEquals(RemoteIdResolution.Rejected("no reason"), resolve(import("failed")))
        assertEquals(RemoteIdResolution.Resolved("activity-2"), resolve(import("skipped"), skipped = "activity-2"))
        assertEquals(RemoteIdResolution.Unknown, resolve(import("skipped")))
    }

    @Test
    fun theImportLogIsReadWithItsNullFields() {
        val body = """
            {"fileImports":[
              {"id":null,"filename":"a.fit","source":"fitFile","status":"queued","errorMessage":null,"activityId":null,"importedOn":null},
              {"id":"fileImport-1","filename":"b.fit","source":"fitFile","status":"success","errorMessage":null,"activityId":"activity-9","importedOn":"2026-10-02T23:17:12"}
            ],"pagination":{"page":1,"size":25,"total":2,"totalPages":1}}
        """.trimIndent()
        assertEquals(
            listOf(
                DreeveImport("a.fit", "queued", null, null),
                DreeveImport("b.fit", "success", "activity-9", null)
            ),
            DreeveApiClient.parseFileImports(body)
        )
    }

    private fun activity(id: String, startDateLocal: String) =
        JSONObject().put("id", id).put("startDateLocal", startDateLocal)

    @Test
    fun aSkippedFileIsLinkedToTheOneActivityStartingAtTheSameMinuteAndSecond() {
        val start = LocalDateTime.parse("2026-09-30T10:05:42")
        val activities = listOf(
            activity("activity-a", "2026-09-30T12:05:42"),
            activity("activity-b", "2026-09-30T12:06:42")
        )
        assertEquals("activity-a", DreeveService.matchStart(activities, start))
    }

    @Test
    fun aSkippedFileIsNotLinkedWhenNothingOrMoreThanOneMatches() {
        val start = LocalDateTime.parse("2026-09-30T10:05:42")
        assertNull(DreeveService.matchStart(listOf(activity("a", "2026-09-30T10:05:43")), start))
        assertNull(DreeveService.matchStart(listOf(activity("a", "2026-09-28T10:05:42")), start))
        assertNull(
            DreeveService.matchStart(
                listOf(activity("a", "2026-09-30T10:05:42"), activity("b", "2026-09-30T11:05:42")),
                start
            )
        )
    }

    @Test
    fun theLocalStartIsReadInTheGivenZone() {
        val start = Date(LocalDateTime.parse("2026-09-30T08:00:00").toInstant(ZoneOffset.UTC).toEpochMilli())
        assertEquals(
            LocalDateTime.parse("2026-09-30T10:00:00"),
            DreeveService.localStart(start, ZoneOffset.ofHours(2))
        )
    }

    @Test
    fun aKindIsSentAsTheSportDreeveGivesItsFitFile() {
        assertEquals("Run", DreeveService.sportTypeFor(ActivityKind.RUNNING))
        assertEquals("TrailRun", DreeveService.sportTypeFor(ActivityKind.TRAIL_RUN))
        assertEquals("Ride", DreeveService.sportTypeFor(ActivityKind.CYCLING))
        assertEquals("Walk", DreeveService.sportTypeFor(ActivityKind.WALKING))
        assertEquals("Hike", DreeveService.sportTypeFor(ActivityKind.HIKING))
        assertEquals("Swim", DreeveService.sportTypeFor(ActivityKind.POOL_SWIM))
        assertEquals("OpenWaterSwim", DreeveService.sportTypeFor(ActivityKind.SWIMMING_OPENWATER))
        assertNull(DreeveService.sportTypeFor(ActivityKind.UNKNOWN))
    }

    @Test
    fun aDescriptionOrGearSetOnDreeveIsTheUsersOwn() {
        fun activity(description: Any?, gearId: Any?) =
            JSONObject().put("description", description ?: JSONObject.NULL).put("gearId", gearId ?: JSONObject.NULL)
        assertFalse(DreeveService.carriesUserContent(activity("", null)))
        assertFalse(DreeveService.carriesUserContent(activity(null, null)))
        assertTrue(DreeveService.carriesUserContent(activity("my notes", null)))
        assertTrue(DreeveService.carriesUserContent(activity("", "gear-b12659861")))
    }

    @Test
    fun aChangedTrackReplacesTheActivityAndItsNameIsWrittenDirectly() {
        val capabilities = DreeveService.capabilities
        assertTrue(capabilities.trackChangeIsDestructive)
        assertTrue(capabilities.editableMetadata)
        assertFalse(capabilities.metadataComesFromFile)
    }
}
