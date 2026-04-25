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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.miscale.MiScaleS400Decryptor.Measurement;

/**
 * Collects the frames of one S400 weigh-in and decides what to store, and when. Plain Java on
 * purpose: no Android, no database, no timers. The device support feeds it every decoded frame
 * and the settle tick, and acts on the {@link Result} it gets back.
 * <p>
 * A weigh-in arrives as up to two frames sharing the scale's timestamp: the weight frame,
 * which carries the weight, the first impedance and (last of all) the heart rate, and the
 * second-impedance frame. Both are repeated for a while. The weight is stored as soon as it is
 * in, under a timestamp the weigh-in then keeps, so the frames that follow complete the record
 * by replacing its rows rather than adding a second weigh-in.
 */
public final class MiScaleS400WeighInCollector {
    /** Time to keep listening for further frames of the same weigh-in (heart rate arrives late). */
    public static final long SETTLE_MS = 15_000;
    /** Accept the scale's own timestamp for the sample when it is within this window of now. */
    public static final long SCALE_CLOCK_TOLERANCE_MS = 24 * 60 * 60 * 1000L;

    /** Why a frame was not taken into a weigh-in. */
    public enum Ignored {
        NONE,
        /** The frame carries no measurement (scale idle / standing on it). */
        IDLE,
        /** A repeat of a weigh-in already finished; the scale keeps re-sending its last frames. */
        FINISHED_REPEAT,
        /** The scale assigned the weigh-in to a profile this phone is not set to store. */
        OTHER_PROFILE,
    }

    /** One write to make: the weight frame, the second-impedance frame if in, and the sample time. */
    public static final class Store {
        public final Measurement weight;
        @Nullable
        public final Measurement secondImpedance;
        /** The sample's timestamp in ms; the same for every store of one weigh-in. */
        public final long timestampMs;

        Store(final Measurement weight, @Nullable final Measurement secondImpedance, final long timestampMs) {
            this.weight = weight;
            this.secondImpedance = secondImpedance;
            this.timestampMs = timestampMs;
        }
    }

    /** What the support has to do after offering a frame or the settle tick. */
    public static final class Result {
        /** Writes to make, in order: a superseded weigh-in's last store comes before the new one's. */
        public final List<Store> stores;
        public final Ignored ignored;
        /** First frame of a weigh-in ignored for its profile: worth telling the user once. */
        public final boolean announceIgnoredProfile;
        /** A weigh-in that had not settled was finished because a new one started. */
        public final boolean supersededPending;
        /** Both frames are in, with the heart rate: nothing more is coming, stop waiting. */
        public final boolean complete;
        /** A weigh-in ended; whatever the caller keeps per weigh-in can go. */
        public final boolean finished;
        /** The settle tick found nothing beyond what was already stored. */
        public final boolean settledWithoutNews;
        /** The settle tick found only the second-impedance frame, which alone is useless. */
        public final boolean discardedSecondOnly;
        /** (Re)start the settle timer: the other frame may still come. */
        public final boolean armSettle;

        private Result(final Draft d) {
            this.stores = Collections.unmodifiableList(new ArrayList<>(d.stores));
            this.ignored = d.ignored;
            this.announceIgnoredProfile = d.announceIgnoredProfile;
            this.supersededPending = d.supersededPending;
            this.complete = d.complete;
            this.finished = d.finished;
            this.settledWithoutNews = d.settledWithoutNews;
            this.discardedSecondOnly = d.discardedSecondOnly;
            this.armSettle = d.armSettle;
        }
    }

    private static final class Draft {
        final List<Store> stores = new ArrayList<>(2);
        Ignored ignored = Ignored.NONE;
        boolean announceIgnoredProfile;
        boolean supersededPending;
        boolean complete;
        boolean finished;
        boolean settledWithoutNews;
        boolean discardedSecondOnly;
        boolean armSettle;
    }

    // Frames of the weigh-in currently being collected, paired by the scale's timestamp
    private long pendingTimestamp = 0;
    private Measurement pendingWeight;
    private Measurement pendingSecondImpedance;
    // What has been stored for the pending weigh-in so far: the timestamp its rows carry (0 while
    // nothing is stored yet) and which of the late values were already in
    private long storedTimestamp = 0;
    private boolean storedHeartRate;
    private boolean storedImpedance;
    private boolean storedSecondImpedance;
    // The last weigh-in that was ignored for belonging to another profile, so it is announced once
    private long ignoredTimestamp = 0;
    // The last weigh-in that was finished, so the frames the scale keeps repeating for it after
    // the record is complete do not start a phantom weigh-in that then "only delivered" one frame
    private long finishedTimestamp = 0;

    /**
     * Offers one decoded frame.
     *
     * @param measurement   the frame, idle or not
     * @param nowMs         the current time, for the sample timestamp
     * @param wantedProfile the scale profile whose weigh-ins to keep, or 0 for all
     */
    @NonNull
    public Result offer(@NonNull final Measurement measurement, final long nowMs, final int wantedProfile) {
        final Draft d = new Draft();
        if (measurement.isIdle()) {
            d.ignored = Ignored.IDLE;
            return new Result(d);
        }
        if (measurement.getTimestamp() == finishedTimestamp) {
            d.ignored = Ignored.FINISHED_REPEAT;
            return new Result(d);
        }
        if (wantedProfile > 0 && measurement.getProfile() != wantedProfile) {
            d.ignored = Ignored.OTHER_PROFILE;
            if (measurement.getTimestamp() != ignoredTimestamp) {
                ignoredTimestamp = measurement.getTimestamp();
                d.announceIgnoredProfile = true;
            }
            return new Result(d);
        }

        if (measurement.getTimestamp() != pendingTimestamp) {
            if (pendingWeight != null) {
                // A new weigh-in started before the previous one settled
                d.supersededPending = true;
                finish(d, nowMs);
            }
            pendingTimestamp = measurement.getTimestamp();
        }

        if (measurement.isSecondImpedanceFrame()) {
            pendingSecondImpedance = measurement;
        } else {
            pendingWeight = measurement;
        }

        if (pendingWeight != null && storedTimestamp == 0) {
            // Store the weight as soon as it is in, so a weigh-in shows up at once rather than
            // after the settle window: for a weight-only weigh-in nothing else is coming, and
            // the frames the scale sends later complete the record under the same timestamp.
            d.stores.add(store(nowMs));
        }

        if (pendingWeight != null && pendingWeight.getHeartRate() != null && pendingSecondImpedance != null) {
            // Both frames of the weigh-in are in: the weight frame with the heart rate (the
            // last value the scale measures) and the frame with the second impedance reading.
            // Nothing more is coming, so finish now instead of waiting for the broadcasts to stop.
            d.complete = true;
            finish(d, nowMs);
        } else {
            // Otherwise wait for the scale to go quiet, in case the other frame is still to come
            d.armSettle = true;
        }
        return new Result(d);
    }

    /** The settle timer fired: the scale went quiet after the last frame. */
    @NonNull
    public Result settle(final long nowMs) {
        final Draft d = new Draft();
        if (pendingWeight == null && pendingSecondImpedance == null) {
            // A timer left over from before the weigh-in completed: nothing to do, and above all
            // nothing to forget about the weigh-in just finished
            return new Result(d);
        }
        finish(d, nowMs);
        return new Result(d);
    }

    /** Forget everything, e.g. when the support is disposed. */
    public void reset() {
        discardPending();
        ignoredTimestamp = 0;
        finishedTimestamp = 0;
    }

    /** The scale timestamp of the weigh-in being collected, or 0. */
    public long getPendingTimestamp() {
        return pendingTimestamp;
    }

    private void finish(final Draft d, final long nowMs) {
        finishedTimestamp = pendingTimestamp;
        d.finished = true;
        if (pendingWeight != null) {
            if (storedTimestamp == 0 || addsToStored()) {
                d.stores.add(store(nowMs));
            } else {
                d.settledWithoutNews = true;
            }
        } else if (pendingSecondImpedance != null) {
            // An impedance without the weight it was measured with cannot be turned into anything,
            // so it is not kept, even though the impedance table could hold it on its own.
            d.discardedSecondOnly = true;
        }
        discardPending();
    }

    /** Whether the frames collected since the last store carry a value the stored rows lack. */
    private boolean addsToStored() {
        return (!storedHeartRate && pendingWeight.getHeartRate() != null)
                || (!storedImpedance && pendingWeight.getImpedanceOhm() != null)
                || (!storedSecondImpedance && pendingSecondImpedance != null && pendingSecondImpedance.getSecondImpedanceOhm() != null);
    }

    private Store store(final long nowMs) {
        final long scaleTime = pendingWeight.getTimestamp() * 1000L;
        // A weigh-in stored early keeps its timestamp, so completing it replaces the rows
        // rather than adding a second weigh-in
        final long timestamp = storedTimestamp != 0 ? storedTimestamp
                : Math.abs(nowMs - scaleTime) <= SCALE_CLOCK_TOLERANCE_MS ? scaleTime : nowMs;
        storedTimestamp = timestamp;
        storedHeartRate = pendingWeight.getHeartRate() != null;
        storedImpedance = pendingWeight.getImpedanceOhm() != null;
        storedSecondImpedance = pendingSecondImpedance != null && pendingSecondImpedance.getSecondImpedanceOhm() != null;
        return new Store(pendingWeight, pendingSecondImpedance, timestamp);
    }

    private void discardPending() {
        pendingTimestamp = 0;
        pendingWeight = null;
        pendingSecondImpedance = null;
        storedTimestamp = 0;
        storedHeartRate = false;
        storedImpedance = false;
        storedSecondImpedance = false;
    }
}
