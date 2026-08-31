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
package nodomain.freeyourgadget.gadgetbridge.service.devices.veryfit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitCapabilities;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitFeature;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.DistanceUnit;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureUnit;
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;

/**
 * Turns preferences into settings payloads. Devices that reported no feature tables get the
 * shorter payload variants, which is all the older bands in this family accept.
 */
public final class VeryFitSettings {
    private VeryFitSettings() {
    }

    public static byte[] hostOs() {
        return VeryFitProtocol.setting(VeryFitConstants.SETTING_HOST_OS, (byte) 0x02, (byte) 0x00);
    }

    public static byte[] time(final VeryFitCapabilities capabilities) {
        final Calendar now = GregorianCalendar.getInstance();
        final int year = now.get(Calendar.YEAR);
        final int weekday = now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                ? 6 : now.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;

        final ByteBuffer buf = buffer(capabilities.isKnown() ? 14 : 8);
        buf.putShort((short) year);
        buf.put((byte) (now.get(Calendar.MONTH) + 1));
        buf.put((byte) now.get(Calendar.DAY_OF_MONTH));
        buf.put((byte) now.get(Calendar.HOUR_OF_DAY));
        buf.put((byte) now.get(Calendar.MINUTE));
        buf.put((byte) now.get(Calendar.SECOND));
        buf.put((byte) weekday);
        if (capabilities.isKnown()) {
            buf.putInt(0);
            buf.putShort((short) timeZone(capabilities, now));
        }
        return VeryFitProtocol.setting(VeryFitConstants.SETTING_TIME, buf.array());
    }

    /**
     * Hundredths of an hour where the watch supports it, whole hours otherwise. Negative offsets
     * are sent as the bias plus their magnitude rather than as a signed value.
     */
    private static int timeZone(final VeryFitCapabilities capabilities, final Calendar now) {
        final int offsetMillis = TimeZone.getDefault().getOffset(now.getTimeInMillis());
        final boolean fractional = capabilities.supports(VeryFitFeature.FRACTIONAL_TIME_ZONE);
        final int offset = fractional ? offsetMillis / 36000 : offsetMillis / 3600000;
        return offset >= 0 ? offset : (fractional ? 1200 : 12) + Math.abs(offset);
    }

    public static byte[] user(final VeryFitCapabilities capabilities) {
        final ActivityUser user = new ActivityUser();
        final LocalDate birthday = user.getDateOfBirth();

        final ByteBuffer buf = buffer(capabilities.isKnown() ? 13 : 8);
        buf.put((byte) user.getHeightCm());
        buf.putShort((short) (user.getWeightKg() * 100));
        buf.put(user.getGender() == ActivityUser.GENDER_FEMALE
                ? VeryFitConstants.GENDER_FEMALE : VeryFitConstants.GENDER_MALE);
        buf.putShort((short) birthday.getYear());
        buf.put((byte) birthday.getMonthValue());
        buf.put((byte) birthday.getDayOfMonth());
        if (capabilities.isKnown()) {
            buf.putInt((int) (System.currentTimeMillis() / 1000L));
            buf.put((byte) 0x02);
        }
        return VeryFitProtocol.setting(VeryFitConstants.SETTING_USER, buf.array());
    }

    /** The units are global preferences, everything else on this packet is per device. */
    public static byte[] units(final DevicePrefs prefs) {
        final byte distance = GBApplication.getPrefs().getDistanceUnit() == DistanceUnit.IMPERIAL
                ? VeryFitConstants.UNITS_IMPERIAL : VeryFitConstants.UNITS_METRIC;
        final byte temperature = GBApplication.getPrefs().getTemperatureUnit() == TemperatureUnit.FAHRENHEIT
                ? VeryFitConstants.UNITS_IMPERIAL : VeryFitConstants.UNITS_METRIC;
        final byte weight = weight(GBApplication.getPrefs().getWeightUnit());
        final byte timeMode = DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H.equals(prefs.getTimeFormat())
                ? VeryFitConstants.TIME_MODE_24H : VeryFitConstants.TIME_MODE_12H;

        return VeryFitProtocol.setting(VeryFitConstants.SETTING_UNITS,
                distance, weight, temperature, (byte) 0x4b, language(prefs), timeMode,
                (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x01, (byte) 0x00, (byte) 0x00, distance);
    }

    private static byte weight(final WeightUnit unit) {
        switch (unit) {
            case POUND:
                return VeryFitConstants.WEIGHT_POUND;
            case STONE:
                return VeryFitConstants.WEIGHT_STONE;
            default:
                return VeryFitConstants.WEIGHT_KILOGRAM;
        }
    }

    /** The phone's locale unless the user picked one; anything the watch does not know is English. */
    private static byte language(final Prefs prefs) {
        String code = prefs.getString(DeviceSettingsPreferenceConst.PREF_LANGUAGE,
                DeviceSettingsPreferenceConst.PREF_LANGUAGE_AUTO);
        if (DeviceSettingsPreferenceConst.PREF_LANGUAGE_AUTO.equals(code)) {
            code = Locale.getDefault().getLanguage() + "_" + Locale.getDefault().getCountry();
        }

        Byte language = VeryFitConstants.LANGUAGES.get(code);
        if (language == null) {
            language = VeryFitConstants.LANGUAGES.get(code.split("_")[0]);
        }
        return language != null ? language : VeryFitConstants.LANGUAGE_ENGLISH;
    }

    public static byte[] stepGoal(final VeryFitCapabilities capabilities) {
        final int steps = new ActivityUser().getStepsGoal();
        final ByteBuffer buf = buffer(capabilities.isKnown() ? 15 : 7);
        buf.put((byte) 0x00);
        buf.putInt(steps);
        if (capabilities.isKnown()) {
            buf.position(9);
            buf.put((byte) 0x01);
        }
        return VeryFitProtocol.setting(VeryFitConstants.SETTING_STEP_GOAL, buf.array());
    }

    public static byte[] findPhone(final Prefs prefs) {
        final boolean enabled = !"off".equals(prefs.getString(DeviceSettingsPreferenceConst.PREF_FIND_PHONE, "off"));
        return VeryFitProtocol.setting(VeryFitConstants.SETTING_FIND_PHONE,
                onOff(enabled), (byte) 0, (byte) 0, (byte) 0);
    }

    /**
     * One switch per movement the watch may start a workout for, then the two that end one. The
     * write carries a tenth byte the vendor always sends clear.
     */
    public static byte[] autoWorkout(final Prefs prefs) {
        final Set<String> types = prefs.getStringSet(
                DeviceSettingsPreferenceConst.PREF_WORKOUT_DETECTION_CATEGORIES, Collections.emptySet());

        final byte[] payload = new byte[VeryFitConstants.AUTO_WORKOUT_SWITCHES + 1];
        for (int i = 0; i < VeryFitConstants.AUTO_WORKOUT_SWITCHES; i++) {
            payload[i] = VeryFitConstants.OFF;
        }
        payload[VeryFitConstants.AUTO_WORKOUT_WALKING] = onOff(types.contains("walking"));
        payload[VeryFitConstants.AUTO_WORKOUT_RUNNING] = onOff(types.contains("outdoor_running"));
        payload[VeryFitConstants.AUTO_WORKOUT_CYCLING] = onOff(types.contains("outdoor_cycling"));
        payload[VeryFitConstants.AUTO_WORKOUT_ELLIPTICAL] = onOff(types.contains("elliptical"));
        payload[VeryFitConstants.AUTO_WORKOUT_ROWING] = onOff(types.contains("rowing_machine"));
        payload[VeryFitConstants.AUTO_WORKOUT_PAUSE] =
                onOff(prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_WORKOUT_AUTO_PAUSE, false));
        payload[VeryFitConstants.AUTO_WORKOUT_END] =
                onOff(prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_WORKOUT_AUTO_END, false));
        return VeryFitProtocol.setting(VeryFitConstants.SETTING_AUTO_WORKOUT, payload);
    }

    /** Raise to wake. The window is the watch's own, and its screen is the only way to set it. */
    public static byte[] wristWake(final Prefs prefs) {
        final boolean enabled = prefs.getBoolean(
                DeviceSettingsPreferenceConst.PREF_LIFTWRIST_NOSHED, false);

        return VeryFitProtocol.setting(VeryFitConstants.SETTING_WRIST_WAKE,
                onOff(enabled), VeryFitConstants.WRIST_WAKE_SECONDS, VeryFitConstants.WRIST_ALL_DAY,
                (byte) 0, (byte) 0, VeryFitConstants.WRIST_END_HOUR, VeryFitConstants.WRIST_END_MINUTE);
    }

    /** The second byte is the watch's own now-playing screen, which the vendor leaves off. */
    public static byte[] music() {
        return VeryFitProtocol.setting(VeryFitConstants.SETTING_MUSIC,
                VeryFitConstants.ON, VeryFitConstants.OFF);
    }

    /** The phone's media level on the watch's fifteen-step scale. */
    public static byte[] volume(final int volume) {
        return VeryFitProtocol.setting(VeryFitConstants.SETTING_VOLUME,
                VeryFitConstants.MUSIC_VOLUME_STEPS, (byte) volume);
    }

    /**
     * The level, then the window the watch dims itself in on its own. The two trailing fields are
     * the night level and how long the screen stays lit, neither of which is exposed.
     */
    public static byte[] brightness(final Prefs prefs) {
        final int percent = prefs.getInt(DeviceSettingsPreferenceConst.PREF_SCREEN_BRIGHTNESS, 50);
        final boolean night = MiBandConst.PREF_NIGHT_MODE_SCHEDULED.equals(
                prefs.getString(MiBandConst.PREF_NIGHT_MODE, MiBandConst.PREF_NIGHT_MODE_OFF));
        final Calendar start = time(prefs, MiBandConst.PREF_NIGHT_MODE_START, "16:00");
        final Calendar end = time(prefs, MiBandConst.PREF_NIGHT_MODE_END, "07:00");

        return VeryFitProtocol.setting(VeryFitConstants.SETTING_BRIGHTNESS,
                level(percent), VeryFitConstants.BRIGHTNESS_FROM_PHONE,
                VeryFitConstants.BRIGHTNESS_AMBIENT_OFF,
                night ? VeryFitConstants.NIGHT_DIM_SCHEDULED : VeryFitConstants.NIGHT_DIM_OFF,
                hour(start), minute(start), hour(end), minute(end),
                VeryFitConstants.NIGHT_DIM_LEVEL, VeryFitConstants.BRIGHTNESS_INTERVAL);
    }

    private static byte level(final int percent) {
        final int step = VeryFitConstants.BRIGHTNESS_STEP;
        final int stops = Math.round(percent / (float) step);
        return (byte) (Math.max(1, Math.min(stops, 100 / step)) * step);
    }

    public static byte[] inactivity(final Prefs prefs) {
        final boolean enabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE, false);
        final int threshold = prefs.getInt(DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD, 60);
        final Calendar start = time(prefs, DeviceSettingsPreferenceConst.PREF_INACTIVITY_START, "06:00");
        final Calendar end = time(prefs, DeviceSettingsPreferenceConst.PREF_INACTIVITY_END, "22:00");

        return VeryFitProtocol.setting(VeryFitConstants.SETTING_INACTIVITY,
                (byte) (enabled ? 1 : 0), (byte) (threshold & 0xff), (byte) ((threshold >> 8) & 0xff),
                hour(start), minute(start), hour(end), minute(end),
                weekdays(prefs), (byte) 0, (byte) 1, (byte) 1,
                (byte) 12, (byte) 0, (byte) 14, (byte) 0);
    }

    public static byte[] hydration(final Prefs prefs) {
        final boolean enabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HYDRATION_SWITCH, false);
        final int period = prefs.getInt(DeviceSettingsPreferenceConst.PREF_HYDRATION_PERIOD, 90);
        final Calendar start = time(prefs, DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_START, "08:00");
        final Calendar end = time(prefs, DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_END, "21:00");

        return VeryFitProtocol.setting(VeryFitConstants.SETTING_HYDRATION,
                (byte) (enabled ? 1 : 0),
                hour(start), minute(start), hour(end), minute(end),
                weekdays(prefs), (byte) (period & 0xff), (byte) 0,
                (byte) 1, (byte) 12, (byte) 0, (byte) 14, (byte) 0);
    }

    /**
     * Zone thresholds as percentages of the age-derived maximum, truncated, followed by the
     * measurement window.
     */
    public static byte[] heartRate(final Prefs prefs) {
        final int max = 220 - new ActivityUser().getAge();
        final boolean enabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_AUTOHEARTRATE_SWITCH, false);
        final Calendar start = time(prefs, DeviceSettingsPreferenceConst.PREF_AUTOHEARTRATE_START, "00:00");
        final Calendar end = time(prefs, DeviceSettingsPreferenceConst.PREF_AUTOHEARTRATE_END, "23:59");

        return VeryFitProtocol.setting(VeryFitConstants.SETTING_HEART_RATE,
                zone(max, 60), zone(max, 70), zone(max, 90), (byte) max,
                zone(max, 50), zone(max, 60), zone(max, 70), zone(max, 80), zone(max, 90),
                (byte) 0x14, (byte) (enabled ? 1 : 0), (byte) 1,
                hour(start), minute(start), hour(end), minute(end));
    }

    private static byte zone(final int max, final int percent) {
        return (byte) (max * percent / 100);
    }

    private static byte weekdays(final Prefs prefs) {
        final String[] keys = {
                DeviceSettingsPreferenceConst.PREF_INACTIVITY_MO,
                DeviceSettingsPreferenceConst.PREF_INACTIVITY_TU,
                DeviceSettingsPreferenceConst.PREF_INACTIVITY_WE,
                DeviceSettingsPreferenceConst.PREF_INACTIVITY_TH,
                DeviceSettingsPreferenceConst.PREF_INACTIVITY_FR,
                DeviceSettingsPreferenceConst.PREF_INACTIVITY_SA,
                DeviceSettingsPreferenceConst.PREF_INACTIVITY_SU,
        };
        int mask = 0;
        for (int i = 0; i < keys.length; i++) {
            if (prefs.getBoolean(keys[i], false)) {
                mask |= 1 << i;
            }
        }
        return (byte) mask;
    }

    private static byte onOff(final boolean enabled) {
        return enabled ? VeryFitConstants.ON : VeryFitConstants.OFF;
    }

    private static Calendar time(final Prefs prefs, final String key, final String fallback) {
        final Date date = prefs.getTimePreference(key, fallback);
        final Calendar calendar = GregorianCalendar.getInstance();
        calendar.setTime(date);
        return calendar;
    }

    private static byte hour(final Calendar calendar) {
        return (byte) calendar.get(Calendar.HOUR_OF_DAY);
    }

    private static byte minute(final Calendar calendar) {
        return (byte) calendar.get(Calendar.MINUTE);
    }

    private static ByteBuffer buffer(final int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
