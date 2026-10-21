/*  Copyright (C) 2026 José Rebelo, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.util.sleep

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession
import nodomain.freeyourgadget.gadgetbridge.model.SleepStage

/** Sleep sessions estimated on read from stored per-minute samples, for devices that stage no sleep themselves. */
object EstimatedSleepSessions {
    /** How far before the requested range to read samples, so a session's onset has the context it needs. */
    const val LOOK_BACK_SECONDS: Int = 12 * 60 * 60

    /** How far after the requested range to read samples, longer than any session. */
    const val LOOK_AHEAD_SECONDS: Int = 24 * 60 * 60

    /**
     * The sessions in [minutes] whose onset lies in `[tsFrom, tsTo)`. [unknownKind] is the kind
     * a not-worn span inside a night gets; null leaves such spans out of the stages.
     */
    fun of(minutes: List<SleepMinute>, tsFrom: Int, tsTo: Int, unknownKind: ActivityKind?): List<SleepSession> =
        SleepStageEstimator.estimateAll(minutes)
            .filter { it.onset.epochSecond in tsFrom..<tsTo }
            .map { estimate ->
                estimate.stages.mapNotNull { span ->
                    activityKind(span.stage, unknownKind)?.let { SleepStage(it, span.start.toEpochMilli(), span.end.toEpochMilli()) }
                }
            }
            .filter { it.isNotEmpty() }
            .map(::SleepSession)

    private fun activityKind(stage: EstimatedStage, unknownKind: ActivityKind?): ActivityKind? = when (stage) {
        EstimatedStage.AWAKE -> ActivityKind.AWAKE_SLEEP
        EstimatedStage.LIGHT -> ActivityKind.LIGHT_SLEEP
        EstimatedStage.DEEP -> ActivityKind.DEEP_SLEEP
        EstimatedStage.REM -> ActivityKind.REM_SLEEP
        EstimatedStage.UNKNOWN -> unknownKind
    }
}
