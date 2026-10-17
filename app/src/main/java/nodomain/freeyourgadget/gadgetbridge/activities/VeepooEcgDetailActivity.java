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
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooEcgChartView;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooEcgWaveform;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooEcgSample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooEcgSampleDao;

/**
 * Detail screen for a stored Veepoo ECG measurement: renders the decoded and normalized waveform
 * in a {@link VeepooEcgChartView} plus a short summary. Opened from the HeartRateDialog ECG rows
 * once a measurement has been saved to the database.
 */
public class VeepooEcgDetailActivity extends Activity {

    public static final String EXTRA_SAMPLE_ID = "veepoo_ecg_sample_id";
    public static final String EXTRA_DEVICE_ADDRESS = "veepoo_ecg_device_address";

    private static final Logger LOG = LoggerFactory.getLogger(VeepooEcgDetailActivity.class);

    /** Fallback sample rate for recordings whose stored rate is missing or implausible. */
    private static final int DEFAULT_SAMPLE_RATE_HZ = 500;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.veepoo_ecg_detail_title);
        setContentView(R.layout.veepoo_ecg_detail);

        long sampleId = getIntent().getLongExtra(EXTRA_SAMPLE_ID, 0);
        String deviceAddress = getIntent().getStringExtra(EXTRA_DEVICE_ADDRESS);
        VeepooEcgSample sample;
        try (DBHandler handler = GBApplication.acquireDB()) {
            List<VeepooEcgSample> samples = handler.getDaoSession().getVeepooEcgSampleDao()
                    .queryBuilder()
                    .where(VeepooEcgSampleDao.Properties.Id.eq(sampleId))
                    .list();
            sample = samples.isEmpty() ? null : samples.get(0);
        } catch (Exception e) {
            LOG.error("Failed to load ECG sample {}", sampleId, e);
            sample = null;
        }
        if (sample == null || sample.getWaveform() == null) {
            Toast.makeText(this, R.string.veepoo_ecg_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // The warm-up length depends on the device's ECG type, persisted when its capability
        // report was received; default to the vendor's 6 s when unknown.
        int ecgType = 0;
        if (deviceAddress != null) {
            ecgType = GBApplication.getDeviceSpecificSharedPrefs(deviceAddress)
                    .getInt(VeepooConstants.CONFIG_ECG_TYPE, 0);
        }

        int sampleRate = sample.getSampleRate() == null ? 0 : sample.getSampleRate();
        if (sampleRate <= 0 || sampleRate > 1000) {
            LOG.warn("Implausible stored ECG sample rate {}; using {} Hz", sampleRate, DEFAULT_SAMPLE_RATE_HZ);
            sampleRate = DEFAULT_SAMPLE_RATE_HZ;
        }

        int[] decoded = VeepooEcgWaveform.decode(sample.getWaveform());
        int[] afterWarmup = VeepooEcgWaveform.dropWarmup(ecgType, sampleRate, decoded);
        float[] normalized = VeepooEcgWaveform.normalize(afterWarmup);

        ((VeepooEcgChartView) findViewById(R.id.veepoo_ecg_detail_chart)).setData(sampleRate, normalized, false);

        final LinearLayout rows = findViewById(R.id.veepoo_vitals_result_rows);
        int heartRate = sample.getHeartRate() == null ? 0 : sample.getHeartRate();
        addRow(rows, R.string.veepoo_ecg_heart_rate, heartRate > 0 ? heartRate + " bpm" : "--");
        addRow(rows, R.string.veepoo_ecg_sample_rate, sampleRate + " Hz");
        int durationMs = sample.getDurationMs() == null ? 0 : sample.getDurationMs();
        addRow(rows, R.string.veepoo_ecg_duration,
                durationMs > 0 ? String.format(Locale.US, "%.1f s", durationMs / 1000.0) : "--");
        addRow(rows, R.string.veepoo_ecg_samples, String.valueOf(afterWarmup.length));
    }

    private void addRow(LinearLayout rows, int labelRes, String value) {
        final View row = LayoutInflater.from(this).inflate(R.layout.veepoo_vitals_result_row, rows, false);
        ((TextView) row.findViewById(R.id.veepoo_vitals_row_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.veepoo_vitals_row_value)).setText(value);
        rows.addView(row);
    }
}
