/*  Copyright (C) 2026 The Gadgetbridge contributors

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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.WorkoutSyncerUtils
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Locale
import kotlin.reflect.KClass

internal object HealthConnectWorkoutDeletion {
    private val LOG = LoggerFactory.getLogger(HealthConnectWorkoutDeletion::class.java)

    private enum class ClientRecordDeleteResult {
        DELETED,
        NOT_FOUND,
        FAILED,
        SKIPPED
    }

    data class WorkoutSummarySnapshot(
        val summaryId: Long,
        val startTime: Instant,
        val endTime: Instant
    )

    fun snapshot(summary: BaseActivitySummary): WorkoutSummarySnapshot? {
        val summaryId = summary.id
        val startTime = summary.startTime
        val endTime = summary.endTime
        if (summaryId == null || startTime == null || endTime == null) {
            LOG.warn(
                "Unable to snapshot workout for Health Connect deletion: id={}, start={}, end={}",
                summaryId,
                startTime,
                endTime
            )
            return null
        }
        return WorkoutSummarySnapshot(
            summaryId = summaryId,
            startTime = startTime.toInstant(),
            endTime = endTime.toInstant()
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun deleteWorkoutFromHealthConnect(context: Context, snapshot: WorkoutSummarySnapshot) {
        deleteWorkoutsFromHealthConnect(context, listOf(snapshot))
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun deleteWorkoutsFromHealthConnect(context: Context, snapshots: List<WorkoutSummarySnapshot>) {
        if (snapshots.isEmpty()) {
            return
        }

        val appContext = context.applicationContext
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (!HealthConnectPermissionManager.isHealthConnectEnabled(appContext)) {
                    LOG.debug("Skipping Health Connect workout deletion for {} workout(s): Health Connect is disabled.", snapshots.size)
                    return@launch
                }

                val healthConnectClient = HealthConnectClientProvider.healthConnectInit(appContext)
                if (healthConnectClient == null) {
                    LOG.warn("Skipping Health Connect workout deletion for {} workout(s): client unavailable.", snapshots.size)
                    return@launch
                }

                val grantedPermissions = GBApplication.getPrefs().preferences.getStringSet(
                    HealthConnectPermissionManager.PREF_KEY_LAST_GRANTED_HC_PERMISSIONS,
                    emptySet()
                ) ?: emptySet()
                val allowSessionDeleteWithoutChildRecords = GBApplication.getPrefs().preferences.getBoolean(
                    GBPrefs.HEALTH_CONNECT_DELETE_ORPHANED_WORKOUT_SESSIONS,
                    false
                )

                for (snapshot in snapshots) {
                    try {
                        deleteWorkoutFromHealthConnect(
                            healthConnectClient,
                            grantedPermissions,
                            snapshot,
                            allowSessionDeleteWithoutChildRecords
                        )
                    } catch (e: Exception) {
                        LOG.warn("Failed to delete workout {} from Health Connect.", snapshot.summaryId, e)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to delete {} workout(s) from Health Connect.", snapshots.size, e)
            }
        }
    }

    internal suspend fun deleteWorkoutFromHealthConnect(
        healthConnectClient: HealthConnectClient,
        grantedPermissions: Set<String>,
        snapshot: WorkoutSummarySnapshot,
        allowSessionDeleteWithoutChildRecords: Boolean = false
    ) {
        var childRecordsDeleted = true
        val childRecordTypes = WorkoutSyncerUtils.WORKOUT_RECORD_TYPES
            .filter { it.key != WorkoutSyncerUtils.RECORD_TYPE_SESSION }

        for (recordType in childRecordTypes) {
            if (!hasWritePermission(grantedPermissions, recordType.recordClass)) {
                LOG.debug(
                    "Skipping Health Connect {} deletion for workout {}: permission not granted.",
                    recordType.recordClass.simpleName,
                    snapshot.summaryId
                )
                childRecordsDeleted = false
                continue
            }

            when (deleteRecordByClientRecordId(healthConnectClient, recordType.recordClass, recordType.key, snapshot)) {
                ClientRecordDeleteResult.DELETED,
                ClientRecordDeleteResult.NOT_FOUND -> Unit

                ClientRecordDeleteResult.FAILED,
                ClientRecordDeleteResult.SKIPPED -> childRecordsDeleted = false
            }
        }

        val sessionDeleteResult = deleteExerciseSessionByClientRecordId(
            healthConnectClient,
            grantedPermissions,
            snapshot,
            childRecordsDeleted,
            allowSessionDeleteWithoutChildRecords
        )

        when {
            allowSessionDeleteWithoutChildRecords && sessionDeleteResult == ClientRecordDeleteResult.NOT_FOUND -> {
                deleteLegacyExerciseSession(healthConnectClient, grantedPermissions, snapshot)
            }

            allowSessionDeleteWithoutChildRecords -> {
                LOG.debug(
                    "Skipping legacy Health Connect exercise deletion for workout {} because clientRecordId deletion result was {}.",
                    snapshot.summaryId,
                    sessionDeleteResult
                )
            }

            else -> {
                LOG.debug(
                    "Skipping legacy Health Connect exercise deletion for workout {} because unmatched session deletion is disabled.",
                    snapshot.summaryId
                )
            }
        }
    }

    private suspend fun deleteExerciseSessionByClientRecordId(
        healthConnectClient: HealthConnectClient,
        grantedPermissions: Set<String>,
        snapshot: WorkoutSummarySnapshot,
        childRecordsDeleted: Boolean,
        allowSessionDeleteWithoutChildRecords: Boolean
    ): ClientRecordDeleteResult {
        if (!hasWritePermission(grantedPermissions, ExerciseSessionRecord::class)) {
            return ClientRecordDeleteResult.SKIPPED
        }
        if (!childRecordsDeleted && !allowSessionDeleteWithoutChildRecords) {
            LOG.warn(
                "Skipping Health Connect ExerciseSessionRecord deletion for workout {} because some child records could not be deleted.",
                snapshot.summaryId
            )
            return ClientRecordDeleteResult.SKIPPED
        }

        return deleteRecordByClientRecordId(
            healthConnectClient,
            ExerciseSessionRecord::class,
            WorkoutSyncerUtils.RECORD_TYPE_SESSION,
            snapshot
        )
    }

    private suspend fun deleteRecordByClientRecordId(
        healthConnectClient: HealthConnectClient,
        recordClass: KClass<out Record>,
        recordTypeKey: String,
        snapshot: WorkoutSummarySnapshot
    ): ClientRecordDeleteResult {
        val clientRecordId = WorkoutSyncerUtils.workoutClientRecordId(recordTypeKey, snapshot.summaryId)
        try {
            healthConnectClient.deleteRecords(
                recordClass,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(clientRecordId)
            )
            LOG.debug(
                "Deleted Health Connect {} with clientRecordId={} for workout {}.",
                recordClass.simpleName,
                clientRecordId,
                snapshot.summaryId
            )
            return ClientRecordDeleteResult.DELETED
        } catch (e: Exception) {
            if (isMissingRecordIdentifierException(e)) {
                LOG.debug(
                    "No Health Connect {} found with clientRecordId={} for workout {}; treating as already absent.",
                    recordClass.simpleName,
                    clientRecordId,
                    snapshot.summaryId
                )
                return ClientRecordDeleteResult.NOT_FOUND
            } else {
                LOG.warn(
                    "Failed to delete Health Connect {} with clientRecordId={} for workout {}.",
                    recordClass.simpleName,
                    clientRecordId,
                    snapshot.summaryId,
                    e
                )
                return ClientRecordDeleteResult.FAILED
            }
        }
    }

    private suspend fun deleteLegacyExerciseSession(
        healthConnectClient: HealthConnectClient,
        grantedPermissions: Set<String>,
        snapshot: WorkoutSummarySnapshot
    ) {
        if (!hasWritePermission(grantedPermissions, ExerciseSessionRecord::class)) {
            return
        }
        if (!snapshot.endTime.isAfter(snapshot.startTime)) {
            LOG.warn(
                "Skipping legacy Health Connect exercise deletion for workout {}: invalid range {} to {}.",
                snapshot.summaryId,
                snapshot.startTime,
                snapshot.endTime
            )
            return
        }

        try {
            healthConnectClient.deleteRecords(
                ExerciseSessionRecord::class,
                TimeRangeFilter.between(snapshot.startTime, snapshot.endTime)
            )
            LOG.debug(
                "Deleted legacy Health Connect ExerciseSessionRecord(s) for workout {} in range {} to {}.",
                snapshot.summaryId,
                snapshot.startTime,
                snapshot.endTime
            )
        } catch (e: Exception) {
            LOG.warn(
                "Failed to delete legacy Health Connect ExerciseSessionRecord(s) for workout {}.",
                snapshot.summaryId,
                e
            )
        }
    }

    private fun hasWritePermission(
        grantedPermissions: Set<String>,
        recordClass: KClass<out Record>
    ): Boolean {
        return HealthPermission.getWritePermission(recordClass) in grantedPermissions
    }

    private fun isMissingRecordIdentifierException(e: Exception): Boolean {
        val message = generateSequence(e as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        return listOf(
            "not found",
            "not exist",
            "does not exist",
            "doesn't exist",
            "non-existing",
            "non existing",
            "no record",
            "invalid uid",
            "invalid identifier",
            "invalid id"
        ).any { it in message }
    }
}
