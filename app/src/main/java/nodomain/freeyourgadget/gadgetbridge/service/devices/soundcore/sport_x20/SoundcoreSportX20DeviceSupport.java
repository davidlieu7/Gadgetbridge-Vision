package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sport_x20;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointPairingActivity;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcoreDeviceSupport;

public class SoundcoreSportX20DeviceSupport extends SoundcoreDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(SoundcoreSportX20DeviceSupport.class);

    private boolean multipointReceiverRegistered;

    @Override
    protected SoundcoreSportX20Protocol createDeviceProtocol() {
        return new SoundcoreSportX20Protocol(getDevice());
    }

    private SoundcoreSportX20Protocol getSportX20Protocol() {
        return (SoundcoreSportX20Protocol) mDeviceProtocol;
    }

    @Override
    public void setContext(final GBDevice gbDevice, final BluetoothAdapter btAdapter, final Context context) {
        super.setContext(gbDevice, btAdapter, context);
        registerMultipointReceiver();
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            if (multipointReceiverRegistered) {
                LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(multipointReceiver);
                multipointReceiverRegistered = false;
            }
            super.dispose();
        }
    }

    private void registerMultipointReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_ENABLE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_DISABLE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_GET_DEVICES);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_GET_STATUS);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_CONNECT_DEVICE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_DISCONNECT_DEVICE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_FORGET_DEVICE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_START_PAIRING);
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(multipointReceiver, intentFilter);
        multipointReceiverRegistered = true;
    }

    private final BroadcastReceiver multipointReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final GBDevice intentDevice = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
            if (intentDevice == null || !intentDevice.getAddress().equals(getDevice().getAddress())) {
                return; // not for this device
            }

            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case MultipointPairingActivity.ACTION_MULTIPOINT_GET_STATUS:
                    getSportX20Protocol().broadcastMultipointStatus(isDualConnectionEnabled());
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_GET_DEVICES:
                    sendToDevice(getSportX20Protocol().encodePairedDevicesRequest());
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_ENABLE:
                    setDualConnection(true);
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_DISABLE:
                    setDualConnection(false);
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_START_PAIRING:
                    final boolean enabled = intent.getBooleanExtra(MultipointPairingActivity.EXTRA_PAIRING_ENABLED, false);
                    if (enabled) {
                        sendToDevice(getSportX20Protocol().encodeStartPairing());
                    }
                    getSportX20Protocol().broadcastMultipointPairing(enabled);
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_CONNECT_DEVICE:
                    connectDevice(intent.getStringExtra(MultipointPairingActivity.EXTRA_DEVICE_ADDRESS), true);
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_DISCONNECT_DEVICE:
                    connectDevice(intent.getStringExtra(MultipointPairingActivity.EXTRA_DEVICE_ADDRESS), false);
                    break;
                case MultipointPairingActivity.ACTION_MULTIPOINT_FORGET_DEVICE:
                    forgetDevice(intent.getStringExtra(MultipointPairingActivity.EXTRA_DEVICE_ADDRESS));
                    break;
                default:
                    LOG.warn("Unknown multipoint action {}", action);
            }
        }
    };

    private boolean isDualConnectionEnabled() {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        return prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_DUAL_CONNECTION, false);
    }

    private void setDualConnection(final boolean enabled) {
        GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()).edit()
                .putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_DUAL_CONNECTION, enabled)
                .apply();
        sendToDevice(getSportX20Protocol().encodeDualConnection(enabled));
        getSportX20Protocol().broadcastMultipointStatus(enabled);
    }

    private void connectDevice(final String address, final boolean connect) {
        if (address == null) {
            return;
        }
        sendToDevice(connect
                ? getSportX20Protocol().encodeConnectDevice(address)
                : getSportX20Protocol().encodeDisconnectDevice(address));
        // Refresh the connection/active-audio status and the paired-device list afterwards.
        sendToDevice(getSportX20Protocol().encodeConnectionStatusRequest());
        sendToDevice(getSportX20Protocol().encodePairedDevicesRequest());
    }

    /** Forgets/unpairs a paired device. */
    private void forgetDevice(final String address) {
        if (address == null) {
            return;
        }
        sendToDevice(getSportX20Protocol().encodeForgetDevice(address));
        // Refresh the paired-device list afterwards.
        sendToDevice(getSportX20Protocol().encodePairedDevicesRequest());
    }

    private void sendToDevice(final byte[] bytes) {
        if (bytes == null) {
            return;
        }
        final TransactionBuilder builder = createTransactionBuilder("multipoint");
        builder.write(bytes);
        builder.queue();
    }
}
