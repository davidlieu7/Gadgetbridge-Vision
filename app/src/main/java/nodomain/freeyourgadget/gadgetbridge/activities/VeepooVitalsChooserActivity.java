/*  Copyright (C) 2026 Gadgetbridge Contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 or later;
    see the LICENSE file for details.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class VeepooVitalsChooserActivity extends Activity {

    private static final int[] ICONS = {
            R.drawable.ic_spo2,
            R.drawable.ic_thermometer,
            R.drawable.ic_stress,
            R.drawable.ic_pressure,
            R.drawable.ic_weight,
            R.drawable.baseline_bloodtype_24,
            R.drawable.ic_heartrate
    };

    private static final int[] TITLES = {
            R.string.veepoo_action_measure_spo2,
            R.string.veepoo_action_measure_temperature,
            R.string.veepoo_action_measure_stress,
            R.string.veepoo_action_measure_blood_pressure,
            R.string.veepoo_action_measure_body_composition,
            R.string.veepoo_action_measure_blood_lipids,
            R.string.veepoo_action_measure_ecg
    };

    private static final String[] CONFIG_KEYS = {
            VeepooConstants.CONFIG_MEASURE_SPO2,
            VeepooConstants.CONFIG_MEASURE_TEMPERATURE,
            VeepooConstants.CONFIG_MEASURE_STRESS,
            VeepooConstants.CONFIG_MEASURE_BLOOD_PRESSURE,
            VeepooConstants.CONFIG_MEASURE_BODY_COMPOSITION,
            VeepooConstants.CONFIG_MEASURE_BLOOD_LIPIDS,
            VeepooConstants.CONFIG_MEASURE_ECG
    };

    private static final int[] DIALOG_TYPES = {
            HeartRateDialog.TYPE_SPO2,
            HeartRateDialog.TYPE_TEMPERATURE,
            HeartRateDialog.TYPE_STRESS,
            HeartRateDialog.TYPE_BLOOD_PRESSURE,
            HeartRateDialog.TYPE_BODY_COMPOSITION,
            HeartRateDialog.TYPE_BLOOD_LIPID,
            HeartRateDialog.TYPE_ECG
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.veepoo_action_measure_vitals);
        final GBDevice device = (GBDevice) getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
        if (device == null || !device.isConnected()) {
            Toast.makeText(this, R.string.device_not_connected, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        ListView listView = new ListView(this);
        listView.setAdapter(new VitalAdapter(this, device));
        setContentView(listView);
    }

    private final class VitalAdapter extends ArrayAdapter<String> {
        private final GBDevice device;

        VitalAdapter(Context context, GBDevice device) {
            super(context, 0);
            this.device = device;
        }

        @Override
        public int getCount() {
            return TITLES.length;
        }

        @NonNull
        @Override
        public String getItem(int position) {
            return getString(TITLES[position]);
        }

        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.veepoo_vital_item, parent, false);
            }
            ImageView icon = view.findViewById(R.id.veepoo_vital_item_icon);
            TextView title = view.findViewById(R.id.veepoo_vital_item_title);
            icon.setImageResource(ICONS[position]);
            title.setText(getItem(position));
            view.setOnClickListener(v -> {
                GBApplication.deviceService(device).onSendConfiguration(CONFIG_KEYS[position]);
                new HeartRateDialog(device, VeepooVitalsChooserActivity.this, DIALOG_TYPES[position]).show();
            });
            return view;
        }
    }
}
