/*  Copyright (C) 2026 Vitaliy Tomin

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

package nodomain.freeyourgadget.gadgetbridge.devices.samsung;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.install.InstallActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.Fit3WatchfaceFile;

/**
 * Installs Galaxy Fit3 watchface packages ({@code SM-R390_<id>_256x402.bin}).
 * The package is validated structurally (see {@link Fit3WatchfaceFile}) and
 * must name the Fit3's model and screen size before install is offered.
 */
public class SamsungFit3InstallHandler implements InstallHandler {
    private final Context context;
    private final Fit3WatchfaceFile file;

    public SamsungFit3InstallHandler(Uri uri, Context context) {
        this.context = context;
        this.file = Fit3WatchfaceFile.fromUri(uri, context);
    }

    @Override
    public boolean isValid() {
        return file != null;
    }

    @Override
    public void validateInstallation(@NonNull InstallActivity installActivity, @NonNull GBDevice device) {
        if (device.isBusy()) {
            installActivity.setInfoText(device.getBusyTask());
            installActivity.setInstallEnabled(false);
            return;
        }
        if (!device.isInitialized()) {
            installActivity.setInfoText(context.getString(R.string.fwapp_install_device_not_ready));
            installActivity.setInstallEnabled(false);
            return;
        }

        GenericItem installItem = new GenericItem();
        installItem.setIcon(R.drawable.ic_watchface);
        installItem.setName(context.getString(R.string.kind_watchface) + " " + file.id);
        installItem.setDetails(String.format(Locale.ROOT, "%s, %d×%d", file.model, file.width, file.height));
        installActivity.setInstallItem(installItem);

        if (!file.isForFit3()) {
            installActivity.setInfoText(context.getString(R.string.fwinstaller_file_not_compatible_to_device));
            installActivity.setInstallEnabled(false);
            return;
        }

        installActivity.setInfoText(context.getString(R.string.watchface_install_resolution_matches));
        installActivity.setInstallEnabled(true);
    }

    @NonNull
    @Override
    public Class<? extends Activity> getInstallActivity() {
        return FwAppInstallerActivity.class;
    }

    @Override
    public void onStartInstall(@NonNull GBDevice device) {
        // The device support re-reads the file from its URI.
    }
}
