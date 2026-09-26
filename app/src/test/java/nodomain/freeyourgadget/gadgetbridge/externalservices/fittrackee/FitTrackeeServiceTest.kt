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
package nodomain.freeyourgadget.gadgetbridge.externalservices.fittrackee

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.TimeZone

/**
 * Covers which workouts without a track FitTrackee is offered, the figures they are sent as, and
 * what a re-created workout takes over from the one it replaces.
 */
class FitTrackeeServiceTest : TestBase() {

    private fun trackless(
        kind: ActivityKind = ActivityKind.RUNNING,
        meters: Number? = 5230,
        activeSeconds: Number? = 1800,
        gain: Number? = null,
        loss: Number? = null
    ) = BaseActivitySummary().apply {
        id = 1L
        startTime = Date(1_600_000_000_000L) // 2020-09-13 12:26:40 UTC
        endTime = Date(1_600_003_600_000L)
        activityKind = kind.code
        summaryData = ActivitySummaryData().apply {
            meters?.let { add(ActivitySummaryEntries.DISTANCE_METERS, it, ActivitySummaryEntries.UNIT_METERS) }
            activeSeconds?.let { add(ActivitySummaryEntries.ACTIVE_SECONDS, it, ActivitySummaryEntries.UNIT_SECONDS) }
            gain?.let { add(ActivitySummaryEntries.ELEVATION_GAIN, it, ActivitySummaryEntries.UNIT_METERS) }
            loss?.let { add(ActivitySummaryEntries.ELEVATION_LOSS, it, ActivitySummaryEntries.UNIT_METERS) }
        }.toJson()
    }

    @Test
    fun takesAWorkoutWithoutATrackThatHasASportADistanceAndADuration() {
        assertTrue(FitTrackeeService.capabilities.appliesTo(trackless()))
    }

    @Test
    fun refusesAWorkoutWithoutATrackThatWentNowhere() {
        assertFalse(FitTrackeeService.capabilities.appliesTo(trackless(meters = 0)))
        assertFalse(FitTrackeeService.capabilities.appliesTo(trackless(meters = null)))
    }

    @Test
    fun refusesAWorkoutWithoutATrackOfAKindItHasNoSportFor() {
        assertFalse(FitTrackeeService.capabilities.appliesTo(trackless(kind = ActivityKind.STRENGTH_TRAINING)))
    }

    @Test
    fun fallsBackToTheRecordedSpanWhenNoActiveTimeIsKnown() {
        assertEquals(3600, FitTrackeeService.figuresOf(trackless(activeSeconds = null))!!.durationSeconds)
    }

    @Test
    fun sendsTheFiguresInKilometresAndTheProfileTimezone() {
        val body = JSONObject()
        FitTrackeeService.figuresOf(trackless(gain = 40, loss = 38))!!
            .applyTo(body, TimeZone.getTimeZone("Europe/Paris"))
        assertEquals("2020-09-13 14:26", body.getString("workout_date"))
        assertEquals(1800, body.getInt("duration"))
        assertEquals(5.23, body.getDouble("distance"), 1e-9)
        assertEquals(40.0, body.getDouble("ascent"), 1e-9)
        assertEquals(38.0, body.getDouble("descent"), 1e-9)
    }

    @Test
    fun sendsNeitherAscentNorDescentWhenOnlyOneIsKnown() {
        val body = JSONObject()
        FitTrackeeService.figuresOf(trackless(gain = 40))!!.applyTo(body, TimeZone.getTimeZone("UTC"))
        assertFalse(body.has("ascent"))
        assertFalse(body.has("descent"))
    }

    @Test
    fun theReplacementTakesOverWhatTheUserSetButNotTheOldPhoto() {
        val workout = JSONObject()
            .put("description", "windy")
            .put("notes", JSONObject.NULL)
            .put("workout_visibility", "followers_only")
            .put("map_visibility", "private")
            .put("equipments", JSONArray().put(JSONObject().put("id", "eq1")))
            .put(
                "media_attachments",
                JSONArray().put(JSONObject().put("id", "gbphoto")).put(JSONObject().put("id", "userphoto"))
            )
        val kept = FitTrackeeService.KeptContent.of(workout, ownPhoto = "gbphoto")
        assertEquals(listOf("userphoto"), kept.mediaIds)

        val withFile = JSONObject()
        kept.applyTo(withFile, withFile = true)
        assertEquals("windy", withFile.getString("description"))
        assertFalse(withFile.has("notes"))
        assertEquals("followers_only", withFile.getString("workout_visibility"))
        assertEquals("private", withFile.getString("map_visibility"))
        assertEquals("eq1", withFile.getJSONArray("equipment_ids").getString(0))

        val withoutFile = JSONObject()
        kept.applyTo(withoutFile, withFile = false)
        assertFalse(withoutFile.has("map_visibility"))
    }

    @Test
    fun hasNoSportForAKindItDoesNotKnow() {
        assertNull(FitTrackeeService.sportIdFor(trackless(kind = ActivityKind.YOGA)))
        assertEquals(13, FitTrackeeService.sportIdFor(trackless(kind = ActivityKind.INDOOR_CYCLING)))
    }
}
