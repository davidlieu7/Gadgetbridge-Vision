/*  Copyright (C) 2026 The Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.canon

import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid
import android.text.InputType
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.canon.CanonCameraSupport

class CanonCameraCoordinator : AbstractBLEDeviceCoordinator() {
    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind {
        return DeviceCoordinator.DeviceKind.CAMERA
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_canon_camera
    }

    override fun getDefaultIconResource(): Int {
        return R.drawable.ic_device_camera
    }

    override fun getManufacturer(): String {
        return "Canon"
    }

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> {
        return CanonCameraSupport::class.java
    }

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec {
        return deviceSettings {
            DeviceSpecificSettingsScreen.LOCATION.screen {
                text(
                    key = CanonCameraConstants.PREF_GPS_UPDATE_INTERVAL,
                    title = R.string.pref_title_canon_camera_gps_update_interval,
                    summary = R.string.pref_summary_canon_camera_gps_update_interval,
                    icon = R.drawable.ic_access_time,
                    defaultValue = CanonCameraConstants.PREF_GPS_UPDATE_INTERVAL_DEFAULT.toString(),
                    inputType = InputType.TYPE_CLASS_NUMBER,
                    connectedOnly = false,
                )

                switchSetting(
                    key = CanonCameraConstants.PREF_GPS_USE_NETWORK,
                    title = R.string.pref_title_canon_camera_gps_network,
                    summary = R.string.pref_summary_canon_camera_gps_network,
                    icon = R.drawable.ic_gps_location,
                    defaultValue = CanonCameraConstants.PREF_GPS_USE_NETWORK_DEFAULT,
                    connectedOnly = false,
                )
            }
        }
    }

    override fun getCustomActions(): List<DeviceCardAction> {
        return listOf(
            DeviceCardAction.forActivity(
                R.drawable.ic_camera_remote,
                R.string.canon_camera_remote_control,
                CanonCameraControlActivity::class.java
            ),
        )
    }

    override fun getBondingStyle(): Int {
        return BONDING_STYLE_BOND
    }

    override fun createBLEScanFilters(): List<ScanFilter> {
        return listOf(
            ScanFilter.Builder()
                .setManufacturerData(CanonCameraConstants.MANUFACTURER_ID, byteArrayOf(), byteArrayOf())
                .setServiceUuid(ParcelUuid(CanonCameraConstants.UUID_SERVICE_MAIN))
                .build()
        )
    }

    override fun supports(candidate: GBDeviceCandidate): Boolean {
        return candidate.manufacturerSpecificData.get(CanonCameraConstants.MANUFACTURER_ID) != null &&
            candidate.supportsService(CanonCameraConstants.UUID_SERVICE_MAIN);
    }
}
