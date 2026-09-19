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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload;

/**
 * Covers what asking a service to take a workout amounts to, for each state that service can
 * already be in: never asked, asked and refused, holding it as it stands, and holding a version
 * the workout has since moved on from.
 */
public class WorkoutSyncActionTest {

    private static final String HASH = "abc123";
    private static final String OTHER_HASH = "def456";

    private WorkoutUpload row(final int status, final String sourceHash, final String lastError) {
        final WorkoutUpload upload = new WorkoutUpload();
        upload.setSummaryId(1L);
        upload.setService(WorkoutUploadStore.SERVICE_ENDURAIN);
        upload.setStatus(status);
        upload.setSourceHash(sourceHash);
        upload.setLastError(lastError);
        return upload;
    }

    @Test
    public void testAWorkoutNoServiceHasSeenIsUploaded() {
        assertEquals(
                WorkoutUploader.SyncAction.UPLOAD,
                WorkoutUploader.syncActionFor(null, HASH)
        );
    }

    @Test
    public void testAnUploadThatFailedIsTriedAgainFromScratch() {
        assertEquals(
                WorkoutUploader.SyncAction.UPLOAD,
                WorkoutUploader.syncActionFor(row(WorkoutUploadStore.STATUS_FAILED, null, "boom"), HASH)
        );
    }

    @Test
    public void testAServiceAlreadyHoldingTheWorkoutIsLeftAlone() {
        assertEquals(
                WorkoutUploader.SyncAction.ALREADY_IN_STEP,
                WorkoutUploader.syncActionFor(row(WorkoutUploadStore.STATUS_SUCCESS, HASH, null), HASH)
        );
    }

    @Test
    public void testAWorkoutChangedSinceItWasUploadedIsResynced() {
        assertEquals(
                WorkoutUploader.SyncAction.RESYNC,
                WorkoutUploader.syncActionFor(row(WorkoutUploadStore.STATUS_SUCCESS, OTHER_HASH, null), HASH)
        );
    }

    @Test
    public void testAnUploadWithSomethingLeftOutstandingIsResynced() {
        assertEquals(
                WorkoutUploader.SyncAction.RESYNC,
                WorkoutUploader.syncActionFor(row(WorkoutUploadStore.STATUS_SUCCESS, HASH, "photo refused"), HASH)
        );
    }

    @Test
    public void testAnUploadRecordedBeforeAnyFingerprintIsResynced() {
        assertEquals(
                WorkoutUploader.SyncAction.RESYNC,
                WorkoutUploader.syncActionFor(row(WorkoutUploadStore.STATUS_SUCCESS, null, null), HASH)
        );
    }
}
