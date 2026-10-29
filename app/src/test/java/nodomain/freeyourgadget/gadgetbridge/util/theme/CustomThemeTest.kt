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
package nodomain.freeyourgadget.gadgetbridge.util.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing of the theme.json, in particular that themes made before the opacity and time-of-day additions still
 * parse (the format version is unchanged and the new slot forms are optional).
 */
class CustomThemeTest {
    private val colors =
        "\"background\":\"#FF101010\",\"surface\":\"#FF202020\",\"onSurface\":\"#FFEEEEEE\"," +
            "\"onSurfaceVariant\":\"#FFAAAAAA\",\"primary\":\"#FF3366FF\",\"onPrimary\":\"#FF000000\"," +
            "\"primaryContainer\":\"#FF223366\",\"onPrimaryContainer\":\"#FFDDEEFF\""

    private fun theme(slots: String) =
        "{\"format\":1,\"name\":\"T\",\"base\":\"dark\",\"colors\":{$colors},\"slots\":{$slots}}"

    @Test
    fun oldStringSlotStillParses() {
        val t = CustomTheme.parse(theme("\"background\":\"images/bg.png\""))
        assertEquals("images/bg.png", t.slots["background"])
        assertEquals(1f, t.getSlotOpacity("background"), 0f)
        assertNull(t.getSlotTimes("background"))
    }

    @Test
    fun oldObjectSlotWithOpacityStillParses() {
        val t = CustomTheme.parse(theme("\"background\":{\"image\":\"images/bg.webp\",\"opacity\":0.5}"))
        assertEquals("images/bg.webp", t.slots["background"])
        assertEquals(0.5f, t.getSlotOpacity("background"), 1e-6f)
        assertNull(t.getSlotTimes("background"))
    }

    @Test
    fun byTimeSlotParsesAndRepresentativeIsDay() {
        val t = CustomTheme.parse(
            theme("\"dashboardHero\":{\"byTime\":{\"morning\":\"m.png\",\"day\":\"d.png\",\"evening\":\"e.png\",\"night\":\"n.png\"}}")
        )
        val times = t.getSlotTimes("dashboardHero")!!
        assertEquals(setOf("morning", "day", "evening", "night"), times.keys)
        assertEquals("d.png", times["day"])
        assertEquals("d.png", t.slots["dashboardHero"]) // representative image for presence/import
    }

    @Test(expected = CustomTheme.InvalidThemeException::class)
    fun unknownTimePeriodRejected() {
        CustomTheme.parse(theme("\"dashboardHero\":{\"byTime\":{\"noon\":\"x.png\"}}"))
    }

    /** The editor builds a theme with CustomTheme.of + writes it with toJson; a byTime cycle must survive that. */
    @Test
    fun byTimeRoundTripsThroughOfAndToJson() {
        val required = mapOf(
            CustomTheme.COLOR_BACKGROUND to 0xFF101010.toInt(), CustomTheme.COLOR_SURFACE to 0xFF202020.toInt(),
            CustomTheme.COLOR_ON_SURFACE to 0xFFEEEEEE.toInt(), CustomTheme.COLOR_ON_SURFACE_VARIANT to 0xFFAAAAAA.toInt(),
            CustomTheme.COLOR_PRIMARY to 0xFF3366FF.toInt(), CustomTheme.COLOR_ON_PRIMARY to 0xFF000000.toInt(),
            CustomTheme.COLOR_PRIMARY_CONTAINER to 0xFF223366.toInt(), CustomTheme.COLOR_ON_PRIMARY_CONTAINER to 0xFFDDEEFF.toInt(),
        )
        val times = linkedMapOf(
            "morning" to "dashboardHero_morning.png", "day" to "dashboardHero_day.png",
            "evening" to "dashboardHero_evening.png", "night" to "dashboardHero_night.png",
        )
        val built = CustomTheme.of(
            "T", "", true, required,
            mapOf("dashboardHero" to "dashboardHero_day.png", "background" to "background.png"),
            mapOf("dashboardHero" to 1f),
            mapOf("dashboardHero" to times),
        )
        val reparsed = CustomTheme.parse(built.toJson())
        assertEquals(times, reparsed.getSlotTimes("dashboardHero"))
        assertEquals("background.png", reparsed.slots["background"]) // the static slot survives too
        assertNull(reparsed.getSlotTimes("background"))
    }
}
