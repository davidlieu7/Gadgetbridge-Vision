/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.devices.veryfit;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.install.InstallActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;

/** A watchface is the only thing these watches take over the file channel. */
public class VeryFitInstallHandler implements InstallHandler {
    private final Context context;
    private final VeryFitWatchfaceFile face;

    public VeryFitInstallHandler(final Uri uri, final Context context) {
        this.context = context;
        this.face = new VeryFitWatchfaceFile(uri, context);
    }

    @NonNull
    @Override
    public Class<? extends Activity> getInstallActivity() {
        return FwAppInstallerActivity.class;
    }

    @Override
    public boolean isValid() {
        return face.isValid();
    }

    @Override
    public void validateInstallation(@NonNull final InstallActivity installActivity,
                                     @NonNull final GBDevice device) {
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

        if (!face.isValid()) {
            installActivity.setInfoText(context.getString(R.string.fwapp_install_device_not_supported));
            installActivity.setInstallEnabled(false);
            return;
        }

        final GenericItem item = new GenericItem();
        item.setIcon(R.drawable.ic_watchface);
        item.setName(context.getString(R.string.kind_watchface));
        item.setDetails(details());

        installActivity.setInfoText(context.getString(R.string.firmware_install_warning, "(unknown)"));
        installActivity.setInstallItem(item);
        installActivity.setInstallEnabled(true);
    }

    /** What the face calls itself, and the model it says it was drawn for. */
    private String details() {
        final StringBuilder details = new StringBuilder();
        if (face.getName() != null) {
            details.append(face.getName());
        }
        if (face.getModel() != null) {
            if (details.length() > 0) {
                details.append(", ");
            }
            details.append(face.getModel());
        }
        if (details.length() > 0) {
            details.append(", ");
        }
        details.append(face.getOriginalSize()).append(" bytes");
        return details.toString();
    }

    @Override
    public void onStartInstall(@NonNull final GBDevice device) {
    }
}
