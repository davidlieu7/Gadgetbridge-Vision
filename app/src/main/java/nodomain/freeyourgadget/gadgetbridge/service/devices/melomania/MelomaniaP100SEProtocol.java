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
package nodomain.freeyourgadget.gadgetbridge.service.devices.melomania;

import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdateDeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.melomania.MelomaniaP100SEConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;

/**
 * Cambridge Audio custom API over Qualcomm GAIA v3 RFCOMM framing.
 *
 * Frame: FF <version=3> <flags=0> <payload length>
 *        vendor(2, BE) command(2, BE) payload
 * command word = feature << 9 | type << 7 | command id
 *
 */
public class MelomaniaP100SEProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(MelomaniaP100SEProtocol.class);

    private static final int SOF = 0xFF;
    private static final int FRAME_VERSION = 3;
    private static final int FLAG_CHECKSUM = 0x01;
    private static final int FLAG_LENGTH_EXTENSION = 0x02;

    private static final int VENDOR = 0x0B9E;

    private static final int TYPE_COMMAND = 0;
    private static final int TYPE_NOTIFICATION = 1;
    private static final int TYPE_RESPONSE = 2;
    private static final int TYPE_ERROR = 3;

    private static final int FEATURE_MISC = 0;
    private static final int FEATURE_MODES = 1;
    private static final int FEATURE_CODEC = 2;

    private static final int MISC_GET_MANUFACTURER = 0;
    private static final int MISC_GET_MODEL = 1;
    private static final int MISC_GET_API_LEVEL = 2;
    private static final int MISC_GET_BATTERY_STATE = 3;
    private static final int MISC_GET_FIRMWARE_VERSIONS = 4;
    private static final int MISC_GET_BLUETOOTH_ADDRESSES = 5;
    private static final int MISC_GET_LANGUAGE = 6;
    private static final int MISC_SET_LANGUAGE = 7;
    private static final int MISC_SET_AUTO_POWER_DOWN_TIMEOUT = 8;
    private static final int MISC_GET_AUTO_POWER_DOWN_TIMEOUT = 9;
    private static final int MISC_GET_LEFT_RIGHT_BALANCE = 11;
    private static final int MISC_SET_FIND_HEADPHONES_ALERT = 12;
    private static final int MISC_GET_FIND_HEADPHONES_ALERT = 13;
    private static final int MISC_SWITCH_AMBIENT_NOISE_MODE = 14;
    private static final int MISC_SET_AMBIENT_NOISE_MODE = 15;
    private static final int MISC_GET_AMBIENT_NOISE_MODE = 16;
    private static final int MISC_GET_VARIANT = 17;
    private static final int MISC_SET_AMBIENT_NOISE_TOGGLE_OPTIONS = 18;
    private static final int MISC_GET_AMBIENT_NOISE_TOGGLE_OPTIONS = 19;
    private static final int MISC_SET_LOUDNESS = 20;
    private static final int MISC_GET_LOUDNESS = 21;
    private static final int MISC_GET_DEVICES = 22;
    private static final int MISC_SET_SIDE_TONE = 25;
    private static final int MISC_GET_SIDE_TONE = 26;
    private static final int MISC_GET_SPATIAL_AUDIO = 28;

    private static final int MODES_SET_GAMING = 0;
    private static final int MODES_GET_GAMING = 1;
    private static final int MODES_GET_FLIGHT = 3;
    private static final int MODES_GET_AUDIO_MODE = 5;
    private static final int MODES_SET_PROXIMITY = 6;
    private static final int MODES_GET_PROXIMITY = 7;
    private static final int MODES_GET_MONO = 9;
    private static final int MODES_GET_SLEEP = 11;

    private static final int CODEC_SET_ENABLED = 0;
    private static final int CODEC_GET_ENABLED = 1;
    private static final int CODEC_GET_AVAILABLE = 2;
    private static final int CODEC_GET_CURRENT = 3;

    private static final String[] CODEC_NAMES = {
            "SBC", "AAC-LC", "aptX", "aptX Adaptive", "aptX Adaptive Lossless",
            "aptX HD", "aptX LL", "aptX Mono", "aptX Voice", "LDAC", "LHDC", "LC3",
    };

    private final GBDevice device;
    private byte[] rxBuffer = new byte[8192];
    private int rxLength = 0;
    private String model = "Melomania P100 SE";

    public MelomaniaP100SEProtocol(final GBDevice device) {
        this.device = device;
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    public void resetBuffer() {
        rxLength = 0;
    }

    public List<byte[]> encodeInitialRequests() {
        final List<byte[]> requests = new ArrayList<>();
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_MANUFACTURER));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_MODEL));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_VARIANT));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_API_LEVEL));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_FIRMWARE_VERSIONS));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_BLUETOOTH_ADDRESSES));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_BATTERY_STATE));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_AMBIENT_NOISE_MODE));
        requests.add(encodeFrame(FEATURE_MODES, MODES_GET_GAMING));
        requests.add(encodeFrame(FEATURE_MODES, MODES_GET_PROXIMITY));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_LOUDNESS));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_SIDE_TONE));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_AUTO_POWER_DOWN_TIMEOUT));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_LANGUAGE));
        requests.add(encodeFrame(FEATURE_CODEC, CODEC_GET_CURRENT));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_AMBIENT_NOISE_TOGGLE_OPTIONS));
        requests.add(encodeFrame(FEATURE_MISC, MISC_GET_DEVICES));
        return requests;
    }

    public byte[] encodeSendConfiguration(final String config) {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());

        if (DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_MODE.equals(config)) {
            final int mode = MelomaniaP100SEConstants.ancModeId(
                    prefs.getString(config, MelomaniaP100SEConstants.ANC_MODE_OFF));
            // The vendor app sends both: Switch changes the active mode, Set
            // makes it stick (a lone Switch was observed to revert).
            final byte[] payload = new byte[]{(byte) mode};
            final byte[] switchFrame = encodeFrame(FEATURE_MISC, MISC_SWITCH_AMBIENT_NOISE_MODE, payload);
            final byte[] setFrame = encodeFrame(FEATURE_MISC, MISC_SET_AMBIENT_NOISE_MODE, payload);
            final byte[] both = new byte[switchFrame.length + setFrame.length];
            System.arraycopy(switchFrame, 0, both, 0, switchFrame.length);
            System.arraycopy(setFrame, 0, both, switchFrame.length, setFrame.length);
            return both;
        }

        if (DeviceSettingsPreferenceConst.PREF_MELOMANIA_GAMING_MODE.equals(config)) {
            return encodeFrame(FEATURE_MODES, MODES_SET_GAMING,
                    new byte[]{(byte) (prefs.getBoolean(config, false) ? 1 : 0)});
        }

        if (DeviceSettingsPreferenceConst.PREF_MELOMANIA_WEAR_DETECTION.equals(config)) {
            return encodeFrame(FEATURE_MODES, MODES_SET_PROXIMITY,
                    new byte[]{(byte) (prefs.getBoolean(config, true) ? 1 : 0)});
        }

        if (DeviceSettingsPreferenceConst.PREF_MELOMANIA_LOUDNESS.equals(config)) {
            return encodeFrame(FEATURE_MISC, MISC_SET_LOUDNESS,
                    new byte[]{(byte) (prefs.getBoolean(config, true) ? 1 : 0)});
        }

        if (DeviceSettingsPreferenceConst.PREF_MELOMANIA_SIDE_TONE.equals(config)) {
            return encodeFrame(FEATURE_MISC, MISC_SET_SIDE_TONE,
                    new byte[]{(byte) (prefs.getBoolean(config, true) ? 1 : 0)});
        }

        if (DeviceSettingsPreferenceConst.PREF_MELOMANIA_AUTO_POWER_DOWN.equals(config)) {
            return encodeFrame(FEATURE_MISC, MISC_SET_AUTO_POWER_DOWN_TIMEOUT,
                    encodeTimeout(prefs.getString(config, "3600")));
        }

        if (DeviceSettingsPreferenceConst.PREF_MELOMANIA_LANGUAGE.equals(config)) {
            final int language = MelomaniaP100SEConstants.languageId(
                    prefs.getString(config, MelomaniaP100SEConstants.LANGUAGE_ENGLISH));
            return encodeFrame(FEATURE_MISC, MISC_SET_LANGUAGE,
                    new byte[]{(byte) language});
        }

        if (DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_NORMAL.equals(config)
                || DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_NOISE_CANCELLING.equals(config)
                || DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_TRANSPARENCY.equals(config)) {
            return encodeFrame(FEATURE_MISC, MISC_SET_AMBIENT_NOISE_TOGGLE_OPTIONS,
                    new byte[]{
                            (byte) (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_NORMAL, true) ? 1 : 0),
                            (byte) (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_NOISE_CANCELLING, true) ? 1 : 0),
                            (byte) (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_TRANSPARENCY, true) ? 1 : 0),
                    });
        }

        LOG.warn("Unhandled configuration key {}", config);
        return null;
    }

    private static byte[] encodeTimeout(final String value) {
        // The preference stores the total number of seconds (0 = never).
        int total = 3600;
        if (value != null) {
            if ("off".equalsIgnoreCase(value.trim()) || "never".equalsIgnoreCase(value.trim())) {
                total = 0;
            } else {
                try {
                    total = Math.max(0, Integer.parseInt(value.trim()));
                } catch (final NumberFormatException ignored) {
                    total = 3600;
                }
            }
        }
        final int hours = clamp(total / 3600);
        final int minutes = clamp((total % 3600) / 60);
        final int seconds = clamp(total % 60);
        return new byte[]{(byte) hours, (byte) minutes, (byte) seconds};
    }

    private static int clamp(final int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static byte[] encodeFrame(final int feature, final int command) {
        return encodeFrame(feature, command, new byte[0]);
    }

    private static byte[] encodeFrame(final int feature, final int command, final byte[] payload) {
        final byte[] safePayload = payload == null ? new byte[0] : payload;
        if (safePayload.length > 250) {
            throw new IllegalArgumentException("payload too large for GAIA v3 frame");
        }
        final byte[] frame = new byte[8 + safePayload.length];
        frame[0] = (byte) SOF;
        frame[1] = (byte) FRAME_VERSION;
        frame[2] = 0; // no checksum, no length extension
        frame[3] = (byte) safePayload.length;
        frame[4] = (byte) ((VENDOR >> 8) & 0xFF);
        frame[5] = (byte) (VENDOR & 0xFF);
        final int word = (feature << 9) | (TYPE_COMMAND << 7) | (command & 0x7F);
        frame[6] = (byte) ((word >> 8) & 0xFF);
        frame[7] = (byte) (word & 0xFF);
        System.arraycopy(safePayload, 0, frame, 8, safePayload.length);
        return frame;
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    public GBDeviceEvent[] decodeResponse(final byte[] responseData) {
        if (responseData == null || responseData.length == 0) {
            return new GBDeviceEvent[0];
        }
        append(responseData);

        final List<GBDeviceEvent> events = new ArrayList<>();
        final Map<String, Object> prefUpdates = new LinkedHashMap<>();

        byte[] content;
        while ((content = nextFrame()) != null) {
            handleFrame(content, events, prefUpdates);
        }

        if (!prefUpdates.isEmpty()) {
            events.add(new GBDeviceEventUpdatePreferences(prefUpdates));
        }

        return events.toArray(new GBDeviceEvent[0]);
    }

    private void append(final byte[] data) {
        if (rxLength + data.length > rxBuffer.length) {
            rxBuffer = Arrays.copyOf(rxBuffer, Math.max(rxBuffer.length * 2, rxLength + data.length));
        }
        System.arraycopy(data, 0, rxBuffer, rxLength, data.length);
        rxLength += data.length;
    }

    private byte[] nextFrame() {
        while (true) {
            if (rxLength < 4) {
                return null;
            }
            if ((rxBuffer[0] & 0xFF) != SOF) {
                int next = -1;
                for (int i = 1; i < rxLength; i++) {
                    if ((rxBuffer[i] & 0xFF) == SOF) {
                        next = i;
                        break;
                    }
                }
                if (next < 0) {
                    rxLength = 0;
                    return null;
                }
                consume(next);
                continue;
            }

            final int version = rxBuffer[1] & 0xFF;
            final int flags = rxBuffer[2] & 0xFF;
            final boolean extended = version >= 4 && (flags & FLAG_LENGTH_EXTENSION) != 0;
            final int header = extended ? 5 : 4;
            if (rxLength < header) {
                return null;
            }
            final int length = extended
                    ? ((rxBuffer[3] & 0xFF) << 8) | (rxBuffer[4] & 0xFF)
                    : (rxBuffer[3] & 0xFF);
            final int checksum = (flags & FLAG_CHECKSUM) != 0 ? 1 : 0;
            final int total = header + 4 + length + checksum;
            if (total < 8 || total > 65545) {
                consume(1);
                continue;
            }
            if (rxLength < total) {
                return null;
            }
            final byte[] content = Arrays.copyOfRange(rxBuffer, header, header + 4 + length);
            consume(total);
            return content;
        }
    }

    private void consume(final int count) {
        System.arraycopy(rxBuffer, count, rxBuffer, 0, rxLength - count);
        rxLength -= count;
    }

    private void handleFrame(final byte[] content,
                             final List<GBDeviceEvent> events,
                             final Map<String, Object> prefUpdates) {
        if (content.length < 4) {
            return;
        }
        final int vendor = ((content[0] & 0xFF) << 8) | (content[1] & 0xFF);
        if (vendor != VENDOR) {
            return;
        }
        final int word = ((content[2] & 0xFF) << 8) | (content[3] & 0xFF);
        final int type = (word >> 7) & 0x03;
        final int feature = (word >> 9) & 0x7F;
        final int command = word & 0x7F;
        final byte[] payload = Arrays.copyOfRange(content, 4, content.length);

        if (type == TYPE_ERROR) {
            final int status = payload.length > 0 ? payload[0] & 0xFF : -1;
            LOG.warn("GAIA error feature={} command={} status={}", feature, command, status);
            return;
        }
        if (type != TYPE_RESPONSE && type != TYPE_NOTIFICATION) {
            return;
        }

        try {
            switch (feature) {
                case FEATURE_MISC:
                    handleMisc(command, payload, events, prefUpdates);
                    break;
                case FEATURE_MODES:
                    handleModes(command, payload, events, prefUpdates);
                    break;
                case FEATURE_CODEC:
                    handleCodec(command, payload, events, prefUpdates);
                    break;
                default:
                    LOG.debug("Unhandled feature {} command {}", feature, command);
                    break;
            }
        } catch (final RuntimeException e) {
            LOG.warn("Failed to decode feature={} command={}", feature, command, e);
        }
    }

    private void handleMisc(final int command, final byte[] payload,
                            final List<GBDeviceEvent> events,
                            final Map<String, Object> prefUpdates) {
        switch (command) {
            case MISC_GET_MANUFACTURER:
                addInfo(events, "MANUFACTURER", cstr(payload));
                break;
            case MISC_GET_MODEL:
                model = cstr(payload);
                device.setModel(model);
                addInfo(events, "MODEL", model);
                break;
            case MISC_GET_API_LEVEL:
                if (payload.length >= 2) {
                    addInfo(events, "API_LEVEL", (payload[0] & 0xFF) + "." + (payload[1] & 0xFF));
                }
                break;
            case MISC_GET_BATTERY_STATE:
                addBatteryEvent(events, payload);
                break;
            case MISC_GET_FIRMWARE_VERSIONS:
                if (payload.length >= 3) {
                    final GBDeviceEventVersionInfo info = new GBDeviceEventVersionInfo();
                    info.fwVersion = versionAt(payload, 0);
                    if (payload.length >= 6) {
                        info.fwVersion2 = versionAt(payload, 3);
                    }
                    info.hwVersion = model;
                    events.add(info);
                }
                break;
            case MISC_GET_BLUETOOTH_ADDRESSES:
                addInfo(events, "BLUETOOTH_ADDRESS", decodeAddresses(payload));
                break;
            case MISC_GET_LANGUAGE:
            case MISC_SET_LANGUAGE:
                if (payload.length >= 1) {
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_LANGUAGE,
                            MelomaniaP100SEConstants.languageName(payload[0] & 0xFF));
                }
                break;
            case MISC_SET_AUTO_POWER_DOWN_TIMEOUT:
            case MISC_GET_AUTO_POWER_DOWN_TIMEOUT:
                if (payload.length >= 3) {
                    final int totalSeconds = (payload[0] & 0xFF) * 3600
                            + (payload[1] & 0xFF) * 60
                            + (payload[2] & 0xFF);
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_AUTO_POWER_DOWN,
                            String.valueOf(totalSeconds));
                }
                break;
            case MISC_GET_LEFT_RIGHT_BALANCE:
                if (payload.length >= 2) {
                    addInfo(events, "LEFT_RIGHT_BALANCE",
                            (payload[0] != 0 ? "left " : "right ") + (payload[1] & 0xFF) + "%");
                }
                break;
            case MISC_SET_FIND_HEADPHONES_ALERT:
            case MISC_GET_FIND_HEADPHONES_ALERT:
                if (payload.length >= 1) {
                    addInfo(events, "FIND_HEADPHONES_ALERT", payload[0] != 0 ? "on" : "off");
                }
                break;
            case MISC_SWITCH_AMBIENT_NOISE_MODE:
            case MISC_SET_AMBIENT_NOISE_MODE:
            case MISC_GET_AMBIENT_NOISE_MODE:
                if (payload.length >= 1) {
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_MODE,
                            MelomaniaP100SEConstants.ancModeName(payload[0] & 0xFF));
                }
                break;
            case MISC_GET_VARIANT:
                addInfo(events, "VARIANT", cstr(payload));
                break;
            case MISC_SET_AMBIENT_NOISE_TOGGLE_OPTIONS:
            case MISC_GET_AMBIENT_NOISE_TOGGLE_OPTIONS:
                if (payload.length >= 3) {
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_NORMAL,
                            payload[0] != 0);
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_NOISE_CANCELLING,
                            payload[1] != 0);
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_ANC_CYCLE_TRANSPARENCY,
                            payload[2] != 0);
                }
                break;
            case MISC_SET_LOUDNESS:
            case MISC_GET_LOUDNESS:
                if (payload.length >= 1) {
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_LOUDNESS,
                            payload[0] != 0);
                }
                break;
            case MISC_GET_DEVICES:
                addInfo(events, "CONNECTED_DEVICES", decodeDevices(payload));
                break;
            case MISC_SET_SIDE_TONE:
            case MISC_GET_SIDE_TONE:
                if (payload.length >= 1) {
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_SIDE_TONE,
                            payload[0] != 0);
                }
                break;
            case MISC_GET_SPATIAL_AUDIO:
                if (payload.length >= 2) {
                    addInfo(events, "SPATIAL_AUDIO",
                            (payload[0] != 0 ? "on" : "off") + ", mode " + (payload[1] & 0xFF));
                }
                break;
            default:
                break;
        }
    }

    private void handleModes(final int command, final byte[] payload,
                             final List<GBDeviceEvent> events,
                             final Map<String, Object> prefUpdates) {
        switch (command) {
            case MODES_SET_GAMING:
            case MODES_GET_GAMING:
                if (payload.length >= 1) {
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_GAMING_MODE,
                            payload[0] != 0);
                }
                break;
            case MODES_SET_PROXIMITY:
            case MODES_GET_PROXIMITY:
                if (payload.length >= 1) {
                    prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_WEAR_DETECTION,
                            payload[0] != 0);
                }
                break;
            case MODES_GET_FLIGHT:
                if (payload.length >= 1) {
                    addInfo(events, "FLIGHT_MODE", payload[0] != 0 ? "on" : "off");
                }
                break;
            case MODES_GET_AUDIO_MODE:
                if (payload.length >= 1) {
                    addInfo(events, "AUDIO_MODE", (payload[0] & 0xFF) == 0
                            ? "battery saver" : "high quality");
                }
                break;
            case MODES_GET_MONO:
                if (payload.length >= 1) {
                    addInfo(events, "MONO_MODE", payload[0] != 0 ? "on" : "off");
                }
                break;
            case MODES_GET_SLEEP:
                if (payload.length >= 1) {
                    addInfo(events, "SLEEP_MODE", payload[0] != 0 ? "on" : "off");
                }
                break;
            default:
                break;
        }
    }

    private void handleCodec(final int command, final byte[] payload,
                             final List<GBDeviceEvent> events,
                             final Map<String, Object> prefUpdates) {
        if (command == CODEC_GET_CURRENT) {
            if (payload.length >= 1) {
                final String codec = codecName(payload[0] & 0xFF);
                prefUpdates.put(DeviceSettingsPreferenceConst.PREF_MELOMANIA_CODEC, codec);
                addInfo(events, "AUDIO_CODEC", codec);
            }
            return;
        }
        if (command == CODEC_SET_ENABLED || command == CODEC_GET_ENABLED
                || command == CODEC_GET_AVAILABLE) {
            final String codecs = decodeCodecList(payload);
            addInfo(events, command == CODEC_GET_AVAILABLE ? "AVAILABLE_CODECS" : "ENABLED_CODECS",
                    codecs);
        }
    }

    private void addBatteryEvent(final List<GBDeviceEvent> events, final byte[] payload) {
        if (payload.length < 9) {
            return;
        }
        final GBDeviceEventBatteryInfo battery = new GBDeviceEventBatteryInfo();
        final int percent = payload[4] & 0xFF;
        battery.level = percent == 0xFF ? GBDevice.BATTERY_UNKNOWN : percent;
        final int charging = payload[1] & 0xFF;
        if (charging == 0) {
            battery.state = BatteryState.BATTERY_NORMAL;
        } else if (charging == 1) {
            battery.state = BatteryState.BATTERY_CHARGING;
        } else {
            battery.state = BatteryState.UNKNOWN;
        }
        events.add(battery);
    }

    private static void addInfo(final List<GBDeviceEvent> events, final String name, final String details) {
        if (details != null) {
            events.add(new GBDeviceEventUpdateDeviceInfo(name, details));
        }
    }

    private static String cstr(final byte[] payload) {
        int end = 0;
        while (end < payload.length && payload[end] != 0) {
            end++;
        }
        return new String(payload, 0, end, StandardCharsets.UTF_8);
    }

    private static String versionAt(final byte[] payload, final int offset) {
        return (payload[offset] & 0xFF) + "." + (payload[offset + 1] & 0xFF)
                + "." + (payload[offset + 2] & 0xFF);
    }

    private static String decodeAddresses(final byte[] payload) {
        final StringBuilder builder = new StringBuilder();
        for (int offset = 0; offset + 5 < payload.length; offset += 6) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(String.format(Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X",
                    payload[offset] & 0xFF, payload[offset + 1] & 0xFF,
                    payload[offset + 2] & 0xFF, payload[offset + 3] & 0xFF,
                    payload[offset + 4] & 0xFF, payload[offset + 5] & 0xFF));
        }
        return builder.toString();
    }

    private static String decodeDevices(final byte[] payload) {
        final StringBuilder builder = new StringBuilder();
        for (int offset = 0; offset + 25 < payload.length; offset += 26) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            final int id = payload[offset] & 0xFF;
            final String name = cstr(Arrays.copyOfRange(payload, offset + 1, offset + 21));
            builder.append(id).append(": ").append(name);
        }
        return builder.toString();
    }

    private static String codecName(final int id) {
        if (id >= 0 && id < CODEC_NAMES.length) {
            return CODEC_NAMES[id];
        }
        return "unknown(" + id + ")";
    }

    private static String decodeCodecList(final byte[] payload) {
        final StringBuilder builder = new StringBuilder();
        for (final byte value : payload) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(codecName(value & 0xFF));
        }
        return builder.toString();
    }
}
