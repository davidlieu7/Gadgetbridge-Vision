/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.devices.veryfit;

import android.app.Activity;
import android.bluetooth.le.ScanFilter;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.appmanager.AppManagerActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryParser;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.veryfit.VeryFitSupport;

/**
 * Base for the VeryFit family. The GATT service alone does not identify a model, so every device
 * matches on its advertised name, and the settings offered depend on what it reported when it last
 * connected.
 */
public abstract class VeryFitCoordinator extends AbstractBLEDeviceCoordinator {

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        final ParcelUuid service = new ParcelUuid(VeryFitConstants.UUID_SERVICE);
        return Collections.singletonList(new ScanFilter.Builder().setServiceUuid(service).build());
    }

    @Override
    public boolean supports(final GBDeviceCandidate candidate) {
        return candidate.getName() != null && getSupportedDeviceName().matcher(candidate.getName()).matches();
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_BOND;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return VeryFitSupport.class;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.WATCH;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_default;
    }

    @Override
    public boolean supportsFindDevice(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsMusicInfo(@NonNull final GBDevice device) {
        return getCapabilities(device).supports(VeryFitFeature.FRAMED_PROTOCOL);
    }

    @Override
    public SampleProvider<? extends AbstractActivitySample> getSampleProvider(final GBDevice device,
                                                                             final DaoSession session) {
        return new VeryFitActivitySampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(final GBDevice device,
                                                                          final DaoSession session) {
        return new GenericSpo2SampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(final GBDevice device,
                                                                              final DaoSession session) {
        return new GenericStressSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HrvValueSample> getHrvValueSampleProvider(final GBDevice device,
                                                                                  final DaoSession session) {
        return new GenericHrvValueSampleProvider(device, session);
    }

    @Override
    public ActivitySummaryParser getActivitySummaryParser(final GBDevice device, final Context context) {
        return new VeryFitWorkoutSummaryParser(device);
    }

    @Override
    public ActivityTrackProvider getActivityTrackProvider(@NonNull final GBDevice device,
                                                          @NonNull final Context context) {
        return new VeryFitActivityTrackProvider(device);
    }

    /** Faces are listed, picked, installed and deleted, but the watch draws them all itself. */
    @Override
    public boolean supportsAppsManagement(@NonNull final GBDevice device) {
        return getCapabilities(device).supports(VeryFitFeature.FRAMED_PROTOCOL);
    }

    @Override
    public boolean supportsWatchfaceManagement(@NonNull final GBDevice device) {
        return supportsAppsManagement(device);
    }

    @Override
    public boolean supportsCachedAppManagement(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsInstalledAppManagement(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsAppListFetching(@NonNull final GBDevice device) {
        return true;
    }

    /** Puts the picker on the face list itself, since a face is the only thing to pick. */
    @Override
    public boolean supportsFlashing(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public Class<? extends Activity> getAppsManagementActivity(@NonNull final GBDevice device) {
        return AppManagerActivity.class;
    }

    @Override
    public InstallHandler findInstallHandler(final Uri uri, @NonNull final Bundle options,
                                             @NonNull final Context context) {
        final VeryFitInstallHandler handler = new VeryFitInstallHandler(uri, context);
        return handler.isValid() ? handler : null;
    }

    @Override
    public boolean supportsWeather(@NonNull final GBDevice device) {
        return getCapabilities(device).supports(VeryFitFeature.FRAMED_PROTOCOL);
    }

    @Override
    public boolean supportsRecordedActivities(@NonNull final GBDevice device) {
        return supportsDataFetching(device);
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return getCapabilities(device).supports(VeryFitFeature.FRAMED_PROTOCOL);
    }

    @Override
    public boolean supportsActivityTracking(@NonNull final GBDevice device) {
        return supportsDataFetching(device);
    }

    @Override
    public boolean supportsActiveCalories(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsSpo2(@NonNull final GBDevice device) {
        return getCapabilities(device).supports(VeryFitFeature.SPO2_ALL_DAY);
    }

    @Override
    public boolean supportsStressMeasurement(@NonNull final GBDevice device) {
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
    public int getAlarmSlotCount(final GBDevice device) {
        final VeryFitCapabilities capabilities = getCapabilities(device);
        return capabilities.supports(VeryFitFeature.FRAMED_PROTOCOL) ? capabilities.getAlarmSlots() : 0;
    }

    @Override
    public boolean supportsAlarmTitle(@NonNull final GBDevice device) {
        return getAlarmSlotCount(device) > 0;
    }

    @Override
    public int getAlarmTitleLimit(final GBDevice device) {
        return VeryFitConstants.ALARM_NAME_LEN;
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(@NonNull final GBDevice device) {
        final VeryFitCapabilities capabilities = getCapabilities(device);
        final DeviceSpecificSettings settings = new DeviceSpecificSettings();

        final List<Integer> health = settings.addRootScreen(DeviceSpecificSettingsScreen.HEALTH);
        health.add(R.xml.devicesettings_autoheartrate);
        if (capabilities.supports(VeryFitFeature.INACTIVITY_REMINDER)) {
            health.add(R.xml.devicesettings_inactivity_sheduled);
        }
        if (capabilities.supports(VeryFitFeature.HYDRATION_REMINDER)) {
            health.add(R.xml.devicesettings_hydration_reminder_sheduled);
        }

        if (capabilities.isKnown()) {
            final List<Integer> display = settings.addRootScreen(DeviceSpecificSettingsScreen.DISPLAY);
            display.add(R.xml.devicesettings_liftwrist_display_noshed);
            display.add(R.xml.devicesettings_screen_brightness);
            display.add(R.xml.devicesettings_nightmode);
        }

        if (capabilities.isKnown()) {
            settings.addRootScreen(DeviceSpecificSettingsScreen.WORKOUT,
                    R.xml.devicesettings_veryfit_workout_detection);
        }

        final List<Integer> dateTime = settings.addRootScreen(DeviceSpecificSettingsScreen.DATE_TIME);
        dateTime.add(R.xml.devicesettings_timeformat);

        final List<Integer> notifications = settings.addRootScreen(DeviceSpecificSettingsScreen.NOTIFICATIONS);
        notifications.add(R.xml.devicesettings_send_app_notifications);
        notifications.add(R.xml.devicesettings_transliteration);

        settings.addRootScreen(R.xml.devicesettings_find_phone);

        return settings;
    }

    @Override
    public String[] getSupportedLanguageSettings(final GBDevice device) {
        return new String[]{
                "auto",
                "ar",
                "bg",
                "my",
                "zh_CN",
                "zh_TW",
                "hr",
                "cs",
                "da_DK",
                "nl",
                "en",
                "fil",
                "fr",
                "de",
                "el_GR",
                "hi",
                "hu_HU",
                "id",
                "it",
                "ja",
                "ko",
                "lt",
                "ms_MY",
                "pl",
                "pt",
                "ro_RO",
                "ru",
                "sr",
                "sk",
                "sl",
                "es",
                "th",
                "tr",
                "uk_UA",
                "vi",
        };
    }

    protected VeryFitCapabilities getCapabilities(final GBDevice device) {
        return VeryFitCapabilities.fromPreferences(GBApplication.getDevicePrefs(device));
    }
}
