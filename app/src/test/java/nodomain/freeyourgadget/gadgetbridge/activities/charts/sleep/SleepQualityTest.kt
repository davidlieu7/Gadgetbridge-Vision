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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepQualityTest {

    @Test
    fun noSleep_hasNoScore() {
        assertNull(SleepQuality.score(0.0, 0.0, 0.0, 0.0, 480))
    }

    @Test
    fun goalWithEnoughDeepSleepAndLittleAwake_withoutRem_is100() {
        assertEquals(100, SleepQuality.score(480.0, 120.0, 0.0, 5.0, 480))
    }

    @Test
    fun goalWithIdealRem_is100() {
        assertEquals(100, SleepQuality.score(480.0, 96.0, 96.0, 10.0, 480))
    }

    @Test
    fun shortNight() {
        // 50 * 0.75² + 25 * 0.15 / 0.2 + 15 * 25 / 50 = 54.375, scaled to 100 without REM
        assertEquals(60, SleepQuality.score(360.0, 54.0, 0.0, 35.0, 480))
    }

    @Test
    fun oversleep_losesPointsBeyondTwoHours() {
        // 50 - 2 h * 10 + 25 + 15 = 70, scaled to 100 without REM
        assertEquals(78, SleepQuality.score(720.0, 144.0, 0.0, 0.0, 480))
    }

    @Test
    fun withoutDeepOrRemData_isScaledTo100() {
        // Only light sleep: duration and awake time make the whole score
        assertEquals(100, SleepQuality.score(480.0, 0.0, 0.0, 5.0, 480))
        // 50 * 0.75² + 15 * 25 / 50 = 35.625 of 65
        assertEquals(55, SleepQuality.score(360.0, 0.0, 0.0, 35.0, 480))
    }

    @Test
    fun levels() {
        assertEquals(SleepQualityLevel.EXCELLENT, SleepQualityLevel.of(100))
        assertEquals(SleepQualityLevel.EXCELLENT, SleepQualityLevel.of(85))
        assertEquals(SleepQualityLevel.GOOD, SleepQualityLevel.of(84))
        assertEquals(SleepQualityLevel.GOOD, SleepQualityLevel.of(70))
        assertEquals(SleepQualityLevel.FAIR, SleepQualityLevel.of(69))
        assertEquals(SleepQualityLevel.FAIR, SleepQualityLevel.of(55))
        assertEquals(SleepQualityLevel.POOR, SleepQualityLevel.of(54))
        assertEquals(SleepQualityLevel.POOR, SleepQualityLevel.of(0))
    }
}
