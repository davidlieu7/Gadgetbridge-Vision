/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.devices.oura;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.BondingInterface;
import nodomain.freeyourgadget.gadgetbridge.util.BondingUtil;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * The ring only accepts writes over an encrypted link, so it has to be bonded before connecting.
 * A ring bonded earlier, for instance by its vendor app, is found under the address the phone
 * resolves its rotating one to, and is already bonded there.
 */
@SuppressLint("MissingPermission")
public class OuraPairingActivity extends AbstractGBActivity implements BondingInterface {

    private static final Logger LOG = LoggerFactory.getLogger(OuraPairingActivity.class);
    /** How long to wait for bonding to start. */
    private static final long BOND_TIMEOUT_MS = 30_000L;

    private GBDeviceCandidate target;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = () -> failPairing(R.string.pairing_unable_to_pair_with, "bond timed out");
    private boolean finished;

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            final BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (target == null || dev == null || !dev.getAddress().equals(target.getMacAddress())) {
                return;
            }
            final int newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            final int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
            LOG.info("Oura bond state {} -> {}", prevState, newState);

            if (newState == BluetoothDevice.BOND_BONDING) {
                // Android may now wait on its pairing dialog, and ends the bond itself if that fails
                timeoutHandler.removeCallbacks(timeoutRunnable);
            } else if (newState == BluetoothDevice.BOND_BONDED) {
                succeedPairing();
            } else if (newState == BluetoothDevice.BOND_NONE && prevState == BluetoothDevice.BOND_BONDING) {
                failPairing(R.string.pairing_unable_to_pair_with, "ring rejected pairing");
            }
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        intent.setExtrasClassLoader(GBDeviceCandidate.class.getClassLoader());
        target = intent.getParcelableExtra(DeviceCoordinator.EXTRA_DEVICE_CANDIDATE);
        if (target == null || target.getDevice() == null) {
            LOG.error("OuraPairingActivity launched without EXTRA_DEVICE_CANDIDATE");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        final BluetoothDevice device = target.getDevice();
        final int currentBond = device.getBondState();
        LOG.info("OuraPairingActivity: starting bond for {} (current state={})",
                target.getMacAddress(), currentBond);

        if (currentBond == BluetoothDevice.BOND_BONDED) {
            succeedPairing();
            return;
        }

        registerBroadcastReceivers();
        timeoutHandler.postDelayed(timeoutRunnable, BOND_TIMEOUT_MS);

        try {
            if (!device.createBond()) {
                failPairing(R.string.discovery_bonding_failed_immediately, "could not start bonding");
            }
        } catch (final SecurityException e) {
            LOG.error("SecurityException creating bond", e);
            failPairing(R.string.pairing_unable_to_pair_with, "pairing permission denied");
        }
    }

    private synchronized void succeedPairing() {
        if (finished) {
            return;
        }
        finished = true;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        unregisterBroadcastReceivers();
        LOG.info("Bonded, connecting");
        BondingUtil.connectThenComplete(this, target);
    }

    /** @param message a string resource given the ring's name and address as format arguments, which it may leave unused */
    private synchronized void failPairing(@StringRes final int message, final String reason) {
        if (finished) {
            return;
        }
        finished = true;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        unregisterBroadcastReceivers();
        LOG.warn("Pairing failed: {}", reason);
        GB.toast(this, getString(message, target.getName(), target.getMacAddress()), Toast.LENGTH_LONG, GB.WARN);
        new Handler(Looper.getMainLooper()).post(() -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    @Override
    public void onBondingComplete(final boolean success) {
        new Handler(Looper.getMainLooper()).post(() -> {
            setResult(success ? RESULT_OK : RESULT_CANCELED);
            finish();
        });
    }

    @Override
    public GBDeviceCandidate getCurrentTarget() {
        return target;
    }

    @Override
    public boolean getAttemptToConnect() {
        return true;
    }

    @Override
    public Context getContext() {
        return this;
    }

    @Override
    public void registerBroadcastReceivers() {
        ContextCompat.registerReceiver(this, bondReceiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    public void unregisterBroadcastReceivers() {
        AndroidUtils.safeUnregisterBroadcastReceiver(this, bondReceiver);
    }

    @Override
    protected void onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable);
        unregisterBroadcastReceivers();
        super.onDestroy();
    }
}
