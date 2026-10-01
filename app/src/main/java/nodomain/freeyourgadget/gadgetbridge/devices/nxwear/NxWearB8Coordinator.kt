package nodomain.freeyourgadget.gadgetbridge.devices.nxwear

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear.NxWearB8Support
import java.util.regex.Pattern

class NxWearB8Coordinator : AbstractBLEDeviceCoordinator() {
    override fun getSupportedDeviceName(): Pattern = Pattern.compile("^B8-SW$")

    override fun getManufacturer(): String = "Nx Wear"

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> = NxWearB8Support::class.java

    override fun getDeviceNameResource(): Int = R.string.devicetype_nx_wear_b8

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind = DeviceCoordinator.DeviceKind.WATCH

    override fun isExperimental(): Boolean = true
}
