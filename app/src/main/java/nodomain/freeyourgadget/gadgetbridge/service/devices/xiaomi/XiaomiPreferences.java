/*  Copyright (C) 2023-2024 José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.XiaomiWorkoutType;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.WearCommon;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public final class XiaomiPreferences {
    public static final String PREF_ALARM_SLOTS = "alarm_slots";
    public static final String PREF_REMINDER_SLOTS = "reminder_slots";
    public static final String PREF_CANNED_MESSAGES_MIN = "canned_messages_min";
    public static final String PREF_CANNED_MESSAGES_MAX = "canned_messages_max";
    public static final String PREF_WORKOUT_TYPES = "workout_types";
    public static final String PREF_WIDGET_SCREENS = "widget_screens"; // FIXME the value is a protobuf hex string
    public static final String PREF_WIDGET_PARTS = "widget_parts"; // FIXME the value is a protobuf hex string

    public static final String FEAT_WEAR_MODE = "feat_wear_mode";
    public static final String FEAT_DEVICE_ACTIONS = "feat_device_actions";
    public static final String FEAT_DISPLAY_ITEMS = "feat_display_items";
    public static final String FEAT_STRESS = "feat_stress";
    public static final String FEAT_SPO2 = "feat_spo2";
    public static final String FEAT_PASSWORD = "feat_password";
    public static final String FEAT_INACTIVITY = "feat_inactivity";
    public static final String FEAT_SLEEP_MODE_SCHEDULE = "feat_sleep_mode_schedule";
    public static final String FEAT_GOAL_NOTIFICATION = "feat_goal_notification";
    public static final String FEAT_GOAL_SECONDARY = "feat_goal_secondary";
    public static final String FEAT_VITALITY_SCORE = "feat_vitality_score";
    public static final String FEAT_SCREEN_ON_ON_NOTIFICATIONS = "feat_screen_on_on_notifications";
    public static final String FEAT_CAMERA_REMOTE = "feat_camera_remote";
    public static final String FEAT_WIDGETS = "feat_widgets";
    public static final String FEAT_MULTIPLE_WEATHER_LOCATIONS = "feat_multiple_weather_locations";

    /**
     * Wall-clock spans a Sleep as Android synthetic workout covered, as {@code "<startMs>-<endMs>"}
     * entries. The band charges those minutes to that workout at a rate its sport type fixes, so
     * the calories it reports for them were never burnt.
     */
    public static final String PREF_SAA_SESSION_WINDOWS = "xiaomi_saa_session_windows";

    private static final long SAA_WINDOW_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000L;

    private XiaomiPreferences() {
        // util class
    }

    /**
     * The recorded Sleep as Android windows, oldest first, each as a {@code {startMs, endMs}} pair.
     */
    public static List<long[]> sleepAsAndroidWindows(final String deviceAddress) {
        final Set<String> stored = GBApplication.getDeviceSpecificSharedPrefs(deviceAddress)
                .getStringSet(PREF_SAA_SESSION_WINDOWS, Collections.emptySet());

        final List<long[]> windows = new ArrayList<>(stored.size());
        for (final String entry : stored) {
            final long[] window = parseWindow(entry);
            if (window != null) {
                windows.add(window);
            }
        }
        windows.sort((a, b) -> Long.compare(a[0], b[0]));

        return windows;
    }

    public static void rememberSleepAsAndroidWindow(final String deviceAddress,
                                                    final long startMs,
                                                    final long endMs) {
        final long oldest = System.currentTimeMillis() - SAA_WINDOW_MAX_AGE_MS;

        final Set<String> entries = new HashSet<>();
        for (final long[] window : sleepAsAndroidWindows(deviceAddress)) {
            if (window[1] >= oldest) {
                entries.add(window[0] + "-" + window[1]);
            }
        }
        entries.add(startMs + "-" + endMs);

        GBApplication.getDeviceSpecificSharedPrefs(deviceAddress)
                .edit()
                .putStringSet(PREF_SAA_SESSION_WINDOWS, entries)
                .apply();
    }

    @Nullable
    private static long[] parseWindow(final String entry) {
        // Both bounds are epoch milliseconds, so the separator cannot be mistaken for a sign.
        final int separator = entry.indexOf('-');
        if (separator <= 0) {
            return null;
        }

        try {
            final long start = Long.parseLong(entry.substring(0, separator));
            final long end = Long.parseLong(entry.substring(separator + 1));
            return end > start ? new long[]{start, end} : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    public static String prefFromHourMin(final WearCommon.HourMinute hourMinute) {
        return String.format(Locale.ROOT, "%02d:%02d", hourMinute.getHour(), hourMinute.getMinute());
    }

    public static WearCommon.HourMinute prefToHourMin(final Date date) {
        final Calendar calendar = GregorianCalendar.getInstance();
        calendar.setTime(date);

        return WearCommon.HourMinute.newBuilder()
                .setHour(calendar.get(Calendar.HOUR_OF_DAY))
                .setMinute(calendar.get(Calendar.MINUTE))
                .build();
    }

    public static Date toDate(final WearCommon.Date date, final WearCommon.Time time) {
        // For some reason, the watch expects those in UTC...
        // TODO double-check with official app, this does not make sense
        final Calendar calendar = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.set(
                date.getYear(), date.getMonth() - 1, date.getDay(),
                time.getHour(), time.getMinute(), time.getSecond()
        );

        return calendar.getTime();
    }

    public static boolean keepActivityDataOnDevice(final GBDevice gbDevice) {
        final Prefs prefs = new Prefs(GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress()));
        return prefs.getBoolean("keep_activity_data_on_device", false);
    }
}
