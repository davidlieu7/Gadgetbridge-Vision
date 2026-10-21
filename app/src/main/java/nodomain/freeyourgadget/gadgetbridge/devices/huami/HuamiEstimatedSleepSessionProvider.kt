package nodomain.freeyourgadget.gadgetbridge.devices.huami

import nodomain.freeyourgadget.gadgetbridge.devices.SleepSessionProvider
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBand2SampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.MiBandActivitySample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession
import nodomain.freeyourgadget.gadgetbridge.util.sleep.EstimatedSleepSessions
import nodomain.freeyourgadget.gadgetbridge.util.sleep.SleepMinute
import nodomain.freeyourgadget.gadgetbridge.util.sleep.SleepMinuteKind
import java.time.Instant

class HuamiEstimatedSleepSessionProvider(
    private val device: GBDevice,
    private val session: DaoSession,
) : SleepSessionProvider {
    override fun getSleepSessions(tsFrom: Int, tsTo: Int): List<SleepSession> {
        val samples = MiBand2SampleProvider(device, session).getDeviceSamples(
            tsFrom - EstimatedSleepSessions.LOOK_BACK_SECONDS,
            tsTo + EstimatedSleepSessions.LOOK_AHEAD_SECONDS
        )

        return EstimatedSleepSessions.of(samples.map(::toMinute), tsFrom, tsTo, ActivityKind.SLEEP_ANY)
    }

    companion object {
        /**
         * Divisor to convert intensity to movement, matched against 3 nights from Amazfit Bip samples.
         */
        private const val INTENSITY_TO_MOVEMENT = 8f

        private fun toMinute(sample: MiBandActivitySample): SleepMinute {
            val time = Instant.ofEpochSecond(sample.timestamp.toLong())
            return when (sample.rawKind) {
                HuamiConst.TYPE_NONWEAR, HuamiConst.TYPE_CHARGING -> SleepMinute(time, SleepMinuteKind.UNMEASURABLE)
                else -> SleepMinute(
                    time,
                    SleepMinuteKind.RAW,
                    sample.rawIntensity / INTENSITY_TO_MOVEMENT,
                    sample.heartRate.takeIf { it in 1..254 }?.toFloat()
                )
            }
        }
    }
}
