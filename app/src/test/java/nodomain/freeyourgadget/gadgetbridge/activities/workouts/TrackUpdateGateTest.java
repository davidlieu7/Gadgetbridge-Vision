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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload;
import nodomain.freeyourgadget.gadgetbridge.externalservices.dreeve.DreeveService;
import nodomain.freeyourgadget.gadgetbridge.externalservices.endurain.EndurainService;
import nodomain.freeyourgadget.gadgetbridge.externalservices.wanderer.WandererService;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

/**
 * Covers when a track a service already holds is brought in line with the workout: freely for a
 * service that writes onto the activity, only once asked for one that has to replace it, and never
 * for one that cannot change an activity at all.
 */
public class TrackUpdateGateTest extends TestBase {

    private BaseActivitySummary summary(final boolean withTrack) throws Exception {
        final BaseActivitySummary summary = new BaseActivitySummary();
        summary.setId(1L);
        summary.setStartTime(new Date(1_600_000_000_000L));
        summary.setEndTime(new Date(1_600_003_600_000L));
        summary.setActivityKind(ActivityKind.RUNNING.getCode());
        if (withTrack) {
            final File gpx = File.createTempFile("track", ".gpx");
            try (FileOutputStream out = new FileOutputStream(gpx)) {
                out.write("<gpx/>".getBytes(StandardCharsets.UTF_8));
            }
            gpx.deleteOnExit();
            summary.setGpxTrack(gpx.getAbsolutePath());
        }
        return summary;
    }

    private WorkoutUpload rowThatHadATrack(final boolean hadTrack) {
        final WorkoutUpload upload = new WorkoutUpload();
        upload.setSummaryId(1L);
        upload.setStatus(WorkoutUploadStore.STATUS_SUCCESS);
        upload.setHadTrack(hadTrack);
        return upload;
    }

    private void setSyncUpdates(final String prefKey, final boolean enabled) {
        GBApplication.getPrefs().getPreferences().edit().putBoolean(prefKey, enabled).commit();
    }

    private void clearSyncUpdates(final String prefKey) {
        GBApplication.getPrefs().getPreferences().edit().remove(prefKey).commit();
    }

    @Test
    public void testAServiceThatWritesOntoTheActivityIsOptedIn() throws Exception {
        clearSyncUpdates(WandererService.INSTANCE.getSyncUpdatesPrefKey());
        assertTrue(WorkoutUploader.mayPushTrackUpdate(
                WandererService.INSTANCE, summary(true), rowThatHadATrack(true)));
    }

    @Test
    public void testAServiceThatWritesOntoTheActivityCanStillBeTurnedOff() throws Exception {
        setSyncUpdates(WandererService.INSTANCE.getSyncUpdatesPrefKey(), false);
        try {
            assertFalse(WorkoutUploader.mayPushTrackUpdate(
                    WandererService.INSTANCE, summary(true), rowThatHadATrack(true)));
        } finally {
            clearSyncUpdates(WandererService.INSTANCE.getSyncUpdatesPrefKey());
        }
    }

    @Test
    public void testAServiceThatMustReplaceTheActivityIsOptedOut() throws Exception {
        clearSyncUpdates(EndurainService.INSTANCE.getSyncUpdatesPrefKey());
        assertFalse(WorkoutUploader.mayPushTrackUpdate(
                EndurainService.INSTANCE, summary(true), rowThatHadATrack(true)));
    }

    @Test
    public void testAServiceThatMustReplaceTheActivityGoesAheadOnceAsked() throws Exception {
        setSyncUpdates(EndurainService.INSTANCE.getSyncUpdatesPrefKey(), true);
        try {
            assertTrue(WorkoutUploader.mayPushTrackUpdate(
                    EndurainService.INSTANCE, summary(true), rowThatHadATrack(true)));
        } finally {
            clearSyncUpdates(EndurainService.INSTANCE.getSyncUpdatesPrefKey());
        }
    }

    @Test
    public void testAddingTheFirstTrackNeedsNoAsking() throws Exception {
        clearSyncUpdates(EndurainService.INSTANCE.getSyncUpdatesPrefKey());
        assertTrue(WorkoutUploader.mayPushTrackUpdate(
                EndurainService.INSTANCE, summary(true), rowThatHadATrack(false)));
    }

    @Test
    public void testAServiceThatCannotChangeAnActivityNeverPushes() throws Exception {
        setSyncUpdates(DreeveService.INSTANCE.getSyncUpdatesPrefKey(), true);
        try {
            assertFalse(WorkoutUploader.mayPushTrackUpdate(
                    DreeveService.INSTANCE, summary(true), rowThatHadATrack(false)));
        } finally {
            clearSyncUpdates(DreeveService.INSTANCE.getSyncUpdatesPrefKey());
        }
    }
}
