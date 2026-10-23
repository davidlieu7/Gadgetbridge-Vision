/*  Copyright (C) 2024-2026 Gadgetbridge contributors

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

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.AbstractHeadphoneBTBRDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;

public class MelomaniaP100SESupport extends AbstractHeadphoneBTBRDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(MelomaniaP100SESupport.class);

    // Standard Serial Port Profile.
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private MelomaniaP100SEProtocol protocol;

    public MelomaniaP100SESupport() {
        super(LOG, 4096);
        addSupportedService(SPP_UUID);
    }

    @Override
    public void setContext(@NonNull final GBDevice gbDevice,
                           @NonNull final BluetoothAdapter btAdapter,
                           @NonNull final Context context) {
        super.setContext(gbDevice, btAdapter, context);
        protocol = new MelomaniaP100SEProtocol(gbDevice);
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        protocol.resetBuffer();
        for (final byte[] request : protocol.encodeInitialRequests()) {
            builder.write(request);
            // The vendor app paces requests 100 ms apart.
            builder.sleep(100);
        }
        return builder.setDeviceState(GBDevice.State.INITIALIZED);
    }

    @Override
    public void onSocketRead(final byte[] data) {
        if (protocol == null) {
            LOG.warn("Received data before the protocol was initialized");
            return;
        }
        final GBDeviceEvent[] events = protocol.decodeResponse(data);
        if (events == null) {
            return;
        }
        for (final GBDeviceEvent event : events) {
            if (event != null) {
                evaluateGBDeviceEvent(event);
            }
        }
    }

    @Override
    public void onSendConfiguration(final String config) {
        if (protocol == null) {
            LOG.warn("Ignoring configuration {}, protocol is not initialized", config);
            return;
        }
        final byte[] bytes = protocol.encodeSendConfiguration(config);
        if (bytes == null) {
            LOG.warn("No frame for configuration {}", config);
            return;
        }
        if (!isConnected()) {
            LOG.warn("Not sending configuration {}, device is not connected", config);
            return;
        }
        createTransactionBuilder("send configuration").write(bytes).queue();
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }
}
