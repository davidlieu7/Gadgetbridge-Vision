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
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;

public class PolarSettingsTest extends TestBase {
    /** /U/0/S/UDEVSET.BPB as written by Polar Flow after enabling smart notifications */
    private static final String UDEVSET =
            "0a221801200138004001480050035802680178028a010a08011001100210031004900102120c08011208080a10001800"
            + "20001a0a0a0808001000180020002a0e0a0c0801120808001001180020004a20080110011a1608011208081610001800"
            + "20001a080807100018002000280130015a19080012150a0708d00f100118011208080010001800200018018a011a0a15"
            + "0a0708ea0f100a1819120808011000180020001801109f389201020800a20118080012140a0808161000180020001208"
            + "0807100018002000aa01260a24080112140a080816100018002000120808071000180020001a0a0a0808161000180020"
            + "00b2010e0a0c080110011800200128003000c201050d0000c040ca01020801d201020801aa06160a0708ea0f100a1813"
            + "12090815101c182520cb021801";

    private SharedPreferences prefs() {
        final SharedPreferences prefs = getContext().getSharedPreferences("polar_settings_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        return prefs;
    }

    private static PolarProto.PbUserDeviceSettings withoutVolatile(final PolarProto.PbUserDeviceSettings settings) {
        return settings.toBuilder().clearLastModified().clearDaylightSaving().build();
    }

    @Test
    public void importThenApplyKeepsWatchSettings() throws Exception {
        final PolarProto.PbUserDeviceSettings watch = PolarProto.PbUserDeviceSettings.parseFrom(GB.hexStringToByteArray(UDEVSET));
        final SharedPreferences prefs = prefs();
        final SharedPreferences.Editor editor = prefs.edit();
        PolarSettings.importDeviceSettings(watch, editor);
        editor.commit();

        assertEquals(true, prefs.getBoolean(PolarSettings.PREF_WATCH_NOTIFICATIONS, false));
        assertEquals("off", prefs.getString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB, null));
        assertEquals("22:00", prefs.getString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_START, null));
        assertEquals("always", prefs.getString(PolarSettings.PREF_CONTINUOUS_HR, null));

        final PolarProto.PbUserDeviceSettings applied = PolarSettings.applyDeviceSettings(watch, new DevicePrefs(prefs, null));
        // at most the daylight saving switch and the modification time may change
        final PolarProto.PbUserDeviceSettings result = applied != null ? applied : watch;
        assertEquals(withoutVolatile(watch), withoutVolatile(result));
    }

    @Test
    public void applyChangesOnlyWhatChanged() throws Exception {
        final PolarProto.PbUserDeviceSettings watch = PolarProto.PbUserDeviceSettings.parseFrom(GB.hexStringToByteArray(UDEVSET));
        final SharedPreferences prefs = prefs();
        final SharedPreferences.Editor editor = prefs.edit();
        PolarSettings.importDeviceSettings(watch, editor);
        editor.putString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB, "scheduled");
        editor.putString(PolarSettings.PREF_CONTINUOUS_HR, "night");
        editor.commit();

        final PolarProto.PbUserDeviceSettings applied = PolarSettings.applyDeviceSettings(watch, new DevicePrefs(prefs, null));
        assertNotNull(applied);
        assertEquals(PolarProto.PbUserDeviceDoNotDisturbSettings_v2.PbDoNotDisturbState.TIMED,
                applied.getDoNotDisturbSettingsV2().getState());
        assertEquals(PolarProto.PbAutomaticMeasurementSettings.PbAutomaticMeasurementState.INTELLIGENT_TIMED,
                applied.getAutomaticMeasurementSettings().getAutomaticOhrMeasurement().getState());
        assertEquals(watch.getGeneralSettings(), applied.getGeneralSettings());
        assertEquals(watch.getSmartWatchNotificationSettings(), applied.getSmartWatchNotificationSettings());
        // unknown fields survive the round trip
        assertEquals(watch.getGnssSettings(), applied.getGnssSettings());
    }

    @Test
    public void unsetProfileValuesAreNotWritten() {
        final PolarProto.PbUserPhysData watch = PolarProto.PbUserPhysData.newBuilder()
                .setHeight(PolarProto.PbUserHeight.newBuilder().setValue(190))
                .setWeight(PolarProto.PbUserWeight.newBuilder().setValue(80))
                .setSleepGoal(PolarProto.PbSleepGoal.newBuilder().setSleepGoalMinutes(480))
                .build();
        final Prefs userPrefs = GBApplication.getPrefs();
        final SharedPreferences prefs = userPrefs.getPreferences();
        prefs.edit()
                .remove(ActivityUser.PREF_USER_DATE_OF_BIRTH)
                .remove(ActivityUser.PREF_USER_GENDER)
                .remove(ActivityUser.PREF_USER_HEIGHT_CM)
                .remove(ActivityUser.PREF_USER_WEIGHT_KG)
                .remove(ActivityUser.PREF_USER_SLEEP_DURATION_MINUTES)
                .commit();
        assertNull(PolarSettings.applyUserProfile(watch, new ActivityUser(), userPrefs));

        prefs.edit().putString(ActivityUser.PREF_USER_HEIGHT_CM, "185").commit();
        final PolarProto.PbUserPhysData applied = PolarSettings.applyUserProfile(watch, new ActivityUser(), userPrefs);
        prefs.edit().remove(ActivityUser.PREF_USER_HEIGHT_CM).commit();
        assertNotNull(applied);
        assertEquals(185, applied.getHeight().getValue(), 0);
        assertEquals(80, applied.getWeight().getValue(), 0);
        assertEquals(480, applied.getSleepGoal().getSleepGoalMinutes());
    }
}
