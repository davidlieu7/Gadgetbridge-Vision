/*  Copyright (C) 2026 Davide Gessa

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
package nodomain.freeyourgadget.gadgetbridge.devices.casio.ggb100;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.bluetooth.BluetoothDevice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Collections;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryTableRowEntry;
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.charts.DefaultWorkoutCharts;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.casio.Casio2C2DDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.casio.CasioLocationIndicatorSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.devices.casio.gbx100.CasioGBX100SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.CasioGBX100ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryParser;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.workout.Workout;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.ggb100.CasioGGB100DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;

public class CasioGGB100DeviceCoordinator extends Casio2C2DDeviceCoordinator {
    protected static final Logger LOG = LoggerFactory.getLogger(CasioGGB100DeviceCoordinator.class);

    @Override
    protected void applyDefaultPreferences(final DevicePrefs devicePreferences, final SharedPreferences.Editor editor) {
        editor.putString(DeviceSettingsPreferenceConst.PREFS_DEVICE_CHARTS_TABS, "activity,activitylist,stepsweek");
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^CASIO GG-B100.*");
    }

    @Override
    public int getBlePhyMask() {
        return BluetoothDevice.PHY_LE_1M_MASK;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_LAZY;
    }

    @Override
    public boolean supportsAlarmSnoozing(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public Class<? extends Activity> getPairingActivity() {
        return null;
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
    public boolean supportsRecordedActivities(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(GBDevice device, DaoSession session) {
        return new CasioGBX100SampleProvider(device, session);
    }

    @Override
    public ActivitySummaryParser getActivitySummaryParser(final GBDevice device, final Context context) {
        return new ActivitySummaryParser() {
            @Override
            public BaseActivitySummary parseBinaryData(final BaseActivitySummary summary, final boolean forDetails) {
                return summary;
            }

            @Override
            public Workout parseWorkout(final BaseActivitySummary summary, final boolean forDetails) {
                final ActivitySummaryData data = ActivitySummaryData.fromJson(summary.getSummaryData());
                if (!forDetails) {
                    return new Workout(summary, data, Collections.emptyList());
                }

                // Create the elevation chart from watch elevation data
                final long baseTs = summary.getStartTime().getTime();
                final List<ActivityPoint> points = new ArrayList<>();
                for (int i = 1; data.has("sample_" + i); i++) {
                    if (data.get("sample_" + i) instanceof ActivitySummaryTableRowEntry row
                        && row.getColumns().size() >= 3 && row.getColumns().get(1).value() instanceof Number minutes
                        && row.getColumns().get(2).value() instanceof Number alt) {
                        final ActivityPoint point = new ActivityPoint(
                            new Date(baseTs + (long) (minutes.doubleValue() * 60_000)));
                        point.setAltitude(alt.doubleValue());
                        points.add(point);
                    }
                }
                return new Workout(summary, data, DefaultWorkoutCharts.buildDefaultCharts(context, points,
                    ActivityKind.fromCode(summary.getActivityKind()), data, device));
            }
        };
    }

    @Override
    public int getAlarmSlotCount(GBDevice device) {
        return 4;
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        Map<AbstractDao<?, ?>, Property> map = new HashMap<>(1);
        map.put(session.getCasioGBX100ActivitySampleDao(), CasioGBX100ActivitySampleDao.Properties.DeviceId);
        return map;
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(GBDevice device) {
        return new int[]{ 
            R.xml.devicesettings_find_phone, 
            R.xml.devicesettings_wearlocation,
                R.xml.devicesettings_timeformat, 
                R.xml.devicesettings_autolight, 
                R.xml.devicesettings_operating_sounds,
                R.xml.devicesettings_hourly_chime_enable, 
                R.xml.devicesettings_casio_connection_duration,
                R.xml.devicesettings_casio_location_indicator
                // R.xml.devicesettings_casio_interval_timer
        };
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return CasioGGB100DeviceSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_casioggb100;
    }

    @Override
    public DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(final GBDevice device) {
        return new CasioLocationIndicatorSettingsCustomizer(device);
    }
}
