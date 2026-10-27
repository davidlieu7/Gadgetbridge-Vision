/*  Copyright (C) 2026 ChrisB85

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
package nodomain.freeyourgadget.gadgetbridge.util.cgm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Message understood by the WatchDrip+ band apps and watchfaces (Xiaomi Vela), as sent by the WatchDrip+ phone app,
 * and by the Gadgetbridge CGM band app.
 * The field names, line names and the graph time unit are a contract with those band apps.
 */
public final class WatchDripPayload {
    /** Graph x values are timestamps divided by this, in ms. */
    static final int GRAPH_TIME_UNIT_MS = 30000;
    /** A reading older than this is shown as stale. */
    static final long STALE_AFTER_MS = 11 * 60 * 1000;

    private static final double MMOL_TO_MGDL = 18.0182;
    private static final String COLOR_IN_RANGE = "0x33B5E6";
    private static final String COLOR_HIGH = "0xFFBB33";
    private static final String COLOR_LOW = "0xC30909";

    private WatchDripPayload() {
    }

    /**
     * @param graphHours   length of the graph window ending now
     * @param phoneBattery phone battery in percent, shown on some watchfaces
     */
    public static String build(final CgmSnapshot snapshot, final long now, final double graphHours, final int phoneBattery)
            throws JSONException {
        final CgmSnapshot.Reading latest = snapshot.latest();
        if (latest == null) {
            throw new IllegalArgumentException("no readings");
        }

        final JSONObject bg = new JSONObject()
                .put("val", formatValue(latest.mgdl, snapshot.mgdlUnits))
                .put("delta", formatDelta(latest.deltaMgdl, snapshot.mgdlUnits))
                .put("trend", latest.direction)
                .put("time", latest.timestamp)
                .put("isHigh", latest.mgdl >= snapshot.highMgdl)
                .put("isLow", latest.mgdl <= snapshot.lowMgdl)
                .put("isStale", now - latest.timestamp > STALE_AFTER_MS);

        final long start = now - (long) (graphHours * 3600_000L);
        final JSONArray inRange = new JSONArray();
        final JSONArray high = new JSONArray();
        final JSONArray low = new JSONArray();
        for (final CgmSnapshot.Reading reading : snapshot.readings) {
            if (reading.timestamp < start) {
                continue;
            }
            final JSONArray point = new JSONArray().put(reading.timestamp / GRAPH_TIME_UNIT_MS).put(reading.mgdl);
            if (reading.mgdl >= snapshot.highMgdl) {
                high.put(point);
            } else if (reading.mgdl <= snapshot.lowMgdl) {
                low.put(point);
            } else {
                inRange.put(point);
            }
        }
        final long graphStart = start / GRAPH_TIME_UNIT_MS;
        final long graphEnd = now / GRAPH_TIME_UNIT_MS;
        final JSONArray lines = new JSONArray()
                .put(line("inRange", COLOR_IN_RANGE, inRange))
                .put(line("high", COLOR_HIGH, high))
                .put(line("low", COLOR_LOW, low))
                .put(line("lineLow", COLOR_LOW, threshold(graphStart, graphEnd, snapshot.lowMgdl)))
                .put(line("lineHigh", COLOR_HIGH, threshold(graphStart, graphEnd, snapshot.highMgdl)));
        final JSONObject graph = new JSONObject()
                .put("start", graphStart)
                .put("end", graphEnd)
                .put("fuzzer", GRAPH_TIME_UNIT_MS)
                .put("lines", lines);

        return new JSONObject()
                .put("bg", bg)
                .put("graph", graph)
                .put("pump", new JSONObject().put("bat", -1.0).put("iob", -1.0).put("reservoir", -1.0))
                // staleAfter is not sent by WatchDrip+; the Gadgetbridge CGM band app uses it
                .put("status", new JSONObject().put("bat", phoneBattery).put("isMgdl", snapshot.mgdlUnits).put("now", now)
                        .put("staleAfter", STALE_AFTER_MS))
                .put("treatment", new JSONObject())
                .toString();
    }

    static String formatValue(final int mgdl, final boolean mgdlUnits) {
        return mgdlUnits ? Integer.toString(mgdl) : String.format(Locale.ROOT, "%.1f", mgdl / MMOL_TO_MGDL);
    }

    static String formatDelta(final double deltaMgdl, final boolean mgdlUnits) {
        if (Double.isNaN(deltaMgdl)) {
            return "";
        }
        return mgdlUnits
                ? String.format(Locale.ROOT, "%+d", Math.round(deltaMgdl))
                : String.format(Locale.ROOT, "%+.1f", deltaMgdl / MMOL_TO_MGDL);
    }

    private static JSONObject line(final String name, final String color, final JSONArray points) throws JSONException {
        return new JSONObject().put("name", name).put("color", color).put("points", points);
    }

    private static JSONArray threshold(final long start, final long end, final double mgdl) throws JSONException {
        final long value = Math.round(mgdl);
        return new JSONArray()
                .put(new JSONArray().put(start).put(value))
                .put(new JSONArray().put(end).put(value));
    }
}
