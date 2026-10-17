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
package nodomain.freeyourgadget.gadgetbridge.devices.oura;

import android.app.Activity;
import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.ComputedHrvSummarySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SleepSessionProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.samples.OuraActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.HeartRrIntervalSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvSummarySample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.OuraRing4Support;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraAuth;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraProtocol;

public class OuraRing4Coordinator extends AbstractBLEDeviceCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^Oura.*");
    }

    /** The ring rotates its random address, so it is found by its service. */
    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        return Collections.singletonList(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(OuraProtocol.SERVICE))
                .build());
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_REQUIRE_KEY;
    }

    @Override
    public boolean validateAuthKey(final String authKey) {
        return OuraAuth.parseHexKey(authKey) != null;
    }

    @Nullable
    @Override
    public Class<? extends Activity> getPairingActivity() {
        return OuraPairingActivity.class;
    }

    @Override
    public int[] getSupportedDeviceSpecificAuthenticationSettings() {
        return new int[]{R.xml.devicesettings_pairingkey};
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        final Map<AbstractDao<?, ?>, Property> map = new HashMap<>();
        map.put(session.getOuraActivitySampleDao(), OuraActivitySampleDao.Properties.DeviceId);
        map.put(session.getHeartRrIntervalSampleDao(), HeartRrIntervalSampleDao.Properties.DeviceId);
        map.put(session.getGenericHrvValueSampleDao(), GenericHrvValueSampleDao.Properties.DeviceId);
        map.put(session.getGenericTemperatureSampleDao(), GenericTemperatureSampleDao.Properties.DeviceId);
        return map;
    }

    @Override
    public String getManufacturer() {
        return "Oura";
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_oura_ring_4;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_smartring;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return OuraRing4Support.class;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(final GBDevice device, final DaoSession session) {
        return new OuraActivitySampleProvider(device, session);
    }

    @NonNull
    @Override
    public SleepSessionProvider getSleepSessionProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new OuraEstimatedSleepSessionProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HrvSummarySample> getHrvSummarySampleProvider(final GBDevice device, final DaoSession session) {
        return new ComputedHrvSummarySampleProvider(getHrvValueSampleProvider(device, session), device, session);
    }

    @Override
    public TimeSampleProvider<? extends HrvValueSample> getHrvValueSampleProvider(final GBDevice device, final DaoSession session) {
        return new GenericHrvValueSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends TemperatureSample> getTemperatureSampleProvider(final GBDevice device, final DaoSession session) {
        return new GenericTemperatureSampleProvider(device, session, TemperatureSample.TYPE_SKIN, TemperatureSample.LOCATION_FINGER);
    }

    @Override
    public boolean supportsActivityTracking(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHrvMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsTemperatureMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsContinuousTemperature(@NonNull final GBDevice device) {
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
    public boolean supportsSpeedzones(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(final GBDevice device) {
        final DeviceSpecificSettings settings = new DeviceSpecificSettings();
        final List<Integer> health = settings.addRootScreen(DeviceSpecificSettingsScreen.HEALTH);
        health.add(R.xml.devicesettings_oura_health);
        return settings;
    }

    @Override
    public DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(final GBDevice device) {
        return new OuraSettingsCustomizer();
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.RING;
    }
}
