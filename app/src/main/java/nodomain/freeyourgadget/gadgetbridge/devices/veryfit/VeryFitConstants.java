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
    public static final byte QUERY_FIRMWARE = (byte) 0xeb;
    public static final byte QUERY_MTU = (byte) 0xf0;

    public static final byte SETTING_TIME = 0x01;
    public static final byte SETTING_STEP_GOAL = 0x03;
    public static final byte SETTING_USER = 0x10;
    public static final byte SETTING_UNITS = 0x11;
    public static final byte SETTING_HOST_OS = 0x23;
    public static final byte SETTING_HEART_RATE = 0x24;
    public static final byte SETTING_FIND_PHONE = 0x26;
    public static final byte SETTING_MUSIC = 0x2a;
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
