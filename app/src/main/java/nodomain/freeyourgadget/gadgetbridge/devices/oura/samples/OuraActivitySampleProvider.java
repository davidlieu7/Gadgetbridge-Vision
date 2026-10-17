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
package nodomain.freeyourgadget.gadgetbridge.devices.oura.samples;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.OuraEstimatedSleepSessionProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class OuraActivitySampleProvider extends AbstractSampleProvider<OuraActivitySample> {
    /** Raw intensity is MET in tenths; at rest it is one MET. */
    private static final int REST_MET_TENTHS = 10;
    /** Tenths above rest that show as full intensity. */
    private static final float FULL_INTENSITY_MET_TENTHS = 80f;

    public OuraActivitySampleProvider(final GBDevice device, final DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<OuraActivitySample, ?> getSampleDao() {
        return getSession().getOuraActivitySampleDao();
    }

    @Nullable
    @Override
    protected Property getRawKindSampleProperty() {
        return OuraActivitySampleDao.Properties.RawKind;
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return OuraActivitySampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return OuraActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public ActivityKind normalizeType(final int rawType) {
        return ActivityKind.fromCode(rawType);
    }

    @Override
    public int toRawActivityKind(final ActivityKind activityKind) {
        return activityKind.getCode();
    }

    @Override
    public float normalizeIntensity(final int rawIntensity) {
        if (rawIntensity <= REST_MET_TENTHS) {
            return 0f;
        }
        return Math.min((rawIntensity - REST_MET_TENTHS) / FULL_INTENSITY_MET_TENTHS, 1f);
    }

    @Override
    public OuraActivitySample createActivitySample() {
        return new OuraActivitySample();
    }

    @Override
    protected List<OuraActivitySample> getGBActivitySamples(final int timestamp_from, final int timestamp_to) {
        final List<OuraActivitySample> samples = getDeviceSamples(timestamp_from, timestamp_to);
        overlaySleep(new OuraEstimatedSleepSessionProvider(getDevice(), getSession()), samples, timestamp_from, timestamp_to, true);
        return samples;
    }

    /**
     * The samples as stored, without the overlay of estimated sleep.
     */
    public List<OuraActivitySample> getDeviceSamples(final int timestamp_from, final int timestamp_to) {
        return super.getGBActivitySamples(timestamp_from, timestamp_to);
    }
}
