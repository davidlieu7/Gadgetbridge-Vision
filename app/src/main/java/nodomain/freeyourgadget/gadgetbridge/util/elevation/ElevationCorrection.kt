/*  Copyright (C) 2026 Manuel Marcatili

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
package nodomain.freeyourgadget.gadgetbridge.util.elevation

import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate

/** Elevation in meters at a position, or null when nothing covers it. */
fun interface ElevationLookup {
    fun elevationAt(latitude: Double, longitude: Double): Double?
}

/**
 * Replaces a track's altitudes with offline elevation model values.
 *
 * Every point with a position must be covered, or the whole track keeps the
 * altitudes it came with: a profile half from the tiles and half from a
 * drifting barometer would be worse than either. A point without a position
 * cannot be looked up, so its altitude is dropped rather than mixing the
 * sensor the correction replaces into the corrected profile.
 */
object ElevationCorrection {

    /** True when the track was corrected in place; false leaves it untouched. */
    @JvmStatic
    fun correct(track: ActivityTrack, lookup: ElevationLookup): Boolean = correct(track.allPoints, lookup)

    /** True when the points were corrected in place; false leaves them untouched. */
    @JvmStatic
    fun correct(points: List<ActivityPoint>, lookup: ElevationLookup): Boolean {
        val positioned = points.filter { it.hasPosition() }
        if (positioned.isEmpty()) {
            return false
        }

        val altitudes = DoubleArray(positioned.size)
        for ((index, point) in positioned.withIndex()) {
            val location = point.location!!
            altitudes[index] = lookup.elevationAt(location.latitude, location.longitude) ?: return false
        }

        for ((index, point) in positioned.withIndex()) {
            val location = point.location!!
            // The vertical accuracy described the altitude being replaced
            point.location = GPSCoordinate(
                location.longitude,
                location.latitude,
                altitudes[index],
                location.hdop,
                GPSCoordinate.UNKNOWN_DOP,
                location.pdop
            )
            point.altitude = altitudes[index]
        }
        for (point in points) {
            if (!point.hasPosition()) {
                point.altitude = GPSCoordinate.UNKNOWN_ALTITUDE
                // getAltitude() falls back to the location, so a placeholder fix loses its altitude too
                point.location?.let { location ->
                    point.location = GPSCoordinate(
                        location.longitude,
                        location.latitude,
                        GPSCoordinate.UNKNOWN_ALTITUDE,
                        location.hdop,
                        location.vdop,
                        location.pdop
                    )
                }
            }
        }
        return true
    }

    /** Total ascent and descent of the track as it now stands. */
    @JvmStatic
    fun elevationChange(track: ActivityTrack): ElevationGain.Change = elevationChange(track.allPoints)

    /** Total ascent and descent of the points as they now stand. */
    @JvmStatic
    fun elevationChange(points: List<ActivityPoint>): ElevationGain.Change =
        ElevationGain.fromAltitudes(points.map { it.knownAltitude() })

    /** Lowest, highest and mean altitude, or null when no point has one. */
    @JvmStatic
    fun altitudeRange(points: List<ActivityPoint>): DoubleArray? {
        val altitudes = points.mapNotNull { it.knownAltitude() }
        if (altitudes.isEmpty()) {
            return null
        }
        return doubleArrayOf(altitudes.min(), altitudes.max(), altitudes.average())
    }

    private fun ActivityPoint.knownAltitude(): Double? = altitude.takeIf { it > GPSCoordinate.UNKNOWN_ALTITUDE }

    /** A fix at exactly 0,0 is a placeholder some devices write before they have one. */
    private fun ActivityPoint.hasPosition(): Boolean {
        val location = location ?: return false
        return location.latitude != 0.0 || location.longitude != 0.0
    }
}
