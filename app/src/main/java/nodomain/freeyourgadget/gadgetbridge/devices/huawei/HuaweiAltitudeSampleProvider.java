package nodomain.freeyourgadget.gadgetbridge.devices.huawei;

import androidx.annotation.NonNull;

import java.util.List;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiAltitudeSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiAltitudeSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class HuaweiAltitudeSampleProvider extends AbstractTimeSampleProvider<HuaweiAltitudeSample> {
    public HuaweiAltitudeSampleProvider(final GBDevice device, final DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public AbstractDao<HuaweiAltitudeSample, ?> getSampleDao() {
        return getSession().getHuaweiAltitudeSampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return HuaweiAltitudeSampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return HuaweiAltitudeSampleDao.Properties.DeviceId;
    }

    @Override
    public HuaweiAltitudeSample createSample() {
        return new HuaweiAltitudeSample();
    }


    public long getLastFetchTimestamp() {
        QueryBuilder<HuaweiAltitudeSample> qb = getSampleDao().queryBuilder();
        Device dbDevice = DBHelper.findDevice(getDevice(), getSession());
        if (dbDevice == null)
            return 0;
        final Property deviceProperty = HuaweiAltitudeSampleDao.Properties.DeviceId;
        final Property timestampProperty = HuaweiAltitudeSampleDao.Properties.LastTimestamp;

        qb.where(deviceProperty.eq(dbDevice.getId()))
                .orderDesc(timestampProperty)
                .limit(1);

        List<HuaweiAltitudeSample> samples = qb.build().list();
        if (samples.isEmpty())
            return 0;

        HuaweiAltitudeSample sample = samples.get(0);
        return sample.getLastTimestamp();
    }

}
