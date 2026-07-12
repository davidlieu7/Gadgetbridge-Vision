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

import java.util.HashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

/**
 * Maps the band's workout type id (the {@code exerciseType} field of a
 * {@link Fit3HealthWorkout}) to a Gadgetbridge
 * {@link ActivityKind}. The ids come from the band's own sport-type table
 * (e.g. 1001 = walking, 1002 = running); unmapped ids fall back to the generic
 * {@link ActivityKind#EXERCISE}.
 */
public final class Fit3ExerciseType {

    private static final Map<Integer, ActivityKind> TYPES = new HashMap<>();

    static {
        TYPES.put(1001, ActivityKind.WALKING);
        TYPES.put(1002, ActivityKind.RUNNING);
        TYPES.put(11007, ActivityKind.CYCLING);
        TYPES.put(13001, ActivityKind.HIKING);
        TYPES.put(14001, ActivityKind.SWIMMING);
        TYPES.put(15005, ActivityKind.TREADMILL);
        TYPES.put(15003, ActivityKind.INDOOR_CYCLING);
        TYPES.put(15004, ActivityKind.ROWING_MACHINE);
        TYPES.put(15006, ActivityKind.ELLIPTICAL_TRAINER);
        TYPES.put(9002, ActivityKind.YOGA);
        TYPES.put(6002, ActivityKind.TENNIS);
        TYPES.put(14010, ActivityKind.ROWING);
        TYPES.put(16002, ActivityKind.SKIING);
    }

    private Fit3ExerciseType() {
    }

    public static ActivityKind toActivityKind(int exerciseType) {
        ActivityKind kind = TYPES.get(exerciseType);
        return kind != null ? kind : ActivityKind.EXERCISE;
    }
}
