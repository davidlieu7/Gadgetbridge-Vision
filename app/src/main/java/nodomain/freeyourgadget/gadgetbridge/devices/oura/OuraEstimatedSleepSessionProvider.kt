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
package nodomain.freeyourgadget.gadgetbridge.devices.oura

import androidx.annotation.VisibleForTesting
import nodomain.freeyourgadget.gadgetbridge.devices.SleepSessionProvider
import nodomain.freeyourgadget.gadgetbridge.devices.oura.samples.OuraActivitySampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.OuraActivitySample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession
import nodomain.freeyourgadget.gadgetbridge.util.sleep.EstimatedSleepSessions
import nodomain.freeyourgadget.gadgetbridge.util.sleep.SleepMinute
import nodomain.freeyourgadget.gadgetbridge.util.sleep.SleepMinuteKind
import java.time.Instant

/** Sleep estimated from the stored samples each time it is read; the ring stages none itself. */
class OuraEstimatedSleepSessionProvider(
    private val device: GBDevice,
    private val session: DaoSession,
) : SleepSessionProvider {
    override fun getSleepSessions(tsFrom: Int, tsTo: Int): List<SleepSession> {
        val samples = OuraActivitySampleProvider(device, session).getDeviceSamples(
            tsFrom - EstimatedSleepSessions.LOOK_BACK_SECONDS,
            tsTo + EstimatedSleepSessions.LOOK_AHEAD_SECONDS
        )

        // Not-worn spans inside the night are left out, so they overlay nothing
        return EstimatedSleepSessions.of(samples.map(::toMinute), tsFrom, tsTo, null)
    }

    companion object {
        /**
         * Minutes of the ring's rest periods carry movement and heart rate; other worn minutes are
         * awake, and minutes off the finger cannot be measured.
         */
        @JvmStatic
        @VisibleForTesting
        fun toMinute(sample: OuraActivitySample): SleepMinute {
            val time = Instant.ofEpochSecond(sample.timestamp.toLong())
            val restMovement = sample.restMovement
            return when {
                sample.rawKind == ActivityKind.NOT_WORN.code -> SleepMinute(time, SleepMinuteKind.UNMEASURABLE)
                restMovement != null -> SleepMinute(
                    time,
                    SleepMinuteKind.RAW,
                    restMovement,
                    sample.heartRate.takeIf { it > 0 }?.toFloat()
                )
                else -> SleepMinute(time, SleepMinuteKind.AWAKE)
            }
        }
    }
}
