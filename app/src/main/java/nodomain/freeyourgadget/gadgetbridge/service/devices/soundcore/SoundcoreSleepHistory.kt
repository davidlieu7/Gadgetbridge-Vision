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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions

/** A complete history snapshot. Transport paging is independent of the record chronology. */
object SoundcoreSleepHistory {
    const val CMD_STAGES: Short = 0x0415
    const val CMD_SESSIONS: Short = 0x8815.toShort()
    private const val MAX_PAGES = 32
    private const val MAX_SESSION_SECONDS = 24 * 60 * 60

    data class Page(val selector: Int, val index: Int, val total: Int, val records: List<ByteArray>)
    data class Record(val timestamp: Long, val packed: Int, val rollovers: Int, val flags: Int) {
        val stage: Int get() = packed and 15
        val position: Int get() = packed ushr 4
    }
    data class Session(val start: Long, val end: Long, val type: Int, val score: Int,
                       val unknownCount: Int, val durationMinutes: Int, val unknown: Int)
    data class Interval(val start: Long, val end: Long, val stage: Int, val position: Int)
    data class Report(val records: List<Record>, val sessions: List<Session>, val intervals: List<Interval>)

    class Pages(private val recordSize: Int, private val requiredSelectors: Set<Int> = emptySet()) {
        private class Stream(val total: Int) {
            val pages = sortedMapOf<Int, ByteArray>()
            val complete: Boolean get() = pages.size == total
        }

        private val streams = sortedMapOf<Int, Stream>()
        val complete: Boolean get() = streams.isNotEmpty() &&
            (requiredSelectors.isEmpty() || streams.keys.containsAll(requiredSelectors)) &&
            streams.values.all { it.complete }

        fun accept(payload: ByteArray): Boolean {
            val page = decodePage(payload, recordSize) ?: return false
            if (requiredSelectors.isEmpty()) {
                // A stage request binds to whichever selector responds first.
                if (streams.isNotEmpty() && page.selector !in streams) return false
            } else if (page.selector !in requiredSelectors) return false
            val stream = streams[page.selector]
            if (stream != null) {
                if (stream.total != page.total) return false
                val previous = stream.pages[page.index]
                if (previous != null) return previous.contentEquals(payload)
            }
            val target = stream ?: Stream(page.total).also { streams[page.selector] = it }
            target.pages[page.index] = payload.copyOf()
            return true
        }

        fun records(): List<ByteArray> = streams.values.flatMap { stream ->
            stream.pages.values.flatMap { decodePage(it, recordSize)!!.records }
        }
    }

    @JvmStatic fun decodePage(payload: ByteArray, recordSize: Int): Page? {
        if (recordSize !in setOf(7, 14) || payload.size < 4) return null
        val selector = payload[0].toInt() and 255
        val index = payload[1].toInt() and 255
        val total = payload[2].toInt() and 255
        val count = payload[3].toInt() and 255
        if (selector !in 0..1 || total !in 1..MAX_PAGES || index >= total ||
            payload.size != 4 + count * recordSize) return null
        return Page(selector, index, total, (0 until count).map {
            payload.copyOfRange(4 + it * recordSize, 4 + (it + 1) * recordSize)
        })
    }

    private fun timestamp(bytes: ByteArray, offset: Int): Long =
        BLETypeConversions.toUint32(bytes, offset).toLong() and 0xffffffffL

    @JvmStatic fun buildReport(rawRecords: List<ByteArray>, rawSessions: List<ByteArray>): Report? {
        if (rawRecords.any { it.size != 7 } || rawSessions.any { it.size != 14 }) return null
        val records = rawRecords.map {
            Record(timestamp(it, 0), it[4].toInt() and 255, it[5].toInt() and 255, it[6].toInt() and 255)
        }
        // Unix seconds must remain representable by the activity API, which uses signed int seconds.
        if (records.any { it.timestamp !in 1..Int.MAX_VALUE.toLong() }) return null
        if (records.groupBy { it.timestamp }.any { it.value.distinct().size > 1 }) return null
        val sorted = records.distinct().sortedBy { it.timestamp }
        val sessions = rawSessions.map {
            Session(timestamp(it, 0), timestamp(it, 4), it[8].toInt() and 255, it[9].toInt() and 255,
                it[10].toInt() and 255, BLETypeConversions.toUint16(it, 11), it[13].toInt() and 255)
        }
        if (sessions.any { it.start < 1 || it.end > Int.MAX_VALUE || it.end <= it.start ||
                it.end - it.start > MAX_SESSION_SECONDS || it.score > 100 }) return null
        val latest = sessions.groupBy { it.start }.values.map { versions -> versions.maxBy { it.end } }
            .sortedBy { it.start }
        // Independently measured summaries may differ slightly in their boundaries. Merge only
        // same-type versions covering exactly the same nonterminal stage records; empty histories
        // provide no evidence of identity. Keep the endpoint that also includes the terminal marker.
        val canonical = mutableListOf<Session>()
        for (session in latest) {
            val points = sorted.filter { it.stage != 4 && it.timestamp in session.start..session.end }
            val mirror = if (points.isEmpty()) -1 else canonical.indexOfFirst { other ->
                other.type == session.type &&
                    sorted.filter { it.stage != 4 && it.timestamp in other.start..other.end } == points
            }
            if (mirror < 0) canonical.add(session)
            else if (session.end > canonical[mirror].end) canonical[mirror] = session
        }
        val intervals = canonical.flatMap { session ->
            val boundedPoints = sorted.filter { it.timestamp in session.start..session.end }
            // Summary and stage endpoints are independently measured. A terminal marker may
            // follow the summary, but never skip another stage or borrow across a session.
            val following = sorted.firstOrNull { it.timestamp > session.end }
            val points = if (boundedPoints.isNotEmpty() && boundedPoints.last().stage != 4 &&
                following?.stage == 4 && following.timestamp - session.start <= MAX_SESSION_SECONDS &&
                canonical.none { other -> other != session &&
                    other.start <= following.timestamp && other.end >= session.end }) {
                boundedPoints + following
            } else boundedPoints
            // The last sleep transition precedes the terminal stage-4 record. Its timestamp is
            // the sleep endpoint; the short tail leading to the terminal record is not sleep.
            val ambiguous = canonical.any { other -> other != session &&
                points.any { it.timestamp in other.start..other.end } }
            if (ambiguous || points.size < 3 || points.last().stage != 4) emptyList()
            else points.dropLast(1).zipWithNext().map { (a, b) ->
                Interval(a.timestamp, b.timestamp, a.stage, a.position)
            }
        }
        return Report(sorted, canonical.sortedBy { it.start }, intervals)
    }
}

class SoundcoreSleepHistoryEvent(val report: SoundcoreSleepHistory.Report?) : GBDeviceEvent() {
    override fun evaluate(context: android.content.Context, device: nodomain.freeyourgadget.gadgetbridge.impl.GBDevice) {
        // Consumed by model support, which owns fetch completion and timeout cancellation.
    }
}
