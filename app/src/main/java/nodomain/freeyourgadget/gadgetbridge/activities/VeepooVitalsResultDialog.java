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

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.BodyCompositionSample;
import nodomain.freeyourgadget.gadgetbridge.model.BloodLipidSample;
import nodomain.freeyourgadget.gadgetbridge.model.BloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo.VeepooDeviceSupport;

public class VeepooVitalsResultDialog extends Dialog {
    public static final int TYPE_HEART_RATE = 0;
    public static final int TYPE_SPO2 = 1;
    public static final int TYPE_TEMPERATURE = 2;
    public static final int TYPE_STRESS = 3;
    public static final int TYPE_BLOOD_PRESSURE = 4;
    public static final int TYPE_BODY_COMPOSITION = 5;
    public static final int TYPE_BLOOD_LIPID = 6;
    public static final int TYPE_ECG = 7;

    private final GBDevice device;
    private final int type;

    protected static final Logger LOG = LoggerFactory.getLogger(VeepooVitalsResultDialog.class);
    LinearLayout heart_rate_dialog_results_layout;
    RelativeLayout heart_rate_dialog_loading_layout;

    TextView heart_rate_widget_hr_value;
    TextView heart_rate_widget_spo2_value;
    TextView heart_rate_widget_pressure_value;

    LinearLayout heart_rate_hr;
    LinearLayout heart_rate_spo2;
    LinearLayout heart_rate_pressure;

    LinearLayout vitals_result_rows;

    TextView heart_rate_dialog_label;

    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (Objects.requireNonNull(intent.getAction())) {
                case DeviceService.ACTION_REALTIME_SAMPLES:
                    final GBDevice incomingDevice = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
                    if (device.equals(incomingDevice)) {
                        setMeasurementResults(intent.getSerializableExtra(DeviceService.EXTRA_REALTIME_SAMPLE));
                    }
                    break;
            default:
                    LOG.info("ignoring intent action {}", intent.getAction());
                    break;
            }
        }
    };

    public VeepooVitalsResultDialog(final GBDevice device, @NonNull Context context) {
        this(device, context, TYPE_HEART_RATE);
    }

    public VeepooVitalsResultDialog(final GBDevice device, @NonNull Context context, int type) {
        super(context);
        this.device = device;
        this.type = type;
    }

    private void setMeasurementResults(Serializable result) {
        if (result == null) {
            return;
        }
        heart_rate_dialog_results_layout.setVisibility(View.VISIBLE);
        heart_rate_dialog_loading_layout.setVisibility(View.GONE);
        heart_rate_dialog_label.setText(getContext().getString(R.string.heart_rate_result));

        switch (type) {
            case TYPE_SPO2:
                if (result instanceof Spo2Sample) {
                    heart_rate_widget_hr_value.setText(((Spo2Sample) result).getSpo2() + " %");
                }
                break;
            case TYPE_TEMPERATURE:
                if (result instanceof VeepooDeviceSupport.VeepooTemperatureMeasurementResult) {
                    final VeepooDeviceSupport.VeepooTemperatureMeasurementResult temp =
                            (VeepooDeviceSupport.VeepooTemperatureMeasurementResult) result;
                    if (vitals_result_rows != null) {
                        vitals_result_rows.removeAllViews();
                        addVitalsRow(R.string.veepoo_temp_body, String.format("%.1f °C", temp.bodyCelsius));
                        if (temp.skinCelsius != null) {
                            addVitalsRow(R.string.veepoo_temp_skin, String.format("%.1f °C", temp.skinCelsius));
                        }
                    }
                } else if (result instanceof TemperatureSample && vitals_result_rows != null) {
                    addVitalsRow(R.string.veepoo_temp_body,
                            String.format("%.1f °C", ((TemperatureSample) result).getTemperature()));
                }
                break;
            case TYPE_STRESS:
                if (result instanceof StressSample) {
                    heart_rate_widget_hr_value.setText(String.valueOf(((StressSample) result).getStress()));
                }
                break;
            case TYPE_BLOOD_PRESSURE:
                if (result instanceof BloodPressureSample) {
                    BloodPressureSample bp = (BloodPressureSample) result;
                    heart_rate_widget_hr_value.setText(bp.getBpSystolic() + "/" + bp.getBpDiastolic() + " mmHg");
                }
                break;
            case TYPE_BODY_COMPOSITION:
                if (result instanceof BodyCompositionSample) {
                    final BodyCompositionSample bc = (BodyCompositionSample) result;
                    addVitalsRow(R.string.veepoo_bc_bmi, String.format("%.1f", bc.getBmi()));
                    addVitalsRow(R.string.veepoo_bc_body_fat, String.format("%.1f %%", bc.getBodyFatRate()));
                    addVitalsRow(R.string.veepoo_bc_fat_mass, String.format("%.1f kg", bc.getFatWeight()));
                    addVitalsRow(R.string.veepoo_bc_lean_mass, String.format("%.1f kg", bc.getFfm()));
                    addVitalsRow(R.string.veepoo_bc_muscle_ratio, String.format("%.1f %%", bc.getMuscleRate()));
                    addVitalsRow(R.string.veepoo_bc_muscle_mass, String.format("%.1f kg", bc.getMuscleMass()));
                    addVitalsRow(R.string.veepoo_bc_subcutaneous_fat, String.format("%.1f kg", bc.getSubcutaneousFat()));
                    addVitalsRow(R.string.veepoo_bc_body_water, String.format("%.1f %%", bc.getBodyWaterRate()));
                    addVitalsRow(R.string.veepoo_bc_water_mass, String.format("%.1f kg", bc.getWaterContent()));
                    addVitalsRow(R.string.veepoo_bc_skeletal_muscle, String.format("%.1f %%", bc.getSkeletalMuscleRate()));
                    addVitalsRow(R.string.veepoo_bc_bone_mass, String.format("%.1f kg", bc.getBoneMass()));
                    addVitalsRow(R.string.veepoo_bc_protein_ratio, String.format("%.1f %%", bc.getProteinProportion()));
                    addVitalsRow(R.string.veepoo_bc_protein_mass, String.format("%.1f kg", bc.getProteinMass()));
                    addVitalsRow(R.string.veepoo_bc_bmr, String.format("%.0f kcal", bc.getBasalMetabolicRate()));
                }
                break;
            case TYPE_BLOOD_LIPID:
                if (result instanceof BloodLipidSample) {
                    final BloodLipidSample lipid = (BloodLipidSample) result;
                    addVitalsRow(R.string.veepoo_lipid_uric_acid, String.format("%.1f µmol/L", lipid.getUricAcid()));
                    addVitalsRow(R.string.veepoo_lipid_cholesterol, String.format("%.2f mmol/L", lipid.getTotalCholesterol()));
                    addVitalsRow(R.string.veepoo_lipid_triglycerides, String.format("%.2f mmol/L", lipid.getTriglyceride()));
                    addVitalsRow(R.string.veepoo_lipid_hdl, String.format("%.2f mmol/L", lipid.getHdl()));
                    addVitalsRow(R.string.veepoo_lipid_ldl, String.format("%.2f mmol/L", lipid.getLdl()));
                }
                break;
            case TYPE_ECG:
                if (result instanceof VeepooDeviceSupport.VeepooEcgMeasurementResult) {
                    // Live-updating measurement; rebuild the rows on every announcement.
                    if (vitals_result_rows != null) {
                        vitals_result_rows.removeAllViews();
                        final VeepooDeviceSupport.VeepooEcgMeasurementResult ecg =
                                (VeepooDeviceSupport.VeepooEcgMeasurementResult) result;
                        addVitalsRow(R.string.veepoo_ecg_heart_rate,
                                ecg.latestHr > 0 ? ecg.latestHr + " bpm" : "--");
                        if (ecg.savedId == 0 && ecg.progress > 0) {
                            addVitalsRow(R.string.veepoo_ecg_progress, ecg.progress + " %");
                        }
                        addVitalsRow(R.string.veepoo_ecg_sample_rate,
                                ecg.drawFrequency > 0 ? ecg.drawFrequency + " Hz" : "--");
                        addVitalsRow(R.string.veepoo_ecg_samples, String.valueOf(ecg.sampleCount));
                        addVitalsRow(R.string.veepoo_ecg_frames, String.valueOf(ecg.frameCount));
                        if (ecg.failCode != 0) {
                            addVitalsRow(R.string.veepoo_ecg_result, getContext().getString(ecg.failCode));
                        } else if (ecg.savedId > 0) {
                            addVitalsRow(R.string.veepoo_ecg_saved, "Yes");
                            addVitalsRowClickable(R.string.veepoo_ecg_waveform, "\u203A", v ->
                                    getContext().startActivity(new Intent(getContext(), VeepooEcgDetailActivity.class)
                                            .putExtra(VeepooEcgDetailActivity.EXTRA_SAMPLE_ID, ecg.savedId)
                                            .putExtra(VeepooEcgDetailActivity.EXTRA_DEVICE_ADDRESS, device.getAddress())));
                        }
                    }
                }
                break;
            default:
                int heartRate = 0;
                if (result instanceof ActivitySample) {
                    heartRate = ((ActivitySample) result).getHeartRate();
                } else if (result instanceof HeartRateSample) {
                    heartRate = ((HeartRateSample) result).getHeartRate();
                }
                if (HeartRateUtils.getInstance().isValidHeartRateValue(heartRate)) {
                    heart_rate_widget_hr_value.setText(String.valueOf(heartRate));
                }
        }
    }

    private void addVitalsRow(int labelRes, String value) {
        final View row = LayoutInflater.from(getContext())
                .inflate(R.layout.veepoo_vitals_result_row, vitals_result_rows, false);
        ((TextView) row.findViewById(R.id.veepoo_vitals_row_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.veepoo_vitals_row_value)).setText(value);
        vitals_result_rows.addView(row);
    }

    private void addVitalsRowClickable(int labelRes, String value, View.OnClickListener listener) {
        final View row = LayoutInflater.from(getContext())
                .inflate(R.layout.veepoo_vitals_result_row, vitals_result_rows, false);
        ((TextView) row.findViewById(R.id.veepoo_vitals_row_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.veepoo_vitals_row_value)).setText(value);
        row.setOnClickListener(listener);
        vitals_result_rows.addView(row);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        IntentFilter filter = new IntentFilter();
        filter.addAction(DeviceService.ACTION_REALTIME_SAMPLES);
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(mReceiver, filter);

        setContentView(R.layout.heart_rate_dialog);
        heart_rate_dialog_results_layout = findViewById(R.id.heart_rate_dialog_results_layout);
        heart_rate_dialog_loading_layout = findViewById(R.id.heart_rate_dialog_loading_layout);

        heart_rate_hr = findViewById(R.id.heart_rate_measurements1);
        heart_rate_spo2 = findViewById(R.id.heart_rate_measurements2);
        heart_rate_pressure = findViewById(R.id.heart_rate_measurements3);

        TextView heart_rate_widget_hr_title = heart_rate_hr.findViewById(R.id.generic_widget_title);
        TextView heart_rate_widget_spo2_title = heart_rate_spo2.findViewById(R.id.generic_widget_title);
        TextView heart_rate_widget_pressure_title = heart_rate_pressure.findViewById(R.id.generic_widget_title);

        heart_rate_widget_hr_value = heart_rate_hr.findViewById(R.id.generic_widget_value);
        heart_rate_widget_spo2_value = heart_rate_spo2.findViewById(R.id.generic_widget_value);
        heart_rate_widget_pressure_value = heart_rate_pressure.findViewById(R.id.generic_widget_value);

        ImageView heart_rate_widget_hr_icon = heart_rate_hr.findViewById(R.id.generic_widget_icon);
        ImageView heart_rate_widget_spo2_icon = heart_rate_spo2.findViewById(R.id.generic_widget_icon);
        ImageView heart_rate_widget_pressure_icon = heart_rate_pressure.findViewById(R.id.generic_widget_icon);

        heart_rate_widget_hr_icon.setImageResource(R.drawable.ic_heart);
        heart_rate_widget_spo2_icon.setImageResource(R.drawable.ic_circle);
        heart_rate_widget_pressure_icon.setImageResource(R.drawable.ic_heartrate);

        heart_rate_hr.setVisibility(View.VISIBLE);
        heart_rate_spo2.setVisibility(View.GONE);
        heart_rate_pressure.setVisibility(View.GONE);

        switch (type) {
            case TYPE_SPO2:
                heart_rate_widget_hr_icon.setImageResource(R.drawable.ic_spo2);
                heart_rate_widget_hr_title.setText(R.string.menuitem_spo2);
                break;
            case TYPE_TEMPERATURE:
                heart_rate_widget_hr_icon.setImageResource(R.drawable.ic_thermometer);
                heart_rate_widget_hr_title.setText(R.string.menuitem_temperature);
                break;
            case TYPE_STRESS:
                heart_rate_widget_hr_icon.setImageResource(R.drawable.ic_stress);
                heart_rate_widget_hr_title.setText(R.string.menuitem_stress);
                break;
            case TYPE_BLOOD_PRESSURE:
                heart_rate_widget_hr_icon.setImageResource(R.drawable.ic_pressure);
                heart_rate_widget_hr_title.setText(R.string.blood_pressure);
                break;
            case TYPE_BODY_COMPOSITION:
                heart_rate_widget_hr_icon.setImageResource(R.drawable.ic_weight);
                heart_rate_widget_hr_title.setText(R.string.veepoo_action_measure_body_composition);
                break;
            case TYPE_BLOOD_LIPID:
                heart_rate_widget_hr_icon.setImageResource(R.drawable.baseline_bloodtype_24);
                heart_rate_widget_hr_title.setText(R.string.veepoo_action_measure_blood_lipids);
                break;
            case TYPE_ECG:
                heart_rate_widget_hr_icon.setImageResource(R.drawable.ic_heartrate);
                heart_rate_widget_hr_title.setText(R.string.veepoo_action_measure_ecg);
                break;
            default:
                heart_rate_widget_hr_title.setText(R.string.heart_rate);
        }

        if (type == TYPE_BODY_COMPOSITION || type == TYPE_BLOOD_LIPID || type == TYPE_ECG || type == TYPE_TEMPERATURE) {
            final View widgetsRow = (View) heart_rate_hr.getParent();
            widgetsRow.setVisibility(View.GONE);
            final View list = LayoutInflater.from(getContext())
                    .inflate(R.layout.veepoo_vitals_result_list, heart_rate_dialog_results_layout, false);
            heart_rate_dialog_results_layout.addView(list);
            vitals_result_rows = list.findViewById(R.id.veepoo_vitals_result_rows);
        }

        heart_rate_dialog_label = findViewById(R.id.heart_rate_dialog_title);
        heart_rate_dialog_results_layout.setVisibility(View.GONE);
        heart_rate_dialog_loading_layout.setVisibility(View.VISIBLE);

        if (device.getDeviceCoordinator().supportsLiveOnlyHeartRateDisplay(device)) {
            GBApplication.deviceService(device).onEnableRealtimeHeartRateMeasurement(true);
        }

        setOnCancelListener(dialogInterface -> {
            if (device.getDeviceCoordinator().supportsLiveOnlyHeartRateDisplay(device)) {
                GBApplication.deviceService(device).onEnableRealtimeHeartRateMeasurement(false);
            }
            LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(mReceiver);
        });
    }
}
