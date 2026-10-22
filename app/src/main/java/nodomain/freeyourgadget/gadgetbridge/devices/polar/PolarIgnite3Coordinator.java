/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.devices.polar;

import java.util.HashMap;
import java.util.Map;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericRespiratoryRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepScoreSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SleepSessionProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepScoreSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSampleDao;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.RespiratoryRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.SleepScoreSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.PolarIgnite3ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.polar.PolarIgnite3DeviceSupport;

/**
 * Polar Ignite 3 watch.
 *
 * <p>Unlike the H9/H10 chest straps it talks Polar's PFTP file protocol, see
 * {@link PolarIgnite3DeviceSupport}. The watch requires an encrypted, bonded LE link.
 */
public class PolarIgnite3Coordinator extends AbstractBLEDeviceCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        // e.g. "Polar Ignite 3 11BA3D3E", the suffix is the device id
        return Pattern.compile("^Polar Ignite 3 [0-9A-F]{8}$");
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_BOND;
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public String getManufacturer() {
        return "Polar";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return PolarIgnite3DeviceSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_polar_ignite_3;
    }

    @Override
    public boolean supportsMusicInfo(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public int getCannedRepliesSlotCount(final GBDevice device) {
        return PolarIgnite3DeviceSupport.CANNED_REPLY_SLOTS;
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(final GBDevice device) {
        return new int[]{
                R.xml.devicesettings_polar_watch_notifications,
                R.xml.devicesettings_canned_reply_16,
                R.xml.devicesettings_ak102_dnd,
                R.xml.devicesettings_polar_continuous_hr,
                R.xml.devicesettings_inactivity_noshed,
                R.xml.devicesettings_timeformat,
        };
    }

    @Override
    public boolean supportsActivityTracking(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityDistance(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActiveCalories(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsSleepMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsRemSleep(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsAwakeSleep(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsContinuousTemperature(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public SampleProvider<? extends AbstractActivitySample> getSampleProvider(final GBDevice device, final DaoSession session) {
        return new PolarIgnite3ActivitySampleProvider(device, session);
    }

    @Override
    public SleepSessionProvider getSleepSessionProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new PolarIgnite3SleepSessionProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends TemperatureSample> getTemperatureSampleProvider(@NonNull final GBDevice device,
                                                                                         @NonNull final DaoSession session) {
        return new GenericTemperatureSampleProvider(device, session, TemperatureSample.TYPE_SKIN, TemperatureSample.LOCATION_WRIST);
    }

    @Override
    public boolean supportsHrvMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsSleepScore(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsRespiratoryRate(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsDayRespiratoryRate(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsSleepRespiratoryRate(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public TimeSampleProvider<? extends HrvValueSample> getHrvValueSampleProvider(@NonNull final GBDevice device,
                                                                                   @NonNull final DaoSession session) {
        return new GenericHrvValueSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends RespiratoryRateSample> getRespiratoryRateSampleProvider(@NonNull final GBDevice device,
                                                                                                 @NonNull final DaoSession session) {
        return new GenericRespiratoryRateSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends SleepScoreSample> getSleepScoreProvider(@NonNull final GBDevice device,
                                                                                 @NonNull final DaoSession session) {
        return new GenericSleepScoreSampleProvider(device, session);
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        final Map<AbstractDao<?, ?>, Property> map = new HashMap<>();
        map.put(session.getPolarIgnite3ActivitySampleDao(), PolarIgnite3ActivitySampleDao.Properties.DeviceId);
        map.put(session.getGenericSleepStageSampleDao(), GenericSleepStageSampleDao.Properties.DeviceId);
        map.put(session.getGenericHrvValueSampleDao(), GenericHrvValueSampleDao.Properties.DeviceId);
        map.put(session.getGenericRespiratoryRateSampleDao(), GenericRespiratoryRateSampleDao.Properties.DeviceId);
        map.put(session.getGenericSleepScoreSampleDao(), GenericSleepScoreSampleDao.Properties.DeviceId);
        map.put(session.getGenericTemperatureSampleDao(), GenericTemperatureSampleDao.Properties.DeviceId);
        return map;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.WATCH;
    }
}
