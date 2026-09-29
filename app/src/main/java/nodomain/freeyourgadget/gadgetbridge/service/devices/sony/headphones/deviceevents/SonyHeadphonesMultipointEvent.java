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
package nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones.deviceevents;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointDevice;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointPairingActivity;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class SonyHeadphonesMultipointEvent extends GBDeviceEvent {
    public enum Type {
        DEVICE_LIST,
        STATUS,
        PAIRING
    }

    private final Type type;
    private final List<MultipointDevice> devices;
    private final boolean enabled;

    private SonyHeadphonesMultipointEvent(
            final Type type,
            @Nullable final List<MultipointDevice> devices,
            final boolean enabled
    ) {
        this.type = type;
        this.devices = devices == null ? Collections.emptyList() : new ArrayList<>(devices);
        this.enabled = enabled;
    }

    public static SonyHeadphonesMultipointEvent deviceList(final List<MultipointDevice> devices) {
        return new SonyHeadphonesMultipointEvent(Type.DEVICE_LIST, devices, false);
    }

    public static SonyHeadphonesMultipointEvent status(final boolean enabled) {
        return new SonyHeadphonesMultipointEvent(Type.STATUS, null, enabled);
    }

    public static SonyHeadphonesMultipointEvent pairing(final boolean enabled) {
        return new SonyHeadphonesMultipointEvent(Type.PAIRING, null, enabled);
    }

    public Type getType() {
        return type;
    }

    public List<MultipointDevice> getDevices() {
        return Collections.unmodifiableList(devices);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void evaluate(@NonNull final Context context, @NonNull final GBDevice device) {
        final Intent intent;
        switch (type) {
            case DEVICE_LIST:
                intent = new Intent(MultipointPairingActivity.ACTION_MULTIPOINT_DEVICE_LIST);
                intent.putParcelableArrayListExtra(
                        MultipointPairingActivity.EXTRA_DEVICE_LIST,
                        new ArrayList<>(devices)
                );
                break;
            case STATUS:
                intent = new Intent(MultipointPairingActivity.ACTION_MULTIPOINT_STATUS_UPDATE);
                intent.putExtra(MultipointPairingActivity.EXTRA_MULTIPOINT_ENABLED, enabled);
                intent.putExtra(MultipointPairingActivity.EXTRA_MULTIPOINT_DISABLE_SUPPORTED, true);
                intent.putExtra(MultipointPairingActivity.EXTRA_MULTIPOINT_PAIR_SUPPORTED, true);
                break;
            case PAIRING:
                intent = new Intent(MultipointPairingActivity.ACTION_MULTIPOINT_PAIRING_UPDATE);
                intent.putExtra(MultipointPairingActivity.EXTRA_PAIRING_ENABLED, enabled);
                break;
            default:
                throw new IllegalStateException("Unknown multipoint event type " + type);
        }

        intent.putExtra(GBDevice.EXTRA_DEVICE, device);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }
}
