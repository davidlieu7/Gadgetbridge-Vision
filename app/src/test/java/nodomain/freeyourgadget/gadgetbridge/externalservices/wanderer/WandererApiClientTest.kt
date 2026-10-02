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
package nodomain.freeyourgadget.gadgetbridge.externalservices.wanderer

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers how Wanderer's answers to a write on an existing trail are read, and which figures go
 * with a replacement track.
 */
class WandererApiClientTest : TestBase() {

    private val appContext get() = GBApplication.getContext()

    private fun write(statusCode: Int?, body: String? = null, reason: String? = null) =
        WandererApiClient.writeResultOf(appContext, statusCode != null, statusCode, body, reason)

    @Test
    fun aWriteThatWentThroughIsDone() {
        assertEquals(WandererWriteResult.Done, write(200, "{}"))
    }

    @Test
    fun anUnknownTrailIsGone() {
        assertEquals(WandererWriteResult.Gone, write(404, """{"message":"The requested resource wasn't found."}"""))
    }

    @Test
    fun aMissingOrRevokedKeyIsExplainedAsSuch() {
        val invalidKey = WandererWriteResult.Failed(appContext.getString(R.string.toast_error_invalid_api_key))
        assertEquals(invalidKey, write(401, """{"message":"Unauthorized"}"""))
        assertEquals(
            invalidKey,
            write(500, """{"message":"Failed to verify API token ClientResponseError 404: Invalid or revoked API token."}""")
        )
    }

    @Test
    fun anyOtherRefusalCarriesTheServersMessage() {
        assertEquals(WandererWriteResult.Failed("Invalid file"), write(400, """{"message":"Invalid file"}"""))
        assertEquals(WandererWriteResult.Failed("HTTP 500"), write(500, "<html>"))
    }

    @Test
    fun noAnswerCarriesTheNetworkReason() {
        assertEquals(WandererWriteResult.Failed("offline"), write(null, reason = "offline"))
    }

    @Test
    fun onlyTheFiguresTheSummaryCarriesGoWithAReplacementTrack() {
        val summary = BaseActivitySummary().apply {
            summaryData = ActivitySummaryData().apply {
                add(ActivitySummaryEntries.DISTANCE_METERS, 5230, ActivitySummaryEntries.UNIT_METERS)
                add(ActivitySummaryEntries.ACTIVE_SECONDS, 1800, ActivitySummaryEntries.UNIT_SECONDS)
            }.toJson()
        }
        val stats = WandererService.statsOf(summary)
        assertEquals(setOf("distance", "duration"), stats.keys)
        assertEquals(5230.0, stats.getValue("distance").toDouble(), 0.0)
        assertEquals(1800.0, stats.getValue("duration").toDouble(), 0.0)
        assertEquals(emptyMap<String, String>(), WandererService.statsOf(BaseActivitySummary()))
    }
}
