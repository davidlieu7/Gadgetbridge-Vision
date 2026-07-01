package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.WorkoutSyncerUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.reflect.KClass

class HealthConnectWorkoutDeletionTest {
    @Test
    fun deleteWorkoutFromHealthConnect_deletesExactIdsAndSkipsLegacySessionByDefault() = runBlocking {
        val client = CapturingClient()
        val snapshot = HealthConnectWorkoutDeletion.WorkoutSummarySnapshot(
            summaryId = 42L,
            startTime = Instant.parse("2026-06-01T10:00:00Z"),
            endTime = Instant.parse("2026-06-01T11:00:00Z")
        )
        val grantedPermissions = WorkoutSyncerUtils.WORKOUT_RECORD_TYPES
            .map { HealthPermission.getWritePermission(it.recordClass) }
            .toSet()

        HealthConnectWorkoutDeletion.deleteWorkoutFromHealthConnect(client, grantedPermissions, snapshot)

        assertEquals(WorkoutSyncerUtils.WORKOUT_RECORD_TYPES.size, client.idDeletes.size)
        for (recordType in WorkoutSyncerUtils.WORKOUT_RECORD_TYPES) {
            val delete = client.idDeletes.single { it.recordType == recordType.recordClass }
            assertTrue(delete.recordIds.isEmpty())
            assertEquals(
                listOf(WorkoutSyncerUtils.workoutClientRecordId(recordType.key, snapshot.summaryId)),
                delete.clientRecordIds
            )
        }

        assertTrue(client.rangeDeletes.isEmpty())
    }

    @Test
    fun deleteWorkoutFromHealthConnect_deletesLegacySessionWhenExplicitlyAllowed() = runBlocking {
        val client = CapturingClient()
        val snapshot = HealthConnectWorkoutDeletion.WorkoutSummarySnapshot(
            summaryId = 42L,
            startTime = Instant.parse("2026-06-01T10:00:00Z"),
            endTime = Instant.parse("2026-06-01T11:00:00Z")
        )
        val grantedPermissions = WorkoutSyncerUtils.WORKOUT_RECORD_TYPES
            .map { HealthPermission.getWritePermission(it.recordClass) }
            .toSet()

        HealthConnectWorkoutDeletion.deleteWorkoutFromHealthConnect(
            client,
            grantedPermissions,
            snapshot,
            allowSessionDeleteWithoutChildRecords = true
        )

        assertEquals(1, client.rangeDeletes.size)
        assertEquals(ExerciseSessionRecord::class, client.rangeDeletes.single().recordType)
    }

    @Test
    fun deleteWorkoutFromHealthConnect_skipsSessionWhenChildPermissionMissingByDefault() = runBlocking {
        val client = CapturingClient()
        val snapshot = HealthConnectWorkoutDeletion.WorkoutSummarySnapshot(
            summaryId = 42L,
            startTime = Instant.parse("2026-06-01T10:00:00Z"),
            endTime = Instant.parse("2026-06-01T11:00:00Z")
        )
        val grantedPermissions = setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))

        HealthConnectWorkoutDeletion.deleteWorkoutFromHealthConnect(client, grantedPermissions, snapshot)

        assertTrue(client.idDeletes.none { it.recordType == ExerciseSessionRecord::class })
        assertTrue(client.rangeDeletes.isEmpty())
    }

    private data class IdDelete(
        val recordType: KClass<out Record>,
        val recordIds: List<String>,
        val clientRecordIds: List<String>
    )

    private data class RangeDelete(
        val recordType: KClass<out Record>,
        val timeRangeFilter: TimeRangeFilter
    )

    private class CapturingClient : HealthConnectClient {
        val idDeletes = mutableListOf<IdDelete>()
        val rangeDeletes = mutableListOf<RangeDelete>()

        override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse = throw NotImplementedError()
        override val permissionController: PermissionController get() = throw NotImplementedError()
        override suspend fun updateRecords(records: List<Record>) = throw NotImplementedError()
        override suspend fun deleteRecords(
            recordType: KClass<out Record>,
            recordIdsList: List<String>,
            clientRecordIdsList: List<String>
        ) {
            idDeletes.add(IdDelete(recordType, recordIdsList, clientRecordIdsList))
        }

        override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) {
            rangeDeletes.add(RangeDelete(recordType, timeRangeFilter))
        }

        override suspend fun <T : Record> readRecord(recordType: KClass<T>, recordId: String): ReadRecordResponse<T> = throw NotImplementedError()
        override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> = throw NotImplementedError()
        override suspend fun aggregate(request: AggregateRequest): AggregationResult = throw NotImplementedError()
        override suspend fun aggregateGroupByDuration(request: AggregateGroupByDurationRequest): List<AggregationResultGroupedByDuration> = throw NotImplementedError()
        override suspend fun aggregateGroupByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> = throw NotImplementedError()
        override suspend fun getChangesToken(request: ChangesTokenRequest): String = throw NotImplementedError()
        override suspend fun getChanges(changesToken: String): ChangesResponse = throw NotImplementedError()
    }
}
