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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;

/**
 * Wire constants for the VeryFit family. Older bands and newer watches share one GATT profile and
 * one short-packet command set; the newer ones add a framed packet type and announce what they
 * support in a feature table.
 */
public final class VeryFitConstants {
    private VeryFitConstants() {
    }

    public static final UUID UUID_SERVICE = UUID.fromString(String.format(AbstractBTLEDeviceSupport.BASE_UUID, "0AF0"));
    public static final UUID UUID_CHARACTERISTIC_WRITE = UUID.fromString(String.format(AbstractBTLEDeviceSupport.BASE_UUID, "0AF6"));
    public static final UUID UUID_CHARACTERISTIC_NOTIFY = UUID.fromString(String.format(AbstractBTLEDeviceSupport.BASE_UUID, "0AF7"));
    public static final UUID UUID_CHARACTERISTIC_WRITE_HEALTH = UUID.fromString(String.format(AbstractBTLEDeviceSupport.BASE_UUID, "0AF1"));
    public static final UUID UUID_CHARACTERISTIC_NOTIFY_HEALTH = UUID.fromString(String.format(AbstractBTLEDeviceSupport.BASE_UUID, "0AF2"));

    // Framed packet: 0x33 | DA AD DA AD | 0x01 | len:2 | cmd:2 | seq:2 | payload | crc16:2, all LE.
    public static final byte FRAME_MARKER = 0x33;
    public static final byte[] FRAME_MAGIC = {(byte) 0xDA, (byte) 0xAD, (byte) 0xDA, (byte) 0xAD};
    public static final byte FRAME_VERSION = 0x01;
    /** Magic through seq, which is what the length field adds on top of the payload. */
    public static final int FRAME_HEADER_LEN = 11;

    public static final int FRAMED_NOTIFICATION = 0x0060;
    public static final int FRAMED_WORLD_CLOCK = 0x003b;
    public static final int FRAMED_WEATHER = 0x003a;
    public static final int FRAMED_MUSIC = 0x000a;
    public static final int FRAMED_ALARMS = 0x000e;
    public static final int FRAMED_ALARMS_QUERY = 0x000f;
    public static final int FRAMED_QUICK_REPLY = 0x0fff;
    public static final int FRAMED_APP_REGISTRY = 0x003c;
    public static final int FRAMED_WATCHFACE = 0x0008;
    public static final int FRAMED_WATCHFACE_LIST = 0x0031;

    /** Selects the face named in the fixed-size slot behind it. */
    public static final byte WATCHFACE_SELECT = 0x01;
    public static final int WATCHFACE_NAME_LEN = 30;
    /** The counts and the storage report the list opens with, ahead of the per-face records. */
    public static final int WATCHFACE_LIST_HEADER_LEN = 63;
    public static final int WATCHFACE_RECORD_LEN = 40;
    /** Which face is being shown, in the same slot shape the records use. */
    public static final int WATCHFACE_CURRENT_OFFSET = 5;
    public static final int WATCHFACE_TOTAL_OFFSET = 38;
    public static final int WATCHFACE_USED_OFFSET = 42;
    public static final int WATCHFACE_COUNT_OFFSET = 62;
    /** Inside a record, behind the marker, the slot it sits in and the bytes it takes. */
    public static final int WATCHFACE_RECORD_NAME = 10;
    /** Every face is a file, and the watch wants the whole file name. */
    public static final String WATCHFACE_SUFFIX = ".iwf";

    /** Add the apps that follow to the watch's list, or ask for the list it holds. */
    public static final byte REGISTRY_ADD = 0x01;
    public static final byte REGISTRY_LIST = 0x03;
    public static final int REGISTRY_ENTRY_LEN = 4;
    /**
     * The state the watch keeps per app. It answers with {@link #APP_PENDING} for one just added
     * and moves it to {@link #APP_READY} later, so the vendor's freshly registered apps sit at the
     * former until whatever settles them has happened.
     */
    public static final byte APP_READY = 0x01;
    public static final byte APP_PENDING = 0x02;

    /** Fixed-size now-playing record: state, position, then the two padded text fields. */
    public static final int MUSIC_INFO_LEN = 139;
    public static final int MUSIC_TEXT_LEN = 64;
    public static final byte MUSIC_PLAYING = 0x01;
    public static final byte MUSIC_PAUSED = 0x02;
    public static final byte MUSIC_IDLE = 0x03;
    /** The scale the watch shows the volume on; the phone's own level is quantised onto it. */
    public static final byte MUSIC_VOLUME_STEPS = 0x0f;

    // Every slot is written on every push, empty ones included.
    public static final int ALARM_RECORD_LEN = 34;
    public static final int ALARM_NAME_LEN = 24;
    /** Whether the slot holds an alarm at all; inverted against {@link #ON} and {@link #OFF}. */
    public static final byte ALARM_IN_USE = 0x55;
    public static final byte ALARM_EMPTY = (byte) 0xaa;
    public static final byte ALARM_DELAY_MINUTES = 0x0a;
    public static final byte ALARM_REPEAT_COUNT = 0x03;

    // Short packet: [group][key][payload]. An empty payload is a query, anything else a set; both
    // are answered by a notification repeating [group][key].
    public static final byte GROUP_FIRMWARE = 0x01;
    public static final byte GROUP_QUERY = 0x02;
    public static final byte GROUP_SETTING = 0x03;
    public static final byte GROUP_BIND = 0x04;
    public static final byte GROUP_NOTIFY = 0x05;
    public static final byte GROUP_APP = 0x06;
    public static final byte GROUP_LINK = 0x07;
    public static final byte GROUP_HEALTH = 0x08;
    public static final byte GROUP_RESTART = (byte) 0xf0;

    public static final byte QUERY_DEVICE = 0x01;
    public static final byte QUERY_FEATURES = 0x02;
    public static final byte QUERY_ADDRESS = 0x04;
    public static final byte QUERY_BATTERY = 0x05;
    public static final byte QUERY_FEATURES_EXTRA = 0x07;
    public static final byte QUERY_STEP_GOAL = 0x08;
    public static final byte QUERY_UNITS = 0x22;
    public static final byte QUERY_CLOCK = (byte) 0xa3;
    public static final byte QUERY_BRIGHTNESS = (byte) 0xb0;
    public static final byte QUERY_AUTO_WORKOUT = (byte) 0xea;

    public static final int FRAMED_HEALTH = 0x0004;
    public static final int FRAMED_HEALTH_TYPES = 0x0005;

    /** Fetch a type, then close it again so the watch moves on. */
    public static final byte HEALTH_FETCH = 0x00;
    public static final byte HEALTH_CLOSE = 0x01;
    /** Header in front of the summary block: the request echoed back, then three sizes. */
    public static final int HEALTH_HEADER_LEN = 14;
    public static final int HEALTH_REQUEST_LEN = 5;
    /** The announcement the round opens with has room for twenty of them. */
    public static final int HEALTH_TYPE_SLOTS = 20;

    public static final byte HEALTH_SPO2 = 0x01;
    public static final byte HEALTH_STRESS = 0x02;
    public static final byte HEALTH_HEART_RATE = 0x03;
    public static final byte HEALTH_STEPS = 0x08;
    public static final byte HEALTH_SLEEP = 0x09;
    public static final byte HEALTH_HRV = 0x11;
    public static final byte HEALTH_GPS = 0x05;
    public static final byte HEALTH_WORKOUT = 0x0e;

    /** The only sport code ever seen on the wire, and the vendor app named it. */
    public static final int WORKOUT_OUTDOOR_WALKING = 0x10;
    /** Summary of one workout, the same shape whatever the sport was. */
    public static final int WORKOUT_SUMMARY_LEN = 118;
    /** A track is blocks of a full position followed by fixes that move it. */
    public static final int GPS_BLOCK_LEN = 9;
    public static final int GPS_FIX_LEN = 5;
    public static final byte GPS_BLOCK = 0x01;
    public static final int GPS_LONGITUDE_UP = 0x40;
    public static final int GPS_LATITUDE_UP = 0x80;
    /** Coordinates are degrees and whole minutes, with ten-thousandths of a minute beside them. */
    public static final int GPS_SIGN_BIT = 0x8000;
    public static final int GPS_MINUTE_FRACTION = 10000;

    /** The version the watch was sent in the one capture of this command. */
    public static final byte WEATHER_VERSION = 0x03;
    /** The three runs the header counts off, in the lengths the vendor asks for. */
    public static final byte WEATHER_SUN_TIMES = 0x01;
    public static final byte WEATHER_HOURS = 0x30;
    public static final byte WEATHER_DAYS = 0x07;
    /** Where those runs leave the city name: 32 + 1 x 4 + 48 x 3 + 7 x 3. */
    public static final int WEATHER_FIXED_LEN = 201;
    /** As much of the name as fits the hourly run the vendor's own copy of it sits in. */
    public static final int WEATHER_CITY_LEN = 29;
    public static final int WEATHER_FORECAST_DAYS = 5;
    /** Temperatures are offset so that anything down to -100 C fits in a byte. */
    public static final int WEATHER_TEMPERATURE_OFFSET = 100;

    public static final byte SETTING_WEATHER = 0x2d;

    /** No reading was taken in that slot. */
    public static final int HEALTH_NO_VALUE = 0xff;

    public static final int SLEEP_AWAKE = 1;
    public static final int SLEEP_LIGHT = 2;
    public static final int SLEEP_DEEP = 3;
    public static final int SLEEP_REM = 4;
    public static final byte QUERY_FIRMWARE = (byte) 0xeb;
    public static final byte QUERY_MTU = (byte) 0xf0;

    public static final byte SETTING_TIME = 0x01;
    public static final byte SETTING_STEP_GOAL = 0x03;
    public static final byte SETTING_USER = 0x10;
    public static final byte SETTING_UNITS = 0x11;
    public static final byte SETTING_HOST_OS = 0x23;
    public static final byte SETTING_HEART_RATE = 0x24;
    public static final byte SETTING_FIND_PHONE = 0x26;
    public static final byte SETTING_WRIST_WAKE = 0x28;
    public static final byte SETTING_MUSIC = 0x2a;
    public static final byte SETTING_BRIGHTNESS = 0x32;
    public static final byte SETTING_AUTO_WORKOUT = 0x49;
    public static final byte SETTING_INACTIVITY = 0x47;
    public static final byte SETTING_HYDRATION = 0x60;
    public static final byte SETTING_VOLUME = (byte) 0xf0;

    public static final byte BIND_START = 0x01;
    public static final byte BIND_RELEASE = 0x02;
    public static final byte BIND_PLAIN_AUTH = 0x03;
    public static final byte BIND_AUTH = 0x05;

    public static final byte RESTART_REBOOT = 0x01;

    /** Pushed by the watch on its own; the payload has to be echoed back. */
    public static final byte LINK_EVENT = 0x40;
    /** Code `00` says the user changed something on the watch, and byte 2 says what. */
    public static final byte LINK_EVENT_CHANGED = 0x00;
    public static final byte LINK_EVENT_CHANGED_ALARMS = 0x01;
    /** Pushed by the watch when a media button on its music screen is pressed. */
    public static final byte LINK_MEDIA = 0x01;
    public static final byte MEDIA_PLAY = 0x01;
    public static final byte MEDIA_PAUSE = 0x02;
    public static final byte MEDIA_PREVIOUS = 0x04;
    public static final byte MEDIA_NEXT = 0x05;
    /** Followed by the level the watch's own slider was moved to, as a percentage. */
    public static final byte MEDIA_VOLUME = 0x0f;

    /** Pushed by the watch when its find-my-phone button is used: {@code [00 start / 01 stop][0f]}. */
    public static final byte LINK_FIND_PHONE = 0x02;
    public static final byte LINK_FIND_PHONE_STOP = 0x01;

    /** Opens and closes the watch's music screen, start/stop as the first payload byte. */
    public static final byte APP_MUSIC = 0x01;
    public static final byte APP_MUSIC_START = 0x00;
    public static final byte APP_MUSIC_STOP = 0x01;

    /** Alert on the watch, with the start/stop selector as the first payload byte. */
    public static final byte APP_FIND_DEVICE = 0x04;
    public static final byte APP_FIND_DEVICE_START = 0x00;
    public static final byte APP_FIND_DEVICE_STOP = 0x01;

    /**
     * Which movement the watch starts a workout for, one switch each, plus the two switches that
     * end one. The last two have never been set by either vendor app and are left clear.
     */
    public static final int AUTO_WORKOUT_WALKING = 0;
    public static final int AUTO_WORKOUT_RUNNING = 1;
    public static final int AUTO_WORKOUT_CYCLING = 2;
    public static final int AUTO_WORKOUT_PAUSE = 3;
    public static final int AUTO_WORKOUT_END = 4;
    public static final int AUTO_WORKOUT_ELLIPTICAL = 5;
    public static final int AUTO_WORKOUT_ROWING = 6;
    public static final int AUTO_WORKOUT_SWITCHES = 9;

    /** How long the screen stays lit after a raise, in seconds; the vendor never changes it. */
    public static final byte WRIST_WAKE_SECONDS = 0x05;
    /**
     * The window the gesture is limited to. The watch keeps its own and ignores this one, so it
     * is sent as the whole day the way the vendor app sends it.
     */
    public static final byte WRIST_ALL_DAY = 0x01;
    public static final byte WRIST_END_HOUR = 23;
    public static final byte WRIST_END_MINUTE = 59;

    /** The watch's own slider has five stops, so any percentage is rounded onto them. */
    public static final int BRIGHTNESS_STEP = 20;
    /** Set from the phone rather than left to the watch, which is what makes the write stick. */
    public static final byte BRIGHTNESS_FROM_PHONE = 0x01;
    /** The ambient-light sensor, which the vendor never turns on. */
    public static final byte BRIGHTNESS_AMBIENT_OFF = 0x00;
    public static final byte NIGHT_DIM_OFF = 0x01;
    public static final byte NIGHT_DIM_SCHEDULED = 0x03;
    /** The level held inside the night window, and how long the screen stays lit, both fixed. */
    public static final byte NIGHT_DIM_LEVEL = 0x14;
    public static final byte BRIGHTNESS_INTERVAL = 0x06;

    public static final byte ON = (byte) 0xaa;
    public static final byte OFF = (byte) 0x55;

    public static final byte UNITS_METRIC = 0x01;
    public static final byte UNITS_IMPERIAL = 0x02;

    public static final byte WEIGHT_KILOGRAM = 0x01;
    public static final byte WEIGHT_POUND = 0x02;
    public static final byte WEIGHT_STONE = 0x03;

    public static final byte TIME_MODE_24H = 0x01;
    public static final byte TIME_MODE_12H = 0x02;

    public static final byte LANGUAGE_ENGLISH = 0x02;

    /** Watch UI language, keyed by the codes Gadgetbridge's generic language list uses. */
    public static final Map<String, Byte> LANGUAGES;

    static {
        final Map<String, Byte> languages = new HashMap<>();
        languages.put("zh", (byte) 1);
        languages.put("en", (byte) 2);
        languages.put("fr", (byte) 3);
        languages.put("de", (byte) 4);
        languages.put("it", (byte) 5);
        languages.put("es", (byte) 6);
        languages.put("ja", (byte) 7);
        languages.put("pl", (byte) 8);
        languages.put("cs", (byte) 9);
        languages.put("ro", (byte) 10);
        languages.put("lt", (byte) 11);
        languages.put("nl", (byte) 12);
        languages.put("sl", (byte) 13);
        languages.put("hu", (byte) 14);
        languages.put("ru", (byte) 15);
        languages.put("uk", (byte) 16);
        languages.put("sk", (byte) 17);
        languages.put("da", (byte) 18);
        languages.put("hr", (byte) 19);
        languages.put("id", (byte) 20);
        languages.put("in", (byte) 20);
        languages.put("ko", (byte) 21);
        languages.put("hi", (byte) 22);
        languages.put("pt", (byte) 23);
        languages.put("tr", (byte) 24);
        languages.put("th", (byte) 25);
        languages.put("vi", (byte) 26);
        languages.put("my", (byte) 27);
        languages.put("fil", (byte) 28);
        languages.put("tl", (byte) 28);
        languages.put("zh_TW", (byte) 29);
        languages.put("zh_HK", (byte) 29);
        languages.put("el", (byte) 30);
        languages.put("ar", (byte) 31);
        languages.put("ms", (byte) 32);
        languages.put("sr", (byte) 33);
        languages.put("sh", (byte) 33);
        languages.put("bg", (byte) 34);
        LANGUAGES = Collections.unmodifiableMap(languages);
    }

    public static final byte GENDER_MALE = 0x00;
    public static final byte GENDER_FEMALE = 0x01;

    /**
     * Answers a 12-byte bind challenge {@code [ address(6), nonce(6) ]} with
     * {@code [ nonce, address ^ nonce ]}.
     */
    public static byte[] bindResponse(final byte[] challenge) {
        if (challenge == null || challenge.length < 12) {
            throw new IllegalArgumentException("bind challenge must be at least 12 bytes");
        }
        final byte[] response = new byte[12];
        for (int i = 0; i < 6; i++) {
            response[i] = challenge[i + 6];
            response[i + 6] = (byte) (challenge[i] ^ challenge[i + 6]);
        }
        return response;
    }
}
