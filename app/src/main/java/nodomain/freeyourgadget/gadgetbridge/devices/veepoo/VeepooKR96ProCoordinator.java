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
package nodomain.freeyourgadget.gadgetbridge.devices.veepoo;

import android.bluetooth.le.ScanFilter;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.VeepooVitalsChooserActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.VeepooWorkoutStartActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.samples.VeepooActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBodyCompositionSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodLipidSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.BloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo.VeepooDeviceSupport;

public class VeepooKR96ProCoordinator extends AbstractBLEDeviceCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        // Advertised name is not yet confirmed by a live capture; the VeePoo app
        // matches on manufacturer-specific scan data instead. Adjust after capture.
        return Pattern.compile("^KR96.*", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_veepoo_kr96_pro;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_amazfit_bip;
    }

    @Override
    public String getManufacturer() {
        return "VeePoo";
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.FITNESS_BAND;
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(@NonNull final GBDevice device) {
        return VeepooDeviceSupport.class;
    }

    @Override
    public java.util.Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        return new java.util.HashMap<>() {{
            put(session.getVeepooActivitySampleDao(), VeepooActivitySampleDao.Properties.DeviceId);
            put(session.getGenericHeartRateSampleDao(), GenericHeartRateSampleDao.Properties.DeviceId);
            put(session.getGenericSpo2SampleDao(), GenericSpo2SampleDao.Properties.DeviceId);
            put(session.getGenericTemperatureSampleDao(), GenericTemperatureSampleDao.Properties.DeviceId);
            put(session.getGenericStressSampleDao(), GenericStressSampleDao.Properties.DeviceId);
            put(session.getGenericBloodPressureSampleDao(), GenericBloodPressureSampleDao.Properties.DeviceId);
            put(session.getGenericBodyCompositionSampleDao(), GenericBodyCompositionSampleDao.Properties.DeviceId);
            put(session.getGenericBloodLipidSampleDao(), GenericBloodLipidSampleDao.Properties.DeviceId);
            put(session.getGenericHrvValueSampleDao(), GenericHrvValueSampleDao.Properties.DeviceId);
        }};
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(final GBDevice device, final DaoSession session) {
        return new VeepooActivitySampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new GenericSpo2SampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends TemperatureSample> getTemperatureSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new GenericTemperatureSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new GenericStressSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends BloodPressureSample> getBloodPressureSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new GenericBloodPressureSampleProvider(device, session);
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsRealtimeData(@NonNull GBDevice device) {
        // Gates the heart-rate button on the device card (manual 0xDE read-back); continuous streaming is not implemented.
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsSpo2(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsTemperatureMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsStressMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsBloodPressureMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHrvMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public TimeSampleProvider<? extends HrvValueSample> getHrvValueSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new GenericHrvValueSampleProvider(device, session);
    }

    @Override
    public int getBondingStyle() {
        // The band pairs via its own password command (0xA1), not BLE pairing.
        return BONDING_STYLE_LAZY;
    }

    private volatile boolean workoutRunning = false;

    public void setWorkoutRunning(final boolean running) {
        if (this.workoutRunning != running) {
            this.workoutRunning = running;
            LocalBroadcastManager.getInstance(GBApplication.app().getApplicationContext())
                    .sendBroadcast(new Intent(DeviceManager.ACTION_REFRESH_DEVICELIST));
        }
    }

    @NonNull
    @Override
    public List<DeviceCardAction> getCustomActions() {
        return Arrays.asList(
                new ToggleWorkoutAction(),
                new VitalsChooserAction());
    }

    private final class ToggleWorkoutAction implements DeviceCardAction {
        @Override
        public int getIcon(@NonNull final GBDevice device) {
            return workoutRunning ? R.drawable.ic_stop : R.drawable.ic_activity_running;
        }

        @NonNull
        @Override
        public String getDescription(@NonNull final GBDevice device, @NonNull final Context context) {
            return context.getString(workoutRunning ? R.string.veepoo_workout_stop : R.string.veepoo_workout_start);
        }

        @Override
        public void onClick(@NonNull final GBDevice device, @NonNull final Context context) {
            if (workoutRunning) {
                setWorkoutRunning(false);
                GBApplication.deviceService(device).onSendConfiguration(VeepooConstants.CONFIG_WORKOUT_STOP);
            } else {
                final Intent intent = new Intent(context, VeepooWorkoutStartActivity.class);
                intent.putExtra(GBDevice.EXTRA_DEVICE, device);
                context.startActivity(intent);
            }
        }
    }

    private static final class VitalsChooserAction implements DeviceCardAction {
        @Override
        public int getIcon(@NonNull final GBDevice device) {
            return R.drawable.ic_health;
        }

        @NonNull
        @Override
        public String getDescription(@NonNull final GBDevice device, @NonNull final Context context) {
            return context.getString(R.string.veepoo_action_measure_vitals);
        }

        @Override
        public void onClick(@NonNull final GBDevice device, @NonNull final Context context) {
            final Intent intent = new Intent(context, VeepooVitalsChooserActivity.class);
            intent.putExtra(GBDevice.EXTRA_DEVICE, device);
            context.startActivity(intent);
        }
    }
}
