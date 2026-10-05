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
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectSyncStateDao
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectWorkoutSyncFailure
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectWorkoutSyncFailureDao
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.RecordedWorkoutSyncer
import org.slf4j.LoggerFactory

enum class HealthConnectWorkoutStatus(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int
) {
    SYNCED(R.drawable.ic_health_connect_synced, R.string.health_connect_workout_synced),
    FAILED(R.drawable.ic_health_connect_sync_problem, R.string.health_connect_workout_sync_failed)
}

/**
 * Per-workout view of the Health Connect sync.
 *
 * A workout counts as synced when it lies within the WORKOUTS cursor of its device
 * (`HealthConnectSyncState.lastSyncTimestamp`, the end of the latest workout inserted), so a reset
 * of that cursor also clears the status. The cursor cannot tell a workout that failed and was
 * skipped from one that went through, so failures are kept in [HealthConnectWorkoutSyncFailure]
 * and take precedence.
 */
object HealthConnectWorkoutSync {
    /** Local broadcast sent when workout sync states may have changed: a sync ended or was reset. */
    const val ACTION_STATE_CHANGED = "nodomain.freeyourgadget.gadgetbridge.healthconnect.action.workout_sync_state_changed"

    private val LOG = LoggerFactory.getLogger(HealthConnectWorkoutSync::class.java)

    fun statusesFor(context: Context, summaries: Collection<BaseActivitySummary>): Map<Long, HealthConnectWorkoutStatus> {
        val withIds = summaries.filter { it.id != null }
        if (withIds.isEmpty()) return emptyMap()
        val initialSyncStartSeconds = context
            .getSharedPreferences(GBPrefs.HEALTH_CONNECT_SETTINGS, Context.MODE_PRIVATE)
            .getLong(GBPrefs.HEALTH_CONNECT_INITIAL_SYNC_START_TS, -1L)
        return try {
            GBApplication.acquireDbReadOnly().use { db ->
                val session = db.daoSession
                // epoch seconds, keyed by device id
                val cursors = session.healthConnectSyncStateDao.queryBuilder()
                    .where(
                        HealthConnectSyncStateDao.Properties.DeviceId.`in`(withIds.map { it.deviceId }.distinct()),
                        HealthConnectSyncStateDao.Properties.DataType.eq(
                            HealthConnectPermissionManager.HealthConnectDataType.WORKOUTS.name
                        )
                    )
                    .list()
                    .associate { it.deviceId to it.lastSyncTimestamp }
                val failed = session.healthConnectWorkoutSyncFailureDao.queryBuilder()
                    .where(HealthConnectWorkoutSyncFailureDao.Properties.SummaryId.`in`(withIds.map { it.id }))
                    .list()
                    .mapTo(HashSet()) { it.summaryId }

                val statuses = HashMap<Long, HealthConnectWorkoutStatus>()
                for (summary in withIds) {
                    val id = summary.id ?: continue
                    if (id in failed) {
                        statuses[id] = HealthConnectWorkoutStatus.FAILED
                        continue
                    }
                    val cursor = cursors[summary.deviceId] ?: continue
                    if (initialSyncStartSeconds != -1L && summary.startTime.time / 1000 < initialSyncStartSeconds) continue
                    if (summary.endTime.time / 1000 > cursor) continue
                    if (!RecordedWorkoutSyncer.isSyncedWorkout(summary)) continue
                    statuses[id] = HealthConnectWorkoutStatus.SYNCED
                }
                statuses
            }
        } catch (e: Exception) {
            LOG.error("Failed to resolve Health Connect status of {} workouts", withIds.size, e)
            emptyMap()
        }
    }

    fun failureOf(summaryId: Long): HealthConnectWorkoutSyncFailure? {
        return try {
            GBApplication.acquireDbReadOnly().use { db ->
                // A query, not load(): resets delete rows by query, which leaves them in the session cache
                db.daoSession.healthConnectWorkoutSyncFailureDao.queryBuilder()
                    .where(HealthConnectWorkoutSyncFailureDao.Properties.SummaryId.eq(summaryId))
                    .unique()
            }
        } catch (e: Exception) {
            LOG.error("Failed to read Health Connect sync failure of summary {}", summaryId, e)
            null
        }
    }

    fun recordFailure(summary: BaseActivitySummary, error: String) {
        val summaryId = summary.id ?: return
        try {
            GBApplication.acquireDB().use { db ->
                db.daoSession.healthConnectWorkoutSyncFailureDao.insertOrReplace(
                    HealthConnectWorkoutSyncFailure(summaryId, summary.deviceId, System.currentTimeMillis(), error)
                )
            }
        } catch (e: Exception) {
            LOG.error("Failed to record Health Connect sync failure of summary {}", summaryId, e)
        }
    }

    fun clearFailure(summaryId: Long) {
        try {
            GBApplication.acquireDB().use { db ->
                db.daoSession.healthConnectWorkoutSyncFailureDao.deleteByKey(summaryId)
            }
        } catch (e: Exception) {
            LOG.error("Failed to clear Health Connect sync failure of summary {}", summaryId, e)
        }
    }

    @JvmStatic
    fun notifyStateChanged(context: Context) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(ACTION_STATE_CHANGED))
    }
}
