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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectSyncState
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Date

class HealthConnectWorkoutSyncTest : TestBase() {

    @Before
    override fun setUp() {
        super.setUp()
        GBApplication.acquireDB().use { db ->
            db.daoSession.healthConnectSyncStateDao.deleteAll()
            db.daoSession.healthConnectWorkoutSyncFailureDao.deleteAll()
        }
        initialSyncStart(-1L)
    }

    private fun summary(id: Long, deviceId: Long, startSeconds: Long, endSeconds: Long, kind: ActivityKind = ActivityKind.RUNNING) =
        BaseActivitySummary().apply {
            this.id = id
            this.deviceId = deviceId
            startTime = Date(startSeconds * 1000)
            endTime = Date(endSeconds * 1000)
            activityKind = kind.code
        }

    private fun cursor(deviceId: Long, seconds: Long) {
        GBApplication.acquireDB().use { db ->
            db.daoSession.healthConnectSyncStateDao.insertOrReplace(
                HealthConnectSyncState(deviceId, HealthConnectPermissionManager.HealthConnectDataType.WORKOUTS.name, seconds)
            )
        }
    }

    private fun initialSyncStart(seconds: Long) {
        context.getSharedPreferences(GBPrefs.HEALTH_CONNECT_SETTINGS, Context.MODE_PRIVATE).edit()
            .putLong(GBPrefs.HEALTH_CONNECT_INITIAL_SYNC_START_TS, seconds)
            .commit()
    }

    private fun statusOf(summary: BaseActivitySummary) =
        HealthConnectWorkoutSync.statusesFor(context, listOf(summary))[summary.id]

    @Test
    fun workoutEndingAtOrBeforeTheCursorIsSynced() {
        cursor(1, 2_000)
        assertEquals(HealthConnectWorkoutStatus.SYNCED, statusOf(summary(10, 1, 1_000, 2_000)))
    }

    @Test
    fun workoutEndingAfterTheCursorIsNotSynced() {
        cursor(1, 2_000)
        assertNull(statusOf(summary(10, 1, 1_500, 2_001)))
    }

    @Test
    fun withoutCursorNothingIsSynced() {
        assertNull(statusOf(summary(10, 1, 1_000, 2_000)))
    }

    @Test
    fun cursorOfAnotherDeviceDoesNotApply() {
        cursor(2, 5_000)
        assertNull(statusOf(summary(10, 1, 1_000, 2_000)))
    }

    @Test
    fun kindsTheSyncerSkipsAreNeverSynced() {
        cursor(1, 5_000)
        assertNull(statusOf(summary(10, 1, 1_000, 2_000, ActivityKind.NOT_MEASURED)))
        assertNull(statusOf(summary(11, 1, 1_000, 2_000, ActivityKind.SLEEP_ANY)))
    }

    @Test
    fun workoutStartingBeforeTheInitialSyncStartIsNotSynced() {
        cursor(1, 5_000)
        initialSyncStart(1_500)
        assertNull(statusOf(summary(10, 1, 1_000, 2_000)))
        assertEquals(HealthConnectWorkoutStatus.SYNCED, statusOf(summary(11, 1, 1_500, 2_000)))
    }

    @Test
    fun failureWinsOverTheCursorAndClears() {
        cursor(1, 5_000)
        val workout = summary(10, 1, 1_000, 2_000)
        HealthConnectWorkoutSync.recordFailure(workout, "boom")

        assertEquals(HealthConnectWorkoutStatus.FAILED, statusOf(workout))
        assertEquals("boom", HealthConnectWorkoutSync.failureOf(10)?.error)

        HealthConnectWorkoutSync.clearFailure(10)
        assertEquals(HealthConnectWorkoutStatus.SYNCED, statusOf(workout))
    }

    @Test
    fun failureShowsWithoutCursor() {
        val workout = summary(10, 1, 1_000, 2_000)
        HealthConnectWorkoutSync.recordFailure(workout, "boom")
        assertEquals(HealthConnectWorkoutStatus.FAILED, statusOf(workout))
    }

    @Test
    fun resettingWorkoutsOfOneDeviceClearsItsIconsOnly() {
        val device = GBApplication.acquireDB().use { db ->
            DBHelper.getDevice(createDummyGDevice("00:00:00:00:00:01"), db.daoSession).id!!
        }
        val other = GBApplication.acquireDB().use { db ->
            DBHelper.getDevice(createDummyGDevice("00:00:00:00:00:02"), db.daoSession).id!!
        }
        cursor(device, 5_000)
        cursor(other, 5_000)
        val synced = summary(10, device, 1_000, 2_000)
        val failed = summary(11, device, 1_000, 2_000)
        val otherSynced = summary(12, other, 1_000, 2_000)
        HealthConnectWorkoutSync.recordFailure(failed, "boom")

        HealthConnectUtils.resetSyncState(
            createDummyGDevice("00:00:00:00:00:01"),
            HealthConnectPermissionManager.HealthConnectDataType.WORKOUTS
        )

        val statuses = HealthConnectWorkoutSync.statusesFor(context, listOf(synced, failed, otherSynced))
        assertNull(statuses[10])
        assertNull(statuses[11])
        assertEquals(HealthConnectWorkoutStatus.SYNCED, statuses[12])
        assertNull(HealthConnectWorkoutSync.failureOf(11))
    }
}
