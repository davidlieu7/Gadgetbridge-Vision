/*  Copyright (C) 2026 Davide Gessa

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.casio.ggb100;

import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.AbstractCasioConvoyFetchOperation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.Casio2C2DSupport;
import nodomain.freeyourgadget.gadgetbridge.util.BcdUtil;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.ACTIVE_SECONDS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.ALTITUDE_AVG;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.ALTITUDE_MAX;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.ALTITUDE_MIN;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.ELEVATION_GAIN;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_METERS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_SECONDS;


public class FetchMissionLogDataOperation extends AbstractCasioConvoyFetchOperation<CasioGGB100DeviceSupport> {
    private static final Logger LOG = LoggerFactory.getLogger(FetchMissionLogDataOperation.class);
    private static final int MISSION_LOG_LENGTH = 294;
    private static final int SERIES_SAMPLES = 60;
    private static final int SERIES_INTERVAL_SECONDS = 120;
    private static final int RECORD_COUNT = 14;
    private static final int RECORD_STRIDE = 12;
    private static final int RECORDS_OFFSET = 126;
    private static final short NO_DATA_ALTITUDE = 0x7fff;

    public FetchMissionLogDataOperation(CasioGGB100DeviceSupport support) {
        super(support, Casio2C2DSupport.FEATURE_MISSION_LOG_DATA, MISSION_LOG_LENGTH,
                R.string.busy_task_fetch_activity_data);
    }

    @Override
    protected void parsePayload(byte[] data) {
        parseMissionLog(getSupport(), data);
    }

    public static void parseMissionLog(Casio2C2DSupport support, byte[] data) {
        if (data.length < MISSION_LOG_LENGTH) {
            LOG.warn("Mission log too short: {} bytes", data.length);
            return;
        }

        parseAltitudeRecords(data);
        parseAndSaveSeries(support, data);
    }

    private static void parseAltitudeRecords(byte[] data) {
        for (int i = 0; i < RECORD_COUNT; i++) {
            final int base = RECORDS_OFFSET + i * RECORD_STRIDE;
            if (data[base + 2] == (byte) 0xff) continue; // empty slot

            final short alt = (short) ((data[base] & 0xff) | (data[base + 1] << 8));
            final Date ts = parseBcdUtc(data, base + 2, true);
            LOG.info("Altitude record {}: {} m at {}", i, alt, ts);

            // TODO: we need to archive these altitue records somewhere
        }
    }

    private static void parseAndSaveSeries(Casio2C2DSupport support, byte[] data) {
        if (data[0] == (byte) 0xff) {
            LOG.debug("No altitude series stored");
            return;
        }

        final Date startTime = parseBcdUtc(data, 0, false);
        final int count = Math.min(data[5] & 0xff, SERIES_SAMPLES);

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        int valid = 0;
        int elevationGain = 0;
        int prev = Integer.MIN_VALUE;

        for (int i = 0; i < count; i++) {
            final short alt = (short) ((data[6 + i * 2] & 0xff) | (data[6 + i * 2 + 1] << 8));
            if (alt == NO_DATA_ALTITUDE) continue;
            min = Math.min(min, alt);
            max = Math.max(max, alt);
            sum += alt;
            valid++;
            if (prev != Integer.MIN_VALUE && alt > prev) {
                elevationGain += alt - prev;
            }
            prev = alt;
        }

        if (valid == 0) {
            LOG.debug("Altitude series header present but no valid samples");
            return;
        }

        LOG.info("Altitude series from {} UTC: {} samples, min={} max={} gain={}",
                startTime, valid, min, max, elevationGain);

        final ActivitySummaryData sd = new ActivitySummaryData();
        sd.add(ACTIVE_SECONDS, valid * SERIES_INTERVAL_SECONDS, UNIT_SECONDS);
        sd.add(ALTITUDE_MIN, min, UNIT_METERS);
        sd.add(ALTITUDE_MAX, max, UNIT_METERS);
        sd.add(ALTITUDE_AVG, sum / (double) valid, UNIT_METERS);
        if (elevationGain > 0) {
            sd.add(ELEVATION_GAIN, elevationGain, UNIT_METERS);
        }

        final BaseActivitySummary summary = new BaseActivitySummary();
        summary.setName("Mission");
        summary.setStartTime(startTime);
        summary.setEndTime(new Date(startTime.getTime() + valid * SERIES_INTERVAL_SECONDS * 1000L));
        summary.setActivityKind(ActivityKind.ACTIVITY.getCode());
        summary.setSummaryData(sd.toString());

        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            final DaoSession session = dbHandler.getDaoSession();
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);
            summary.setDevice(device);
            summary.setUser(user);

            // Cleared by the ack, but if we refetch we skip if a summary with the same start exists
            final List<BaseActivitySummary> existing = session.getBaseActivitySummaryDao()
                    .queryBuilder()
                    .where(
                            BaseActivitySummaryDao.Properties.DeviceId.eq(device.getId()),
                            BaseActivitySummaryDao.Properties.StartTime.eq(summary.getStartTime()))
                    .list();
            if (!existing.isEmpty()) {
                LOG.info("Mission at {} already in database - skipping", startTime);
                return;
            }

            session.getBaseActivitySummaryDao().insertOrReplace(summary);
            LOG.info("Saved mission starting {} to database", startTime);
        } catch (Exception e) {
            GB.toast(support.getContext(), "Error saving mission log: " + e.getLocalizedMessage(),
                    Toast.LENGTH_LONG, GB.ERROR, e);
        }
    }

    /** BCD timestamp, yy mm dd hh mm [ss], UTC. */
    private static Date parseBcdUtc(byte[] p, int offset, boolean withSeconds) {
        final int year = 2000 + BcdUtil.fromBcd8(p[offset]);
        final int month = BcdUtil.fromBcd8(p[offset + 1]) - 1; // 0-based
        final int day = BcdUtil.fromBcd8(p[offset + 2]);
        final int hour = BcdUtil.fromBcd8(p[offset + 3]);
        final int min = BcdUtil.fromBcd8(p[offset + 4]);
        final int sec = withSeconds ? BcdUtil.fromBcd8(p[offset + 5]) : 0;
        final Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(year, month, day, hour, min, sec);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }
}
