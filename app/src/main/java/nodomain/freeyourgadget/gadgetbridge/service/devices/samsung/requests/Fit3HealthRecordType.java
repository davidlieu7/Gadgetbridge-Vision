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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Names for the outer "feature data type" marker byte that introduces every
 * record in a {@code CH_HEALTH} (0x0a) payload. This is the subset of those markers worth naming in a
 * log line. Purely descriptive: nothing here changes how a record is parsed,
 * it just makes "what did the band actually send us" answerable from a log
 * without a capture.
 *
 * <p>This matters most for the bulk-transfer path, where the reassembled
 * object can be several kilobytes of something that is not activity data at
 * all: every bulk transfer observed in this implementation so far has been a
 * single {@link #MARKER_APP_PREFERENCES} record (7819 bytes of app
 * preferences - scalar settings, the workout-type list and its per-type
 * goals), and "0 records parsed" out of it is the right answer rather than a
 * parser failure.
 */
public final class Fit3HealthRecordType {

    /**
     * App preferences mirrored from the band. Holds no activity data, so
     * scanning it for records is pure false-positive risk - see
     * {@link #carriesActivityRecords}.
     */
    public static final int MARKER_APP_PREFERENCES = 0x95;

    private static final Map<Integer, String> NAMES;
    /** Markers whose bodies are known to never contain an activity record. */
    private static final Set<Integer> NO_ACTIVITY_RECORDS;

    static {
        Map<Integer, String> names = new HashMap<>();
        names.put(0x04, "message info");
        names.put(0x05, "device info");
        names.put(0x07, "step count");
        names.put(0x08, "pedometer event");
        names.put(0x09, "pedometer recommendation");
        names.put(0x0a, "heart rate");
        names.put(0x0b, "user profile");
        names.put(0x10, "sleep");
        names.put(0x11, "sleep stage");
        names.put(0x12, "sleep detail");
        names.put(0x17, "calories burned");
        names.put(0x18, "deleted data");
        names.put(0x1b, "manually entered exercise");
        names.put(0x1c, "height");
        names.put(0x1d, "weight");
        names.put(0x40, "band setting");
        names.put(0x7c, "active-calories goal");
        names.put(0x7d, "daily activity goal");
        names.put(0x7e, "day summary");
        names.put(0x80, "workout");
        names.put(0x8b, "per-minute activity");
        names.put(0x94, "activity goal");
        names.put(MARKER_APP_PREFERENCES, "app preferences");
        names.put(0x96, "badge");
        names.put(0xa5, "heart-rate zones");
        names.put(0xa6, "heart-rate zone settings");
        names.put(0xe0, "sequence number");
        NAMES = Collections.unmodifiableMap(names);

        Set<Integer> noRecords = new HashSet<>();
        noRecords.add(MARKER_APP_PREFERENCES);
        NO_ACTIVITY_RECORDS = Collections.unmodifiableSet(noRecords);
    }

    private Fit3HealthRecordType() {
    }

    /**
     * @return a short human-readable name for a record marker, for logging -
     *     {@code "unknown"} for the (many) markers this build has no name for.
     */
    public static String name(final int marker) {
        String name = NAMES.get(marker);
        return name != null ? name : "unknown";
    }

    /**
     * Whether a payload led by this marker is worth scanning for pedometer /
     * heart-rate records.
     *
     * <p>Deliberately conservative: only markers <em>confirmed</em> to hold
     * nothing parseable are excluded, so an unknown or not-yet-decoded record
     * type is still scanned. The scanners re-validate each candidate anyway,
     * but they work by looking for a marker byte at any offset, and a
     * multi-kilobyte body of floats and identifiers is exactly the input most
     * likely to produce a spurious hit.
     */
    public static boolean carriesActivityRecords(final int marker) {
        return !NO_ACTIVITY_RECORDS.contains(marker);
    }
}
