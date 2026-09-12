/*  Copyright (C) 2026 Gadgetbridge contributors

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
    along with this program. If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.app.DatePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.NumberPicker;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Women's health (cycle tracking) settings for the VeePoo KR96 Pro. The values are persisted in
 * device-specific prefs and pushed to the band as 0x85 frames by VeepooDeviceSupport; when the
 * device is not connected they are re-pushed after the next connection instead.
 */
public class VeepooWomenHealthActivity extends AbstractGBActivity {

    private static final Logger LOG = LoggerFactory.getLogger(VeepooWomenHealthActivity.class);
    private static final SimpleDateFormat LMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private GBDevice device;
    private SharedPreferences prefs;
    private Calendar lmpDate;
    private int cycleLength;
    private int periodLength;
    private boolean suppressPickerEvents = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.veepoo_women_title);
        device = (GBDevice) getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
        if (device == null) {
            finish();
            return;
        }
        prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        lmpDate = Calendar.getInstance();
        String storedLmp = prefs.getString(VeepooConstants.CONFIG_WOMEN_LMP, null);
        if (storedLmp != null) {
            try {
                lmpDate.setTime(LMP_FORMAT.parse(storedLmp));
            } catch (java.text.ParseException e) {
                LOG.warn("Stored women's LMP {} is not a valid date; defaulting to today", storedLmp);
            }
        }
        cycleLength = prefs.getInt(VeepooConstants.CONFIG_WOMEN_CYCLE_LENGTH, VeepooConstants.WOMEN_CYCLE_LENGTH_DEFAULT);
        periodLength = prefs.getInt(VeepooConstants.CONFIG_WOMEN_PERIOD_LENGTH, VeepooConstants.WOMEN_PERIOD_LENGTH_DEFAULT);

        setContentView(R.layout.veepoo_women_health);
        final boolean enabled = isTrackingEnabled();

        Switch enableSwitch = findViewById(R.id.veepoo_women_switch);
        enableSwitch.setChecked(enabled);
        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putInt(VeepooConstants.CONFIG_WOMEN_STATUS,
                    isChecked ? VeepooConstants.WOMEN_STATUS_MENES : VeepooConstants.WOMEN_STATUS_NONE).apply();
            setInputsEnabled(isChecked);
            // Toggling must always reach the band (enabling starts tracking, disabling stops it).
            pushToDevice();
        });

        TextView lmpValue = findViewById(R.id.veepoo_women_lmp_value);
        lmpValue.setText(formatLmp());
        View lmpRow = findViewById(R.id.veepoo_women_lmp_row);
        lmpRow.setOnClickListener(v -> showLmpPicker());

        NumberPicker cyclePicker = findViewById(R.id.veepoo_women_cycle_picker);
        // setMinValue/setMaxValue are deprecated since API 26 but the app targets minSdk 23.
        cyclePicker.setMinValue(VeepooConstants.WOMEN_CYCLE_LENGTH_MIN);
        cyclePicker.setMaxValue(VeepooConstants.WOMEN_CYCLE_LENGTH_MAX);
        suppressPickerEvents = true;
        cyclePicker.setValue(cycleLength);
        suppressPickerEvents = false;
        cyclePicker.setOnValueChangedListener((picker, oldVal, newVal) -> {
            if (suppressPickerEvents) {
                return;
            }
            cycleLength = newVal;
            persistAndMaybePush();
        });

        NumberPicker periodPicker = findViewById(R.id.veepoo_women_period_picker);
        periodPicker.setMinValue(VeepooConstants.WOMEN_PERIOD_LENGTH_MIN);
        periodPicker.setMaxValue(VeepooConstants.WOMEN_PERIOD_LENGTH_MAX);
        suppressPickerEvents = true;
        periodPicker.setValue(periodLength);
        suppressPickerEvents = false;
        periodPicker.setOnValueChangedListener((picker, oldVal, newVal) -> {
            if (suppressPickerEvents) {
                return;
            }
            periodLength = newVal;
            persistAndMaybePush();
        });

        setInputsEnabled(enabled);
    }

    private boolean isTrackingEnabled() {
        return prefs.getInt(VeepooConstants.CONFIG_WOMEN_STATUS, VeepooConstants.WOMEN_STATUS_NONE)
                == VeepooConstants.WOMEN_STATUS_MENES;
    }

    /** Grays out the date/length rows while tracking is off (they only matter when on). */
    private void setInputsEnabled(final boolean enabled) {
        View lmpRow = findViewById(R.id.veepoo_women_lmp_row);
        NumberPicker cyclePicker = findViewById(R.id.veepoo_women_cycle_picker);
        NumberPicker periodPicker = findViewById(R.id.veepoo_women_period_picker);
        float alpha = enabled ? 1f : 0.45f;
        lmpRow.setAlpha(alpha);
        cyclePicker.setAlpha(alpha);
        periodPicker.setAlpha(alpha);
        lmpRow.setEnabled(enabled);
        lmpRow.setClickable(enabled);
        cyclePicker.setEnabled(enabled);
        periodPicker.setEnabled(enabled);
    }

    private String formatLmp() {
        return android.text.format.DateFormat.getDateFormat(this).format(lmpDate.getTime());
    }

    private void showLmpPicker() {
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            lmpDate.set(year, month, dayOfMonth, 0, 0, 0);
            ((TextView) findViewById(R.id.veepoo_women_lmp_value)).setText(formatLmp());
            persistAndMaybePush();
        }, lmpDate.get(Calendar.YEAR), lmpDate.get(Calendar.MONTH), lmpDate.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void persistAndMaybePush() {
        prefs.edit()
                .putString(VeepooConstants.CONFIG_WOMEN_LMP, LMP_FORMAT.format(lmpDate.getTime()))
                .putInt(VeepooConstants.CONFIG_WOMEN_CYCLE_LENGTH, cycleLength)
                .putInt(VeepooConstants.CONFIG_WOMEN_PERIOD_LENGTH, periodLength)
                .apply();
        if (!isTrackingEnabled()) {
            // Values only take effect on the band while tracking is on.
            return;
        }
        pushToDevice();
    }

    /** Hands the stored settings to the driver; when disconnected they go out after reconnect. */
    private void pushToDevice() {
        if (device.isConnected()) {
            GBApplication.deviceService(device).onSendConfiguration(VeepooConstants.CONFIG_WOMEN_APPLY);
        } else {
            LOG.info("Band not connected; the women's health settings will be pushed on the next connection");
        }
    }
}
