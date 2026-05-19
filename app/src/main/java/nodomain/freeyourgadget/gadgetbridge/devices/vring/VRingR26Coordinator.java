/*  Copyright (C) 2026 Ariel Saghiv

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
package nodomain.freeyourgadget.gadgetbridge.devices.vring;

import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import de.greenrobot.dao.query.QueryBuilder;

import nodomain.freeyourgadget.gadgetbridge.GBException;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.vring.samples.VRingR26ActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.VRingR26ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.vring.VRingR26DeviceSupport;

/** Coordinator for the VRing R26 smart ring. */
public class VRingR26Coordinator extends AbstractBLEDeviceCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^VRing$");
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        ParcelUuid service = new ParcelUuid(VRingR26Constants.UUID_SERVICE);
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(service).build();
        return Collections.singletonList(filter);
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(GBDevice device) {
        return VRingR26DeviceSupport.class;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public String getManufacturer() {
        return "MO YOUNG";
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_vring_r26;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_smartring;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.RING;
    }

    // -------- Sample providers --------

    @NonNull
    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        Map<AbstractDao<?, ?>, Property> map = new HashMap<>(4);
        map.put(session.getVRingR26ActivitySampleDao(), VRingR26ActivitySampleDao.Properties.DeviceId);
        map.put(session.getGenericHeartRateSampleDao(), GenericHeartRateSampleDao.Properties.DeviceId);
        map.put(session.getGenericSpo2SampleDao(), GenericSpo2SampleDao.Properties.DeviceId);
        map.put(session.getGenericStressSampleDao(), GenericStressSampleDao.Properties.DeviceId);
        return map;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(GBDevice device, DaoSession session) {
        return new VRingR26ActivitySampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HeartRateSample> getHeartRateMaxSampleProvider(GBDevice device, DaoSession session) {
        return new GenericHeartRateSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(GBDevice device, DaoSession session) {
        return new GenericSpo2SampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(GBDevice device, DaoSession session) {
        return new GenericStressSampleProvider(device, session);
    }

    @Override
    protected void deleteDevice(@NonNull GBDevice gbDevice, @NonNull Device device,
                                @NonNull DaoSession session) throws GBException {
        Long deviceId = device.getId();
        QueryBuilder<?> qb = session.getVRingR26ActivitySampleDao().queryBuilder();
        qb.where(VRingR26ActivitySampleDao.Properties.DeviceId.eq(deviceId))
                .buildDelete().executeDeleteWithoutDetachingEntities();
        session.getGenericHeartRateSampleDao().queryBuilder()
                .where(GenericHeartRateSampleDao.Properties.DeviceId.eq(deviceId))
                .buildDelete().executeDeleteWithoutDetachingEntities();
        session.getGenericSpo2SampleDao().queryBuilder()
                .where(GenericSpo2SampleDao.Properties.DeviceId.eq(deviceId))
                .buildDelete().executeDeleteWithoutDetachingEntities();
        session.getGenericStressSampleDao().queryBuilder()
                .where(GenericStressSampleDao.Properties.DeviceId.eq(deviceId))
                .buildDelete().executeDeleteWithoutDetachingEntities();
    }

    // -------- Capabilities --------

    @Override
    public boolean supportsActivityTracking(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsStepCounter(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsDataFetching(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsFindDevice(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsManualHeartRateMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsSpo2(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsStressMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean isExperimental() {
        return true;
    }
}
