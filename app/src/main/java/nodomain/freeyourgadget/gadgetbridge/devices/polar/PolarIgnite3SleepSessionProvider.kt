package nodomain.freeyourgadget.gadgetbridge.devices.polar

import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.SleepSessionProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession
import nodomain.freeyourgadget.gadgetbridge.model.SleepStage

/** Sleep stages as stored by the Polar sync: the stage is a [Stage] code, the duration is in seconds. */
class PolarIgnite3SleepSessionProvider(
    private val device: GBDevice,
    private val session: DaoSession
) : SleepSessionProvider {
    override fun getSleepSessions(tsFrom: Int, tsTo: Int): List<SleepSession> {
        val fromMillis = (tsFrom - LOOK_BACK_SECONDS) * 1000L
        val toMillis = (tsTo + LOOK_AHEAD_SECONDS) * 1000L

        val stages = GenericSleepStageSampleProvider(device, session).getAllSamples(fromMillis, toMillis).map {
            SleepStage(stageToActivityKind(it.stage), it.timestamp, it.timestamp + it.duration * 1000L)
        }
        return SleepSessionProvider.groupIntoSessions(stages)
            .filter { (it.startTime / 1000L) in tsFrom..<tsTo }
            .sortedBy { it.startTime }
    }

    object Stage {
        const val AWAKE = 1
        const val LIGHT = 2
        const val DEEP = 3
        const val REM = 4
    }

    companion object {
        private const val LOOK_BACK_SECONDS = 12 * 60 * 60
        private const val LOOK_AHEAD_SECONDS = 12 * 60 * 60

        fun stageToActivityKind(stage: Int): ActivityKind = when (stage) {
            Stage.AWAKE -> ActivityKind.AWAKE_SLEEP
            Stage.LIGHT -> ActivityKind.LIGHT_SLEEP
            Stage.DEEP -> ActivityKind.DEEP_SLEEP
            Stage.REM -> ActivityKind.REM_SLEEP
            else -> ActivityKind.UNKNOWN
        }
    }
}
