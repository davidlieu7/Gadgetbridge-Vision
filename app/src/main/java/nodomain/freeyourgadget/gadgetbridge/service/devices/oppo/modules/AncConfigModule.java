package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.modules;

import android.content.Context;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.devices.oppo.OppoHeadphonesPreferences;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.OppoUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.AncConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.AncConfigValue;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.OppoCommand;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.OppoMessage;

public class AncConfigModule extends AbstractModule {
    private static final Logger LOG = LoggerFactory.getLogger(AncConfigModule.class);

    public AncConfigModule(@NonNull final Context context) {
        super(context);
    }

    @NonNull
    public List<OppoMessage> encodeReq(@NonNull final EnumSet<AncConfigType> types) {
        final List<OppoMessage> messages = new ArrayList<>();
        for (AncConfigType type : types) {
            messages.add(encodeReq(type));
        }

        return messages;
    }

    @NonNull
    public OppoMessage encodeReq(@NonNull final AncConfigType type) {
        byte[] payload = new byte[]{
            (byte) type.getCode(),
            (byte) 0x01,
        };

        return new OppoMessage(OppoCommand.ANC_CONFIG_REQ, payload);
    }

    @NonNull
    public OppoMessage encodeSetMode(@NonNull final AncConfigValue value) {
        return encodeSet(AncConfigType.MODE, value.getCode());
    }

    public OppoMessage encodeSetTouchCycleModes(@NonNull final EnumSet<AncConfigValue> values) {
        if (values.size() < 2) {
            LOG.warn("ANC cycle must contain at least 2 values. Current selection: {}", values);
            return null;
        }
        return encodeSet(AncConfigType.TOUCH_CYCLE_MODES, AncConfigValue.toMask(values));
    }

    @NonNull
    public GBDeviceEventUpdatePreferences decodeRet(@NonNull final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload);
        final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();

        if (buf.remaining() != 4) {
            LOG.warn("Unexpected anc config ret payload remaining {}, expected 4", buf.remaining());
            return event;
        }

        final int zero = buf.get();
        final int typeCode = buf.get() & 0xFF;
        final int one = buf.get();
        final int valueCode = buf.get() & 0xff;

        final AncConfigType type = AncConfigType.fromCode(typeCode);
        if (type == null) {
            LOG.warn("Unknown anc type code 0x{}", OppoUtils.numberToHex(typeCode, 2));
            return event;
        }

        switch (type) {
            case MODE: {
                final AncConfigValue value = AncConfigValue.fromCode(valueCode);
                if (value == null) {
                    LOG.warn("Unknown anc value code 0x{}", OppoUtils.numberToHex(valueCode, 2));
                    break;
                }

                LOG.debug("Got anc config for {} = {}", type, value);
                event.withPreference(OppoHeadphonesPreferences.ANC_SELECTOR, value.getPrefId());
                break;
            }
            case TOUCH_CYCLE_MODES: {
                final EnumSet<AncConfigValue> values = AncConfigValue.fromMask(valueCode);
                if (values.isEmpty()) {
                    LOG.warn("Unknown anc value mask 0x{}", OppoUtils.numberToHex(valueCode, 2));
                    break;
                }
                final Set<String> valuePrefIds = AncConfigValue.toPrefIds(values);
                LOG.debug("Got anc config for {} = {}", type, valuePrefIds);
                event.withPreference(OppoHeadphonesPreferences.ANC_TOUCH_CYCLE_MODES, valuePrefIds);
                break;
            }
            default: {
                LOG.debug("Unknown anc type code {}", typeCode);
                break;
            }
        }
        return event;
    }

    private OppoMessage encodeSet(@NonNull final AncConfigType type, final int value) {
        LOG.debug("Send {} = {}", type, value);
        final byte[] payload = new byte[]{
            (byte) type.getCode(),
            (byte) 0x01,
            (byte) value
        };

        return new OppoMessage(OppoCommand.ANC_CONFIG_SET, payload);
    }
}
