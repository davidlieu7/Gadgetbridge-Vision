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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointDevice;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointPairingActivity;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreV20iCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class SoundcoreV20iProtocol extends SoundcoreProtocolImplV1 {
    private static final int DEVICE_INFO_HEADER_LENGTH = 30;
    private static final int LEFT_LONG_PRESS_OFFSET = 65;
    private static final int LONG_PRESS_FUNCTION_PREFIX = 0xf0;
    private static final int TAP_FUNCTION_CODE_MASK = 0x0f;

    private final List<MultipointDevice> pairedDevices = new ArrayList<>();
    private String activeSourceAddress;

    public SoundcoreV20iProtocol(final GBDevice device) {
        super(device);
    }

    @Override
    protected GBDeviceEvent[] decodeDeviceInfo(final byte[] payload) {
        if (payload.length < DEVICE_INFO_HEADER_LENGTH) {
            return new GBDeviceEvent[0];
        }
        if (payload.length > LEFT_LONG_PRESS_OFFSET) {
            final String function = decodeLongPressFunction(payload[LEFT_LONG_PRESS_OFFSET]);
            if (function != null) {
                getDevicePrefs().getPreferences().edit().putString(
                        DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT,
                        function).apply();
            }
        }
        final GBDeviceEvent[] batteries = decodeBatteryInfo(new byte[]{payload[2], payload[3]});
        return new GBDeviceEvent[]{
            batteries[0],
            batteries[1],
            buildVersionInfo(
                readString(payload, 4, 5),
                readString(payload, 9, 5),
                readString(payload, 14, 16)
            )
        };
    }

    @Override
    protected GBDeviceEvent[] decodeBatteryInfo(final byte[] payload) {
        if (payload.length < 2) {
            return new GBDeviceEvent[0];
        }
        // Two-earbud coordinators use indices 0 and 1, without a charging-case slot.
        return new GBDeviceEvent[]{
                buildBatteryInfo(0, Byte.toUnsignedInt(payload[0]) * getBatteryMultiplier()),
                buildBatteryInfo(1, Byte.toUnsignedInt(payload[1]) * getBatteryMultiplier())
        };
    }

    private static String decodeLongPressFunction(final byte function) {
        final int encodedFunction = Byte.toUnsignedInt(function);
        if ((encodedFunction & LONG_PRESS_FUNCTION_PREFIX) != LONG_PRESS_FUNCTION_PREFIX) {
            return null;
        }
        final int code = encodedFunction & TAP_FUNCTION_CODE_MASK;
        for (SoundcoreEarbudsCoordinator.TapFunction tapFunction : SoundcoreEarbudsCoordinator.TapFunction.values()) {
            if (tapFunction.getCode() == code) {
                return tapFunction.name();
            }
        }
        return null;
    }

    @Override
    protected GBDeviceEvent[] decodePairedDevices(final byte[] payload) {
        decodePairedDevicesPayload(payload);
        return new GBDeviceEvent[0];
    }

    @Override
    protected GBDeviceEvent[] decodeConnectionStatus(final byte[] payload) {
        decodeConnectionStatusPayload(payload);
        return new GBDeviceEvent[0];
    }

    @Override
    protected GBDeviceEvent[] decodeCommand(final short command, final byte[] payload) {
        // Setter replies acknowledge receipt, not the resulting device state.
        return new GBDeviceEvent[0];
    }

    @Override
    public byte[] encodeSendConfiguration(final String config) {
        final Prefs prefs = getDevicePrefs();
        switch (config) {
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_LEFT:
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT:
                final SoundcoreEarbudsCoordinator.TapFunction function;
                try {
                    function = SoundcoreEarbudsCoordinator.TapFunction.valueOf(prefs.getString(config, "NONE"));
                } catch (IllegalArgumentException e) {
                    return null;
                }
                final byte functionByte = (byte) (LONG_PRESS_FUNCTION_PREFIX | function.getCode());
                return encodeControlFunction(config.equals(
                        DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_LONG_PRESS_ACTION_RIGHT),
                        (byte) 0x01, functionByte);
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_GAMING_MODE:
                return encodeBooleanCommand(CMD_SET_GAMING_MODE, prefs.getBoolean(config, false));
            case SoundcoreV20iCoordinator.PREF_LIGHTS_ENABLED:
                return encodeBooleanCommand(CMD_SET_LIGHTS_ENABLED, prefs.getBoolean(config, false));
            case SoundcoreV20iCoordinator.PREF_LIGHT_MODE:
                final int mode;
                try {
                    mode = Integer.parseInt(prefs.getString(config, "0"));
                } catch (NumberFormatException e) {
                    return null;
                }
                return mode >= 0 && mode <= 3 ? encodeByteCommand(CMD_SET_LIGHT_MODE, (byte) mode) : null;
            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_AUTO_POWER_OFF:
                final int duration;
                try {
                    duration = Integer.parseInt(prefs.getString(config, "2"));
                } catch (NumberFormatException e) {
                    return null;
                }
                return duration >= 0 && duration <= 4
                        ? encodeAutoPowerOff(CMD_SET_AUTO_POWER_OFF, duration, (byte) 0x03) : null;
            default:
                return super.encodeSendConfiguration(config);
        }
    }

    private void decodePairedDevicesPayload(final byte[] payload) {
        if (payload.length < 2) {
            return;
        }
        final List<MultipointDevice> devices = new ArrayList<>();
        int offset = 2; // skip the 2-byte header
        while (offset + 8 <= payload.length) {
            final int entrySize = Byte.toUnsignedInt(payload[offset]);
            if (entrySize < 8 || offset + entrySize > payload.length) {
                break;
            }
            final boolean connected = payload[offset + 1] != 0;
            final String address = formatMacAddress(payload, offset + 2);
            final int nameLen = entrySize - 8;
            int nameEnd = 0;
            while (nameEnd < nameLen && payload[offset + 8 + nameEnd] != 0) {
                nameEnd++;
            }
            final String name = new String(payload, offset + 8, nameEnd, StandardCharsets.UTF_8);
            devices.add(new MultipointDevice(address, name, connected, false, ((SoundcoreEarbudsCoordinator) getDevice().getDeviceCoordinator())
                    .getSupportedMultipointActions().contains(MultipointPairingActivity.ACTION_MULTIPOINT_FORGET_DEVICE)));
            offset += entrySize;
        }
        pairedDevices.clear();
        pairedDevices.addAll(devices);
        broadcastPairedDevices(pairedDevices, activeSourceAddress);
    }

    private void decodeConnectionStatusPayload(final byte[] payload) {
        if (payload.length != 7 || (payload[0] != 0x00 && payload[0] != 0x01)) {
            return;
        }
        activeSourceAddress = payload[0] == 0x01 ? formatMacAddress(payload, 1) : null;
        broadcastPairedDevices(pairedDevices, activeSourceAddress);
    }


}
