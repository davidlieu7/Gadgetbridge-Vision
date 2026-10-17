package nodomain.freeyourgadget.gadgetbridge.database.schema;

import android.database.sqlite.SQLiteDatabase;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.database.DBUpdateScript;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericEstimatedSleepStageSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRawSleepSampleDao;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.util.sleep.RawSleepNights;

/**
 * Moves the raw sleep minutes and estimated stages of Garmin watches into the generic tables shared
 * by every device whose sleep is estimated on the phone, then drops the Garmin tables.
 */
public class GadgetbridgeUpdate_158 implements DBUpdateScript {
    private static final String GARMIN_RAW = "GARMIN_RAW_SLEEP_SAMPLE";
    private static final String GARMIN_ESTIMATED = "GARMIN_ESTIMATED_SLEEP_STAGE_SAMPLE";
    private static final String GARMIN_EVENT = "GARMIN_EVENT_SAMPLE";

    /** Garmin FIT values: event DETECT_SLEEP, event type START, and the data marking an estimated sleep. */
    private static final int DETECT_SLEEP = 74;
    private static final int START = 0;
    private static final long ESTIMATED_SLEEP_DATA = -2L;

    @Override
    public void upgradeSchema(final SQLiteDatabase db) {
        if (DBHelper.existsColumn(GARMIN_RAW, "TIMESTAMP", db)) {
            db.execSQL("INSERT OR IGNORE INTO " + GenericRawSleepSampleDao.TABLENAME
                    + " (TIMESTAMP, DEVICE_ID, USER_ID, KIND, HEART_RATE, MOVEMENT)"
                    + " SELECT TIMESTAMP, DEVICE_ID, USER_ID, KIND, HEART_RATE, MOVEMENT FROM " + GARMIN_RAW);
            db.execSQL("DROP TABLE " + GARMIN_RAW);
        }
        if (DBHelper.existsColumn(GARMIN_ESTIMATED, "TIMESTAMP", db)) {
            // Garmin sleep levels 1..4 (awake, light, deep, REM) become activity kinds
            db.execSQL("INSERT OR IGNORE INTO " + GenericEstimatedSleepStageSampleDao.TABLENAME
                    + " (TIMESTAMP, DEVICE_ID, USER_ID, STAGE)"
                    + " SELECT TIMESTAMP, DEVICE_ID, USER_ID, CASE STAGE"
                    + " WHEN 1 THEN " + ActivityKind.AWAKE_SLEEP.getCode()
                    + " WHEN 2 THEN " + ActivityKind.LIGHT_SLEEP.getCode()
                    + " WHEN 3 THEN " + ActivityKind.DEEP_SLEEP.getCode()
                    + " WHEN 4 THEN " + ActivityKind.REM_SLEEP.getCode()
                    + " ELSE " + ActivityKind.UNKNOWN.getCode() + " END FROM " + GARMIN_ESTIMATED);
            // Each estimated session starts with an onset row, taken from its estimated sleep start event
            db.execSQL("INSERT OR IGNORE INTO " + GenericEstimatedSleepStageSampleDao.TABLENAME
                    + " (TIMESTAMP, DEVICE_ID, USER_ID, STAGE)"
                    + " SELECT TIMESTAMP, DEVICE_ID, USER_ID, " + RawSleepNights.ONSET + " FROM " + GARMIN_EVENT
                    + " WHERE EVENT = " + DETECT_SLEEP + " AND EVENT_TYPE = " + START + " AND DATA = " + ESTIMATED_SLEEP_DATA);
            db.execSQL("DROP TABLE " + GARMIN_ESTIMATED);
        }
    }

    @Override
    public void downgradeSchema(final SQLiteDatabase db) {
    }
}
