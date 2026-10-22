package nodomain.freeyourgadget.gadgetbridge.devices.polar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Optional;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.PolarIgnite3ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.PolarIgnite3ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class PolarIgnite3ActivitySampleProvider extends AbstractSampleProvider<PolarIgnite3ActivitySample> {
    public PolarIgnite3ActivitySampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<PolarIgnite3ActivitySample, ?> getSampleDao() {
        return getSession().getPolarIgnite3ActivitySampleDao();
    }

    @Nullable
    @Override
    protected Property getRawKindSampleProperty() {
        return null;
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return PolarIgnite3ActivitySampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return PolarIgnite3ActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public ActivityKind normalizeType(int rawType) {
        return ActivityKind.fromCode(rawType);
    }

    @Override
    public int toRawActivityKind(ActivityKind activityKind) {
        return activityKind.getCode();
    }

    @Override
    public float normalizeIntensity(int rawIntensity) {
        return rawIntensity;
    }

    /**
     * Factory method to creates an empty sample of the correct type for this sample provider
     *
     * @return the newly created "empty" sample
     */
    @Override
    public PolarIgnite3ActivitySample createActivitySample() {
        return new PolarIgnite3ActivitySample();
    }

    public Optional<PolarIgnite3ActivitySample> getSampleForTimestamp(int timestamp) {
        List<PolarIgnite3ActivitySample> foundSamples = this.getGBActivitySamples(timestamp, timestamp);
        if (foundSamples.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(foundSamples.get(0));
    }
}
