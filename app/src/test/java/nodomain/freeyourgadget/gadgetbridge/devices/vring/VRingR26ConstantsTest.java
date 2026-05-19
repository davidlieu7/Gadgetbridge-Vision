package nodomain.freeyourgadget.gadgetbridge.devices.vring;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class VRingR26ConstantsTest {
    @Test
    public void rawActivityKindsRoundTrip() {
        for (final VRingR26Constants.RawActivityKind kind
                : VRingR26Constants.RawActivityKind.values()) {
            assertEquals(kind,
                    VRingR26Constants.RawActivityKind.fromRawValue(kind.getRawValue()));
            assertEquals(kind,
                    VRingR26Constants.RawActivityKind.fromActivityKind(kind.getActivityKind()));
        }
        assertEquals(VRingR26Constants.RawActivityKind.UNKNOWN,
                VRingR26Constants.RawActivityKind.fromRawValue(999));
        assertEquals(VRingR26Constants.RawActivityKind.UNKNOWN,
                VRingR26Constants.RawActivityKind.fromActivityKind(ActivityKind.RUNNING));
    }
}
