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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.externalservices.dreeve.DreeveService;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

/**
 * Covers which workouts a service is offered for, which format they are exported in, and when the
 * header photo is worth sending.
 */
public class UploadCapabilitiesTest extends TestBase {

    private static final Set<RemoteOp> NOTHING = Collections.emptySet();

    private UploadCapabilities capabilities(final WorkoutPayloadFormat... formats) {
        return new UploadCapabilities(Arrays.asList(formats), NOTHING, NOTHING, NOTHING, false);
    }

    private UploadCapabilities withMedia(final RemoteOp... ops) {
        return new UploadCapabilities(
                Collections.singletonList(WorkoutPayloadFormat.FIT),
                NOTHING,
                NOTHING,
                ops.length == 0 ? NOTHING : EnumSet.copyOf(Arrays.asList(ops)),
                false
        );
    }

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

    @Test
    public void testAFitServiceTakesAWorkoutWithoutATrack() throws Exception {
        final UploadCapabilities fitOnly = capabilities(WorkoutPayloadFormat.FIT);
        assertEquals(WorkoutPayloadFormat.FIT, fitOnly.formatFor(summary(false)));
        assertTrue(fitOnly.appliesTo(summary(false)));
    }

    @Test
    public void testDreeveTakesEveryWorkoutAsFit() throws Exception {
        final UploadCapabilities dreeve = DreeveService.INSTANCE.getCapabilities();
        assertEquals(WorkoutPayloadFormat.FIT, dreeve.formatFor(summary(false)));
        assertEquals(WorkoutPayloadFormat.FIT, dreeve.formatFor(summary(true)));
        assertTrue(dreeve.appliesTo(summary(false)));
    }

    @Test
    public void testAGpxServiceDoesNotTakeAWorkoutWithoutATrack() throws Exception {
        final UploadCapabilities gpxOnly = capabilities(WorkoutPayloadFormat.GPX);
        assertNull(gpxOnly.formatFor(summary(false)));
        assertFalse(gpxOnly.appliesTo(summary(false)));
        assertEquals(WorkoutPayloadFormat.GPX, gpxOnly.formatFor(summary(true)));
    }

    @Test
    public void testAMultiFormatServiceFallsBackToTheFormatThatNeedsNoTrack() throws Exception {
        final UploadCapabilities both =
                capabilities(WorkoutPayloadFormat.GPX, WorkoutPayloadFormat.FIT);
        assertEquals(WorkoutPayloadFormat.GPX, both.formatFor(summary(true)));
        assertEquals(WorkoutPayloadFormat.FIT, both.formatFor(summary(false)));
    }

    @Test
    public void testAServiceWithoutMediaNeverSyncsAPhoto() {
        final UploadCapabilities none = withMedia();
        assertFalse(none.canSyncMedia(false, true));
        assertFalse(none.canSyncMedia(true, false));
        assertFalse(none.canSyncMedia(true, true));
    }

    @Test
    public void testAServiceThatOnlyAttachesOnUploadCannotReplaceOrRemove() {
        final UploadCapabilities createOnly = withMedia(RemoteOp.CREATE);
        assertTrue(createOnly.canSyncMedia(false, true));
        assertFalse(createOnly.canSyncMedia(true, false));
        assertFalse(createOnly.canSyncMedia(true, true));
    }

    @Test
    public void testAServiceWithFullMediaSupportSyncsEveryChange() {
        final UploadCapabilities full =
                withMedia(RemoteOp.CREATE, RemoteOp.REPLACE, RemoteOp.DELETE);
        assertTrue(full.canSyncMedia(false, true));
        assertTrue(full.canSyncMedia(true, false));
        assertTrue(full.canSyncMedia(true, true));
    }

    @Test
    public void testMetadataIsEditableOnlyWhenItCanBeUpdatedInPlace() {
        assertTrue(new UploadCapabilities(
                Collections.singletonList(WorkoutPayloadFormat.FIT),
                NOTHING, EnumSet.of(RemoteOp.UPDATE), NOTHING, false).getEditableMetadata());
        assertFalse(new UploadCapabilities(
                Collections.singletonList(WorkoutPayloadFormat.FIT),
                NOTHING, EnumSet.of(RemoteOp.CREATE), NOTHING, false).getEditableMetadata());
    }

    @Test
    public void testAServiceThatMapsWhatItIsGivenTakesNothingWithoutARoute() throws Exception {
        final UploadCapabilities routeOnly = new UploadCapabilities(
                Arrays.asList(WorkoutPayloadFormat.FIT, WorkoutPayloadFormat.GPX),
                NOTHING, NOTHING, NOTHING, true);
        assertNull(routeOnly.formatFor(summary(false)));
        assertFalse(routeOnly.appliesTo(summary(false)));
        assertEquals(WorkoutPayloadFormat.FIT, routeOnly.formatFor(summary(true)));
    }

    @Test
    public void testATrackMovesWhenItCanBeUpdatedOrReplaced() {
        assertTrue(WorkoutUploadTargetKt.canChange(EnumSet.of(RemoteOp.CREATE, RemoteOp.UPDATE)));
        assertTrue(WorkoutUploadTargetKt.canChange(EnumSet.of(RemoteOp.CREATE, RemoteOp.REPLACE)));
        assertFalse(WorkoutUploadTargetKt.canChange(EnumSet.of(RemoteOp.CREATE, RemoteOp.DELETE)));
    }
}
