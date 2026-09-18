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

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Arrays;
import java.util.List;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.VeepooVitalsChooserActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.VeepooWorkoutStartActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.DeviceChartsProvider;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.ComputedHrvSummarySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericRespiratoryRateSampleProvider;
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
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooEcgSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooSleepSummarySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.BloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvSummarySample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.RespiratoryRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo.VeepooDeviceSupport;

public abstract class AbstractVeepooCoordinator extends AbstractBLEDeviceCoordinator {

    @Override
    public String getManufacturer() {
        return "VeePoo";
    }

    @Override
    public boolean supportsRecordedActivities(@NonNull final GBDevice device) {
        // Workout history (0xD4) sessions are persisted as BaseActivitySummary rows with an
        // optional GPX track, so the Health Connect workout syncer can export them.
        return true;
    }

    @Override
    public boolean supportsActiveCalories(@NonNull final GBDevice device) {
        // The Origin3 0xB2 tag carries per-slot active kcal, exposed through
        // VeepooActivitySample.getActiveCalories; the Health Connect syncer gates on this.
        return true;
    }

    @Override
    public int getAlarmSlotCount(@NonNull final GBDevice device) {
        // Exposes the built-in "set alarms" card action (ConfigureAlarms); the 0xB9 module
        // enforces the firmware capability at send time and reads back the band's state.
        return VeepooConstants.ALARM_MAX_ALARMS;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(@NonNull final GBDevice device) {
        return VeepooDeviceSupport.class;
    }

    @Override
    public DeviceSettingsSpec getDeviceSettings(@NonNull final GBDevice device) {
        return VeepooDeviceSettingsKt.veepooDeviceSettings();
    }

    @Override
    public DeviceChartsProvider getChartsProvider() {
        return new VeepooChartsProvider();
    }

    @Override
    public java.util.Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        return new java.util.HashMap<>() {{
            put(session.getVeepooActivitySampleDao(), VeepooActivitySampleDao.Properties.DeviceId);
            put(session.getVeepooSleepSummarySampleDao(), VeepooSleepSummarySampleDao.Properties.DeviceId);
            put(session.getVeepooEcgSampleDao(), VeepooEcgSampleDao.Properties.DeviceId);
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
    public TimeSampleProvider<? extends RespiratoryRateSample> getRespiratoryRateSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new GenericRespiratoryRateSampleProvider(device, session);
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
    public boolean supportsRemSleep(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsAwakeSleep(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsFindDevice(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public TimeSampleProvider<? extends HrvValueSample> getHrvValueSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new GenericHrvValueSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HrvSummarySample> getHrvSummarySampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        // The band only reports per-minute RMSSD; weekly average and baseline bands are computed locally.
        return new ComputedHrvSummarySampleProvider(getHrvValueSampleProvider(device, session), device, session);
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
            // ic_timer: the built-in "show activity tracks" card button already uses the
            // running-man glyph (ic_activity_tracks == ic_activity_running), so the start
            // workout action needs a clearly distinct icon.
            return workoutRunning ? R.drawable.ic_stop : R.drawable.ic_timer;
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
