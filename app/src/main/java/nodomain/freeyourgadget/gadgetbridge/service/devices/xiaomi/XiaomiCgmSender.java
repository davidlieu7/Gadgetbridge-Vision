/*  Copyright (C) 2026 ChrisB85

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_CGM_GRAPH_HOURS;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_CGM_UNITS_MGDL;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_CGM_WATCH_APP_ENABLED;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_CGM_WATCH_APP_ID;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_GLUCOSE_THRESHOLD_HIGH;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_GLUCOSE_THRESHOLD_LOW;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.GlucoseSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.GlucoseSample;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.cgm.CgmSnapshot;
import nodomain.freeyourgadget.gadgetbridge.util.cgm.WatchDripPayload;
import nodomain.freeyourgadget.gadgetbridge.util.cgm.XdripBroadcast;

/**
 * Stores the glucose readings broadcast by xDrip+ and sends each one, with a graph of the
 * stored readings, to an app on the watch. Disabled unless {@code PREF_CGM_WATCH_APP_ENABLED} is set for the device.
 */
public class XiaomiCgmSender {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiCgmSender.class);

    /** Range used until glucose limits are set: 70-180 mg/dL, the international consensus target range. */
    static final int DEFAULT_LOW_MGDL = 70;
    static final int DEFAULT_HIGH_MGDL = 180;

    private final XiaomiSupport support;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor executor;
    private Context context;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context ctx, final Intent intent) {
            onReading(intent.getExtras());
        }
    };

    public XiaomiCgmSender(final XiaomiSupport support) {
        this(support, Executors.newSingleThreadExecutor());
    }

    XiaomiCgmSender(final XiaomiSupport support, final Executor executor) {
        this.support = support;
        this.executor = executor;
    }

    /** Call when the device is connected and authenticated, and when the settings change. */
    public void start(final Context context) {
        stop();
        if (!prefs().getBoolean(PREF_CGM_WATCH_APP_ENABLED, false)) {
            return;
        }
        this.context = context;
        // xDrip+ sends without a permission only with "Compatible Broadcast"; the data is checked, not trusted
        ContextCompat.registerReceiver(context, receiver, new IntentFilter(XdripBroadcast.ACTION_BG_ESTIMATE),
                ContextCompat.RECEIVER_EXPORTED);
    }

    public void stop() {
        if (context != null) {
            context.unregisterReceiver(receiver);
            context = null;
        }
    }

    public void dispose() {
        stop();
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdownNow();
        }
    }

    void onReading(@Nullable final Bundle extras) {
        final CgmSnapshot.Reading reading = XdripBroadcast.reading(extras);
        if (reading == null) {
            LOG.warn("Glucose broadcast without value or time");
            return;
        }
        final Boolean mgdlUnits = XdripBroadcast.mgdlUnits(extras);
        if (mgdlUnits != null) {
            GBApplication.getDeviceSpecificSharedPrefs(support.getDevice().getAddress()).edit()
                    .putBoolean(PREF_CGM_UNITS_MGDL, mgdlUnits).apply();
        }

        final Prefs prefs = prefs();
        final String appId = prefs.getString(PREF_CGM_WATCH_APP_ID, "").trim();
        final double hours = Double.parseDouble(prefs.getString(PREF_CGM_GRAPH_HOURS,
                support.getContext().getString(R.string.pref_cgm_default_graph_hours)));
        final boolean mgdl = prefs.getBoolean(PREF_CGM_UNITS_MGDL, true);
        final int low = prefs.getInt(PREF_GLUCOSE_THRESHOLD_LOW, DEFAULT_LOW_MGDL);
        final int high = prefs.getInt(PREF_GLUCOSE_THRESHOLD_HIGH, DEFAULT_HIGH_MGDL);

        executor.execute(() -> {
            final long now = System.currentTimeMillis();
            final List<GlucoseSample> samples = storeAndLoad(reading, now - (long) (hours * 3600_000L), now);
            if (appId.isEmpty()) {
                LOG.debug("Glucose stored, no watch app set");
                return;
            }
            try {
                final String payload = WatchDripPayload.build(
                        CgmSnapshot.fromSamples(samples, reading, mgdl, low, high), now, hours, phoneBattery());
                handler.post(() -> support.onSendWatchAppMessage(appId, payload.getBytes(StandardCharsets.UTF_8)));
            } catch (final Exception e) {
                LOG.error("Failed to build the glucose message", e);
            }
        });
    }

    /** @return the stored readings from {@code from} on, oldest first, including the new one */
    private List<GlucoseSample> storeAndLoad(final CgmSnapshot.Reading reading, final long from, final long to) {
        final GlucoseSample sample = new GlucoseSample();
        sample.setTimestamp(reading.timestamp);
        sample.setValueMgDl(reading.mgdl);
        try (DBHandler db = GBApplication.acquireDB()) {
            final GlucoseSampleProvider provider = new GlucoseSampleProvider(support.getDevice(), db.getDaoSession());
            provider.persistSamples(Collections.singletonList(sample), support.getContext());
            return provider.getAllSamples(from, Math.max(to, reading.timestamp));
        } catch (final Exception e) {
            LOG.error("Failed to store the glucose reading", e);
            return Collections.singletonList(sample);
        }
    }

    private int phoneBattery() {
        final BatteryManager battery = (BatteryManager) support.getContext().getSystemService(Context.BATTERY_SERVICE);
        return battery != null ? battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
    }

    private Prefs prefs() {
        return GBApplication.getDevicePrefs(support.getDevice());
    }
}
