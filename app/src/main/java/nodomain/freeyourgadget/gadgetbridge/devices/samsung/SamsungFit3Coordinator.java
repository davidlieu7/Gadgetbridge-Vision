/*  Copyright (C) 2026 Vitaliy Tomin

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
package nodomain.freeyourgadget.gadgetbridge.devices.samsung;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.appmanager.AppManagerActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.SamsungFit3ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryParser;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SamsungDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3PhoneAlarm;

public class SamsungFit3Coordinator extends AbstractBLEDeviceCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("Galaxy Fit3 \\(.*\\)", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_BOND;
    }

    @Override
    public String getManufacturer() {
        return "Samsung";
    }


    @Override
    public boolean supportsHeartRateMeasurement(GBDevice device) {
        return true;
    }


    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_samsung_fit3;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_default;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.FITNESS_BAND;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(GBDevice device) {
        return SamsungDeviceSupport.class;
    }

    @Override
    public boolean supportsFindDevice(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsWeather(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsMusicInfo(final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(GBDevice device, DaoSession session) {
        return new SamsungSampleProvider(device, session);
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        Map<AbstractDao<?, ?>, Property> map = new HashMap<>(1);
        map.put(session.getSamsungFit3ActivitySampleDao(), SamsungFit3ActivitySampleDao.Properties.DeviceId);
        return map;
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsRecordedActivities(@NonNull final GBDevice device) {
        return true;
    }

    @Nullable
    @Override
    public ActivitySummaryParser getActivitySummaryParser(final GBDevice device, final Context context) {
        return new SamsungWorkoutParser();
    }

    @Override
    public InstallHandler findInstallHandler(Uri uri, Bundle options, Context context) {
        SamsungFit3InstallHandler handler = new SamsungFit3InstallHandler(uri, context);
        return handler.isValid() ? handler : null;
    }

    @Override
    public boolean supportsFlashing(@NonNull GBDevice device) {
        return true;
    }

    /** Watchfaces only - the band has no installable apps. */
    @Override
    public boolean supportsAppsManagement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsInstalledAppManagement(@NonNull GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsCachedAppManagement(@NonNull GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsWatchfaceManagement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsAppListFetching(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public Class<? extends Activity> getAppsManagementActivity(GBDevice device) {
        return AppManagerActivity.class;
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(@NonNull GBDevice device) {
        DeviceSpecificSettings settings = new DeviceSpecificSettings(new int[]{
                R.xml.devicesettings_samsung_fit3,
                R.xml.devicesettings_key_vibration,
                R.xml.devicesettings_always_on_display,
                R.xml.devicesettings_liftwrist_display_noshed,
                R.xml.devicesettings_wearlocation,
                R.xml.devicesettings_weardirection,
                R.xml.devicesettings_timeformat,
                R.xml.devicesettings_world_clocks,
                R.xml.devicesettings_upload_notifications_app_icon,
                R.xml.devicesettings_samsung_fit3_menus,
                R.xml.devicesettings_canned_reply_16,
                R.xml.devicesettings_canned_dismisscall_16,
                R.xml.devicesettings_sync_calendar,
                R.xml.devicesettings_samsung_fit3_sos,
                R.xml.devicesettings_sos_contact,
                R.xml.devicesettings_samsung_fit3_cycle,
        });
        settings.addRootScreen(DeviceSpecificSettingsScreen.DEVELOPER,
                R.xml.devicesettings_samsung_fit3_developer);
        return settings;
    }

    /**
     * Phone alarms (Fit3PhoneAlarm) ring only on Gadgetbridge's own in-process
     * timer, which is not reliable enough to wake up to, so they sit behind a
     * developer switch. Zero slots hides the alarm screen, and the device
     * support sends and rings nothing while this is zero.
     */
    @Override
    public int getAlarmSlotCount(@NonNull GBDevice device) {
        boolean enabled = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress())
                .getBoolean(Fit3PhoneAlarm.PREF_ENABLE, false);
        return enabled ? Fit3PhoneAlarm.SLOT_COUNT : 0;
    }

    @Override
    public int getWorldClocksSlotCount() {
        return 10;
    }

    @Override
    public int getWorldClocksLabelLength() {
        return 20;
    }

    @Override
    public boolean supportsCalendarEvents(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public int getCannedRepliesSlotCount(@NonNull GBDevice device) {
        return SamsungDeviceSupport.CANNED_REPLY_SLOTS;
    }

    @Override
    public DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(final GBDevice device) {
        return new SamsungFit3SettingsCustomizer();
    }

}
