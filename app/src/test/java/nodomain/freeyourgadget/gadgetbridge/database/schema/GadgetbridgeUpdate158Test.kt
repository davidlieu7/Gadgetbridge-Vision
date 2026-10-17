package nodomain.freeyourgadget.gadgetbridge.database.schema

import android.database.sqlite.SQLiteDatabase
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.GenericEstimatedSleepStageSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRawSleepSampleDao
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.util.sleep.RawSleepNights
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class GadgetbridgeUpdate158Test {

    private lateinit var db: SQLiteDatabase

    @Before
    fun createSchema157Tables() {
        db = SQLiteDatabase.create(null)
        // The Garmin tables as schema 157 created them
        db.execSQL(
            "CREATE TABLE \"GARMIN_RAW_SLEEP_SAMPLE\" (\"TIMESTAMP\" INTEGER NOT NULL, \"DEVICE_ID\" INTEGER NOT NULL, " +
                "\"USER_ID\" INTEGER NOT NULL, \"KIND\" INTEGER NOT NULL, \"HEART_RATE\" REAL, \"MOVEMENT\" REAL, " +
                "PRIMARY KEY (\"TIMESTAMP\", \"DEVICE_ID\") ON CONFLICT REPLACE) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE \"GARMIN_ESTIMATED_SLEEP_STAGE_SAMPLE\" (\"TIMESTAMP\" INTEGER NOT NULL, \"DEVICE_ID\" INTEGER NOT NULL, " +
                "\"USER_ID\" INTEGER NOT NULL, \"STAGE\" INTEGER NOT NULL, " +
                "PRIMARY KEY (\"TIMESTAMP\", \"DEVICE_ID\") ON CONFLICT REPLACE) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE \"GARMIN_EVENT_SAMPLE\" (\"TIMESTAMP\" INTEGER NOT NULL, \"DEVICE_ID\" INTEGER NOT NULL, " +
                "\"USER_ID\" INTEGER NOT NULL, \"EVENT\" INTEGER NOT NULL, \"EVENT_TYPE\" INTEGER, \"DATA\" INTEGER, " +
                "PRIMARY KEY (\"TIMESTAMP\", \"DEVICE_ID\", \"EVENT\") ON CONFLICT REPLACE) WITHOUT ROWID"
        )
        GenericRawSleepSampleDao.createTable(db, true)
        GenericEstimatedSleepStageSampleDao.createTable(db, true)

        db.execSQL("INSERT INTO GARMIN_RAW_SLEEP_SAMPLE VALUES (60000, 1, 1, 0, 52.5, 1.5)")
        db.execSQL("INSERT INTO GARMIN_RAW_SLEEP_SAMPLE VALUES (120000, 1, 1, 2, NULL, NULL)")
        // Estimated sleep start, then a sleep start the watch detected itself
        db.execSQL("INSERT INTO GARMIN_EVENT_SAMPLE VALUES (100000, 1, 1, 74, 0, -2)")
        db.execSQL("INSERT INTO GARMIN_EVENT_SAMPLE VALUES (900000, 1, 1, 74, 0, NULL)")
        for ((timestamp, level) in listOf(160000L to 2, 220000L to 3, 280000L to 4, 340000L to 1)) {
            db.execSQL("INSERT INTO GARMIN_ESTIMATED_SLEEP_STAGE_SAMPLE VALUES ($timestamp, 1, 1, $level)")
        }
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun movesRawMinutes() {
        GadgetbridgeUpdate_158().upgradeSchema(db)

        assertEquals(
            listOf("60000 0 52.5 1.5", "120000 2 null null"),
            rows("SELECT TIMESTAMP, KIND, HEART_RATE, MOVEMENT FROM GENERIC_RAW_SLEEP_SAMPLE ORDER BY TIMESTAMP")
        )
        assertFalse(DBHelper.existsColumn("GARMIN_RAW_SLEEP_SAMPLE", "TIMESTAMP", db))
    }

    @Test
    fun movesStagesAsActivityKinds_withTheOnsetOfEachEstimatedSession() {
        GadgetbridgeUpdate_158().upgradeSchema(db)

        assertEquals(
            listOf(
                "100000 ${RawSleepNights.ONSET}",
                "160000 ${ActivityKind.LIGHT_SLEEP.code}",
                "220000 ${ActivityKind.DEEP_SLEEP.code}",
                "280000 ${ActivityKind.REM_SLEEP.code}",
                "340000 ${ActivityKind.AWAKE_SLEEP.code}",
            ),
            rows("SELECT TIMESTAMP, STAGE FROM GENERIC_ESTIMATED_SLEEP_STAGE_SAMPLE ORDER BY TIMESTAMP")
        )
        assertFalse(DBHelper.existsColumn("GARMIN_ESTIMATED_SLEEP_STAGE_SAMPLE", "TIMESTAMP", db))
        assertEquals(2, rows("SELECT TIMESTAMP FROM GARMIN_EVENT_SAMPLE").size)
    }

    @Test
    fun runningTwice_isHarmless() {
        GadgetbridgeUpdate_158().upgradeSchema(db)
        GadgetbridgeUpdate_158().upgradeSchema(db)

        assertEquals(2, rows("SELECT TIMESTAMP FROM GENERIC_RAW_SLEEP_SAMPLE").size)
        assertEquals(5, rows("SELECT TIMESTAMP FROM GENERIC_ESTIMATED_SLEEP_STAGE_SAMPLE").size)
    }

    private fun rows(sql: String): List<String> = db.rawQuery(sql, null).use { c ->
        generateSequence { if (c.moveToNext()) c else null }
            .map { row -> (0 until row.columnCount).joinToString(" ") { if (row.isNull(it)) "null" else row.getString(it) } }
            .toList()
    }
}
