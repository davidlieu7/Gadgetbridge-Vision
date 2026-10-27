package nodomain.freeyourgadget.gadgetbridge.util.cgm;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.entities.GlucoseSample;

public class WatchDripPayloadTest {
    private static final long NOW = 1793088833095L;
    private static final long LATEST_TIME = 1793088827259L;

    // Stored readings, oldest first; the last one is the reading just broadcast
    private static final List<GlucoseSample> SAMPLES = Arrays.asList(
            sample(1793070000000L, 100),
            sample(1793088227259L, 65),
            sample(1793088527259L, 180),
            sample(LATEST_TIME, 153));
    private static final CgmSnapshot.Reading LATEST = new CgmSnapshot.Reading(LATEST_TIME, 153, 4.2, "SingleUp");

    private static GlucoseSample sample(final long timestamp, final double mgdl) {
        final GlucoseSample s = new GlucoseSample();
        s.setTimestamp(timestamp);
        s.setValueMgDl(mgdl);
        return s;
    }

    private static CgmSnapshot snapshot(final boolean mgdl) {
        return CgmSnapshot.fromSamples(SAMPLES, LATEST, mgdl, mgdl ? 70 : 72, mgdl ? 170 : 180);
    }

    @Test
    public void snapshotIsNewestFirstWithBroadcastDetailsOnTheLatest() {
        final CgmSnapshot s = snapshot(true);
        Assert.assertEquals(4, s.readings.size());
        Assert.assertSame(LATEST, s.latest());
        Assert.assertEquals(180, s.readings.get(1).mgdl);
        Assert.assertTrue(Double.isNaN(s.readings.get(1).deltaMgdl));
        Assert.assertEquals("", s.readings.get(1).direction);
        Assert.assertEquals(100, s.readings.get(3).mgdl);
    }

    @Test
    public void latestStoredSampleWithoutBroadcastHasNoTrend() {
        final CgmSnapshot s = CgmSnapshot.fromSamples(SAMPLES, null, true, 70, 170);
        Assert.assertEquals(LATEST_TIME, s.latest().timestamp);
        Assert.assertEquals("", s.latest().direction);
    }

    @Test
    public void latestReadingInMgdl() throws Exception {
        final JSONObject bg = new JSONObject(WatchDripPayload.build(snapshot(true), NOW, 4, 80)).getJSONObject("bg");
        Assert.assertEquals("153", bg.getString("val"));
        Assert.assertEquals("+4", bg.getString("delta"));
        Assert.assertEquals("SingleUp", bg.getString("trend"));
        Assert.assertEquals(LATEST_TIME, bg.getLong("time"));
        Assert.assertFalse(bg.getBoolean("isHigh"));
        Assert.assertFalse(bg.getBoolean("isLow"));
        Assert.assertFalse(bg.getBoolean("isStale"));
    }

    @Test
    public void latestReadingInMmol() throws Exception {
        final JSONObject payload = new JSONObject(WatchDripPayload.build(snapshot(false), NOW, 4, 80));
        Assert.assertEquals("8.5", payload.getJSONObject("bg").getString("val"));
        Assert.assertEquals("+0.2", payload.getJSONObject("bg").getString("delta"));
        Assert.assertFalse(payload.getJSONObject("status").getBoolean("isMgdl"));
    }

    @Test
    public void unknownDeltaIsEmpty() throws Exception {
        final CgmSnapshot s = CgmSnapshot.fromSamples(SAMPLES, null, true, 70, 170);
        Assert.assertEquals("", new JSONObject(WatchDripPayload.build(s, NOW, 4, 80)).getJSONObject("bg").getString("delta"));
    }

    @Test
    public void tellsBandAppWhenReadingGoesStale() throws Exception {
        // The CGM band app marks the reading stale by itself when no newer one arrives
        final JSONObject status = new JSONObject(WatchDripPayload.build(snapshot(true), NOW, 4, 80)).getJSONObject("status");
        Assert.assertEquals(WatchDripPayload.STALE_AFTER_MS, status.getLong("staleAfter"));
    }

    @Test
    public void staleWhenLatestIsOld() throws Exception {
        final long later = NOW + WatchDripPayload.STALE_AFTER_MS + 1;
        Assert.assertTrue(new JSONObject(WatchDripPayload.build(snapshot(true), later, 4, 80))
                .getJSONObject("bg").getBoolean("isStale"));
    }

    @Test
    public void graphSplitsByRangeAndDropsOldPoints() throws Exception {
        final JSONObject graph = new JSONObject(WatchDripPayload.build(snapshot(true), NOW, 4, 80)).getJSONObject("graph");
        Assert.assertEquals(WatchDripPayload.GRAPH_TIME_UNIT_MS, graph.getInt("fuzzer"));
        Assert.assertEquals(NOW / WatchDripPayload.GRAPH_TIME_UNIT_MS, graph.getLong("end"));
        Assert.assertEquals((NOW - 4 * 3600_000L) / WatchDripPayload.GRAPH_TIME_UNIT_MS, graph.getLong("start"));

        final JSONArray lines = graph.getJSONArray("lines");
        Assert.assertEquals("[[59769627,153]]", pointsOf(lines, "inRange"));
        Assert.assertEquals("[[59769617,180]]", pointsOf(lines, "high"));
        Assert.assertEquals("[[59769607,65]]", pointsOf(lines, "low"));
        Assert.assertEquals("[[" + graph.getLong("start") + ",70],[" + graph.getLong("end") + ",70]]", pointsOf(lines, "lineLow"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void noReadings() throws Exception {
        WatchDripPayload.build(CgmSnapshot.fromSamples(Collections.emptyList(), null, true, 70, 170), NOW, 4, 80);
    }

    private static String pointsOf(final JSONArray lines, final String name) throws Exception {
        for (int i = 0; i < lines.length(); i++) {
            if (name.equals(lines.getJSONObject(i).getString("name"))) {
                return lines.getJSONObject(i).getJSONArray("points").toString();
            }
        }
        throw new AssertionError("no line " + name);
    }
}
