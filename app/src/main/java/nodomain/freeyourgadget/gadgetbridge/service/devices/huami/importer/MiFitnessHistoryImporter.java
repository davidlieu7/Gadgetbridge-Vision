/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.importer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.HuamiHeartRateRestingSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuamiHeartRateRestingSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.HuamiPaiSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuamiPaiSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.HuamiStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuamiStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.MiBandActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.MiBandActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.HuamiSportsActivityType;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.notifications.GBProgressNotification;

/**
 * Imports the history of a Huami band from a Mi Fitness data export. Data already in the
 * database is never replaced.
 */
public class MiFitnessHistoryImporter {
    private static final Logger LOG = LoggerFactory.getLogger(MiFitnessHistoryImporter.class);

    private final Context context;
    private final GBDevice gbDevice;

    public MiFitnessHistoryImporter(final Context context, final GBDevice gbDevice) {
        // the import outlives the settings screen
        this.context = context.getApplicationContext();
        this.gbDevice = gbDevice;
    }

    public void importAsync(final List<Uri> uris) {
        final Handler handler = new Handler(context.getMainLooper());
        final GBProgressNotification notification = new GBProgressNotification(context, GB.NOTIFICATION_CHANNEL_ID_TRANSFER);
        notification.start(R.string.busy_task_processing_files, 0);

        new Thread(() -> {
            String message;
            int severity = GB.INFO;
            final List<File> files = new ArrayList<>();
            try {
                for (final Uri uri : uris) {
                    final File file = File.createTempFile("mifitness-import", ".bin", context.getCacheDir());
                    files.add(file);
                    FileUtils.copyURItoFile(context, uri, file);
                }
                final MiFitnessExport export = MiFitnessExport.read(files);
                message = context.getString(R.string.huami_import_history_done, persist(export));
            } catch (final Exception e) {
                LOG.error("Failed to import Mi Fitness history", e);
                message = context.getString(R.string.huami_import_history_failed, e.getMessage());
                severity = GB.ERROR;
            } finally {
                for (final File file : files) {
                    if (!file.delete()) {
                        LOG.warn("Failed to delete {}", file);
                    }
                }
            }

            final String finalMessage = message;
            final int finalSeverity = severity;
            handler.post(() -> {
                notification.finish();
                GB.toast(finalMessage, Toast.LENGTH_LONG, finalSeverity);
                gbDevice.sendDeviceUpdateIntent(context);
            });
        }, "mifitness-import").start();
    }

    private String persist(final MiFitnessExport export) throws Exception {
        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final Device device = DBHelper.getDevice(gbDevice, session);
            final User user = DBHelper.getUser(session);
            final long deviceId = device.getId();
            final long userId = user.getId();

            final int[] counts = new int[5];
            session.runInTx(() -> {
                counts[0] = persistActivitySamples(session, export, deviceId, userId);
                counts[1] = persistStress(session, export, deviceId, userId);
                counts[2] = persistRestingHeartRate(session, export, deviceId, userId);
                counts[3] = persistPai(session, export, deviceId, userId);
                counts[4] = persistWorkouts(session, export, deviceId, userId);
            });

            LOG.info("Imported {} minutes, {} stress, {} resting HR, {} PAI, {} workouts",
                    counts[0], counts[1], counts[2], counts[3], counts[4]);
            return context.getString(R.string.huami_import_history_counts, counts[0], counts[4]);
        }
    }

    /**
     * The timestamps already in a sample table, only the column is read to keep the memory low.
     */
    private static Set<Long> getExistingTimestamps(final DaoSession session,
                                                   final String table,
                                                   final Property timestamp,
                                                   final Property deviceIdProperty,
                                                   final long deviceId,
                                                   final long from,
                                                   final long to) {
        final String sql = String.format(
                "SELECT %2$s FROM %1$s WHERE %3$s = ? AND %2$s >= ? AND %2$s <= ?",
                table,
                timestamp.columnName,
                deviceIdProperty.columnName
        );
        final String[] args = {String.valueOf(deviceId), String.valueOf(from), String.valueOf(to)};
        final Set<Long> timestamps = new HashSet<>();
        try (Cursor c = session.getDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                timestamps.add(c.getLong(0));
            }
        }
        return timestamps;
    }

    private int persistActivitySamples(final DaoSession session, final MiFitnessExport export, final long deviceId, final long userId) {
        if (export.minutes.isEmpty()) {
            return 0;
        }
        final Set<Long> existing = getExistingTimestamps(
                session,
                MiBandActivitySampleDao.TABLENAME,
                MiBandActivitySampleDao.Properties.Timestamp,
                MiBandActivitySampleDao.Properties.DeviceId,
                deviceId,
                export.minutes.firstKey(),
                export.minutes.lastKey()
        );

        final List<MiBandActivitySample> samples = new ArrayList<>();
        for (final Map.Entry<Integer, MiFitnessExport.Minute> entry : export.minutes.entrySet()) {
            if (existing.contains((long) entry.getKey())) {
                continue;
            }
            final MiFitnessExport.Minute minute = entry.getValue();
            samples.add(new MiBandActivitySample(
                    entry.getKey(), deviceId, userId,
                    minute.getIntensity(), minute.steps, minute.kind, minute.heartRate
            ));
        }
        session.getMiBandActivitySampleDao().insertInTx(samples);
        return samples.size();
    }

    private int persistStress(final DaoSession session, final MiFitnessExport export, final long deviceId, final long userId) {
        if (export.stress.isEmpty()) {
            return 0;
        }
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        for (final MiFitnessExport.Stress stress : export.stress) {
            from = Math.min(from, stress.timestamp);
            to = Math.max(to, stress.timestamp);
        }
        final Set<Long> existing = getExistingTimestamps(
                session,
                HuamiStressSampleDao.TABLENAME,
                HuamiStressSampleDao.Properties.Timestamp,
                HuamiStressSampleDao.Properties.DeviceId,
                deviceId, from, to
        );

        final List<HuamiStressSample> samples = new ArrayList<>();
        for (final MiFitnessExport.Stress stress : export.stress) {
            if (existing.add(stress.timestamp)) {
                samples.add(new HuamiStressSample(stress.timestamp, deviceId, userId, StressSample.Type.AUTOMATIC.getNum(), stress.stress));
            }
        }
        session.getHuamiStressSampleDao().insertInTx(samples);
        return samples.size();
    }

    private int persistRestingHeartRate(final DaoSession session, final MiFitnessExport export, final long deviceId, final long userId) {
        if (export.restingHeartRates.isEmpty()) {
            return 0;
        }
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        for (final MiFitnessExport.RestingHeartRate hr : export.restingHeartRates) {
            from = Math.min(from, hr.timestamp);
            to = Math.max(to, hr.timestamp);
        }
        final Set<Long> existing = getExistingTimestamps(
                session,
                HuamiHeartRateRestingSampleDao.TABLENAME,
                HuamiHeartRateRestingSampleDao.Properties.Timestamp,
                HuamiHeartRateRestingSampleDao.Properties.DeviceId,
                deviceId, from, to
        );

        final List<HuamiHeartRateRestingSample> samples = new ArrayList<>();
        for (final MiFitnessExport.RestingHeartRate hr : export.restingHeartRates) {
            if (existing.add(hr.timestamp)) {
                samples.add(new HuamiHeartRateRestingSample(hr.timestamp, deviceId, userId, hr.utcOffset, hr.heartRate));
            }
        }
        session.getHuamiHeartRateRestingSampleDao().insertInTx(samples);
        return samples.size();
    }

    private int persistPai(final DaoSession session, final MiFitnessExport export, final long deviceId, final long userId) {
        if (export.pai.isEmpty()) {
            return 0;
        }
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        for (final MiFitnessExport.Pai pai : export.pai) {
            from = Math.min(from, pai.timestamp);
            to = Math.max(to, pai.timestamp);
        }
        final Set<Long> existing = getExistingTimestamps(
                session,
                HuamiPaiSampleDao.TABLENAME,
                HuamiPaiSampleDao.Properties.Timestamp,
                HuamiPaiSampleDao.Properties.DeviceId,
                deviceId, from, to
        );

        final List<HuamiPaiSample> samples = new ArrayList<>();
        for (final MiFitnessExport.Pai pai : export.pai) {
            if (existing.add(pai.timestamp)) {
                // the export has no time in each zone
                samples.add(new HuamiPaiSample(pai.timestamp, deviceId, userId, pai.utcOffset,
                        pai.low, pai.moderate, pai.high, 0, 0, 0, pai.today, pai.total));
            }
        }
        session.getHuamiPaiSampleDao().insertInTx(samples);
        return samples.size();
    }

    private int persistWorkouts(final DaoSession session, final MiFitnessExport export, final long deviceId, final long userId) {
        final BaseActivitySummaryDao dao = session.getBaseActivitySummaryDao();
        int count = 0;
        for (final MiFitnessExport.Workout workout : export.workouts) {
            final HuamiSportsActivityType type = toHuamiType(workout.sport);
            if (type == null) {
                LOG.warn("Skipping workout of unknown type {} at {}", workout.sport, new Date(workout.startTime));
                continue;
            }
            final boolean exists = dao.queryBuilder()
                    .where(BaseActivitySummaryDao.Properties.DeviceId.eq(deviceId),
                            BaseActivitySummaryDao.Properties.StartTime.eq(new Date(workout.startTime)))
                    .count() > 0;
            if (exists) {
                continue;
            }

            final BaseActivitySummary summary = new BaseActivitySummary();
            summary.setName(context.getString(R.string.huami_import_history_workout_name));
            summary.setStartTime(new Date(workout.startTime));
            summary.setEndTime(new Date(workout.endTime));
            summary.setActivityKind(type.toActivityKind().getCode());
            summary.setBaseLongitude(0);
            summary.setBaseLatitude(0);
            summary.setBaseAltitude(0);
            summary.setDeviceId(deviceId);
            summary.setUserId(userId);
            summary.setRawSummaryData(workout.toRawSummary(type.getCode()));
            dao.insert(summary);
            count++;
        }
        return count;
    }

    private static HuamiSportsActivityType toHuamiType(final String sport) {
        switch (sport) {
            case "outdoor_running":
                return HuamiSportsActivityType.OutdoorRunning;
            case "indoor_running":
                return HuamiSportsActivityType.Treadmill;
            case "outdoor_walking":
            case "indoor_walking":
                return HuamiSportsActivityType.Walking;
            case "outdoor_riding":
                return HuamiSportsActivityType.Cycling;
            case "indoor_riding":
                return HuamiSportsActivityType.IndoorCycling;
            case "free_training":
                return HuamiSportsActivityType.Exercise;
            case "pool_swimming":
                return HuamiSportsActivityType.Swimming;
            case "open_water_swimming":
                return HuamiSportsActivityType.OpenWaterSwimming;
            case "outdoor_hiking":
                return HuamiSportsActivityType.OutdoorHiking;
            default:
                return null;
        }
    }
}
