/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.*;

import java.util.Arrays;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.TapFunction;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class SoundcoreSleepA30Protocol extends SoundcoreProtocolImplV1 {
    public static final String ACTION_TOGGLE_LOCAL_PLAYBACK = "soundcore_toggle_local_playback";
    private static final int BATTERY_OFFSET = 1;

    private final int[] currentFunctions = {-1, -1, -1, -1};
    private static final String[] TAP_PREFERENCES = {
        PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT,
        PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT,
        PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_LEFT,
        PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_RIGHT,
    };

    public SoundcoreSleepA30Protocol(final GBDevice device) {
        super(device);
    }

    @Override
    protected int getBatteryOffset() {
        return BATTERY_OFFSET;
    }

    @Override
    protected GBDeviceEvent[] decodeDeviceInfo(final byte[] payload) {
        if (payload == null || payload.length < 51) return new GBDeviceEvent[0];
        // Sleep A30's version header starts two bytes earlier than Liberty's.
        decodeTapControls(Arrays.copyOfRange(payload, 45, 51));
        final GBDeviceEvent[] batteries = handleBatteryInfo(
            new byte[]{payload[2], payload[3]}, getBatteryMultiplier(), getBatteryOffset()
        );
        final GBDeviceEvent version = buildVersionInfo(
            readString(payload, 4, 5),
            readString(payload, 36, 5), // charging case, fw2
            readString(payload, 14, 16)
        );
        final GBDeviceEventUpdatePreferences preferences = new GBDeviceEventUpdatePreferences();
        decodeBooleanPreference(payload, 65, PREF_SOUNDCORE_LISTENING_MODE, preferences, "0", "1");
        decodeBooleanPreference(payload, 69, PREF_SOUNDCORE_BATTERY_LOW_TONE, preferences);
        decodeBooleanPreference(payload, 137, PREF_SOUNDCORE_POWER_OFF_TONE, preferences);
        decodeBooleanPreference(payload, 138, PREF_SOUNDCORE_MODE_TONE, preferences);
        decodeBooleanPreference(payload, 141, PREF_SOUNDCORE_CALLS_ENABLED, preferences);
        decodeBooleanPreference(payload, 143, PREF_SOUNDCORE_SNORE_MASKING, preferences);
        decodeBooleanPreference(payload, 147, PREF_SOUNDCORE_ANC_TONE, preferences);
        return new GBDeviceEvent[]{
            batteries[0], batteries[1],
            version,
            preferences
        };
    }

    private static void decodeBooleanPreference(final byte[] payload, final int offset, final String key,
                                                final GBDeviceEventUpdatePreferences preferences) {
        decodeBooleanPreference(payload, offset, key, preferences, false, true);
    }

    private static void decodeBooleanPreference(final byte[] payload, final int offset, final String key,
                                                final GBDeviceEventUpdatePreferences preferences,
                                                final Object falseValue, final Object trueValue) {
        if (payload.length > offset && isBoolean(payload[offset])) {
            preferences.withPreference(key, payload[offset] == 1 ? trueValue : falseValue);
        }
    }

    // header, four control slots, trailer.
    private void decodeTapControls(final byte[] payload) {
        if (payload == null || payload.length != 6 || payload[0] != 6 || payload[5] != (byte) 0xff) return;
        final android.content.SharedPreferences.Editor editor = getDevicePrefs().getPreferences().edit();
        for (int i = 0; i < currentFunctions.length; i++) {
            currentFunctions[i] = payload[i + 1] & 0x0f;
            for (final TapFunction function : TapFunction.values()) {
                if (function.getCode() == currentFunctions[i]) {
                    editor.putString(TAP_PREFERENCES[i], function.name());
                    break;
                }
            }
        }
        editor.apply();
    }

    @Override
    protected GBDeviceEvent[] decodeAcknowledgement(final short command, final byte[] payload) {
        if (command == CMD_SET_TAP_CONTROLS_FUNCTION || command == CMD_RESET_TAP_CONTROLS) {
            decodeTapControls(payload);
        }
        return new GBDeviceEvent[0];
    }

    @Override
    protected GBDeviceEvent[] decodeCommand(final short command, final byte[] payload) {
        if (command == CMD_NOTIFY_LISTENING_MODE && payload != null
                && payload.length == 1 && isBoolean(payload[0])) {
            return new GBDeviceEvent[]{listeningModeEvent(payload[0])};
        }
        if (command == CMD_NOTIFY_LOCAL_AUDIO && payload != null && payload.length == 11) {
            final GBDeviceEventUpdatePreferences preferences = new GBDeviceEventUpdatePreferences();
            if (isBoolean(payload[9])) {
                preferences.withPreference(PREF_SOUNDCORE_LOCAL_PLAYBACK, payload[9] == 1);
            }
            final int volume = payload[10] & 0xff;
            if (volume <= 65) {
                preferences.withPreference(PREF_SOUNDCORE_LOCAL_VOLUME, volume);
            }
            if (!preferences.preferences.isEmpty()) {
                return new GBDeviceEvent[]{preferences};
            }
        }
        if ((command == CMD_NOTIFY_ANC_ENABLED || command == CMD_SET_ANC_ENABLED)
                && payload != null && payload.length == 1 && (payload[0] == 0 || payload[0] == 1)) {
            return new GBDeviceEvent[]{new GBDeviceEventUpdatePreferences(PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL,
                    payload[0] == 1 ? "noise_cancelling" : "off")};
        }
        // Empty setter responses are acknowledgements, not settings readback.
        return new GBDeviceEvent[0];
    }

    private static boolean isBoolean(final byte value) {
        return value == 0 || value == 1;
    }

    private static GBDeviceEventUpdatePreferences listeningModeEvent(final byte mode) {
        return new GBDeviceEventUpdatePreferences(PREF_SOUNDCORE_LISTENING_MODE, mode == 1 ? "1" : "0");
    }

    public byte[] encodeTapFunction(final boolean right, final boolean triple, final int previous, final TapFunction function) {
        return encodeControlFunction(right, (byte) (triple ? 5 : 0),
                (byte) ((previous << 4) | function.getCode()));
    }

    @Override
    public byte[] encodeSendConfiguration(final String config) {
        final Prefs prefs = getDevicePrefs();
        switch (config) {
            case ACTION_TOGGLE_LOCAL_PLAYBACK:
                if (!"1".equals(prefs.getString(PREF_SOUNDCORE_LISTENING_MODE, "0"))) return null;
                return encodeLocalPlayback(!prefs.getBoolean(PREF_SOUNDCORE_LOCAL_PLAYBACK, false));
            case PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL:
                return encodeAncEnabled("noise_cancelling".equals(prefs.getString(config, "off")));
            case PREF_SOUNDCORE_LISTENING_MODE:
                return encodeListeningMode("1".equals(prefs.getString(config, "0")));
            case PREF_SOUNDCORE_DEFAULT_LISTENING_MODE:
                return encodeBooleanCommand(CMD_SET_DEFAULT_LISTENING_MODE, "1".equals(prefs.getString(config, "0")));
            case PREF_SOUNDCORE_LOCAL_PLAYBACK: return encodeLocalPlayback(prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_LOCAL_VOLUME: return encodeLocalVolume(prefs.getInt(config, 0));
            case PREF_SOUNDCORE_SLEEP_AUDIO: return encodeSleepAudio(prefs.getString(config, "keep"));
            case PREF_SOUNDCORE_SNORE_MASKING: return encodeBooleanCommand(CMD_SET_SNORE_MASKING, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_CONTROL_TOUCH_DISABLED: return encodeBooleanCommand(CMD_SET_TAP_CONTROLS_ENABLE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_BATTERY_LOW_TONE: return encodeBooleanCommand(CMD_SET_BATTERY_LOW_TONE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_CALLS_ENABLED: return encodeBooleanCommand(CMD_SET_CALLS_ENABLED, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_POWER_OFF_TONE: return encodeBooleanCommand(CMD_SET_POWER_OFF_TONE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_ANC_TONE: return encodeBooleanCommand(CMD_SET_ANC_TONE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_MODE_TONE: return encodeBooleanCommand(CMD_SET_MODE_TONE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_CONTROL_RESET: return encodeRequest(CMD_RESET_TAP_CONTROLS);
            case PREF_SOUNDCORE_FIND_LEFT_EARBUD:
            case PREF_SOUNDCORE_FIND_RIGHT_EARBUD:
                return encodeCommand(CMD_SET_FIND_DEVICE, new byte[]{
                        encodeBoolean("1".equals(prefs.getString(PREF_SOUNDCORE_FIND_LEFT_EARBUD, "0"))),
                        encodeBoolean("1".equals(prefs.getString(PREF_SOUNDCORE_FIND_RIGHT_EARBUD, "0"))), 0});
            case PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT:
            case PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT:
            case PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_LEFT:
            case PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_RIGHT:
                final TapFunction function;
                try { function = TapFunction.valueOf(prefs.getString(config, "NONE")); }
                catch (IllegalArgumentException e) { return null; }
                final boolean right = config.endsWith("right");
                final boolean triple = config.contains("triple");
                final int previous = currentFunctions[(triple ? 2 : 0) + (right ? 1 : 0)];
                // Do not build a transition from a locally selected but unconfirmed function.
                if (previous < 0) return null;
                return encodeTapFunction(right, triple, previous, function);
            default: return super.encodeSendConfiguration(config);
        }
    }
}
