package nodomain.freeyourgadget.gadgetbridge.util.elevation

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date

/** A track is corrected only while the toggle is on and an imported tile covers it. */
class ElevationTilesTest : TestBase() {

    @Before
    fun resetTiles() {
        ElevationTiles.resetForTests()
        ElevationTiles.library(getContext()).scan().forEach { ElevationTiles.library(getContext()).deleteTile(it.key) }
        setEnabled(true)
    }

    @After
    fun cleanUp() {
        resetTiles()
    }

    private fun setEnabled(enabled: Boolean) {
        GBApplication.getPrefs().preferences.edit().putBoolean(ElevationTiles.PREF_ENABLED, enabled).commit()
    }

    private fun importFlatTile(value: Int) {
        val resolution = HgtResolution.THREE_ARC_SECOND
        val buffer = ByteBuffer.allocate(resolution.byteSize.toInt()).order(ByteOrder.BIG_ENDIAN)
        repeat(resolution.samplesPerSide * resolution.samplesPerSide) { buffer.putShort(value.toShort()) }
        ElevationTiles.library(getContext()).importTile("N45E007.hgt", ByteArrayInputStream(buffer.array()))
        // As the settings screen does after an import
        ElevationTiles.tiles(getContext())
    }

    private fun track() = ActivityTrack().apply {
        addTrackPoint(ActivityPoint(Date(0)).apply { location = GPSCoordinate(7.5, 45.5, 480.0) })
        addTrackPoint(ActivityPoint(Date(1000)).apply { location = GPSCoordinate(7.6, 45.6, 485.0) })
    }

    @Test
    fun withoutTiles_theTrackKeepsItsAltitudes() {
        val track = track()

        assertFalse(ElevationTiles.isActive(getContext()))
        assertFalse(ElevationTiles.correct(getContext(), track))
        assertEquals(480.0, track.allPoints[0].altitude, 0.0)
    }

    @Test
    fun withACoveringTile_theTrackTakesItsAltitudes() {
        importFlatTile(100)
        val track = track()

        assertTrue(ElevationTiles.correct(getContext(), track))
        assertEquals(listOf(100.0, 100.0), track.allPoints.map { it.altitude })
    }

    @Test
    fun switchedOff_theTrackKeepsItsAltitudes() {
        importFlatTile(100)
        setEnabled(false)
        val track = track()

        assertFalse(ElevationTiles.correct(getContext(), track))
        assertEquals(480.0, track.allPoints[0].altitude, 0.0)
    }

    @Test
    fun deletingTheLastTile_stopsCorrecting() {
        importFlatTile(100)
        ElevationTiles.deleteTile(getContext(), HgtTileKey(45, 7))

        assertFalse(ElevationTiles.isActive(getContext()))
        assertFalse(ElevationTiles.correct(getContext(), track()))
    }

    @Test
    fun noContext_meansNoCorrection() {
        importFlatTile(100)

        assertFalse(ElevationTiles.correct(null, track()))
    }
}
