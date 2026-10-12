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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.entities.CasioGBX100ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.AbstractCasioConvoyFetchOperation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.Casio2C2DSupport;


public class FetchStepCountDataOperation extends AbstractCasioConvoyFetchOperation<CasioGGB100DeviceSupport> {
    private static final Logger LOG = LoggerFactory.getLogger(FetchStepCountDataOperation.class);
    private static final int LIFE_LOG_LENGTH = 160;
    private static final int NO_DATA16 = 0xfffe;
    private static final long NO_DATA32 = 0xfffffffeL;

    public FetchStepCountDataOperation(CasioGGB100DeviceSupport support) {
        super(support, Casio2C2DSupport.FEATURE_LIFE_LOG, LIFE_LOG_LENGTH, R.string.busy_task_fetch_steps);
    }

    @Override
    protected void parsePayload(byte[] data) {
        parseLifeLog(getSupport(), data);
    }

    @Override
    protected void onFetchFinished() {
        getSupport().onStepCountFetchFinished();
    }

    public static void parseLifeLog(Casio2C2DSupport support, byte[] data) {
        if (data.length < LIFE_LOG_LENGTH) {
            LOG.warn("Life log too short: {} bytes", data.length);
            return;
        }

        final ArrayList<CasioGBX100ActivitySample> samples = new ArrayList<>();

        final Calendar now = Calendar.getInstance();
        final int tsNow = (int) (now.getTimeInMillis() / 1000);

        final Calendar dayStart = (Calendar) now.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);
        final int tsDayStart = (int) (dayStart.getTimeInMillis() / 1000);

        final Calendar hourStart = (Calendar) now.clone();
        hourStart.set(Calendar.MINUTE, 0);
        hourStart.set(Calendar.SECOND, 0);
        hourStart.set(Calendar.MILLISECOND, 0);

        // Hourly bins, most recent first: bin 0 = last completed hour
        int stepsTodayFromBins = 0;
        int kcalTodayFromBins = 0;
        for (int i = 0; i < 24; i++) {
            final int steps = (data[i * 2] & 0xff) | ((data[i * 2 + 1] & 0xff) << 8);
            final int kcal = (data[48 + i * 2] & 0xff) | ((data[48 + i * 2 + 1] & 0xff) << 8);
            if (steps == NO_DATA16 && kcal == NO_DATA16) continue;

            final Calendar h = (Calendar) hourStart.clone();
            h.add(Calendar.HOUR, -(i + 1));
            final int ts = (int) (h.getTimeInMillis() / 1000);

            final CasioGBX100ActivitySample sample = new CasioGBX100ActivitySample();
            sample.setSteps(steps == NO_DATA16 ? 0 : steps);
            sample.setCalories(kcal == NO_DATA16 ? 0 : kcal);
            sample.setTimestamp(ts);
            sample.setRawKind(ActivityKind.ACTIVITY.getCode());
            samples.add(sample);

            if (ts >= tsDayStart) {
                stepsTodayFromBins += sample.getSteps();
                kcalTodayFromBins += sample.getCalories();
            }
        }

        final long totalSteps = (data[96] & 0xffL) | ((data[97] & 0xffL) << 8)
                | ((data[98] & 0xffL) << 16) | ((data[99] & 0xffL) << 24);
        final long totalKcal = (data[100] & 0xffL) | ((data[101] & 0xffL) << 8)
                | ((data[102] & 0xffL) << 16) | ((data[103] & 0xffL) << 24);

        // Daily totals of previous days, most recent first (?): slot 0 = yesterday
        for (int j = 0; j < 7; j++) {
            final int base = 104 + j * 8;
            final long steps = (data[base] & 0xffL) | ((data[base + 1] & 0xffL) << 8)
                    | ((data[base + 2] & 0xffL) << 16) | ((data[base + 3] & 0xffL) << 24);
            final long kcal = (data[base + 4] & 0xffL) | ((data[base + 5] & 0xffL) << 8)
                    | ((data[base + 6] & 0xffL) << 16) | ((data[base + 7] & 0xffL) << 24);
            if (steps == NO_DATA32) continue;

            final Calendar d = (Calendar) dayStart.clone();
            d.add(Calendar.DAY_OF_YEAR, -(j + 1));

            final CasioGBX100ActivitySample sample = new CasioGBX100ActivitySample();
            sample.setSteps((int) steps);
            sample.setCalories((int) kcal);
            sample.setTimestamp((int) (d.getTimeInMillis() / 1000));
            sample.setRawKind(ActivityKind.ACTIVITY.getCode());
            samples.add(sample);
        }

        LOG.debug("Life log: today={} steps / {} kcal, {} bins/days parsed",
                totalSteps, totalKcal, samples.size());

        // Today bin not cleared by the ACK, we have to check if data exist already
        final CasioGBX100ActivitySample sum = support.getSumWithinRange(tsDayStart, tsNow);
        final int remainSteps = (int) totalSteps - sum.getSteps() - stepsTodayFromBins;
        final int remainKcal = (int) totalKcal - sum.getCalories() - kcalTodayFromBins;
        if (remainSteps > 0 && remainKcal > 0) {
            final CasioGBX100ActivitySample sample = new CasioGBX100ActivitySample();
            sample.setSteps(remainSteps);
            sample.setCalories(remainKcal);
            sample.setTimestamp(tsNow);
            sample.setRawKind(ActivityKind.ACTIVITY.getCode());
            samples.add(0, sample);
        }

        support.stepCountDataFetched((int) totalSteps, (int) totalKcal, samples);
    }
}
