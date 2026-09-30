package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class EnergyStateStressCalculatorTest {
    private static final int DAY_START = 1790719200; // 2026-09-30T00:00:00+02:00

    // Last 2 bytes of each minute between 00:01 and 00:59 of a Mi Watch Color Sport daily details
    // v2 file (energy state value, total calories + energy state)
    private static final String COLOR_SPORT_FIRST_HOUR = "8000de0bc007b407d007c30bb407ad0b80103d0e331239124d1a59225d1a5a16561e59123c124f0ea007cc078008c407b1079b07c007cb0bbc07b80b800480080b123812400e4516511a5a22602a61225e1a591658124a0e3f1e801480188014800c80088008800c800c800880048004ca0bcf07af0b";

    @Test
    public void matchesOfficialApp() {
        final byte[] bytes = GB.hexStringToByteArray(COLOR_SPORT_FIRST_HOUR);
        final List<EnergyStateStressCalculator.Entry> entries = new ArrayList<>();
        for (int i = 0; i < bytes.length / 2; i++) {
            final int rawValue = bytes[i * 2] & 0xff;
            final int state = bytes[i * 2 + 1] & 0x03;
            final int value = ((rawValue & 0x80) != 0 ? 1 : -1) * (rawValue & 0x7f);
            entries.add(new EnergyStateStressCalculator.Entry(DAY_START + 10 + 60 * (i + 1), state, value));
        }

        final Map<Integer, Integer> stress = EnergyStateStressCalculator.compute(DAY_START, entries);

        // Values shown by Mi Fitness for the same data
        assertEquals(2, stress.size());
        assertEquals(Integer.valueOf(24), stress.get(DAY_START));
        assertEquals(Integer.valueOf(52), stress.get(DAY_START + 1800));
    }

    @Test
    public void mostlyActivityHasNoStress() {
        final List<EnergyStateStressCalculator.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            final int state = i < 20 ? EnergyStateStressCalculator.STATE_ACTIVITY : EnergyStateStressCalculator.STATE_STRESS;
            entries.add(new EnergyStateStressCalculator.Entry(DAY_START + 60 * i, state, -40));
        }

        assertEquals(0, EnergyStateStressCalculator.compute(DAY_START, entries).size());
    }

    @Test
    public void tooFewSamplesHasNoStress() {
        final List<EnergyStateStressCalculator.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entries.add(new EnergyStateStressCalculator.Entry(DAY_START + 60 * i, EnergyStateStressCalculator.STATE_STRESS, -40));
        }

        assertEquals(0, EnergyStateStressCalculator.compute(DAY_START, entries).size());
    }

    @Test
    public void friendlyStress() {
        assertEquals(51f, EnergyStateStressCalculator.friendlyStress(EnergyStateStressCalculator.STATE_STRESS, -40), 0.001f);
        assertEquals(13f, EnergyStateStressCalculator.friendlyStress(EnergyStateStressCalculator.STATE_RECOVERY, 80), 0.001f);
        assertEquals(0f, EnergyStateStressCalculator.friendlyStress(EnergyStateStressCalculator.STATE_ACTIVITY, 80), 0.001f);
    }
}
