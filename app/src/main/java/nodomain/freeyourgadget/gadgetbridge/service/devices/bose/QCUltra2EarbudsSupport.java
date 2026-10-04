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
package nodomain.freeyourgadget.gadgetbridge.service.devices.bose;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdateDeviceState;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.AbstractHeadphoneBTBRDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;

import static nodomain.freeyourgadget.gadgetbridge.service.devices.bose.BoseProtocol.*;

public class QCUltra2EarbudsSupport extends AbstractHeadphoneBTBRDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(QCUltra2EarbudsSupport.class);

    private BoseFrameParser frameParser = new BoseFrameParser();
    private volatile boolean modeChangePending;

    public QCUltra2EarbudsSupport() {
        super(LOG, 1024);
        addSupportedService(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected int getRfcommChannel() {
        // Two SPP services are advertised. The Bose app uses channel 1 for BMAP.
        return 1;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        // Discard any partial frame left by the previous connection.
        frameParser = new BoseFrameParser();
        modeChangePending = false;
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.write(connectHandshake());
        builder.write(enableNotificationsForFunctionBlocks(BLOCK_PRODUCT_INFO, BLOCK_STATUS, BLOCK_AUDIO_MODES));
        builder.write(getFirmwareVersion());
        builder.write(getBattery());
        builder.write(getAudioMode());
        return builder;
    }

    @Override
    public void onSocketRead(final byte[] bytes) {
        for (final byte[] frame : frameParser.feed(bytes)) {
            handleFrame(frame);
        }
    }

    private void handleFrame(final byte[] frame) {
        final int block = frame[0] & 0xff;
        final int function = frame[1] & 0xff;
        final int operator = frame[2] & 0x0f;
        final byte[] payload = Arrays.copyOfRange(frame, 4, frame.length);

        if (operator == OP_ERROR) {
            LOG.warn("Bose error on block {} function {}: {}", block, function,
                    payload.length > 0 ? errorName(payload[0] & 0xff) : "Empty error");
            if (block == BLOCK_AUDIO_MODES && function == FUNCTION_AUDIO_MODE && modeChangePending) {
                modeChangePending = false;
                // Clear the optimistic selection even if the subsequent read also fails.
                evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences(
                        DeviceSettingsPreferenceConst.PREF_BOSE_AUDIO_MODE, ""));
                // Read back the actual mode after rejection.
                final TransactionBuilder builder = createTransactionBuilder("read audio mode after error");
                builder.write(getAudioMode());
                builder.queue();
            }
            return;
        }

        if (block == BLOCK_PRODUCT_INFO && operator == OP_STATUS) {
            if (function == FUNCTION_INIT_HANDSHAKE && payload.length > 0 && getDevice().isInitializing()) {
                evaluateGBDeviceEvent(new GBDeviceEventUpdateDeviceState(GBDevice.State.INITIALIZED));
            } else if (function == FUNCTION_FIRMWARE_VERSION && payload.length > 0) {
                final GBDeviceEventVersionInfo versionInfo = new GBDeviceEventVersionInfo();
                versionInfo.fwVersion = new String(payload, StandardCharsets.UTF_8);
                evaluateGBDeviceEvent(versionInfo);
            }
        } else if (block == BLOCK_STATUS && function == FUNCTION_BATTERY && operator == OP_STATUS) {
            for (final EarbudBattery battery : decodeEarbudBatteries(payload)) {
                final GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
                batteryInfo.batteryIndex = battery.component == 3 ? 0 : battery.component == 2 ? 1 : 2;
                batteryInfo.level = battery.level;
                // The remaining bytes have not been verified as charging indicators.
                evaluateGBDeviceEvent(batteryInfo);
            }
        } else if (block == BLOCK_AUDIO_MODES && function == FUNCTION_AUDIO_MODE
                && (operator == OP_STATUS || operator == OP_RESULT)) {
            // An unchanged selection returns RESULT without a separate STATUS notification.
            if (operator == OP_RESULT) {
                modeChangePending = false;
            }
            final int mode = decodeAudioMode(payload);
            if (mode >= 0) {
                evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences(
                        DeviceSettingsPreferenceConst.PREF_BOSE_AUDIO_MODE, String.valueOf(mode)));
            }
        }
    }

    @Override
    public void onSendConfiguration(@NonNull final String config) {
        if (!DeviceSettingsPreferenceConst.PREF_BOSE_AUDIO_MODE.equals(config)) {
            super.onSendConfiguration(config);
            return;
        }
        final String value = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getString(config, "");
        final int mode;
        try {
            mode = Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            LOG.warn("Ignoring invalid Bose audio mode: {}", value);
            return;
        }
        if (mode < AUDIO_MODE_QUIET || mode > AUDIO_MODE_IMMERSION) {
            LOG.warn("Ignoring unsupported Bose audio mode: {}", mode);
            return;
        }
        final TransactionBuilder builder = createTransactionBuilder("set audio mode");
        modeChangePending = true;
        builder.write(setAudioMode(mode));
        builder.queue();
    }
}
