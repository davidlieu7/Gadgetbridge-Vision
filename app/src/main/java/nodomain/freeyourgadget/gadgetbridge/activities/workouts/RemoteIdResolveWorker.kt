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
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Asks a service what the files it took without naming an activity became, once it has had time to
 * import them (see [WorkoutUploadTarget.resolveRemoteId]). Scheduled by [WorkoutUploader] after such
 * an upload, so the activity is linked whether or not the user has auto-upload on, without waiting
 * for the next device sync. Retried with a backoff while a file is still waiting to be imported.
 */
class RemoteIdResolveWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val service = inputData.getInt(INPUT_SERVICE, -1)
        val target = WorkoutUploadTargets.configured(context).firstOrNull { it.service == service }
            ?: return Result.success()
        val rows = WorkoutUploadStore.allUploadedRows(service).values.filter {
            WorkoutUploader.isAwaitingRemoteId(it) && WorkoutUploadTargets.holds(context, target, it)
        }
        if (rows.isEmpty()) {
            return Result.success()
        }

        val summaries = try {
            GBApplication.acquireDbReadOnly().use { db ->
                db.daoSession.baseActivitySummaryDao.queryBuilder()
                    .where(BaseActivitySummaryDao.Properties.Id.`in`(rows.map { it.summaryId }))
                    .list()
                    .associateBy { it.id }
            }
        } catch (e: Exception) {
            LOG.error("Could not load the summaries awaiting an id from service {}", service, e)
            return Result.retry()
        }
        val devices = GBApplication.app().deviceManager.devices
        val providers = mutableMapOf<String, ActivityTrackProvider?>()

        for (row in rows) {
            val summary = summaries[row.summaryId] ?: continue
            val gbDevice = WorkoutBatchUploader.deviceOf(summary, devices) ?: continue
            val provider = providers.getOrPut(gbDevice.address) {
                gbDevice.deviceCoordinator.getActivityTrackProvider(gbDevice, context)
            }
            val outcome = try {
                WorkoutUploader.resyncWorkout(
                    context, gbDevice, provider, target, summary, row,
                    WorkoutUploadStore.sourceHashOf(summary)
                )
            } catch (e: Exception) {
                LOG.error("Resolving summary {} on service {} failed", row.summaryId, service, e)
                null
            }
            if (outcome is WorkoutUploader.SyncOutcome.Failed && outcome.reason != null) {
                WorkoutUploadWorker.notifyFailure(
                    context, target.failureNotificationId, target.uploadFailedMessage(context, outcome.reason)
                )
            }
        }

        val stillAwaiting = WorkoutUploadStore.rowsForSummaries(rows.map { it.summaryId })
            .values.mapNotNull { it[service] }
            .any { WorkoutUploader.isAwaitingRemoteId(it) }
        if (stillAwaiting && runAttemptCount + 1 < MAX_ATTEMPTS) {
            LOG.info("Service {} has not imported every file yet, asking again later", service)
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(RemoteIdResolveWorker::class.java)

        private const val INPUT_SERVICE = "service"
        private const val WORK_NAME_PREFIX = "RemoteIdResolveWorker_"

        /** Dreeve answers an upload that the file "will be processed within five minutes". */
        private const val FIRST_CHECK_DELAY_MINUTES = 6L

        /**
         * Runs before giving up: the first after [FIRST_CHECK_DELAY_MINUTES], the rest after an
         * exponential backoff starting at [BACKOFF_MINUTES], so the last is about two and a half
         * hours after the upload. A row still waiting then is picked up by the next sync.
         */
        private const val MAX_ATTEMPTS = 6
        private const val BACKOFF_MINUTES = 5L

        /**
         * Schedules a check of [service]'s uploads that named no activity. Unique per service and
         * replaced by each new upload, so the check runs once the latest file has had time to be
         * imported, and covers every one before it.
         */
        @JvmStatic
        fun schedule(context: Context, service: Int) {
            val request = OneTimeWorkRequest.Builder(RemoteIdResolveWorker::class.java)
                .setInputData(Data.Builder().putInt(INPUT_SERVICE, service).build())
                .setInitialDelay(FIRST_CHECK_DELAY_MINUTES, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_PREFIX + service,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
