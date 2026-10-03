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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Covers how a service that can only replace an activity has it re-created: what is read first,
 * which half goes first, what the replacement is handed, and when the activity is left alone; and
 * that an activity recorded under another account is never acted on.
 */
class WorkoutRecreateTest : TestBase() {

    private class FakeTarget(
        override val recreateOrder: RecreateOrder,
        private val snapshot: RemoteSnapshot,
        private val uploadResult: WorkoutUploader.UploadResult =
            WorkoutUploader.UploadResult(true, "new", null, "photo"),
        private val deleteSucceeds: Boolean = true
    ) : WorkoutUploadTarget {
        val calls = mutableListOf<String>()
        var carried: Any? = null

        override val id = "fake"
        override val nameRes = R.string.service_name_fittrackee
        override val authStyle = AuthStyle.LOGIN
        override val service = 99
        override val capabilities = UploadCapabilities(
            listOf(WorkoutPayloadFormat.FIT),
            setOf(RemoteOp.CREATE, RemoteOp.REPLACE),
            emptySet(),
            emptySet()
        )

        override fun isLoggedIn(context: Context) = true
        override fun showSetup(fragment: Fragment) {}
        override fun logout(context: Context) = true
        override fun onLoginCallback(context: Context, uri: Uri) {}
        override fun activityUrl(context: Context, remoteActivityId: String): String? = null

        override fun upload(context: Context, summary: BaseActivitySummary, payload: File) =
            throw AssertionError("a re-create uploads with what it carries")

        override fun upload(
            context: Context,
            summary: BaseActivitySummary,
            payload: File,
            carry: Any?
        ): WorkoutUploader.UploadResult {
            calls += "upload"
            carried = carry
            return uploadResult
        }

        override fun snapshotRemote(context: Context, remoteActivityId: String, row: WorkoutUpload): RemoteSnapshot {
            calls += "snapshot $remoteActivityId"
            return snapshot
        }

        override fun deleteRemote(context: Context, remoteActivityId: String): Boolean {
            calls += "delete $remoteActivityId"
            return deleteSucceeds
        }
    }

    private fun summary() = BaseActivitySummary().apply {
        id = 1L
        startTime = Date(1_600_000_000_000L)
        endTime = Date(1_600_003_600_000L)
    }

    private fun recreate(target: FakeTarget) = WorkoutUploader.recreate(
        GBApplication.getContext(), target, "old", File("payload.fit"), summary(), WorkoutUpload()
    )

    @Test
    fun createsTheReplacementBeforeDeletingWhenTheServiceAsksForIt() {
        val target = FakeTarget(RecreateOrder.CREATE_THEN_DELETE, RemoteSnapshot.Found("kept", false))
        val result = recreate(target)
        assertEquals(listOf("snapshot old", "upload", "delete old"), target.calls)
        assertEquals("kept", target.carried)
        assertEquals(TrackUpdateResult.Recreated("new", "photo"), result)
    }

    @Test
    fun deletesFirstWhenTheServiceCannotHoldTwoAtOnce() {
        val target = FakeTarget(RecreateOrder.DELETE_THEN_CREATE, RemoteSnapshot.Found(null, false))
        assertEquals(TrackUpdateResult.Recreated("new", "photo"), recreate(target))
        assertEquals(listOf("snapshot old", "delete old", "upload"), target.calls)
    }

    @Test
    fun leavesTheActivityAloneWhenItWouldLoseWhatTheUserAddedThere() {
        val target = FakeTarget(RecreateOrder.CREATE_THEN_DELETE, RemoteSnapshot.Found(null, true))
        assertTrue(recreate(target) is TrackUpdateResult.Refused)
        assertEquals(listOf("snapshot old"), target.calls)
    }

    @Test
    fun anActivityDeletedOnTheServiceIsSimplyUploadedAgain() {
        val target = FakeTarget(RecreateOrder.DELETE_THEN_CREATE, RemoteSnapshot.Gone)
        assertEquals(TrackUpdateResult.Recreated("new", "photo"), recreate(target))
        assertEquals(listOf("snapshot old", "upload"), target.calls)
    }

    @Test
    fun aReplacementTheServiceNamesLaterIsNotTreatedAsAFailure() {
        val target = FakeTarget(
            RecreateOrder.DELETE_THEN_CREATE,
            RemoteSnapshot.Found(null, false),
            uploadResult = WorkoutUploader.UploadResult(true, null, null)
        )
        assertEquals(TrackUpdateResult.Recreated(null, null), recreate(target))
        assertEquals(listOf("snapshot old", "delete old", "upload"), target.calls)
    }

    @Test
    fun anUnreadableActivityIsRetriedLater() {
        val target = FakeTarget(RecreateOrder.CREATE_THEN_DELETE, RemoteSnapshot.Failed)
        assertEquals(TrackUpdateResult.Failed(), recreate(target))
        assertEquals(listOf("snapshot old"), target.calls)
    }

    @Test
    fun aFailedReplacementLeavesTheOriginalWhenItWasCreatedFirst() {
        val target = FakeTarget(
            RecreateOrder.CREATE_THEN_DELETE,
            RemoteSnapshot.Found(null, false),
            uploadResult = WorkoutUploader.UploadResult(false, null, "HTTP 500")
        )
        assertEquals(TrackUpdateResult.Failed("HTTP 500"), recreate(target))
        assertEquals(listOf("snapshot old", "upload"), target.calls)
    }

    @Test
    fun theReplacementIsKeptWhenTheOriginalCannotBeDeleted() {
        val target = FakeTarget(
            RecreateOrder.CREATE_THEN_DELETE,
            RemoteSnapshot.Found(null, false),
            deleteSucceeds = false
        )
        assertEquals(TrackUpdateResult.Recreated("new", "photo"), recreate(target))
    }

    @Test
    fun aRowLeftByAnotherAccountIsNotActedOn() {
        val target = object : WorkoutUploadTarget by FakeTarget(
            RecreateOrder.CREATE_THEN_DELETE, RemoteSnapshot.Gone
        ) {
            override fun accountKey(context: Context) = "https://b.example#bob"
        }
        val context = GBApplication.getContext()
        assertTrue(WorkoutUploadTargets.holds(context, target, WorkoutUpload().apply { remoteAccount = null }))
        assertTrue(WorkoutUploadTargets.holds(context, target, WorkoutUpload().apply {
            remoteAccount = "https://b.example#bob"
        }))
        assertEquals(false, WorkoutUploadTargets.holds(context, target, WorkoutUpload().apply {
            remoteAccount = "https://a.example#alice"
        }))
    }
}
