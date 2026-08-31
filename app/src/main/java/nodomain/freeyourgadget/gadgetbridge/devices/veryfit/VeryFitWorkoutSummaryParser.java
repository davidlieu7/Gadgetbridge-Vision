/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.devices.veryfit;

import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.ACTIVE_SECONDS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.CALORIES_BURNT;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.DISTANCE_METERS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.HR_AVG;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.HR_MAX;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.HR_MIN;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.HR_ZONE_FAT_BURN;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.HR_ZONE_WARM_UP;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.PACE_AVG_SECONDS_KM;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.RECOVERY_TIME;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.SPEED_AVG;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.STEPS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_BPM;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_KCAL;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_KMPH;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_METERS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_SECONDS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_SECONDS_PER_KM;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_STEPS;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryParser;

/**
 * The fixed part of a workout record, which is the same shape whatever the sport was: the totals
 * the watch itself shows, then the seconds spent in each heart-rate zone. It dates itself twice,
 * in broken-down fields and in seconds, and both run on the watch's clock rather than on ours.
 */
public class VeryFitWorkoutSummaryParser implements ActivitySummaryParser {

    @Override
    public BaseActivitySummary parseBinaryData(final BaseActivitySummary summary, final boolean forDetails) {
        final byte[] raw = summary.getRawSummaryData();
        if (raw == null || raw.length < VeryFitConstants.WORKOUT_SUMMARY_LEN) {
            return summary;
        }

        final ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        summary.setActivityKind(activityKind(buf.get() & 0xff).getCode());

        // The counted time is when the workout ended; the field one behind it is when the rest
        // the watch advises after it is over.
        final Date start = calendar(buf, 1, TimeZone.getDefault()).getTime();
        final long ended = buf.getInt(102) & 0xffffffffL;
        final long started = watchClock(buf, 1);
        final long elapsed = ended > started ? ended - started : buf.getInt(21);
        summary.setStartTime(start);
        summary.setEndTime(new Date(start.getTime() + elapsed * 1000L));

        final ActivitySummaryData data = new ActivitySummaryData();
        data.add(STEPS, buf.getInt(17), UNIT_STEPS);
        data.add(ACTIVE_SECONDS, buf.getInt(21), UNIT_SECONDS);
        data.add(CALORIES_BURNT, buf.getInt(25), UNIT_KCAL);
        data.add(DISTANCE_METERS, buf.getInt(29), UNIT_METERS);
        data.add(HR_AVG, buf.get(33) & 0xff, UNIT_BPM);
        data.add(HR_MAX, buf.get(34) & 0xff, UNIT_BPM);
        data.add(HR_MIN, buf.get(35) & 0xff, UNIT_BPM);
        data.add(SPEED_AVG, buf.getInt(40) / 100f, UNIT_KMPH);
        data.add(PACE_AVG_SECONDS_KM, buf.getShort(44) & 0xffff, UNIT_SECONDS_PER_KM);
        data.add(HR_ZONE_WARM_UP, buf.getInt(52), UNIT_SECONDS);
        data.add(HR_ZONE_FAT_BURN, buf.getInt(56), UNIT_SECONDS);
        data.add(RECOVERY_TIME, watchClock(buf, 75) - ended, UNIT_SECONDS);

        summary.setSummaryData(data.toString());
        return summary;
    }

    /**
     * Only outdoor walking has ever been seen on the wire, and it is the code the vendor app was
     * showing that names it.
     */
    private static ActivityKind activityKind(final int sport) {
        if (sport == VeryFitConstants.WORKOUT_OUTDOOR_WALKING) {
            return ActivityKind.OUTDOOR_WALKING;
        }
        return ActivityKind.UNKNOWN;
    }

    /** Fields read on the watch's own clock, which is the one its counted times are kept in. */
    private static long watchClock(final ByteBuffer buf, final int offset) {
        return calendar(buf, offset, TimeZone.getTimeZone("UTC")).getTimeInMillis() / 1000L;
    }

    private static Calendar calendar(final ByteBuffer buf, final int offset, final TimeZone zone) {
        final Calendar calendar = new GregorianCalendar(zone);
        calendar.clear();
        calendar.set(buf.getShort(offset) & 0xffff, (buf.get(offset + 2) & 0xff) - 1,
                buf.get(offset + 3) & 0xff, buf.get(offset + 4) & 0xff,
                buf.get(offset + 5) & 0xff, buf.get(offset + 6) & 0xff);
        return calendar;
    }
}
