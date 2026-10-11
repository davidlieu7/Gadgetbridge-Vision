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
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Covers how a sync the user asked for treats a workout the service is recorded as holding,
 * depending on whether the service still has it.
 */
class WorkoutRemoteLookupTest : TestBase() {

    private class FakeTarget(
        private val lookup: RemoteLookup,
        private val uploadSucceeds: Boolean = true,
        canLookUp: Boolean = true
    ) : WorkoutUploadTarget {
        val lookedUp = mutableListOf<String>()
        var uploads = 0

        override val id = "fake"
        override val nameRes = R.string.service_name_fittrackee
        override val authStyle = AuthStyle.LOGIN
        override val service = 97
        override val capabilities = UploadCapabilities(
            listOf(WorkoutPayloadFormat.FIT),
            if (canLookUp) setOf(RemoteOp.CREATE, RemoteOp.UPDATE, RemoteOp.READ)
            else setOf(RemoteOp.CREATE, RemoteOp.UPDATE),
            setOf(RemoteOp.UPDATE),
            emptySet()
        )

        override fun isLoggedIn(context: Context) = true
        override fun showSetup(fragment: Fragment) {}
        override fun logout(context: Context) = true
        override fun onLoginCallback(context: Context, uri: Uri) {}
        override fun activityUrl(context: Context, remoteActivityId: String): String? = null

        override fun upload(context: Context, summary: BaseActivitySummary, payload: File): WorkoutUploader.UploadResult {
            uploads++
            return if (uploadSucceeds) WorkoutUploader.UploadResult(true, "new-$uploads", null)
            else WorkoutUploader.UploadResult(false, null, null)
        }

        override fun updateMetadata(context: Context, remoteActivityId: String, summary: BaseActivitySummary) =
            MetadataUpdateResult.Updated

        override fun updateTrack(
            context: Context,
            remoteActivityId: String,
            payload: File,
            summary: BaseActivitySummary,
            row: WorkoutUpload
        ) = TrackUpdateResult.Updated

        override fun syncPhoto(
            context: Context,
            remoteActivityId: String,
            photoFile: File?,
            previousMediaRef: String?
        ) = WorkoutUploader.PhotoSyncResult(true, null)

        override fun lookupRemote(context: Context, remoteActivityId: String): RemoteLookup {
            lookedUp.add(remoteActivityId)
            return lookup
        }
    }

    private val summary = BaseActivitySummary().apply {
        id = 9L
        startTime = Date(1_790_000_000_000L)
        endTime = Date(1_790_001_800_000L)
        activityKind = ActivityKind.RUNNING.code
    }

    /** Records [summary] as uploaded to [target] under `old`, in step with the workout as it is. */
    private fun recordInStep(target: FakeTarget) {
        WorkoutUploadStore.recordSuccess(
            9L, target.service, "old", null,
            sourceHash = WorkoutUploadStore.sourceHashOf(summary),
            payloadHash = WorkoutUploader.payloadHashFor(target, summary)
        )
    }

    private fun sync(target: FakeTarget, verifyRemote: Boolean = true) = WorkoutUploader.syncWorkout(
        GBApplication.getContext(), createDummyGDevice("00:00:00:00:00:09"), null, target, summary,
        verifyRemote = verifyRemote
    )

    private fun row(target: FakeTarget) = WorkoutUploadStore.rowsForSummaries(listOf(9L))[9L]!![target.service]!!

    @Test
    fun withoutVerifyingAnInStepWorkoutIsLeftAlone() {
        val target = FakeTarget(RemoteLookup.GONE)
        recordInStep(target)
        assertEquals(WorkoutUploader.SyncOutcome.AlreadyInStep, sync(target, verifyRemote = false))
        assertTrue(target.lookedUp.isEmpty())
    }

    @Test
    fun aServiceThatCannotLookUpIsNotAsked() {
        val target = FakeTarget(RemoteLookup.GONE, canLookUp = false)
        recordInStep(target)
        assertEquals(WorkoutUploader.SyncOutcome.AlreadyInStep, sync(target))
        assertTrue(target.lookedUp.isEmpty())
        assertEquals(0, target.uploads)
    }

    @Test
    fun anActivityTheServiceStillHoldsIsNotSentAgain() {
        val target = FakeTarget(RemoteLookup.PRESENT)
        recordInStep(target)
        assertEquals(WorkoutUploader.SyncOutcome.AlreadyInStep, sync(target))
        assertEquals(listOf("old"), target.lookedUp)
        assertEquals(0, target.uploads)
    }

    @Test
    fun anActivityDeletedOnTheServiceIsUploadedAgain() {
        val target = FakeTarget(RemoteLookup.GONE)
        recordInStep(target)
        assertEquals(WorkoutUploader.SyncOutcome.Uploaded(), sync(target))
        assertEquals("new-1", row(target).remoteActivityId)
        assertNull(row(target).lastError)
    }

    @Test
    fun aDeletedActivityThatCannotBeSentAgainIsRecordedAsLost() {
        val target = FakeTarget(RemoteLookup.GONE, uploadSucceeds = false)
        recordInStep(target)
        assertEquals(WorkoutUploader.SyncOutcome.Failed(null), sync(target))
        val row = row(target)
        assertTrue(WorkoutUploader.isLost(row))
        assertNotNull(row.lastError)
        // A lost row is uploaded again on the next request, without asking the service first.
        assertEquals(WorkoutUploader.SyncAction.UPLOAD, WorkoutUploader.syncActionFor(row, row.sourceHash!!))
    }

    @Test
    fun aFailedLookupSendsNothingAndKeepsTheRow() {
        val target = FakeTarget(RemoteLookup.FAILED)
        recordInStep(target)
        assertEquals(WorkoutUploader.SyncOutcome.Failed(null), sync(target))
        assertEquals(0, target.uploads)
        assertEquals("old", row(target).remoteActivityId)
        assertNull(row(target).lastError)
    }
}
