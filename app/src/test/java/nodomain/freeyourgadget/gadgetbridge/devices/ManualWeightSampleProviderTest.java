package nodomain.freeyourgadget.gadgetbridge.devices;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericWeightSample;
import nodomain.freeyourgadget.gadgetbridge.entities.ManualWeightSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.UserWeight;

/**
 * A weight entered by hand belongs to no device: every device that supports weight shows it next to
 * its own readings, and editing keeps a manual weight manual and a device reading with its device.
 * The values are synthetic, not readings from a person.
 */
public class ManualWeightSampleProviderTest extends TestBase {
    private GBDevice scale;
    private GBDevice watch;

    @Before
    public void setUpDevices() {
        scale = createDummyGDevice("00:00:00:00:00:31");
        watch = createDummyGDevice("00:00:00:00:00:32");
        // the providers only find devices that are in the database
        DBHelper.getDevice(scale, daoSession);
        DBHelper.getDevice(watch, daoSession);
    }

    private void addReading(final GBDevice device, final long timestamp, final float kg) {
        final GenericWeightSample sample = new GenericWeightSample();
        sample.setTimestamp(timestamp);
        sample.setWeightKg(kg);
        sample.setDeviceId(DBHelper.getDevice(device, daoSession).getId());
        sample.setUserId(DBHelper.getUser(daoSession).getId());
        new GenericWeightSampleProvider(device, daoSession).addSample(sample);
    }

    private WeightWithManualSampleProvider merged(final GBDevice device) {
        return new WeightWithManualSampleProvider(new GenericWeightSampleProvider(device, daoSession), new ManualWeightSampleProvider(daoSession));
    }

    private static List<String> describe(final List<? extends WeightSample> samples) {
        final List<String> out = new ArrayList<>();
        for (final WeightSample sample : samples) {
            out.add(sample.getTimestamp() + "=" + sample.getWeightKg() + (sample instanceof ManualWeightSample ? "m" : "d"));
        }
        return out;
    }

    @Test
    public void everyDeviceShowsTheManualWeightNextToItsOwnReadings() {
        addReading(scale, 1000L, 70.0f);
        UserWeight.addRecord(daoSession, 71.5, 2000L);
        addReading(watch, 3000L, 72.0f);

        assertEquals(List.of("1000=70.0d", "2000=71.5m"), describe(merged(scale).getAllSamples(0, 10_000L)));
        assertEquals(List.of("2000=71.5m", "3000=72.0d"), describe(merged(watch).getAllSamples(0, 10_000L)));
    }

    @Test
    public void latestAndFirstLookAtBothStores() {
        addReading(scale, 1000L, 70.0f);
        addReading(scale, 3000L, 72.0f);
        UserWeight.addRecord(daoSession, 71.5, 2000L);
        UserWeight.addRecord(daoSession, 69.0, 500L);

        final WeightWithManualSampleProvider provider = merged(scale);
        assertEquals(3000L, provider.getLatestSample().getTimestamp());
        assertEquals(2000L, provider.getLatestSample(2500L).getTimestamp());
        assertEquals(500L, provider.getFirstSample().getTimestamp());
    }

    @Test
    public void aManualWeightIsStoredOnceWhateverTheDeviceCount() {
        UserWeight.addRecord(daoSession, 71.5, 2000L);

        assertEquals(1, daoSession.getManualWeightSampleDao().count());
        assertEquals(0, daoSession.getGenericWeightSampleDao().count());
    }

    @Test
    public void deletingRemovesOnlyTheRowThatWasShown() {
        addReading(scale, 1000L, 70.0f);
        UserWeight.addRecord(daoSession, 71.5, 2000L);

        final WeightSample manual = UserWeight.deleteRecord(daoSession, scale, 2000L);
        assertTrue(manual instanceof ManualWeightSample);
        assertEquals(List.of("1000=70.0d"), describe(merged(scale).getAllSamples(0, 10_000L)));

        final WeightSample reading = UserWeight.deleteRecord(daoSession, scale, 1000L);
        assertTrue(reading instanceof GenericWeightSample);
        assertEquals(List.of(), describe(merged(scale).getAllSamples(0, 10_000L)));

        UserWeight.restoreRecord(daoSession, manual);
        UserWeight.restoreRecord(daoSession, reading);
        assertEquals(List.of("1000=70.0d", "2000=71.5m"), describe(merged(scale).getAllSamples(0, 10_000L)));
    }

    @Test
    public void editingKeepsTheKindOfTheRow() {
        addReading(scale, 1000L, 70.0f);
        UserWeight.addRecord(daoSession, 71.5, 2000L);

        UserWeight.replaceRecord(daoSession, scale, 2000L, 71.0, 2500L);
        UserWeight.replaceRecord(daoSession, scale, 1000L, 70.2, 1200L);

        assertEquals(List.of("1200=70.2d", "2500=71.0m"), describe(merged(scale).getAllSamples(0, 10_000L)));
        // the edited reading stays the scale's, so the watch does not gain it
        assertEquals(List.of("2500=71.0m"), describe(merged(watch).getAllSamples(0, 10_000L)));
    }

    @Test
    public void deletingNothingReturnsNull() {
        assertNull(UserWeight.deleteRecord(daoSession, scale, 4242L));
    }
}
