package nodomain.freeyourgadget.gadgetbridge.devices.polar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;

/** Builds the details of a workout from the samples kept in its summary. */
public class PolarIgnite3ActivityTrackProvider implements ActivityTrackProvider {
    @Nullable
    @Override
    public ActivityTrack getActivityTrack(@NonNull final BaseActivitySummary summary) {
        final byte[] raw = summary.getRawSummaryData();
        if (raw == null || raw.length == 0 || summary.getStartTime() == null) {
            return null;
        }
        final PolarProto.PbExerciseSamples samples;
        try {
            samples = PolarProto.PbExerciseSamples.parseFrom(raw);
        } catch (final InvalidProtocolBufferException e) {
            return null;
        }
        final PolarProto.PbDuration interval = samples.getRecordingInterval();
        final long intervalMillis = (interval.getHours() * 3600L + interval.getMinutes() * 60L + interval.getSeconds()) * 1000L
                + interval.getMillis();
        if (intervalMillis <= 0) {
            return null;
        }
        final ActivityTrack track = new ActivityTrack();
        track.setUser(summary.getUser());
        track.setDevice(summary.getDevice());
        track.setName(summary.getName());
        final long start = summary.getStartTime().getTime();
        for (int i = 0; i < samples.getHeartRateSamplesCount(); i++) {
            final ActivityPoint point = new ActivityPoint(new Date(start + i * intervalMillis));
            point.setHeartRate(samples.getHeartRateSamples(i));
            track.addTrackPoint(point);
        }
        return track;
    }
}
