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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import android.content.Context
import android.net.Uri
import androidx.fragment.app.Fragment
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload
import nodomain.freeyourgadget.gadgetbridge.externalservices.AuthStyle
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Covers an upload the service took without naming the activity it became: what is recorded
 * while the service is still importing it, once it names the activity, and once it says it made
 * nothing of the file.
 */
class WorkoutResolveRemoteIdTest : TestBase() {

    private class FakeTarget(private val resolution: RemoteIdResolution) : WorkoutUploadTarget {
        var resolveCalls = 0
        val metadataPushedTo = mutableListOf<String>()

        override val id = "fake"
        override val nameRes = R.string.service_name_dreeve
        override val authStyle = AuthStyle.API_TOKEN
        override val service = 97
        override val capabilities = UploadCapabilities(
            listOf(WorkoutPayloadFormat.FIT),
            setOf(RemoteOp.CREATE, RemoteOp.REPLACE, RemoteOp.DELETE),
            setOf(RemoteOp.UPDATE),
            emptySet()
        )

        override fun isLoggedIn(context: Context) = true
        override fun showSetup(fragment: Fragment) {}
        override fun logout(context: Context) = true
        override fun onLoginCallback(context: Context, uri: Uri) {}
        override fun activityUrl(context: Context, remoteActivityId: String): String? = null
        override fun upload(context: Context, summary: BaseActivitySummary, payload: File) =
            throw AssertionError("resolving an upload does not upload")

        override fun resolveRemoteId(
            context: Context,
            summary: BaseActivitySummary,
            row: WorkoutUpload
        ): RemoteIdResolution {
            resolveCalls++
            return resolution
        }

        override fun updateMetadata(context: Context, remoteActivityId: String, summary: BaseActivitySummary): Boolean {
            metadataPushedTo += remoteActivityId
            return true
        }
    }

    private val summary = BaseActivitySummary().apply {
        id = 8L
        startTime = Date(1_600_000_000_000L)
        endTime = Date(1_600_003_600_000L)
    }

    /** A row as uploadNew records an upload that named no activity. */
    private fun awaitingRow(target: FakeTarget) = WorkoutUpload().apply {
        summaryId = 8L
        service = target.service
        status = WorkoutUploadStore.STATUS_SUCCESS
        payloadHash = WorkoutUploader.payloadHashFor(target, summary)
    }

    private fun resync(target: FakeTarget, row: WorkoutUpload) = WorkoutUploader.resyncWorkout(
        GBApplication.getContext(), createDummyGDevice("00:00:00:00:00:08"), null, target,
        summary, row, "current"
    )

    private fun storedRow(target: FakeTarget) =
        WorkoutUploadStore.rowsForSummaries(listOf(8L))[8L]?.get(target.service)

    @Test
    fun aFileStillBeingImportedIsAskedAboutAgainNextTime() {
        val target = FakeTarget(RemoteIdResolution.Pending)
        assertEquals(WorkoutUploader.SyncOutcome.AlreadyInStep, resync(target, awaitingRow(target)))
        assertNull(storedRow(target))
    }

    @Test
    fun aNamedActivityIsStoredAndGivenTheWorkoutsName() {
        val target = FakeTarget(RemoteIdResolution.Resolved("activity-1"))
        assertEquals(WorkoutUploader.SyncOutcome.Resynced(null), resync(target, awaitingRow(target)))
        assertEquals(listOf("activity-1"), target.metadataPushedTo)
        val row = storedRow(target)!!
        assertEquals("activity-1", row.remoteActivityId)
        assertEquals("current", row.sourceHash)
        assertNull(row.lastError)
    }

    @Test
    fun aRejectedFileIsReportedOnceAndNotSentAgainOnItsOwn() {
        val target = FakeTarget(RemoteIdResolution.Rejected("corrupt file"))
        assertEquals(WorkoutUploader.SyncOutcome.Failed("corrupt file"), resync(target, awaitingRow(target)))
        val row = storedRow(target)!!
        assertTrue(WorkoutUploader.isLost(row))
        assertEquals("corrupt file", row.lastError)
        assertEquals("current", row.sourceHash)

        // The worker comes back after an app update changes the fingerprint, and leaves it be.
        val again = FakeTarget(RemoteIdResolution.Pending)
        assertEquals(WorkoutUploader.SyncOutcome.AlreadyInStep, resync(again, row))
        assertEquals(0, again.resolveCalls)
        assertEquals("corrupt file", storedRow(target)!!.lastError)

        // Asking for it is sending it again.
        assertEquals(WorkoutUploader.SyncAction.UPLOAD, WorkoutUploader.syncActionFor(row, "current"))
    }

    @Test
    fun aServiceThatCouldNotBeAskedIsAskedAgainNextTime() {
        val target = FakeTarget(RemoteIdResolution.Failed)
        assertEquals(WorkoutUploader.SyncOutcome.Failed(null), resync(target, awaitingRow(target)))
        assertNull(storedRow(target))
    }

    @Test
    fun aServiceThatCannotTellCountsAsInStep() {
        val target = FakeTarget(RemoteIdResolution.Unknown)
        assertEquals(WorkoutUploader.SyncOutcome.AlreadyInStep, resync(target, awaitingRow(target)))
        val row = storedRow(target)!!
        assertNull(row.remoteActivityId)
        assertEquals("current", row.sourceHash)
        assertFalse(WorkoutUploader.isLost(row))
    }

    @Test
    fun anUploadAlreadySettledIsNotAskedAbout() {
        val target = FakeTarget(RemoteIdResolution.Resolved("activity-1"))
        val settled = awaitingRow(target).apply { sourceHash = "older" }
        assertEquals(WorkoutUploader.SyncOutcome.AlreadyInStep, resync(target, settled))
        assertEquals(0, target.resolveCalls)
    }
}
