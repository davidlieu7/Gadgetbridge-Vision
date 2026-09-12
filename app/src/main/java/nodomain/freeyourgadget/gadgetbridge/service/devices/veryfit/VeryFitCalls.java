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
package nodomain.freeyourgadget.gadgetbridge.service.devices.veryfit;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * The watch takes calls as a Bluetooth headset, on a classic address of its own that it reports
 * next to the one this link is on. Nothing about that goes over the link: the phone bonds the
 * address the way it would any headset, and the vendor app leaves that to the system settings.
 * This does the bond from ours, and connects the headset profile once it is in place.
 */
@SuppressLint("MissingPermission")
class VeryFitCalls {
    private static final Logger LOG = LoggerFactory.getLogger(VeryFitCalls.class);

    private final VeryFitSupport support;
    private BroadcastReceiver bondReceiver;

    VeryFitCalls(final VeryFitSupport support) {
        this.support = support;
    }

    void pair(final String address) {
        final BluetoothDevice device = remote(address);
        if (device == null) {
            return;
        }
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            GB.toast(context().getString(R.string.veryfit_calls_paired), 3, GB.INFO);
            connectHeadset(device);
            return;
        }
        watchBond(address);
        boolean started;
        try {
            started = (Boolean) BluetoothDevice.class.getMethod("createBond", int.class)
                    .invoke(device, BluetoothDevice.TRANSPORT_BREDR);
        } catch (final Exception e) {
            started = device.createBond();
        }
        LOG.info("Bonding {} for calls: {}", address, started);
        GB.toast(context().getString(R.string.veryfit_calls_pairing), 3, GB.INFO);
    }

    void unpair(final String address) {
        final BluetoothDevice device = remote(address);
        if (device == null) {
            return;
        }
        try {
            BluetoothDevice.class.getMethod("removeBond").invoke(device);
            GB.toast(context().getString(R.string.veryfit_calls_unpaired), 3, GB.INFO);
        } catch (final Exception e) {
            LOG.warn("Failed to remove the bond with {}", address, e);
        }
    }

    void dispose() {
        if (bondReceiver != null) {
            try {
                context().unregisterReceiver(bondReceiver);
            } catch (final IllegalArgumentException ignored) {
            }
            bondReceiver = null;
        }
    }

    private BluetoothDevice remote(final String address) {
        if (address == null) {
            GB.toast(context().getString(R.string.veryfit_calls_no_address), 3, GB.WARN);
            return null;
        }
        return BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
    }

    private void connectHeadset(final BluetoothDevice device) {
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(context(), new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(final int profile, final BluetoothProfile proxy) {
                try {
                    proxy.getClass().getMethod("connect", BluetoothDevice.class).invoke(proxy, device);
                } catch (final Exception e) {
                    LOG.warn("Headset connect failed", e);
                }
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(profile, proxy);
            }

            @Override
            public void onServiceDisconnected(final int profile) {
            }
        }, BluetoothProfile.HEADSET);
    }

    private void watchBond(final String address) {
        if (bondReceiver != null) {
            return;
        }
        bondReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                final int before = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                if (device == null || !address.equalsIgnoreCase(device.getAddress())) {
                    return;
                }
                if (state == BluetoothDevice.BOND_BONDED) {
                    GB.toast(context.getString(R.string.veryfit_calls_paired), 3, GB.INFO);
                    connectHeadset(device);
                    dispose();
                } else if (state == BluetoothDevice.BOND_NONE && before == BluetoothDevice.BOND_BONDING) {
                    GB.toast(context.getString(R.string.veryfit_calls_pairing_failed), 3, GB.WARN);
                    dispose();
                }
            }
        };
        context().registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
    }

    private Context context() {
        return support.getContext();
    }
}
