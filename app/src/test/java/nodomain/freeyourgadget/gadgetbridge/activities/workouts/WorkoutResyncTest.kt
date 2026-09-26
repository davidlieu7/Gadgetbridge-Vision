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
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Covers what a re-sync records when the name, type or photo does not get through: a failure the
 * next run may get past is retried, a refusal the user has to fix is reported and not retried on
 * its own.
 */
class WorkoutResyncTest : TestBase() {

    private class FakeTarget(
        private val metadataSucceeds: Boolean = true,
        private val photoResult: WorkoutUploader.PhotoSyncResult =
            WorkoutUploader.PhotoSyncResult(true, "photo")
    ) : WorkoutUploadTarget {
        override val id = "fake"
        override val nameRes = R.string.service_name_fittrackee
        override val authStyle = AuthStyle.LOGIN
        override val service = 98
        override val capabilities = UploadCapabilities(
            listOf(WorkoutPayloadFormat.FIT),
            setOf(RemoteOp.CREATE),
            setOf(RemoteOp.UPDATE),
            setOf(RemoteOp.CREATE, RemoteOp.REPLACE, RemoteOp.DELETE)
        )

        override fun isLoggedIn(context: Context) = true
        override fun showSetup(fragment: Fragment) {}
        override fun logout(context: Context) = true
        override fun onLoginCallback(context: Context, uri: Uri) {}
        override fun activityUrl(context: Context, remoteActivityId: String): String? = null
        override fun upload(context: Context, summary: BaseActivitySummary, payload: File) =
            throw AssertionError("a re-sync does not upload")

        override fun updateMetadata(context: Context, remoteActivityId: String, summary: BaseActivitySummary) =
            metadataSucceeds

        override fun syncPhoto(
            context: Context,
            remoteActivityId: String,
            photoFile: File?,
            previousMediaRef: String?
        ) = photoResult
    }

    private fun summary(withPhoto: Boolean) = BaseActivitySummary().apply {
        id = 7L
        startTime = Date(1_600_000_000_000L)
        endTime = Date(1_600_003_600_000L)
        if (withPhoto) {
            headerPhoto = File.createTempFile("photo", ".jpg").apply {
                writeBytes(byteArrayOf(1, 2, 3))
                deleteOnExit()
            }.absolutePath
        }
    }

    private fun uploadedRow(target: FakeTarget) = WorkoutUpload().apply {
        summaryId = 7L
        service = target.service
        remoteActivityId = "remote"
        status = WorkoutUploadStore.STATUS_SUCCESS
        sourceHash = "old"
        payloadHash = "track"
    }

    private fun resync(target: FakeTarget, summary: BaseActivitySummary) = WorkoutUploader.resyncWorkout(
        GBApplication.getContext(), createDummyGDevice("00:00:00:00:00:07"), null, target,
        summary, uploadedRow(target), "new"
    )

    private fun storedRow(target: FakeTarget) =
        WorkoutUploadStore.rowsForSummaries(listOf(7L))[7L]!![target.service]!!

    @Test
    fun aMetadataUpdateThatFailsIsReportedAndRetried() {
        val target = FakeTarget(metadataSucceeds = false)
        assertEquals(WorkoutUploader.SyncOutcome.Failed(null), resync(target, summary(false)))
        assertEquals("old", storedRow(target).sourceHash)
    }

    @Test
    fun aMetadataUpdateThatGoesThroughAdvancesTheFingerprint() {
        val target = FakeTarget()
        assertEquals(WorkoutUploader.SyncOutcome.Resynced(null), resync(target, summary(false)))
        assertEquals("new", storedRow(target).sourceHash)
    }

    @Test
    fun aPhotoRefusedForAReasonTheUserHasToFixIsReportedNotRetried() {
        val target = FakeTarget(photoResult = WorkoutUploader.PhotoSyncResult(false, null, "needs media"))
        assertEquals(WorkoutUploader.SyncOutcome.Resynced("needs media"), resync(target, summary(true)))
        val row = storedRow(target)
        assertEquals("new", row.sourceHash)
        assertEquals("needs media", row.lastError)
        // Left unset so the next sync asked for tries the photo again.
        assertNull(row.photoHash)
    }

    @Test
    fun aPhotoThatFailsIsRetriedWithoutTellingTheUser() {
        val target = FakeTarget(photoResult = WorkoutUploader.PhotoSyncResult(false, null))
        assertEquals(WorkoutUploader.SyncOutcome.Resynced(null), resync(target, summary(true)))
        assertEquals("old", storedRow(target).sourceHash)
        assertNull(storedRow(target).lastError)
    }
}
