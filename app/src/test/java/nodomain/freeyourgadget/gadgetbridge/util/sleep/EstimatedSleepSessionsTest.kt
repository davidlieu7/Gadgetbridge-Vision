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
package nodomain.freeyourgadget.gadgetbridge.util.sleep

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin

class EstimatedSleepSessionsTest {
    private val bedtime: Instant = Instant.parse("2024-03-04T20:30:00Z")

    private fun minute(index: Int, kind: SleepMinuteKind, movement: Float = 0f, heartRate: Float? = null) =
        SleepMinute(bedtime.plusSeconds(60L * index), kind, movement, heartRate)

    /** Restless, 240 still minutes, 20 not worn, 200 still, restless. */
    private val night: List<SleepMinute> = buildList {
        var i = 0
        repeat(30) { add(minute(i, SleepMinuteKind.RAW, 8f + (i * 5) % 8, 64f)); i++ }
        repeat(240) { add(minute(i, SleepMinuteKind.RAW, 0f, heartRate(i))); i++ }
        repeat(20) { add(minute(i, SleepMinuteKind.UNMEASURABLE)); i++ }
        repeat(200) { add(minute(i, SleepMinuteKind.RAW, 0f, heartRate(i))); i++ }
        repeat(30) { add(minute(i, SleepMinuteKind.RAW, 8f + (i * 5) % 8, 64f)); i++ }
    }

    private fun heartRate(index: Int): Float = 52f + 4f * sin(2 * PI * index / 90.0).toFloat()

    private val from = bedtime.epochSecond.toInt()
    private val to = bedtime.plusSeconds(86_400).epochSecond.toInt()

    @Test
    fun aNotWornGap_takesTheGivenKind() {
        val stages = EstimatedSleepSessions.of(night, from, to, ActivityKind.SLEEP_ANY).single().stages

        val unknown = stages.filter { it.kind == ActivityKind.SLEEP_ANY }
        assertEquals(20 * 60_000L, unknown.sumOf { it.endTime - it.startTime })
    }

    @Test
    fun aNotWornGap_isLeftOutWithoutAKind() {
        val stages = EstimatedSleepSessions.of(night, from, to, null).single().stages

        assertTrue(stages.none { it.kind == ActivityKind.SLEEP_ANY })
        val covered = stages.sumOf { it.endTime - it.startTime }
        val span = stages.last().endTime - stages.first().startTime
        assertEquals(20 * 60_000L, span - covered)
    }

    @Test
    fun aSessionWithItsOnsetOutsideTheRange_isLeftOut() {
        val onsetBefore = bedtime.plusSeconds(10 * 60).epochSecond.toInt()

        assertEquals(0, EstimatedSleepSessions.of(night, from, onsetBefore, null).size)
    }
}
