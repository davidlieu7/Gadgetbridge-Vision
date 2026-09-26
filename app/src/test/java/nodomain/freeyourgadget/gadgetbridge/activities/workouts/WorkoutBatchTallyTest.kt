package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import nodomain.freeyourgadget.gadgetbridge.externalservices.dreeve.DreeveService
import nodomain.freeyourgadget.gadgetbridge.externalservices.wanderer.WandererService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutBatchTallyTest {
    @Test
    fun countsEachOutcomePerService() {
        val tally = WorkoutBatchTally(listOf(WandererService, DreeveService))
        tally.record(WandererService, WorkoutUploader.SyncOutcome.Uploaded())
        tally.record(WandererService, WorkoutUploader.SyncOutcome.Resynced(null))
        tally.record(WandererService, WorkoutUploader.SyncOutcome.AlreadyInStep)
        tally.record(WandererService, WorkoutUploader.SyncOutcome.NotApplicable)
        tally.record(DreeveService, WorkoutUploader.SyncOutcome.Failed("HTTP 500"))
        tally.record(DreeveService, WorkoutUploader.SyncOutcome.Uploaded())

        val wanderer = tally.byTarget.getValue(WandererService)
        assertEquals(1, wanderer.uploaded)
        assertEquals(1, wanderer.updated)
        assertEquals(1, wanderer.inStep)
        assertEquals(1, wanderer.notApplicable)
        assertEquals(0, wanderer.failed)
        assertNull(wanderer.reason)

        val dreeve = tally.byTarget.getValue(DreeveService)
        assertEquals(1, dreeve.uploaded)
        assertEquals(1, dreeve.failed)
        assertEquals("HTTP 500", dreeve.reason)
    }

    @Test
    fun totalsLeaveOutWorkoutsAServiceCannotTake() {
        val tally = WorkoutBatchTally(listOf(WandererService, DreeveService))
        tally.record(WandererService, WorkoutUploader.SyncOutcome.Uploaded())
        tally.record(WandererService, WorkoutUploader.SyncOutcome.NotApplicable)
        tally.record(DreeveService, WorkoutUploader.SyncOutcome.AlreadyInStep)
        tally.record(DreeveService, WorkoutUploader.SyncOutcome.Failed(null))

        assertEquals(2, tally.sent)
        assertEquals(1, tally.failed)
        assertEquals(3, tally.attempted)
    }

    @Test
    fun keepsTheFirstReasonGiven() {
        val tally = WorkoutBatchTally(listOf(WandererService))
        tally.record(WandererService, WorkoutUploader.SyncOutcome.Failed(null))
        tally.record(WandererService, WorkoutUploader.SyncOutcome.Resynced("track change declined"))
        tally.record(WandererService, WorkoutUploader.SyncOutcome.Failed("HTTP 500"))

        assertEquals("track change declined", tally.byTarget.getValue(WandererService).reason)
    }

    @Test
    fun ignoresServicesOutsideTheBatch() {
        val tally = WorkoutBatchTally(listOf(WandererService))
        tally.record(DreeveService, WorkoutUploader.SyncOutcome.Uploaded())

        assertEquals(listOf(WandererService), tally.byTarget.keys.toList())
        assertEquals(0, tally.sent)
    }
}
