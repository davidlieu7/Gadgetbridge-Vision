package nodomain.freeyourgadget.gadgetbridge.activities;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;

import java.io.Serializable;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertEquals;

public class HeartRateDialogTest extends TestBase {
    private GBDevice device;
    private HeartRateDialog dialog;
    private Activity activity;

    @Before
    public void showDialog() {
        device = createDummyGDevice("00:11:22:33:44:55");
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.GadgetbridgeTheme);
        dialog = new HeartRateDialog(device, activity);
        dialog.show();
    }

    @After
    public void dismissDialog() {
        dialog.dismiss();
        activity.finish();
    }

    private Intent error(GBDevice source) {
        return new Intent(DeviceService.ACTION_HEARTRATE_MEASUREMENT_ERROR)
                .putExtra(GBDevice.EXTRA_DEVICE, source)
                .putExtra(DeviceService.EXTRA_MEASUREMENT_ERROR, "No valid measurement received");
    }

    private void reading(int bpm) {
        dialog.mReceiver.onReceive(activity, new Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                .putExtra(GBDevice.EXTRA_DEVICE, device)
                .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, new Reading(bpm)));
    }

    @Test
    public void timeoutReplacesSpinnerWithError() {
        dialog.mReceiver.onReceive(activity, error(device));
        assertEquals(View.GONE, dialog.heart_rate_dialog_loading_layout.getVisibility());
        assertEquals(View.GONE, dialog.heart_rate_dialog_results_layout.getVisibility());
        assertEquals("No valid measurement received", dialog.heart_rate_dialog_label.getText().toString());
    }

    @Test
    public void errorForAnotherDeviceDoesNotFinishMeasurement() {
        dialog.mReceiver.onReceive(activity, error(createDummyGDevice("00:11:22:33:44:66")));
        assertEquals(View.VISIBLE, dialog.heart_rate_dialog_loading_layout.getVisibility());
    }

    @Test
    public void invalidReadingDoesNotFinishMeasurement() {
        reading(0);
        assertEquals(View.VISIBLE, dialog.heart_rate_dialog_loading_layout.getVisibility());
    }

    @Test
    public void validReadingIsNotOverwrittenByTimeout() {
        reading(72);
        dialog.mReceiver.onReceive(activity, error(device));
        assertEquals(View.GONE, dialog.heart_rate_dialog_loading_layout.getVisibility());
        assertEquals(View.VISIBLE, dialog.heart_rate_dialog_results_layout.getVisibility());
        assertEquals("72", dialog.heart_rate_widget_hr_value.getText().toString());
        assertEquals(activity.getString(R.string.heart_rate_result), dialog.heart_rate_dialog_label.getText().toString());
    }

    @Test
    public void heartRateErrorDoesNotFinishSpo2Measurement() {
        dialog.dismiss();
        dialog = new HeartRateDialog(device, activity, HeartRateDialog.Measurement.SPO2);
        dialog.show();
        dialog.mReceiver.onReceive(activity, error(device));
        assertEquals(View.VISIBLE, dialog.heart_rate_dialog_loading_layout.getVisibility());
    }

    private static final class Reading implements HeartRateSample, Serializable {
        private final int bpm;
        Reading(int bpm) { this.bpm = bpm; }
        @Override public long getTimestamp() { return 1L; }
        @Override public int getHeartRate() { return bpm; }
    }
}
