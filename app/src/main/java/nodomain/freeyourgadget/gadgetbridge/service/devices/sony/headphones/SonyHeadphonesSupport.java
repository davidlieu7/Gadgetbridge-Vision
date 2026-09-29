/*  Copyright (C) 2021-2024 Arjan Schrijver, José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.ParcelUuid;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointDevice;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointPairingActivity;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.devices.sony.headphones.SonyHeadphonesCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones.deviceevents.SonyHeadphonesEnqueueRequestEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones.deviceevents.SonyHeadphonesMultipointEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones.protocol.Message;
import nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones.protocol.Request;
import nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones.protocol.impl.v2.params.MultipointAction;
import nodomain.freeyourgadget.gadgetbridge.service.serial.AbstractHeadphoneSerialDeviceSupportV2;

public class SonyHeadphonesSupport extends AbstractHeadphoneSerialDeviceSupportV2<SonyHeadphonesProtocol> {
    private static final Logger LOG = LoggerFactory.getLogger(SonyHeadphonesSupport.class);

    private static final UUID BTRFCOMM_UUID_V1 = UUID.fromString("96CC203E-5068-46ad-B32D-E316F5E069BA");
    private static final UUID BTRFCOMM_UUID_V2 = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2");

    // Track whether we got the first init reply
    private final Handler handler = new Handler();
    private int initRetries = 0;

    private final ByteBuffer packetBuffer = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);

    private final List<MultipointDevice> lastKnownMultipointDevices = new ArrayList<>();

    @Nullable
    private String fixedMultipointDevice = null;

    /**
     * Sometimes the headphones will ignore the first init request, so we retry a few times
     * TODO: Implement this in a more elegant way. Ideally, we should retry every command for which we didn't get an ACK.
     */
    private final Runnable initSendRunnable = () -> {
        // If we still haven't got any reply, re-send the init
        if (!mDeviceProtocol.hasProtocolImplementation()) {
            if (initRetries++ < 2) {
                LOG.warn("Init retry {}", initRetries);

                mDeviceProtocol.decreasePendingAcks();
                final TransactionBuilder builder = createTransactionBuilder("init retry " + initRetries);
                builder.write(mDeviceProtocol.encodeInit());
                builder.queue();
                scheduleInitRetry();
            } else {
                LOG.error("Failed to start headphones init after {} tries", initRetries);
                if (GBApplication.getPrefs().getAutoReconnect(getDevice())) {
                    LOG.debug("will wait for reconnect");
                    gbDevice.setUpdateState(GBDevice.State.WAITING_FOR_RECONNECT, getContext());
                } else {
                    LOG.debug("disconnecting");
                    gbDevice.setUpdateState(GBDevice.State.NOT_CONNECTED, getContext());
                }
            }
        }
    };

    @Override
    public void setContext(final GBDevice gbDevice, final BluetoothAdapter btAdapter, final Context context) {
        super.setContext(gbDevice, btAdapter, context);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_ENABLE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_DISABLE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_GET_STATUS);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_GET_DEVICES);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_CONNECT_DEVICE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_DISCONNECT_DEVICE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_SET_ACTIVE_DEVICE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_UNSET_ACTIVE_DEVICE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_START_PAIRING);

        LocalBroadcastManager.getInstance(context).registerReceiver(multipointReceiver, intentFilter);
    }

    private final BroadcastReceiver multipointReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final GBDevice device = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
            if (device == null || !device.getAddress().equals(getDevice().getAddress())) {
                return;
            }

            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case MultipointPairingActivity.ACTION_MULTIPOINT_ENABLE:
                    setConnectTwoDevices(true);
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_DISABLE:
                    setConnectTwoDevices(false);
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_GET_STATUS:
                    broadcastMultipointStatus(isConnectTwoDevicesEnabled());
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_GET_DEVICES:
                    if (!lastKnownMultipointDevices.isEmpty()) {
                        broadcastMultipointDevices();
                    }

                    LOG.info("Requesting multipoint device list");

                    sendMultipointRequests(mDeviceProtocol.buildMultipointDeviceListRequest());
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_CONNECT_DEVICE:
                case MultipointPairingActivity.ACTION_MULTIPOINT_DISCONNECT_DEVICE: {
                    final String address = intent.getStringExtra(MultipointPairingActivity.EXTRA_DEVICE_ADDRESS);
                    if (address == null) {
                        LOG.warn("No device address in {}", action);
                        break;
                    }

                    final MultipointAction multipointAction =
                            MultipointPairingActivity.ACTION_MULTIPOINT_DISCONNECT_DEVICE.equals(action)
                                    ? MultipointAction.DISCONNECT
                                    : MultipointAction.CONNECT;

                    LOG.info("Multipoint {} for {}", multipointAction, address);

                    sendMultipointRequests(mDeviceProtocol.buildMultipointConnectionRequest(address, multipointAction));
                    break;
                }
                case MultipointPairingActivity.ACTION_MULTIPOINT_SET_ACTIVE_DEVICE: {
                    final String address = intent.getStringExtra(MultipointPairingActivity.EXTRA_DEVICE_ADDRESS);
                    if (address == null) {
                        LOG.warn("No device address in {}", action);
                        break;
                    }

                    LOG.info("Fixing multipoint audio to {}", address);

                    fixedMultipointDevice = address;
                    broadcastMultipointDevices();

                    sendMultipointRequests(mDeviceProtocol.buildMultipointActiveDeviceRequest(address));
                    break;
                }
                case MultipointPairingActivity.ACTION_MULTIPOINT_UNSET_ACTIVE_DEVICE:
                    LOG.info("Unfixing multipoint audio");

                    final String previouslyFixedDevice = fixedMultipointDevice;
                    fixedMultipointDevice = null;
                    broadcastMultipointDevices();

                    final List<Request> unfixRequests = new ArrayList<>();

                    for (final MultipointDevice multipointDevice : lastKnownMultipointDevices) {
                        if (multipointDevice.isConnected()
                                && !multipointDevice.getAddress().equalsIgnoreCase(previouslyFixedDevice)) {
                            LOG.info("Switching multipoint audio to {} before releasing fixation",
                                    multipointDevice.getAddress());
                            unfixRequests.addAll(
                                    mDeviceProtocol.buildMultipointActiveDeviceRequest(multipointDevice.getAddress())
                            );
                            break;
                        }
                    }

                    unfixRequests.addAll(mDeviceProtocol.buildMultipointActiveDeviceFixedRequest(false));
                    sendMultipointRequests(unfixRequests);
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_START_PAIRING: {
                    final boolean enabled = intent.getBooleanExtra(
                            MultipointPairingActivity.EXTRA_PAIRING_ENABLED, false
                    );

                    LOG.info("Multipoint pairing mode: {}", enabled);

                    sendMultipointRequests(mDeviceProtocol.buildMultipointPairingModeRequest(enabled));
                    break;
                }
            }
        }
    };

    private void sendMultipointRequests(final List<Request> requests) {
        if (requests.isEmpty()) {
            return;
        }

        if (!getDevice().isInitialized()) {
            LOG.warn("Device is not initialized, ignoring multipoint request");
            return;
        }

        evaluateGBDeviceEvent(new SonyHeadphonesEnqueueRequestEvent(requests));
    }

    private boolean isConnectTwoDevicesEnabled() {
        return GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getBoolean(DeviceSettingsPreferenceConst.PREF_SONY_CONNECT_TWO_DEVICES, false);
    }

    private void setConnectTwoDevices(final boolean enabled) {
        LOG.info("Setting connect to two devices: {}", enabled);

        GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .edit()
                .putBoolean(DeviceSettingsPreferenceConst.PREF_SONY_CONNECT_TWO_DEVICES, enabled)
                .apply();

        onSendConfiguration(DeviceSettingsPreferenceConst.PREF_SONY_CONNECT_TWO_DEVICES);

        broadcastMultipointStatus(enabled);
    }

    private void broadcastMultipointDevices() {
        final List<MultipointDevice> devices = new ArrayList<>(lastKnownMultipointDevices.size());

        for (final MultipointDevice device : lastKnownMultipointDevices) {
            devices.add(new MultipointDevice(
                    device.getAddress(),
                    device.getName(),
                    device.isConnected(),
                    device.getAddress().equalsIgnoreCase(fixedMultipointDevice)
            ));
        }

        SonyHeadphonesMultipointEvent.deviceList(devices).evaluate(getContext(), getDevice());
    }

    private void broadcastMultipointStatus(final boolean enabled) {
        SonyHeadphonesMultipointEvent.status(enabled).evaluate(getContext(), getDevice());
    }

    @Override
    protected SonyHeadphonesProtocol createDeviceProtocol() {
        return new SonyHeadphonesProtocol(getDevice());
    }

    @Override
    protected int getConnectDelayMillis() {
        // Connecting too fast fails with an IOException
        return 500;
    }

    @Override
    public void evaluateGBDeviceEvent(GBDeviceEvent deviceEvent) {
        if (deviceEvent instanceof SonyHeadphonesMultipointEvent multipointEvent
                && multipointEvent.getType() == SonyHeadphonesMultipointEvent.Type.DEVICE_LIST) {
            lastKnownMultipointDevices.clear();
            lastKnownMultipointDevices.addAll(multipointEvent.getDevices());

            if (fixedMultipointDevice != null
                    && lastKnownMultipointDevices.stream().noneMatch(
                            d -> d.isConnected() && d.getAddress().equalsIgnoreCase(fixedMultipointDevice))) {
                LOG.info("Fixed multipoint device {} is gone", fixedMultipointDevice);
                fixedMultipointDevice = null;
            }

            broadcastMultipointDevices();
            return;
        }

        if (deviceEvent instanceof SonyHeadphonesEnqueueRequestEvent enqueueRequestEvent) {
            mDeviceProtocol.enqueueRequests(enqueueRequestEvent.getRequests());

            if (mDeviceProtocol.getPendingAcks() == 0) {
                // There are no pending acks, send one request from the queue
                // TODO: A more elegant way of scheduling these?
                final TransactionBuilder builder = createTransactionBuilder("enqueue request");
                builder.write(mDeviceProtocol.getFromQueue());
                builder.queue();
            }

            return;
        }

        super.evaluateGBDeviceEvent(deviceEvent);
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        packetBuffer.clear();
        builder.write(mDeviceProtocol.encodeInit());
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        scheduleInitRetry();
        return builder;
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            handler.removeCallbacksAndMessages(null);
            fixedMultipointDevice = null;
            LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(multipointReceiver);
            super.dispose();
        }
    }

    @Override
    public void onSocketRead(final byte[] data) {
        packetBuffer.put(data);
        packetBuffer.flip();

        while (packetBuffer.hasRemaining()) {
            final int start = packetBuffer.position();
            packetBuffer.mark();

            final byte header = packetBuffer.get();
            if (header != Message.MESSAGE_HEADER) {
                LOG.warn("Unexpected header byte: {}", String.format("0x%02x", header));
                continue;
            }

            // Find trailer after header
            int end = -1;
            for (int i = start + 1; i < packetBuffer.limit(); i++) {
                if (packetBuffer.get(i) == Message.MESSAGE_TRAILER) {
                    end = i;
                    break;
                }
            }

            if (end < 0) {
                // Header found but trailer not yet available
                packetBuffer.reset();
                break;
            }

            final byte[] messageBytes = new byte[end - start + 1];

            packetBuffer.position(start);
            packetBuffer.get(messageBytes);

            // Hand it upstream, to the protocol
            try {
                super.onSocketRead(messageBytes);
            } catch (final Exception e) {
                LOG.error("Error handling message", e);
            }
        }

        packetBuffer.compact();
    }

    @Override
    protected UUID getSupportedService() {
        boolean hasV1 = false;
        boolean hasV2 = false;
        boolean preferV2 = ((SonyHeadphonesCoordinator) getDevice().getDeviceCoordinator()).preferServiceV2();
        final ParcelUuid[] uuids = getBluetoothDeviceUuids();
        for (final ParcelUuid uuid : uuids) {
            if (uuid.getUuid().equals(BTRFCOMM_UUID_V1)) {
                LOG.info("Found Sony UUID V1");
                hasV1 = true;
            } else if (uuid.getUuid().equals(BTRFCOMM_UUID_V2)) {
                LOG.info("Found Sony UUID V2");
                hasV2 = true;
            }
        }

        if (hasV2) {
            LOG.info("Using Sony UUID V2");
            return BTRFCOMM_UUID_V2;
        } else if (hasV1) {
            LOG.info("Using Sony UUID V1");
            return BTRFCOMM_UUID_V1;
        }

        LOG.warn("Failed to find a known Sony UUID, will fallback to {}", (preferV2 ? "V2" : "V1"));

        return preferV2 ? BTRFCOMM_UUID_V2 : BTRFCOMM_UUID_V1;
    }

    private void scheduleInitRetry() {
        LOG.info("Scheduling init retry");

        handler.postDelayed(initSendRunnable, 1250);
    }
}
