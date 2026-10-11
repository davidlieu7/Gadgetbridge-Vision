package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.aeroFit;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_SOUNDCORE_BATTERY_LOW_TONE;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_SOUNDCORE_CONTROL_TOUCH_DISABLED;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_SOUNDCORE_TOUCH_TONE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class SoundcoreAeroFitProtocol extends SoundcoreProtocolImplV1 {

    private static final Logger LOG = LoggerFactory.getLogger(SoundcoreAeroFitProtocol.class);

    private static final int BATTERY_MULTIPLIER = 10;
    private static final int BATTERY_OFFSET = 1;

    public SoundcoreAeroFitProtocol(GBDevice device) {
        super(device);
    }

    @Override
    protected GBDeviceEvent[] decodeDeviceInfo(final byte[] payload) {
        if (payload.length < 30) {
            LOG.warn("CMD_GET_DEVICE_INFO payload too short: {} bytes", payload.length);
            return new GBDeviceEvent[0];
        }

        final String firmware1 = readString(payload, 4, 5);
        final String firmware2 = readString(payload, 9, 5);
        final String serialNumber = readString(payload, 14, 16);
        final GBDeviceEvent[] batteryEvents = handleBatteryInfo(
                new byte[]{payload[2], payload[3]}, BATTERY_MULTIPLIER, BATTERY_OFFSET
        );
        return new GBDeviceEvent[]{
                batteryEvents[0],
                batteryEvents[1],
                buildVersionInfo(firmware1, firmware2, serialNumber)
        };
    }

    @Override
    protected GBDeviceEvent[] decodePairedDevices(final byte[] payload) {
        LOG.debug("Incoming information about connected devices, {} bytes", payload.length);
        return new GBDeviceEvent[0];
    }

    @Override
    protected int getBatteryMultiplier() {
        return BATTERY_MULTIPLIER;
    }

    @Override
    protected int getBatteryOffset() {
        return BATTERY_OFFSET;
    }

    @Override
    protected GBDeviceEvent[] decodeChargingInfo(final byte[] payload) {
        if (payload.length < 2) {
            LOG.warn("CMD_NOTIFY_CHARGING_INFO payload too short: {} bytes", payload.length);
            return new GBDeviceEvent[0];
        }

        LOG.info("Left Earbud in Charging Case: {}, Right Earbud in Charging Case: {}",
                payload[0] == 0x01, payload[1] == 0x01);
        return new GBDeviceEvent[0];
    }
}
