/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.devices.soundcore.sleep_a30;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import java.io.InputStream;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.install.InstallActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30.SoundcoreSleepA30Audio;

public final class SoundcoreSleepA30AudioInstallHandler implements InstallHandler {
    public static final String EXTRA_APPROVED_SHA256 = "soundcore_a30_audio_sha256";
    private final Context context;
    private SoundcoreSleepA30Audio audio;

    public SoundcoreSleepA30AudioInstallHandler(Uri uri, Context context) {
        this.context = context;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input != null) audio = SoundcoreSleepA30Audio.read(input);
        } catch (Exception ignored) {
            // An invalid native package is not an installable file.
        }
    }

    @Override public Class<? extends Activity> getInstallActivity() { return FwAppInstallerActivity.class; }
    @Override public boolean isValid() { return audio != null; }
    @Override public void onStartInstall(GBDevice device) {
        if (audio == null || !(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        Bundle existing = activity.getIntent().getParcelableExtra(DeviceService.EXTRA_OPTIONS);
        Bundle options = existing == null ? new Bundle() : new Bundle(existing);
        options.putByteArray(EXTRA_APPROVED_SHA256, audio.sha256());
        // The shared installer reads these options after this hook returns.
        activity.getIntent().putExtra(DeviceService.EXTRA_OPTIONS, options);
    }

    @Override public void validateInstallation(InstallActivity activity, GBDevice device) {
        activity.setInstallEnabled(false);
        if (audio == null) {
            activity.setInfoText(context.getString(R.string.installer_activity_unable_to_find_handler));
        } else if (!(device.getDeviceCoordinator() instanceof SoundcoreSleepA30Coordinator)) {
            activity.setInfoText(context.getString(R.string.fwapp_install_device_not_supported));
        } else if (device.isBusy()) {
            activity.setInfoText(device.getBusyTask());
        } else if (!device.isInitialized()) {
            activity.setInfoText(context.getString(R.string.fwapp_install_device_not_ready));
        } else {
            activity.setInstallItem(new GenericItem(audio.title));
            activity.setInfoText(context.getString(R.string.soundcore_audio_upload_warning));
            activity.setInstallEnabled(true);
        }
    }
}
