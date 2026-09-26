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
package nodomain.freeyourgadget.gadgetbridge.database.schema;

import android.database.sqlite.SQLiteDatabase;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.database.DBUpdateScript;
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUploadDao;

public class GadgetbridgeUpdate_159 implements DBUpdateScript {
    /** Integer media id schema 158 and earlier stored the header photo under, carried into PHOTO_MEDIA_REF. */
    private static final String LEGACY_PHOTO_MEDIA_ID = "PHOTO_MEDIA_ID";

    @Override
    public void upgradeSchema(final SQLiteDatabase db) {
        final String table = WorkoutUploadDao.TABLENAME;
        final String photoMediaRef = WorkoutUploadDao.Properties.PhotoMediaRef.columnName;
        if (!DBHelper.existsColumn(table, photoMediaRef, db)) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN \"" + photoMediaRef + "\" TEXT;");
            if (DBHelper.existsColumn(table, LEGACY_PHOTO_MEDIA_ID, db)) {
                db.execSQL("UPDATE " + table + " SET \"" + photoMediaRef + "\" = CAST(\""
                        + LEGACY_PHOTO_MEDIA_ID + "\" AS TEXT) WHERE \"" + LEGACY_PHOTO_MEDIA_ID
                        + "\" IS NOT NULL;");
            }
        }
        final String remoteAccount = WorkoutUploadDao.Properties.RemoteAccount.columnName;
        if (!DBHelper.existsColumn(table, remoteAccount, db)) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN \"" + remoteAccount + "\" TEXT;");
            // Schema 158 and earlier stored a hash of the exported file, which never matches
            // WorkoutUploadStore.trackHashOf; a cleared one is taken to match the current track.
            final String payloadHash = WorkoutUploadDao.Properties.PayloadHash.columnName;
            db.execSQL("UPDATE " + table + " SET \"" + payloadHash + "\" = NULL;");
        }
    }

    @Override
    public void downgradeSchema(final SQLiteDatabase db) {
    }
}
