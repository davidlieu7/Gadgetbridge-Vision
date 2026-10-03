/*  Copyright (C) 2026 José Rebelo, Arjan Schrijver

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
package nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.components

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsScope
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListSetting
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability

/**
 * Adds a heart rate interval [ListSetting] with key [DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL].
 * Pass the intervals the device supports.
 */
fun DeviceSettingsScope.heartrateMeasurementInterval(supported: MutableList<HeartRateCapability.MeasurementInterval?>) {
    val intervals = if (supported.isEmpty()) listOf(HeartRateCapability.MeasurementInterval.OFF) else supported
    items.add(
        ListSetting(
            key = DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL,
            title = R.string.prefs_title_heartrate_measurement_interval,
            icon = R.drawable.ic_heartrate,
            entries = intervals.map { ListEntry.Res(it!!.intervalSeconds.toString(), it.label) },
            defaultValue = "0",
            connectedOnly = true,
        )
    )
}