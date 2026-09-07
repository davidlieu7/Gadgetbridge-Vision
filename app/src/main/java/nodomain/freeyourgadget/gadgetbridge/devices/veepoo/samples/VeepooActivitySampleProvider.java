/*  Copyright (C) 2025 Gadgetbridge contributors

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.veepoo.samples;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

/**
 * Activity sample provider for the VeePoo G Band (KR96 Pro).
 *
 * Samples are stored per minute. Awake 5-minute records are stored as a single row at the
 * window start; sleep records are expanded to one row per minute carrying the sleep stage in
 * {@code rawKind} so the sleep chart (which consumes activity samples) sees an uninterrupted
 * per-minute run of sleep kinds.
 */
public class VeepooActivitySampleProvider extends AbstractSampleProvider<VeepooActivitySample> {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooActivitySampleProvider.class);

    public VeepooActivitySampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<VeepooActivitySample, ?> getSampleDao() {
        return getSession().getVeepooActivitySampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return VeepooActivitySampleDao.Properties.Timestamp;
    }

    @Nullable
    @Override
    protected Property getRawKindSampleProperty() {
        return VeepooActivitySampleDao.Properties.RawKind;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return VeepooActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public VeepooActivitySample createActivitySample() {
        return new VeepooActivitySample();
    }

    @Override
    public ActivityKind normalizeType(int rawType) {
        switch (rawType) {
            case VeepooConstants.RAW_KIND_NOT_MEASURED:
                return ActivityKind.NOT_MEASURED;
            case VeepooConstants.RAW_KIND_ACTIVITY:
                return ActivityKind.ACTIVITY;
            case VeepooConstants.RAW_KIND_DEEP_SLEEP:
                return ActivityKind.DEEP_SLEEP;
            case VeepooConstants.RAW_KIND_LIGHT_SLEEP:
                return ActivityKind.LIGHT_SLEEP;
            case VeepooConstants.RAW_KIND_REM_SLEEP:
                return ActivityKind.REM_SLEEP;
            case VeepooConstants.RAW_KIND_AWAKE_SLEEP:
                return ActivityKind.AWAKE_SLEEP;
            default:
                return ActivityKind.UNKNOWN;
        }
    }

    @Override
    public int toRawActivityKind(ActivityKind activityKind) {
        switch (activityKind) {
            case NOT_MEASURED:
                return VeepooConstants.RAW_KIND_NOT_MEASURED;
            case DEEP_SLEEP:
                return VeepooConstants.RAW_KIND_DEEP_SLEEP;
            case LIGHT_SLEEP:
                return VeepooConstants.RAW_KIND_LIGHT_SLEEP;
            case REM_SLEEP:
                return VeepooConstants.RAW_KIND_REM_SLEEP;
            case AWAKE_SLEEP:
                return VeepooConstants.RAW_KIND_AWAKE_SLEEP;
            default:
                return VeepooConstants.RAW_KIND_ACTIVITY;
        }
    }

    @Override
    public float normalizeIntensity(int rawIntensity) {
        if (rawIntensity == ActivitySample.NOT_MEASURED) {
            return Float.NEGATIVE_INFINITY;
        }
        return rawIntensity;
    }

    @Override
    protected List<VeepooActivitySample> getGBActivitySamples(final int timestamp_from, final int timestamp_to) {
        LOG.trace("Getting Veepoo activity samples between {} and {}", timestamp_from, timestamp_to);
        // Sleep minutes are stored explicitly per minute; fillGaps only bridges awake gaps with
        // NOT_MEASURED dummies, which correctly terminates sleep sessions at the window edges.
        final List<VeepooActivitySample> samples = fillGaps(
                super.getGBActivitySamples(timestamp_from, timestamp_to),
                timestamp_from,
                timestamp_to
        );
        Collections.sort(samples, (a, b) -> Integer.compare(a.getTimestamp(), b.getTimestamp()));
        return samples;
    }
}
