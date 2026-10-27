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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.devices;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcorePacket;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_SOUNDCORE_EQUALIZER_PRESET;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_VOLUME;

public class SoundcoreMotionPlusProtocol extends SoundcoreProtocolImplV1 {
    private static final int HEADER_LENGTH = 9;

    private static final short CMD_SET_VOLUME = (short) 0x8101;
    private static final short CMD_SET_EQUALIZER = (short) 0x8102;
    private static final short CMD_NOTIFY_EQUALIZER = (short) 0x0102;

    public SoundcoreMotionPlusProtocol(final GBDevice device) {
        super(device);
    }

    @Override
    protected byte[] encodeRequest(final short command) {
        return encodeCommand(command, new byte[0]);
    }

    @Override
    protected byte[] encodeCommand(final short command, final byte[] payload) {
        // Motion+ frames have no proprietary checksum.
        return ByteBuffer.allocate(HEADER_LENGTH + payload.length)
                .put(new byte[]{0x08, (byte) 0xee, 0x00, 0x00, 0x00})
                .put(BLETypeConversions.fromUint16(command & 0xffff))
                .put(BLETypeConversions.fromUint16(HEADER_LENGTH + payload.length))
                .put(payload)
                .array();
    }

    @Override
    protected GBDeviceEvent[] decodeCommand(final short command, final byte[] payload) {
        if (command == CMD_NOTIFY_EQUALIZER && payload.length == 1) {
            return new GBDeviceEvent[]{new GBDeviceEventUpdatePreferences(
                    PREF_SOUNDCORE_EQUALIZER_PRESET, presetPreference(payload[0]))};
        }
        return super.decodeCommand(command, payload);
    }

    @Override
    protected SoundcorePacket decodePacket(final byte[] data) {
        if (data == null || data.length < HEADER_LENGTH
                || data[0] != 0x09 || data[1] != (byte) 0xff
                || data[2] != 0x00 || data[3] != 0x00 || data[4] != 0x01) {
            return null;
        }
        final int length = BLETypeConversions.toUint16(data, 7);
        if (length != data.length) {
            return null;
        }
        return new SoundcorePacket((short) BLETypeConversions.toUint16(data, 5),
                Arrays.copyOfRange(data, HEADER_LENGTH, length));
    }

    @Override
    public byte[] encodeSendConfiguration(final String config) {
        switch (config) {
            case PREF_VOLUME:
                final int volume = getDevicePrefs().getInt(PREF_VOLUME, 8);
                return encodeByteCommand(CMD_SET_VOLUME, volume);
            case PREF_SOUNDCORE_EQUALIZER_PRESET:
                // CUSTOM preset ID is sent as FF (255), not FE (254),
                // incompatible with current list of presets.
                int preset = Integer.parseInt(getDevicePrefs().getString(config, "0"));
                if (preset == 254) { preset = 255; }
                return encodeByteCommand(CMD_SET_EQUALIZER, preset);
            default:
                return super.encodeSendConfiguration(config);
        }
    }

    @Override
    protected GBDeviceEvent[] decodeDeviceInfo(final byte[] payload) {
        if (payload == null || payload.length < 36) {
            return new GBDeviceEvent[0];
        }
        final Map<String, Object> preferences = new HashMap<>();
        final int volume = Math.max(Byte.toUnsignedInt(payload[2]), 16);
        preferences.put(PREF_VOLUME, volume);
        preferences.put(PREF_SOUNDCORE_EQUALIZER_PRESET, presetPreference(payload[26]));
        return new GBDeviceEvent[]{
                buildVersionInfo(payload, 5),
                handleBatteryInfo(payload[1], 0)[0],
                new GBDeviceEventUpdatePreferences(preferences)
        };
    }

    private String presetPreference(final byte value) {
        final int preset = Byte.toUnsignedInt(value);
        return String.valueOf(preset == 255 ? 254 : preset);
    }

    @Override
    protected GBDeviceEvent[] decodeBatteryInfo(final byte[] payload) {
        if (payload == null || payload.length != 1) {
            return new GBDeviceEvent[0];
        }
        return handleBatteryInfo(payload[0], 0);
    }
}
