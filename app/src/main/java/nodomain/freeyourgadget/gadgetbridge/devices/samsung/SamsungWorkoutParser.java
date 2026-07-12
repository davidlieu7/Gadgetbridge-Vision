/*  Copyright (C) 2026 Vitaliy Tomin

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
package nodomain.freeyourgadget.gadgetbridge.devices.samsung;

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryParser;

/**
 * The workout summary is stored fully decoded at sync time (its {@code
 * summaryData} JSON is populated when the {@code EXERCISE} record is parsed -
 * see the {@code service.devices.samsung} package), so there's no raw binary
 * left to re-parse. This exists only so the coordinator can return a non-null
 * {@link ActivitySummaryParser} (the workout UI requires one); it returns the
 * summary unchanged.
 */
public class SamsungWorkoutParser implements ActivitySummaryParser {
    @Override
    public BaseActivitySummary parseBinaryData(final BaseActivitySummary summary, final boolean forDetails) {
        return summary;
    }
}
