package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.p2p.dictionarysync;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiState;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiAltitudeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiAltitudeSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.p2p.HuaweiP2PDataDictionarySyncService;

public class HuaweiDictionarySyncAltitude implements  HuaweiDictionarySyncInterface {
    private final Logger LOG = LoggerFactory.getLogger(HuaweiDictionarySyncAltitude.class);

    public static final int ALTITUDE_CLASS = 200003;
    public static final int ALTITUDE_VALUE = 200003586;

    @Override
    public int getDataClass() {
        return ALTITUDE_CLASS;
    }

    @Override
    public boolean supports(HuaweiState state) {
        return state.supportsAltitude();
    }

    @Override
    public long getLastDataSyncTimestamp(GBDevice gbDevice) {
        try (DBHandler db = GBApplication.acquireDB()) {
            HuaweiAltitudeSampleProvider AltitudeStatsSampleProvider = new HuaweiAltitudeSampleProvider(gbDevice, db.getDaoSession());
            return AltitudeStatsSampleProvider.getLastFetchTimestamp();
        } catch (Exception e) {
            LOG.warn("Exception for getting altitude start time", e);
        }
        return 0;
    }

    @Override
    public void handleData(Context context, GBDevice gbDevice, List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
        List<HuaweiAltitudeSample> altitudeSamples = new ArrayList<>();
        for (HuaweiP2PDataDictionarySyncService.DictData dt : dictData) {
            long timestamp = dt.getStartTimestamp();
            long lastTime = Math.max(dt.getEndTimestamp(), dt.getModifyTimestamp());
            Double altitude = null;
            for (HuaweiP2PDataDictionarySyncService.DictData.DictDataValue val : dt.getData()) {
                if (val.getTag() == 10) {
                    if (val.getDataType() == ALTITUDE_VALUE) {
                        double value = convBytes2Double(val.getValue());
                        if (value >= -1000 && value <= 10000) {
                            altitude = value;
                        } else {
                            LOG.info("altitude invalid value: {}", value);
                        }
                    } else {
                        LOG.info("altitude unknown data type: {}", val.getDataType());
                    }
                } else {
                    LOG.info("altitude unsupported tag: {}", val.getTag());
                }
            }
            if(altitude != null) {
                HuaweiAltitudeSample sample = new HuaweiAltitudeSample();
                sample.setTimestamp(timestamp);
                sample.setLastTimestamp(lastTime);
                sample.setAltitude(altitude);
                altitudeSamples.add(sample);
            }
        }
        try (DBHandler db = GBApplication.acquireDB()) {
            final DaoSession session = db.getDaoSession();
            new HuaweiAltitudeSampleProvider(gbDevice, session).persistSamples(altitudeSamples, context);
        } catch (Exception e) {
            LOG.error("Cannot save altitude samples, continue");
        }
    }

    private static double convBytes2Double(byte[] b) {
        if (b == null) {
            return 0.0;
        }

        ByteBuffer buffer = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);

        if (b.length == 8) {
            return buffer.getDouble();
        }
        if (b.length == 4) {
            return buffer.getFloat();
        }

        return 0.0;
    }
}
