/*  Copyright (C) 2026 theupsidedownabyss

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
package nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession
import nodomain.freeyourgadget.gadgetbridge.model.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SleepInsightsTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun millis(time: LocalDateTime) = time.toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun stage(kind: ActivityKind, from: LocalDateTime, to: LocalDateTime) = SleepStage(kind, millis(from), millis(to))

    @Test
    fun minutesSinceNoon_keepsMidnightTogether() {
        assertEquals(660.0, SleepInsights.minutesSinceNoon(millis(LocalDateTime.of(2026, 6, 1, 23, 0)), zone), 0.0)
        assertEquals(780.0, SleepInsights.minutesSinceNoon(millis(LocalDateTime.of(2026, 6, 2, 1, 0)), zone), 0.0)
    }

    @Test
    fun details() {
        val night = LocalDateTime.of(2026, 6, 1, 23, 0)
        val sessions = listOf(
            SleepSession(listOf(
                stage(ActivityKind.LIGHT_SLEEP, night, night.plusHours(2)),
                stage(ActivityKind.AWAKE_SLEEP, night.plusHours(2), night.plusHours(2).plusMinutes(10)),
                stage(ActivityKind.DEEP_SLEEP, night.plusHours(2).plusMinutes(10), night.plusHours(4)),
            )),
            SleepSession(listOf(stage(ActivityKind.LIGHT_SLEEP, night.plusHours(5), night.plusHours(7)))),
        )
        val previous = listOf(millis(night.minusDays(1).minusMinutes(30)), millis(night.minusDays(2).plusMinutes(30)))
        val details = SleepInsights.details(sessions, previous, zone)!!
        assertEquals(7 * 3600L, details.inBedSeconds)
        // One awake stage and one gap between sessions
        assertEquals(2, details.wakeUps)
        // 22:30, 23:00 and 23:30: a standard deviation of about 24.5 minutes
        assertEquals(24, details.bedtimeSpreadMinutes)
    }

    @Test
    fun napBeforeTheNight_isLeftOut() {
        val nap = LocalDateTime.of(2026, 6, 1, 14, 0)
        val night = LocalDateTime.of(2026, 6, 1, 23, 30)
        val sessions = listOf(
            SleepSession(listOf(stage(ActivityKind.LIGHT_SLEEP, nap, nap.plusMinutes(40)))),
            SleepSession(listOf(stage(ActivityKind.LIGHT_SLEEP, night, night.plusHours(7).plusMinutes(30)))),
        )
        val details = SleepInsights.details(sessions, emptyList(), zone)!!
        assertEquals(millis(night), details.fellAsleep)
        assertEquals(7 * 3600L + 1800, details.inBedSeconds)
        assertEquals(0, details.wakeUps)
    }

    @Test
    fun noSessions_noDetails() {
        assertNull(SleepInsights.details(emptyList(), emptyList(), zone))
    }

    @Test
    fun advice() {
        val lateNight = SleepDetails(0, millis(LocalDateTime.of(2026, 6, 2, 1, 30)), 0, 5, 60)
        assertEquals(
            listOf(SleepAdvice.SHORT, SleepAdvice.DEEP_LOW, SleepAdvice.AWAKE_HIGH),
            SleepInsights.advice(300.0, 30.0, 40.0, 480, lateNight, zone),
        )
        val goodNight = SleepDetails(0, millis(LocalDateTime.of(2026, 6, 1, 22, 45)), 0, 1, 15)
        assertEquals(listOf(SleepAdvice.KEEP_IT_UP), SleepInsights.advice(480.0, 120.0, 5.0, 480, goodNight, zone))
        assertEquals(listOf(SleepAdvice.IRREGULAR, SleepAdvice.LATE),
            SleepInsights.advice(480.0, 120.0, 5.0, 480, lateNight.copy(wakeUps = 1), zone))
        // No deep sleep data: no deep sleep tip
        assertEquals(listOf(SleepAdvice.KEEP_IT_UP), SleepInsights.advice(480.0, 0.0, 5.0, 480, goodNight, zone))
    }
}
