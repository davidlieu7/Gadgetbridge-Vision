package nodomain.freeyourgadget.gadgetbridge.service.devices.nxwear

import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import org.slf4j.LoggerFactory
import java.util.UUID

/** Initial connection support; device-specific commands and telemetry remain unimplemented. */
class NxWearB8Support : AbstractBTLESingleDeviceSupport(LOG) {
    init {
        // Discovered from nx-wear-1.txt: primary vendor services 0x0614 and 0xF615.
        addSupportedService(UUID.fromString("00000614-0000-1000-8000-00805f9b34fb"))
        addSupportedService(UUID.fromString("0000f615-0000-1000-8000-00805f9b34fb"))
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(NxWearB8Support::class.java)
    }
}
