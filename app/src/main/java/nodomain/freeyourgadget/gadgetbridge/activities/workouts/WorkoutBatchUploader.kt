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
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

/**
 * Sends several workouts to several services at once, for the workout list's selection mode.
 *
 * A batch can take minutes, so it runs on a scope of its own and outlives the screen that started
 * it. Its outcome is reported once, at the end: to [listener] when a screen is there to show it,
 * otherwise as a single toast, rather than a message per workout and service.
 */
object WorkoutBatchUploader {
    private val LOG = LoggerFactory.getLogger(WorkoutBatchUploader::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val runningData = MutableLiveData(false)

    /** Whether a batch is under way. Only one runs at a time. */
    val running: LiveData<Boolean> = runningData

    /** Receives the outcome of a batch on the main thread, in place of the fallback toast. */
    @set:MainThread
    var listener: ((WorkoutBatchTally) -> Unit)? = null

    /**
     * Starts sending [summaries] to each of [targets], one after another. Answers false, and does
     * nothing, when a batch is already under way.
     */
    @MainThread
    fun start(
        context: Context,
        summaries: List<BaseActivitySummary>,
        targets: List<WorkoutUploadTarget>
    ): Boolean {
        if (runningData.value == true) {
            return false
        }
        val appContext = context.applicationContext
        runningData.value = true
        scope.launch {
            val tally = WorkoutBatchTally(targets)
            try {
                send(appContext, summaries, targets, tally)
            } finally {
                withContext(Dispatchers.Main) {
                    runningData.value = false
                    report(appContext, tally)
                }
            }
        }
        return true
    }

    private fun send(
        context: Context,
        summaries: List<BaseActivitySummary>,
        targets: List<WorkoutUploadTarget>,
        tally: WorkoutBatchTally
    ) {
        val devices = GBApplication.app().deviceManager.devices
        val providers = mutableMapOf<String, ActivityTrackProvider?>()
        LOG.info("Sending {} workouts to {} services", summaries.size, targets.size)
        for (summary in summaries) {
            val gbDevice = deviceOf(summary, devices)
            for (target in targets) {
                if (gbDevice == null || !target.capabilities.appliesTo(summary)) {
                    tally.record(target, WorkoutUploader.SyncOutcome.NotApplicable)
                    continue
                }
                val outcome = try {
                    val provider = providers.getOrPut(gbDevice.address) {
                        gbDevice.deviceCoordinator.getActivityTrackProvider(gbDevice, context)
                    }
                    WorkoutUploader.syncWorkout(context, gbDevice, provider, target, summary)
                } catch (e: Exception) {
                    LOG.error("Sending summary {} to service {} failed", summary.id, target.service, e)
                    WorkoutUploader.SyncOutcome.Failed(null)
                }
                tally.record(target, outcome)
            }
        }
        LOG.info("Batch done: {} of {} sent", tally.sent, tally.attempted)
    }

    /** The known device [summary] was recorded by, or null when it is no longer set up. */
    private fun deviceOf(summary: BaseActivitySummary, devices: List<GBDevice>): GBDevice? {
        val identifier = try {
            summary.device?.identifier
        } catch (e: Exception) {
            LOG.warn("Could not resolve the device of summary {}", summary.id, e)
            null
        } ?: return null
        return devices.firstOrNull { it.address.equals(identifier, ignoreCase = true) }
    }

    private fun report(context: Context, tally: WorkoutBatchTally) {
        val current = listener
        if (current != null) {
            current(tally)
            return
        }
        GB.toast(
            context.getString(R.string.workout_upload_batch_done, tally.sent, tally.attempted),
            Toast.LENGTH_LONG,
            if (tally.failed > 0) GB.WARN else GB.INFO
        )
    }
}

/** What a batch did, per service, in the order the services were asked. */
class WorkoutBatchTally(targets: List<WorkoutUploadTarget>) {
    class Counts {
        var uploaded = 0
        var updated = 0
        var inStep = 0
        var notApplicable = 0
        var failed = 0

        /** First reason a sync failed or was partly refused, null when none was given. */
        var reason: String? = null
    }

    val byTarget: Map<WorkoutUploadTarget, Counts> = targets.associateWith { Counts() }

    fun record(target: WorkoutUploadTarget, outcome: WorkoutUploader.SyncOutcome) {
        val counts = byTarget[target] ?: return
        when (outcome) {
            is WorkoutUploader.SyncOutcome.Uploaded -> {
                counts.uploaded++
                if (counts.reason == null) counts.reason = outcome.refusal
            }
            is WorkoutUploader.SyncOutcome.Resynced -> {
                counts.updated++
                if (counts.reason == null) counts.reason = outcome.refusal
            }
            is WorkoutUploader.SyncOutcome.AlreadyInStep -> counts.inStep++
            is WorkoutUploader.SyncOutcome.NotApplicable -> counts.notApplicable++
            is WorkoutUploader.SyncOutcome.Failed -> {
                counts.failed++
                if (counts.reason == null) counts.reason = outcome.reason
            }
        }
    }

    /** Syncs the service now holds, counting a workout it already held as it stands. */
    val sent: Int get() = byTarget.values.sumOf { it.uploaded + it.updated + it.inStep }

    val failed: Int get() = byTarget.values.sumOf { it.failed }

    /** Syncs that were tried, leaving out workouts a service cannot take. */
    val attempted: Int get() = sent + failed

    /** One line per service, listing only the counts that are not zero. */
    fun describe(context: Context): String = byTarget.entries.joinToString("\n\n") { (target, counts) ->
        val res = context.resources
        val parts = listOf(
            R.plurals.workout_upload_batch_uploaded to counts.uploaded,
            R.plurals.workout_upload_batch_updated to counts.updated,
            R.plurals.workout_upload_batch_in_step to counts.inStep,
            R.plurals.workout_upload_batch_not_applicable to counts.notApplicable,
            R.plurals.workout_upload_batch_failed to counts.failed
        ).filter { it.second > 0 }
            .joinToString(", ") { (id, n) -> res.getQuantityString(id, n, n) }
        val line = context.getString(
            R.string.workout_upload_batch_line, context.getString(target.nameRes), parts
        )
        counts.reason?.let { "$line\n$it" } ?: line
    }
}
