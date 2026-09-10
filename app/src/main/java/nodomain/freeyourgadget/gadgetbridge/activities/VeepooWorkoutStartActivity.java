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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooKR96ProCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Sport type picker for starting a workout on the VeePoo KR96 Pro (0xDA control command). */
public class VeepooWorkoutStartActivity extends AbstractGBActivity {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooWorkoutStartActivity.class);

    // ESportType values offered to the user (curated subset of the vendor's 188 variants).
    private static final int[] SPORT_TYPES = {1, 3, 2, 5, 6, 7, 8, 9, 10, 12, 14, 15, 16, 24, 30, 32};
    private static final String[] SPORT_NAMES = {
            "Outdoor running", "Indoor running", "Outdoor walking", "Hiking",
            "Treadmill", "Outdoor cycling", "Indoor cycling", "Elliptical",
            "Rowing machine", "Swimming", "Skiing", "Jump rope", "Yoga",
            "Fitness", "Triathlon", "HIIT"
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.veepoo_workout_start_title);
        final GBDevice device = (GBDevice) getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
        if (device == null || !device.isConnected()) {
            LOG.warn("Workout start requested but the device is not connected");
            GB.toast(getString(R.string.device_not_connected), Toast.LENGTH_SHORT, GB.ERROR);
            finish();
            return;
        }
        ListView listView = new ListView(this);
        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, SPORT_NAMES));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            LOG.info("Starting workout with sport type {}", SPORT_TYPES[position]);
            GBApplication.deviceService(device).onSendConfiguration(
                    VeepooConstants.CONFIG_WORKOUT_START_PREFIX + SPORT_TYPES[position]);
            if (device.getDeviceCoordinator() instanceof VeepooKR96ProCoordinator) {
                ((VeepooKR96ProCoordinator) device.getDeviceCoordinator()).setWorkoutRunning(true);
            }
            finish();
        });
        setContentView(listView);
    }
}
