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
package nodomain.freeyourgadget.gadgetbridge.service.devices.concept2.protocol

/** A notification recorded from a real PM5, as stored in the test resources. */
data class PM5RecordedNotification(val millis: Long, val shortId: Int, val value: ByteArray)

object PM5Recording {
    /** The 100 m row in `concept2/pm5_row_100m.tsv`, recorded on the individual characteristics. */
    fun row100m(): List<PM5RecordedNotification> = load("/concept2/pm5_row_100m.tsv")

    fun load(resource: String): List<PM5RecordedNotification> {
        val stream = PM5Recording::class.java.getResourceAsStream(resource)
            ?: throw IllegalArgumentException("Missing test resource $resource")
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val (millis, id, hex) = line.split('\t')
                    PM5RecordedNotification(millis.toLong(), id.removePrefix("0x").toInt(16), PM5MessageDecoderTest.hex(hex))
                }
                .toList()
        }
    }
}
