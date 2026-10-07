/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService

/** The device retains sparse IDs after deletion; list indices are not alarm IDs. */
data class SoundcoreSleepA30Alarm(
    val id: Int,
    val enabled: Boolean,
    val minutes: Int,
    val dayMask: Int,
    val tune: Int,
    val volume: Int,
    val snoozeMinutes: Int
) {
    fun encode(): ByteArray = byteArrayOf(
        id.toByte(), (if (enabled) 1 else 0).toByte(), minutes.toByte(), (minutes shr 8).toByte(),
        dayMask.toByte(), tune.toByte(), volume.toByte(), snoozeMinutes.toByte()
    )

    val repetition: Int
        get() = DAY_BITS.indices.fold(0) { result, day ->
            if (dayMask and DAY_BITS[day] != 0) result or (1 shl day) else result
        }

    companion object {
        private val DAY_BITS = intArrayOf(4, 8, 16, 32, 64, 2, 128)

        fun encodeRepetition(repetition: Int): Int = DAY_BITS.indices.fold(0) { result, day ->
            if (repetition and (1 shl day) != 0) result or DAY_BITS[day] else result
        }

        fun decodeList(payload: ByteArray, slotCount: Int): List<SoundcoreSleepA30Alarm>? {
            if (payload.size % 8 != 0 || slotCount < 0 || payload.size / 8 > slotCount) return null
            val records = payload.asList().chunked(8).map { bytes ->
                val values = bytes.map { it.toInt() and 0xff }
                val minutes = values[2] or (values[3] shl 8)
                if (values[0] >= slotCount || values[1] !in 0..1 || minutes >= 1440 || values[6] > 100) return null
                SoundcoreSleepA30Alarm(values[0], values[1] == 1, minutes, values[4], values[5], values[6], values[7])
            }
            if (records.map { it.id }.distinct().size != records.size) return null
            return records
        }
    }
}

class SoundcoreSleepA30AlarmEvent(private val records: List<SoundcoreSleepA30Alarm>) : GBDeviceEvent() {
    override fun evaluate(context: Context, device: GBDevice) {
        val byId = records.associateBy { it.id }
        val slotCount = device.deviceCoordinator.getAlarmSlotCount(device)
        for (alarm in DBHelper.getAlarmsWithDefaults(device)) {
            if (alarm.position !in 0 until slotCount) continue
            val record = byId[alarm.position]
            alarm.unused = record == null
            alarm.enabled = record?.enabled ?: false
            if (record != null) {
                alarm.hour = record.minutes / 60
                alarm.minute = record.minutes % 60
                alarm.repetition = record.repetition
                alarm.snooze = record.snoozeMinutes > 0
                alarm.smartWakeup = false
            }
            DBHelper.store(alarm)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(DeviceService.ACTION_SAVE_ALARMS))
    }
}
