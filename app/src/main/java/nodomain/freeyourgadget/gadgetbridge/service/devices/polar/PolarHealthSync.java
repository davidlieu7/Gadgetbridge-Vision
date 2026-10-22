package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;


import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Date;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.polar.PolarIgnite3ActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepScoreSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.PolarIgnite3ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Reads the health files of the watch and stores them in the Gadgetbridge database. The files are
 * only read, never deleted, so a sync can be repeated; samples are replaced by timestamp.
 */
public class PolarHealthSync {
    private static final Logger LOG = LoggerFactory.getLogger(PolarHealthSync.class);

    private static final String ROOT = "/U/0/";
    private static final Pattern DAY_DIRECTORY = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})/$");

    /** Reads a file of the watch and reports its content, or null when it could not be read. */
    public interface FileReader {
        void get(String path, BiConsumer<String, byte[]> callback);
    }

    private final GBDevice device;
    private final FileReader reader;
    private final Runnable onFinished;

    private final Map<Long, PolarHealthData.Minute> minutes = PolarHealthData.newMinutes();
    private final List<PolarHealthData.SleepStageValue> sleepStages = new ArrayList<>();
    private final List<PolarHealthData.Temperature> temperatures = new ArrayList<>();
    private final List<PolarHealthData.Value> hrv = new ArrayList<>();
    private final List<PolarHealthData.Value> respiration = new ArrayList<>();
    private final List<PolarHealthData.Value> sleepScores = new ArrayList<>();
    private final List<PolarWorkouts.Workout> workouts = new ArrayList<>();
    private int pending;

    public PolarHealthSync(final GBDevice device, final FileReader reader, final Runnable onFinished) {
        this.device = device;
        this.reader = reader;
        this.onFinished = onFinished;
    }

    public void start() {
        pending = 1;
        reader.get(ROOT, (path, payload) -> {
            if (payload != null) {
                try {
                    for (final PolarProto.PbPFtpEntry entry : PolarProto.PbPFtpDirectory.parseFrom(payload).getEntriesList()) {
                        final Matcher matcher = DAY_DIRECTORY.matcher(entry.getName());
                        if (matcher.matches()) {
                            readDay(LocalDate.of(Integer.parseInt(matcher.group(1)),
                                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))), ROOT + entry.getName());
                        }
                    }
                } catch (final InvalidProtocolBufferException e) {
                    LOG.warn("Unreadable directory listing", e);
                }
            }
            finishOne();
        });
    }

    private void readDay(final LocalDate day, final String directory) {
        // Files are answered in order, so the zone is known once the activity file is parsed
        final ZoneId[] zone = {ZoneId.systemDefault()};
        read(directory + "ACT/ASAMPL0.BPB", payload -> {
            final PolarProto.PbActivitySamples samples = PolarProto.PbActivitySamples.parseFrom(payload);
            zone[0] = PolarHealthData.zone(samples.getStartTime());
            PolarHealthData.addSteps(minutes, samples);
        });
        read(directory + "DSUM/DSUM.BPB", payload ->
                PolarHealthData.addDailySummary(minutes, PolarProto.PbDailySummary.parseFrom(payload), day, zone[0]));
        read(directory + "NR/AUTOS.BPB", payload ->
                PolarHealthData.addHeartRates(minutes, PolarProto.PbAutomaticSampleSessions.parseFrom(payload), zone[0]));
        read(directory + "NR/RESPRATE.BPB", payload ->
                respiration.addAll(PolarHealthData.respirationRates(PolarProto.PbRespirationRate.parseFrom(payload), zone[0])));
        read(directory + "NR/NR.BPB", payload -> {
            final PolarProto.PbNightlyRecoveryStatus status = PolarProto.PbNightlyRecoveryStatus.parseFrom(payload);
            hrv.addAll(PolarHealthData.nightlyRecovery(status, true));
            respiration.addAll(PolarHealthData.nightlyRecovery(status, false));
        });
        read(directory + "SLEEPSCO/SLEEPSCO.BPB", payload ->
                sleepScores.addAll(PolarHealthData.sleepScore(PolarProto.PbSleepScore.parseFrom(payload))));
        read(directory + "SLEEP/SLEEPRES.BPB", payload ->
                sleepStages.addAll(PolarHealthData.sleepStages(PolarProto.PbSleepAnalysisResult.parseFrom(payload))));
        readWorkouts(directory + "E/");
        read(directory + "SKINTEMP/TEMPCONT.BPB", payload ->
                temperatures.addAll(PolarHealthData.skinTemperatures(
                        PolarProto.PbSkinTemperatureContinuous.parseFrom(payload), day, zone[0])));
    }

    private static final Pattern TIME_DIRECTORY = Pattern.compile("^\\d{6}/$");

    /** The sessions of a day, each in a directory named after its start time. */
    private void readWorkouts(final String directory) {
        pending++;
        reader.get(directory, (path, payload) -> {
            if (payload != null) {
                try {
                    for (final PolarProto.PbPFtpEntry entry : PolarProto.PbPFtpDirectory.parseFrom(payload).getEntriesList()) {
                        if (TIME_DIRECTORY.matcher(entry.getName()).matches()) {
                            readWorkout(directory + entry.getName());
                        }
                    }
                } catch (final InvalidProtocolBufferException e) {
                    LOG.warn("Unreadable directory listing {}", directory, e);
                }
            }
            finishOne();
        });
    }

    private void readWorkout(final String directory) {
        final PolarProto.PbTrainingSession[] session = new PolarProto.PbTrainingSession[1];
        final PolarProto.PbExerciseBase[] base = new PolarProto.PbExerciseBase[1];
        read(directory + "TSESS.BPB", payload -> session[0] = PolarProto.PbTrainingSession.parseFrom(payload), null);
        read(directory + "00/BASE.BPB", payload -> base[0] = PolarProto.PbExerciseBase.parseFrom(payload), null);
        final byte[][] samples = new byte[1][];
        read(directory + "00/SAMPLES.GZB", payload -> {
            try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(payload))) {
                samples[0] = readAll(in);
            } catch (final java.io.IOException e) {
                LOG.warn("Unreadable samples of {}", directory, e);
            }
        }, () -> {
            if (session[0] != null) {
                final PolarWorkouts.Workout workout = PolarWorkouts.parse(session[0], base[0], samples[0]);
                if (workout != null) {
                    workouts.add(workout);
                }
            }
        });
    }

    private static byte[] readAll(final java.io.InputStream in) throws java.io.IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private interface Parser {
        void parse(byte[] payload) throws InvalidProtocolBufferException;
    }

    private void read(final String path, final Parser parser) {
        read(path, parser, null);
    }

    /** {@code afterwards} runs once the answer is handled, also when the file is missing. */
    private void read(final String path, final Parser parser, @Nullable final Runnable afterwards) {
        pending++;
        reader.get(path, (p, payload) -> {
            if (payload != null) {
                try {
                    parser.parse(payload);
                } catch (final InvalidProtocolBufferException | RuntimeException e) {
                    LOG.warn("Unreadable {}", path, e);
                }
            }
            if (afterwards != null) {
                afterwards.run();
            }
            finishOne();
        });
    }

    private void finishOne() {
        if (--pending == 0) {
            store();
            onFinished.run();
        }
    }

    private static void storeWorkout(final DaoSession session, final Long deviceId, final Long userId,
                                     final PolarWorkouts.Workout workout) {
        final BaseActivitySummaryDao dao = session.getBaseActivitySummaryDao();
        BaseActivitySummary summary = dao.queryBuilder().where(
                BaseActivitySummaryDao.Properties.DeviceId.eq(deviceId),
                BaseActivitySummaryDao.Properties.StartTime.eq(new Date(workout.startMillis))).unique();
        final boolean exists = summary != null;
        if (!exists) {
            summary = new BaseActivitySummary();
            summary.setDeviceId(deviceId);
            summary.setUserId(userId);
            summary.setStartTime(new Date(workout.startMillis));
        }
        summary.setEndTime(new Date(workout.endMillis));
        summary.setActivityKind(workout.kind.getCode());
        summary.setSummaryData(workout.summaryJson);
        summary.setRawSummaryData(workout.samples);
        dao.insertOrReplace(summary);
    }

    private void store() {
        try (DBHandler db = GBApplication.acquireDB()) {
            final DaoSession session = db.getDaoSession();
            final Long userId = DBHelper.getUser(session).getId();
            final Long deviceId = DBHelper.getDevice(device, session).getId();

            final List<PolarIgnite3ActivitySample> activity = new ArrayList<>(minutes.size());
            for (final Map.Entry<Long, PolarHealthData.Minute> entry : minutes.entrySet()) {
                final PolarIgnite3ActivitySample sample = new PolarIgnite3ActivitySample();
                sample.setTimestamp(entry.getKey().intValue());
                sample.setDeviceId(deviceId);
                sample.setUserId(userId);
                sample.setSteps(entry.getValue().steps);
                sample.setDistanceCm(entry.getValue().distanceCm);
                sample.setActiveCalories(entry.getValue().activeCalories);
                sample.setHeartRate(entry.getValue().heartRate());
                activity.add(sample);
            }
            new PolarIgnite3ActivitySampleProvider(device, session).addGBActivitySamples(activity);

            final List<GenericSleepStageSample> stages = new ArrayList<>(sleepStages.size());
            for (final PolarHealthData.SleepStageValue stage : sleepStages) {
                final GenericSleepStageSample sample = new GenericSleepStageSample();
                sample.setTimestamp(stage.startMillis);
                sample.setDeviceId(deviceId);
                sample.setUserId(userId);
                sample.setDuration(stage.durationSeconds);
                sample.setStage(stage.stage);
                stages.add(sample);
            }
            session.getGenericSleepStageSampleDao().insertOrReplaceInTx(stages);

            final List<GenericTemperatureSample> skin = new ArrayList<>(temperatures.size());
            for (final PolarHealthData.Temperature temperature : temperatures) {
                final GenericTemperatureSample sample = new GenericTemperatureSample();
                sample.setTimestamp(temperature.timestampMillis);
                sample.setDeviceId(deviceId);
                sample.setUserId(userId);
                sample.setTemperature(temperature.celsius);
                sample.setTemperatureType(TemperatureSample.TYPE_SKIN);
                sample.setTemperatureLocation(TemperatureSample.LOCATION_WRIST);
                skin.add(sample);
            }
            session.getGenericTemperatureSampleDao().insertOrReplaceInTx(skin);

            final List<GenericHrvValueSample> hrvSamples = new ArrayList<>();
            for (final PolarHealthData.Value value : hrv) {
                final GenericHrvValueSample sample = new GenericHrvValueSample();
                sample.setTimestamp(value.timestampMillis);
                sample.setDeviceId(deviceId);
                sample.setUserId(userId);
                sample.setValue(Math.round(value.value));
                hrvSamples.add(sample);
            }
            session.getGenericHrvValueSampleDao().insertOrReplaceInTx(hrvSamples);

            final List<GenericRespiratoryRateSample> breathing = new ArrayList<>();
            for (final PolarHealthData.Value value : respiration) {
                final GenericRespiratoryRateSample sample = new GenericRespiratoryRateSample();
                sample.setTimestamp(value.timestampMillis);
                sample.setDeviceId(deviceId);
                sample.setUserId(userId);
                sample.setRespiratoryRate(value.value);
                breathing.add(sample);
            }
            session.getGenericRespiratoryRateSampleDao().insertOrReplaceInTx(breathing);

            final List<GenericSleepScoreSample> scores = new ArrayList<>();
            for (final PolarHealthData.Value value : sleepScores) {
                final GenericSleepScoreSample sample = new GenericSleepScoreSample();
                sample.setTimestamp(value.timestampMillis);
                sample.setDeviceId(deviceId);
                sample.setUserId(userId);
                sample.setSleepScore((int) value.value);
                scores.add(sample);
            }
            session.getGenericSleepScoreSampleDao().insertOrReplaceInTx(scores);

            for (final PolarWorkouts.Workout workout : workouts) {
                storeWorkout(session, deviceId, userId, workout);
            }

            LOG.info("Stored {} workouts", workouts.size());
            LOG.info("Stored {} HRV, {} respiration, {} sleep score samples", hrvSamples.size(), breathing.size(), scores.size());
            LOG.info("Stored {} activity minutes, {} sleep stages, {} skin temperatures",
                    activity.size(), stages.size(), skin.size());
        } catch (final Exception e) {
            GB.toast("Polar sync failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG, GB.ERROR, e);
        }
    }
}
