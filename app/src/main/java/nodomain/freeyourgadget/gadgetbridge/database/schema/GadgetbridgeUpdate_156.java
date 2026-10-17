/*  Copyright (C) 2026 Gadgetbridge Contributors

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
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooActivitySampleDao;

/**
 * Adds the sleep apnea/hypoxia columns to the Veepoo activity sample table for databases that
 * predate them. New tables are created with all columns by {@code DaoMaster.createAllTables};
 * only existing tables from earlier schema versions need the ALTER statements here.
 */
public class GadgetbridgeUpdate_156 implements DBUpdateScript {
    @Override
    public void upgradeSchema(final SQLiteDatabase db) {
        final String table = VeepooActivitySampleDao.TABLENAME;
        DBHelper.addColumnIfNotExist(table, VeepooActivitySampleDao.Properties.ApneaCount.columnName, "INTEGER", db);
        DBHelper.addColumnIfNotExist(table, VeepooActivitySampleDao.Properties.HypoxiaMinutes.columnName, "INTEGER", db);
        DBHelper.addColumnIfNotExist(table, VeepooActivitySampleDao.Properties.HypoxiaTimeSum.columnName, "INTEGER", db);
        DBHelper.addColumnIfNotExist(table, VeepooActivitySampleDao.Properties.CardiacLoadMinutes.columnName, "INTEGER", db);
    }

    @Override
    public void downgradeSchema(final SQLiteDatabase db) {
    }
}
