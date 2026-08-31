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

/**
 * A single bit of the feature tables the watch reports. Bands that do not answer the feature
 * queries at all report nothing, and every feature then reads as unsupported.
 */
public enum VeryFitFeature {
    EXTRA_TABLE(Table.BASE, 3, 6),
    FRAMED_PROTOCOL(Table.BASE, 5, 7),
    SPO2_ALL_DAY(Table.BASE, 5, 2),
    INACTIVITY_REMINDER(Table.BASE, 6, 5),
    HYDRATION_REMINDER(Table.BASE, 7, 4),
    TEMPERATURE(Table.BASE, 8, 0),
    CAMERA_REMOTE(Table.BASE, 10, 7),
    BLOOD_PRESSURE(Table.BASE, 14, 5),

    FRACTIONAL_TIME_ZONE(Table.EXTRA, 1, 2),
    HOURLY_WEATHER(Table.EXTRA, 1, 3),
    SPORTS_PLAN(Table.EXTRA, 2, 1),
    MTU_QUERY(Table.EXTRA, 6, 7),
    TIME_GOAL(Table.EXTRA, 9, 7),
    DO_NOT_DISTURB_ALL_DAY(Table.EXTRA, 11, 2),
    DO_NOT_DISTURB_SMART(Table.EXTRA, 11, 3),
    WATCH_FACE_FRAMES(Table.EXTRA, 13, 3),
    ;

    public enum Table {
        BASE, EXTRA
    }

    private final Table table;
    private final int index;
    private final int bit;

    VeryFitFeature(final Table table, final int index, final int bit) {
        this.table = table;
        this.index = index;
        this.bit = bit;
    }

    public Table getTable() {
        return table;
    }

    public boolean isSetIn(final byte[] data) {
        return data != null && index < data.length && ((data[index] >> bit) & 1) != 0;
    }
}
