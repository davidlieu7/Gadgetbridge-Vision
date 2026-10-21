/*  Copyright (C) 2022-2024 LukasEdl

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
package nodomain.freeyourgadget.gadgetbridge.externalevents.gps;

import androidx.annotation.Nullable;
import android.content.Context;
import android.location.LocationManager;
import android.os.Build;

import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.providers.MockLocationProvider;
import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.providers.PhoneLocationProvider;

public enum GBLocationProviderType {
    GPS {
        @Override
        public GBLocationProvider newInstance(final Context context, final GBLocationListener locationListener) {
            return new PhoneLocationProvider(context, locationListener, LocationManager.GPS_PROVIDER);
        }
    },
    FUSED {
        @Override
        @Nullable
        public GBLocationProvider newInstance(final Context context, final GBLocationListener locationListener) {
            final LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                return null;
            }
            // "fused" may be provided by Google Play Services or, on API 31+, by the AOSP
            // LocationManager itself (LocationManager.FUSED_PROVIDER). Fall back to NETWORK.
            // Do NOT fall back to GPS here: handleWorkoutOpen starts GPS separately, so a
            // GPS fallback would deliver every fix twice.
            String provider = null;
            if (locationManager.getAllProviders().contains("fused")) {
                provider = "fused";
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && locationManager.getAllProviders().contains(LocationManager.FUSED_PROVIDER)) {
                provider = LocationManager.FUSED_PROVIDER;
            } else if (locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            }
            if (provider == null) {
                return null;
            }
            return new PhoneLocationProvider(context, locationListener, provider);
        }
    },
    NETWORK {
        @Override
        public GBLocationProvider newInstance(final Context context, final GBLocationListener locationListener) {
            return new PhoneLocationProvider(context, locationListener, LocationManager.NETWORK_PROVIDER);
        }
    },
    MOCK {
        @Override
        public GBLocationProvider newInstance(final Context context, final GBLocationListener locationListener) {
            return new MockLocationProvider(context, locationListener);
        }
    },
    ;

    @Nullable
    public abstract GBLocationProvider newInstance(final Context context, final GBLocationListener locationListener);
}