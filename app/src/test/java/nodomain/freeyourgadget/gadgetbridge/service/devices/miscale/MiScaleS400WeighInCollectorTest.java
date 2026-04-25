/*  Copyright (C) 2026 oddballza

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.miscale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.service.devices.miscale.MiScaleS400Decryptor.Measurement;
import nodomain.freeyourgadget.gadgetbridge.service.devices.miscale.MiScaleS400WeighInCollector.Ignored;
import nodomain.freeyourgadget.gadgetbridge.service.devices.miscale.MiScaleS400WeighInCollector.Result;

/**
 * The weigh-in state machine, driven with synthetic frames. Values are made up; they are not
 * anyone's readings.
 */
public class MiScaleS400WeighInCollectorTest {
    private static final long TS = 1_800_000_000L;             // a scale timestamp, seconds
    private static final long NOW = TS * 1000L + 2_000;        // 2 s after it, in ms
    private static final int ANY_PROFILE = 0;

    private final MiScaleS400WeighInCollector collector = new MiScaleS400WeighInCollector();

    // -- frame builders

    private static Measurement weightOnly(final long ts) {
        return new Measurement(1, ts, 70.0f, null, null, null);
    }

    private static Measurement weightWithImpedance(final long ts) {
        return new Measurement(1, ts, 70.0f, null, 500.0f, null);
    }

    private static Measurement weightComplete(final long ts) {
        return new Measurement(1, ts, 70.0f, 80, 500.0f, null);
    }

    private static Measurement second(final long ts) {
        return new Measurement(1, ts, null, null, null, 460.0f);
    }

    private static Measurement idle(final long ts) {
        return new Measurement(1, ts, null, null, null, null);
    }

    private static Measurement otherProfile(final long ts) {
        return new Measurement(2, ts, 90.0f, 70, 400.0f, null);
    }

    // -- storing

    @Test
    public void theWeightIsStoredAsSoonAsItArrives() {
        final Result r = collector.offer(weightOnly(TS), NOW, ANY_PROFILE);

        assertEquals(Ignored.NONE, r.ignored);
        assertEquals(1, r.stores.size());
        assertEquals(70.0f, r.stores.get(0).weight.getWeightKg(), 0.001f);
        assertNull(r.stores.get(0).secondImpedance);
        assertTrue("the other frame may still come", r.armSettle);
        assertFalse(r.complete);
        assertFalse(r.finished);
    }

    @Test
    public void theSampleKeepsTheScaleClockWhenItIsClose() {
        final Result r = collector.offer(weightOnly(TS), NOW, ANY_PROFILE);

        assertEquals(TS * 1000L, r.stores.get(0).timestampMs);
    }

    @Test
    public void theSampleUsesNowWhenTheScaleClockIsFarOff() {
        final long farOff = NOW + MiScaleS400WeighInCollector.SCALE_CLOCK_TOLERANCE_MS + 1;
        final Result r = collector.offer(weightOnly(TS), farOff, ANY_PROFILE);

        assertEquals(farOff, r.stores.get(0).timestampMs);
    }

    @Test
    public void theSecondFrameCompletesTheRecordUnderTheSameTimestamp() {
        final Result first = collector.offer(weightComplete(TS), NOW, ANY_PROFILE);
        final Result then = collector.offer(second(TS), NOW + 1_200, ANY_PROFILE);

        assertTrue(then.complete);
        assertTrue(then.finished);
        assertFalse("nothing more is coming", then.armSettle);
        assertEquals(1, then.stores.size());
        assertEquals("completing replaces, it does not add", first.stores.get(0).timestampMs, then.stores.get(0).timestampMs);
        assertNotNull(then.stores.get(0).secondImpedance);
        assertEquals(460.0f, then.stores.get(0).secondImpedance.getSecondImpedanceOhm(), 0.001f);
        assertEquals(80, (int) then.stores.get(0).weight.getHeartRate());
    }

    @Test
    public void theFramesMayArriveInEitherOrder() {
        final Result first = collector.offer(second(TS), NOW, ANY_PROFILE);
        final Result then = collector.offer(weightComplete(TS), NOW + 900, ANY_PROFILE);

        assertTrue("an impedance alone is stored by nothing", first.stores.isEmpty());
        assertTrue(first.armSettle);
        assertTrue(then.complete);
        // the second frame was already in, so the one store carries everything
        assertEquals(1, then.stores.size());
        assertNotNull(then.stores.get(0).secondImpedance);
        assertEquals(80, (int) then.stores.get(0).weight.getHeartRate());
        assertTrue("nothing beyond the early store was left to write", then.settledWithoutNews);
    }

    // -- settling

    @Test
    public void aWeightOnlyWeighInSettlesWithoutStoringAgain() {
        collector.offer(weightOnly(TS), NOW, ANY_PROFILE);

        final Result r = collector.settle(NOW + MiScaleS400WeighInCollector.SETTLE_MS);

        assertTrue(r.finished);
        assertTrue(r.settledWithoutNews);
        assertTrue(r.stores.isEmpty());
        assertFalse(r.discardedSecondOnly);
    }

    @Test
    public void theEmptyFrameAfterAWeightOnlyWeighInIsIgnored() {
        // The scale gave up on the body composition: it sends the weight, then a frame of the same
        // weigh-in with no values at all, then goes quiet
        final Result weight = collector.offer(weightOnly(TS), NOW, ANY_PROFILE);
        final Result empty = collector.offer(idle(TS), NOW + 1_000, ANY_PROFILE);
        final long pendingAfterEmpty = collector.getPendingTimestamp();
        final Result settled = collector.settle(NOW + 1_000 + MiScaleS400WeighInCollector.SETTLE_MS);

        assertEquals(1, weight.stores.size());
        assertEquals(70.0f, weight.stores.get(0).weight.getWeightKg(), 0.001f);
        assertEquals(Ignored.IDLE, empty.ignored);
        assertTrue(empty.stores.isEmpty());
        assertFalse(empty.finished);
        assertEquals("the empty frame does not end the weigh-in", TS, pendingAfterEmpty);
        assertTrue(settled.finished);
        assertTrue(settled.settledWithoutNews);
        assertTrue("the weight is stored once", settled.stores.isEmpty());
    }

    @Test
    public void settlingStoresAgainWhenTheHeartRateNeverCame() {
        collector.offer(weightWithImpedance(TS), NOW, ANY_PROFILE);
        final Result mid = collector.offer(second(TS), NOW + 1_000, ANY_PROFILE);
        assertFalse("without the heart rate the weigh-in is not complete", mid.complete);
        assertTrue(mid.stores.isEmpty());

        final Result r = collector.settle(NOW + MiScaleS400WeighInCollector.SETTLE_MS);

        assertEquals(1, r.stores.size());
        assertNotNull(r.stores.get(0).secondImpedance);
        assertFalse(r.settledWithoutNews);
    }

    @Test
    public void aLoneSecondImpedanceIsDiscardedAtSettle() {
        collector.offer(second(TS), NOW, ANY_PROFILE);

        final Result r = collector.settle(NOW + MiScaleS400WeighInCollector.SETTLE_MS);

        assertTrue(r.finished);
        assertTrue(r.discardedSecondOnly);
        assertTrue(r.stores.isEmpty());
    }

    @Test
    public void aSettleWithNothingPendingChangesNothing() {
        collector.offer(weightComplete(TS), NOW, ANY_PROFILE);
        collector.offer(second(TS), NOW + 1_000, ANY_PROFILE);          // complete

        final Result stale = collector.settle(NOW + MiScaleS400WeighInCollector.SETTLE_MS);   // a timer left over
        final Result late = collector.offer(second(TS), NOW + 20_000, ANY_PROFILE);          // a late repeat

        assertFalse(stale.finished);
        assertTrue(stale.stores.isEmpty());
        assertEquals("the finished weigh-in must still be remembered", Ignored.FINISHED_REPEAT, late.ignored);
    }

    // -- repeats and phantoms

    @Test
    public void repeatsOfAFinishedWeighInAreIgnored() {
        collector.offer(weightComplete(TS), NOW, ANY_PROFILE);
        collector.offer(second(TS), NOW + 1_000, ANY_PROFILE);

        final Result repeat = collector.offer(second(TS), NOW + 1_020, ANY_PROFILE);

        assertEquals(Ignored.FINISHED_REPEAT, repeat.ignored);
        assertTrue(repeat.stores.isEmpty());
        assertFalse(repeat.armSettle);
        assertEquals(0, collector.getPendingTimestamp());
    }

    @Test
    public void idleFramesAreIgnored() {
        final Result r = collector.offer(idle(TS), NOW, ANY_PROFILE);

        assertEquals(Ignored.IDLE, r.ignored);
        assertTrue(r.stores.isEmpty());
        assertFalse(r.armSettle);
    }

    @Test
    public void aRepeatedWeightFrameDoesNotStoreTwice() {
        final Result first = collector.offer(weightOnly(TS), NOW, ANY_PROFILE);
        final Result again = collector.offer(weightOnly(TS), NOW + 500, ANY_PROFILE);

        assertEquals(1, first.stores.size());
        assertTrue(again.stores.isEmpty());
        assertTrue(again.armSettle);
    }

    // -- a new weigh-in before the old one settled

    @Test
    public void aNewWeighInFinishesThePendingOneFirst() {
        collector.offer(weightWithImpedance(TS), NOW, ANY_PROFILE);
        collector.offer(second(TS), NOW + 1_000, ANY_PROFILE);          // pending, no heart rate yet

        final Result r = collector.offer(weightOnly(TS + 30), NOW + 30_000, ANY_PROFILE);

        assertTrue(r.supersededPending);
        assertEquals("the old weigh-in's completing store, then the new weight", 2, r.stores.size());
        assertEquals(TS, r.stores.get(0).weight.getTimestamp());
        assertNotNull(r.stores.get(0).secondImpedance);
        assertEquals(TS + 30, r.stores.get(1).weight.getTimestamp());
        assertEquals(TS + 30, collector.getPendingTimestamp());
    }

    @Test
    public void aSupersededWeighInWithNothingNewIsNotStoredAgain() {
        collector.offer(weightOnly(TS), NOW, ANY_PROFILE);

        final Result r = collector.offer(weightOnly(TS + 30), NOW + 30_000, ANY_PROFILE);

        assertTrue(r.supersededPending);
        assertTrue(r.settledWithoutNews);
        assertEquals("only the new weight is stored", 1, r.stores.size());
        assertEquals(TS + 30, r.stores.get(0).weight.getTimestamp());
    }

    // -- profiles

    @Test
    public void anotherProfileIsIgnoredAndAnnouncedOncePerWeighIn() {
        final Result first = collector.offer(otherProfile(TS), NOW, 1);
        final Result again = collector.offer(otherProfile(TS), NOW + 500, 1);
        final Result next = collector.offer(otherProfile(TS + 60), NOW + 60_000, 1);

        assertEquals(Ignored.OTHER_PROFILE, first.ignored);
        assertTrue(first.announceIgnoredProfile);
        assertTrue(first.stores.isEmpty());
        assertFalse(first.armSettle);
        assertEquals(Ignored.OTHER_PROFILE, again.ignored);
        assertFalse("the same weigh-in is announced once", again.announceIgnoredProfile);
        assertTrue("a new weigh-in is announced again", next.announceIgnoredProfile);
    }

    @Test
    public void theWantedProfileIsKeptAndZeroKeepsEveryone() {
        final Result wanted = collector.offer(weightOnly(TS), NOW, 1);
        assertEquals(Ignored.NONE, wanted.ignored);

        final MiScaleS400WeighInCollector other = new MiScaleS400WeighInCollector();
        final Result everyone = other.offer(otherProfile(TS), NOW, ANY_PROFILE);
        assertEquals(Ignored.NONE, everyone.ignored);
        assertEquals(1, everyone.stores.size());
    }

    // -- reset

    @Test
    public void resetForgetsEverything() {
        collector.offer(weightComplete(TS), NOW, ANY_PROFILE);
        collector.offer(second(TS), NOW + 1_000, ANY_PROFILE);          // finished: TS is remembered
        collector.reset();

        final Result r = collector.offer(weightOnly(TS), NOW + 5_000, ANY_PROFILE);

        assertEquals("after a reset nothing is a repeat", Ignored.NONE, r.ignored);
        assertEquals(1, r.stores.size());
    }

    @Test
    public void storesCarryTheFramesTheyWereMadeFrom() {
        final Measurement w = weightComplete(TS);
        final Measurement s = second(TS);
        collector.offer(w, NOW, ANY_PROFILE);
        final Result r = collector.offer(s, NOW + 1_000, ANY_PROFILE);

        assertSame(w, r.stores.get(0).weight);
        assertSame(s, r.stores.get(0).secondImpedance);
    }
}
