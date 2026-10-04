/*  Copyright (C) 2026 David Girón

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class NxWearBootstrap(
    val userId: Int,
    val isMale: Boolean,
    val age: Int,
    val heightCm: Int,
    val weightKg: Int,
    val epochSeconds: Long,
    val timezoneOffsetSeconds: Int,
    val is24Hour: Boolean,
    val stepsGoal: Int,
    val distanceGoalMeters: Int,
    val caloriesGoal: Int,
    val sleepGoalSeconds: Int,
    val activeTimeGoalSeconds: Int,
) {
    fun encode(): ByteArray {
        require(epochSeconds in 0..0xffff_ffffL)
        val records = listOf(
            record(0x66, le32(userId) + byteArrayOf(
                (if (isMale) 0 else 1).toByte(),
                age.coerceIn(1, 255).toByte(),
                heightCm.coerceIn(1, 255).toByte(),
                weightKg.coerceIn(1, 255).toByte(),
                0,
            )),
            record(0x68, le32(epochSeconds.toInt()) + le32(timezoneOffsetSeconds) +
                byteArrayOf((if (is24Hour) 1 else 0).toByte())),
            record(0x7c, byteArrayOf(1, -1, -1, 0, 0)),
            record(0x67, byteArrayOf(0)),
            record(0x6d, byteArrayOf(1)),
            record(0x6f, le32(stepsGoal.coerceAtLeast(0)) + le32(distanceGoalMeters.coerceAtLeast(0)) +
                le32(caloriesGoal.coerceAtLeast(0)) + le16(sleepGoalSeconds.coerceIn(0, 0xffff)) +
                le16(activeTimeGoalSeconds.coerceIn(0, 0xffff))),
            record(0x78, byteArrayOf(0, 0)),
        )
        val recordBytes = records.fold(byteArrayOf()) { result, record -> result + record }
        val payload = le16(recordBytes.size + 1) + byteArrayOf(records.size.toByte()) + recordBytes
        return NxWearPacket.frame(0, 0x01, NxWearDataType.BOOTSTRAP, payload, deviceType = 0x01)
    }

    private fun record(type: Int, data: ByteArray): ByteArray =
        le16(data.size + 3) + byteArrayOf(type.toByte()) + data

    private fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()
}
