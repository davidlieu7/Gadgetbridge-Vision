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
package nodomain.freeyourgadget.gadgetbridge.model;

import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.export.FitExporter;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.RecordData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitLap;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitRecord;

/**
 * Builds the track of every activity FIT below the directory named by {@value #DIR_ENV}, e.g. a
 * checkout of https://github.com/ThomasKuehne/FIT-test-files. Skipped when it is not set, since
 * the corpus is not committed. Writes a survey of lap events and intensities, and the files
 * holding rest laps, to {@code build/fit-corpus-survey.txt}.
 */
public class FitActivityTrackProviderCorpusTest {
    private static final String DIR_ENV = "GB_FIT_TEST_DIR";

    @Test
    public void corpusTracksFollowLapIntensity() throws IOException {
        final String env = System.getenv(DIR_ENV);
        Assume.assumeTrue(DIR_ENV + " not set", env != null && new File(env).isDirectory());

        final List<File> files = new ArrayList<>();
        collect(new File(env), files);

        final Map<String, Integer> lapEvents = new TreeMap<>();
        final Map<String, Integer> lapIntensities = new TreeMap<>();
        final List<String> restFiles = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<String> roundTripFailures = new ArrayList<>();
        int parsed = 0;
        long totalMs = 0;
        long slowestMs = 0;
        String slowest = null;
        for (final File file : files) {
            final FitFile fit;
            try {
                fit = FitFile.parseIncoming(file);
            } catch (final Exception e) {
                continue;
            }
            final List<FitLap> laps = new ArrayList<>();
            boolean hasRecords = false;
            for (final RecordData r : fit.getRecords()) {
                if (r instanceof FitLap lap) {
                    laps.add(lap);
                    lapEvents.merge(lap.getEvent() + "/" + lap.getEventType(), 1, Integer::sum);
                    lapIntensities.merge(String.valueOf(lap.getIntensity()), 1, Integer::sum);
                } else if (r instanceof FitRecord) {
                    hasRecords = true;
                }
            }
            if (!hasRecords) {
                continue;
            }
            parsed++;

            final ActivityTrack track;
            try {
                final long t0 = System.nanoTime();
                FitFile.parseIncoming(file);
                track = new FitActivityTrackProvider().getActivityTrack(new BaseActivitySummary(), fit);
                final long ms = (System.nanoTime() - t0) / 1_000_000L;
                totalMs += ms;
                if (ms > slowestMs) {
                    slowestMs = ms;
                    slowest = file.getName() + " (" + file.length() / 1024 + " KiB)";
                }
            } catch (final Exception e) {
                failures.add(file.getName() + ": " + e);
                continue;
            }
            final List<ActivityTrack.SegmentInfo> infos = track.getSegmentInfos();
            if (infos.size() != track.getSegments().size()) {
                failures.add(file.getName() + ": " + infos.size() + " infos for " + track.getSegments().size() + " segments");
            }
            final long rests = infos.stream().filter(i -> i.getIntensity() == ActivityTrack.SegmentIntensity.REST).count();
            final long actives = infos.stream().filter(i -> i.getIntensity() == ActivityTrack.SegmentIntensity.ACTIVE).count();
            final boolean restLap = laps.stream()
                    .anyMatch(l -> FitActivityTrackProvider.intensityOf(l) == ActivityTrack.SegmentIntensity.REST);
            if (restLap) {
                restFiles.add(file.getName() + " laps=" + laps.size()
                        + " " + laps.stream().map(l -> l.getEvent() + "/" + l.getEventType()).distinct().collect(Collectors.toList())
                        + " segments=" + infos.size()
                        + " active=" + actives + " rest=" + rests);
            } else if (rests + actives > 0) {
                failures.add(file.getName() + ": active or rest tagged without any rest lap");
            }

            try {
                final String lost = roundTripLoss(track);
                if (lost != null) {
                    roundTripFailures.add(file.getName() + ": " + lost);
                }
            } catch (final Exception e) {
                roundTripFailures.add(file.getName() + ": export failed: " + e);
            }
        }

        final StringBuilder survey = new StringBuilder();
        survey.append(parsed).append(" activities with records\n");
        survey.append("parse + track: ").append(parsed > 0 ? totalMs / parsed : 0).append(" ms average, slowest ")
                .append(slowestMs).append(" ms for ").append(slowest).append("\n\nlap event/event_type:\n");
        lapEvents.forEach((k, v) -> survey.append("  ").append(k).append(' ').append(v).append('\n'));
        survey.append("\nlap intensity:\n");
        lapIntensities.forEach((k, v) -> survey.append("  ").append(k).append(' ').append(v).append('\n'));
        survey.append("\nfiles with rest laps (").append(restFiles.size()).append("):\n");
        restFiles.forEach(f -> survey.append("  ").append(f).append('\n'));
        survey.append("\nround trip losses (").append(roundTripFailures.size()).append("):\n");
        roundTripFailures.forEach(f -> survey.append("  ").append(f).append('\n'));
        Files.write(new File("build/fit-corpus-survey.txt").toPath(), survey.toString().getBytes(StandardCharsets.UTF_8));

        assertTrue("no activity FIT below " + env, parsed > 0);
        failures.addAll(roundTripFailures);
        assertTrue(failures.size() + " failed:\n" + String.join("\n", failures), failures.isEmpty());
    }

    /**
     * Exports {@code track} with {@link FitExporter}, reads the result back and names what the
     * laps lost on the way, or answers null when every lap the exporter writes came back with its
     * start and intensity. The exporter writes no lap for a segment under 5 records or 10 s other
     * than the first, and leaves byte-identical consecutive points out, so point counts are not
     * compared.
     */
    private String roundTripLoss(final ActivityTrack track) throws Exception {
        final List<ActivityPoint> points = track.getAllPoints();
        if (points.isEmpty()) {
            return null;
        }
        final BaseActivitySummary summary = new BaseActivitySummary();
        summary.setStartTime(points.get(0).getTime());
        summary.setEndTime(points.get(points.size() - 1).getTime());
        summary.setActivityKind(ActivityKind.ACTIVITY.getCode());

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new FitExporter().performExport(track, summary, null, out);
        final ActivityTrack back = new FitActivityTrackProvider()
                .getActivityTrack(summary, FitFile.parseIncoming(out.toByteArray()));

        final List<String> expected = new ArrayList<>();
        final List<ActivityTrack.SegmentIntensity> expectedIntensities = new ArrayList<>();
        for (int i = 0; i < track.getSegments().size(); i++) {
            final List<ActivityPoint> segment = track.getSegments().get(i);
            if (segment.isEmpty()) {
                continue;
            }
            final long start = segment.get(0).getTime().getTime() / 1000L;
            final long span = segment.get(segment.size() - 1).getTime().getTime() / 1000L - start;
            if (!expected.isEmpty() && (segment.size() < 5 || span < 10)) {
                continue;
            }
            expected.add(String.valueOf(start));
            expectedIntensities.add(track.getSegmentInfos().get(i).getIntensity());
        }
        // The exporter writes an unknown lap as active, and an active lap reads back as one only
        // next to a rest.
        final boolean rest = expectedIntensities.contains(ActivityTrack.SegmentIntensity.REST);
        expectedIntensities.replaceAll(i -> i == ActivityTrack.SegmentIntensity.ACTIVE || i == ActivityTrack.SegmentIntensity.UNKNOWN
                ? (rest ? ActivityTrack.SegmentIntensity.ACTIVE : ActivityTrack.SegmentIntensity.UNKNOWN)
                : i);
        for (int i = 0; i < expected.size(); i++) {
            expected.set(i, expected.get(i) + " " + expectedIntensities.get(i));
        }

        final List<String> actual = new ArrayList<>();
        for (int i = 0; i < back.getSegments().size(); i++) {
            final List<ActivityPoint> segment = back.getSegments().get(i);
            if (!segment.isEmpty()) {
                actual.add(segment.get(0).getTime().getTime() / 1000L + " " + back.getSegmentInfos().get(i).getIntensity());
            }
        }
        if (!expected.equals(actual)) {
            return "laps " + expected.size() + " " + abbreviate(expected) + " came back as " + actual.size() + " " + abbreviate(actual);
        }
        return null;
    }

    private static String abbreviate(final List<?> list) {
        return list.size() <= 8 ? list.toString() : list.subList(0, 8) + "...";
    }

    private static void collect(final File dir, final List<File> out) {
        final File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (final File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else if (child.getName().toLowerCase().endsWith(".fit")) {
                out.add(child);
            }
        }
    }
}
