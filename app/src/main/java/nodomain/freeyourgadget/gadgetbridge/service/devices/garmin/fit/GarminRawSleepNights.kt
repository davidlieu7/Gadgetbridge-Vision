/*  Copyright (C) 2026 Manuel Marcatili

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.garmin.GarminEventSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GarminEventSampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.Event
import nodomain.freeyourgadget.gadgetbridge.util.sleep.EstimatedSleepSession
import nodomain.freeyourgadget.gadgetbridge.util.sleep.RawSleepNights

/**
 * Sleep of watches that do not stage it (#4048), estimated per sleep episode.
 *
 * Such a watch writes its raw sleep stream around the clock, cut into a new file at midnight and at
 * every sync. Besides the stage rows, every estimated session gets a START/STOP pair of sleep events
 * marked [RawSleepStream.ESTIMATED_SLEEP_DATA], like the sessions a staging watch reports.
 */
object GarminRawSleepNights : RawSleepNights() {

    override fun onStretchReplaced(device: GBDevice, session: DaoSession, fromMillis: Long, toMillis: Long) {
        GarminEventSampleProvider(device, session).deleteFakedSleepEvents(fromMillis, toMillis)
    }

    override fun onSessionsRemoved(device: GBDevice, session: DaoSession, fromMillis: Long, toMillis: Long) {
        val dbDevice = DBHelper.findDevice(device, session) ?: return
        session.garminEventSampleDao.queryBuilder()
            .where(
                GarminEventSampleDao.Properties.DeviceId.eq(dbDevice.id),
                GarminEventSampleDao.Properties.Event.eq(Event.DETECT_SLEEP.num),
                GarminEventSampleDao.Properties.Data.eq(RawSleepStream.ESTIMATED_SLEEP_DATA),
                GarminEventSampleDao.Properties.Timestamp.between(fromMillis, toMillis),
            )
            .buildDelete()
            .executeDeleteWithoutDetachingEntities()
    }

    override fun onSessionStored(device: GBDevice, session: DaoSession, context: Context?, estimate: EstimatedSleepSession) {
        GarminEventSampleProvider(device, session).persistSamples(RawSleepStream.sleepEvents(estimate), context)
    }
}
