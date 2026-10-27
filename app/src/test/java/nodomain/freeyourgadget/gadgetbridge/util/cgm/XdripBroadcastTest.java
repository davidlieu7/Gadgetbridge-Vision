package nodomain.freeyourgadget.gadgetbridge.util.cgm;

import android.os.Bundle;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import nodomain.freeyourgadget.gadgetbridge.test.GBTestApplication;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 23, application = GBTestApplication.class)
public class XdripBroadcastTest {
    // Extras as put by xDrip+ BroadcastGlucose
    private static Bundle extras(final String units) {
        final Bundle b = new Bundle();
        b.putDouble("com.eveningoutpost.dexdrip.Extras.BgEstimate", 153.4);
        b.putDouble("com.eveningoutpost.dexdrip.Extras.BgSlope", 4.0 / 300_000);
        b.putString("com.eveningoutpost.dexdrip.Extras.BgSlopeName", "FortyFiveUp");
        b.putLong("com.eveningoutpost.dexdrip.Extras.Time", 1793088827259L);
        if (units != null) {
            b.putString("com.eveningoutpost.dexdrip.Extras.Display.Units", units);
        }
        return b;
    }

    @Test
    public void reading() {
        final CgmSnapshot.Reading r = XdripBroadcast.reading(extras("mg/dl"));
        Assert.assertNotNull(r);
        Assert.assertEquals(1793088827259L, r.timestamp);
        Assert.assertEquals(153, r.mgdl);
        Assert.assertEquals(4.0, r.deltaMgdl, 1e-9);
        Assert.assertEquals("FortyFiveUp", r.direction);
    }

    @Test
    public void units() {
        Assert.assertEquals(Boolean.TRUE, XdripBroadcast.mgdlUnits(extras("mg/dl")));
        Assert.assertEquals(Boolean.FALSE, XdripBroadcast.mgdlUnits(extras("mmol")));
        Assert.assertNull(XdripBroadcast.mgdlUnits(extras(null)));
    }

    @Test
    public void withoutSlopeDeltaAndTrendAreUnknown() {
        final Bundle b = extras("mg/dl");
        b.remove("com.eveningoutpost.dexdrip.Extras.BgSlope");
        b.remove("com.eveningoutpost.dexdrip.Extras.BgSlopeName");
        final CgmSnapshot.Reading r = XdripBroadcast.reading(b);
        Assert.assertTrue(Double.isNaN(r.deltaMgdl));
        Assert.assertEquals("", r.direction);
    }

    @Test
    public void withoutValueOrTimeThereIsNoReading() {
        final Bundle noValue = extras("mg/dl");
        noValue.remove("com.eveningoutpost.dexdrip.Extras.BgEstimate");
        Assert.assertNull(XdripBroadcast.reading(noValue));
        final Bundle noTime = extras("mg/dl");
        noTime.remove("com.eveningoutpost.dexdrip.Extras.Time");
        Assert.assertNull(XdripBroadcast.reading(noTime));
        Assert.assertNull(XdripBroadcast.reading(null));
    }
}
