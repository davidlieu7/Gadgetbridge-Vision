/*  Copyright (C) 2026 Gadgetbridge Contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 or later;
    see the LICENSE file for details.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.veepoo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VeepooEcgWaveformTest {

    private static byte[] toBytes(int... rawSamples) {
        byte[] result = new byte[rawSamples.length * 3];
        for (int i = 0; i < rawSamples.length; i++) {
            result[3 * i] = (byte) (rawSamples[i] >> 16);
            result[3 * i + 1] = (byte) (rawSamples[i] >> 8);
            result[3 * i + 2] = (byte) rawSamples[i];
        }
        return result;
    }

    @Test
    public void testDecodeSignedInt24() {
        int[] decoded = VeepooEcgWaveform.decode(toBytes(0x000001, 0xFFFFFFFE, 0x800000, 0x7FFFFF));
        assertArrayEquals(new int[]{1, -2, -8388608, 8388607}, decoded);
    }

    @Test
    public void testDecodeSkipsMarkers() {
        int[] decoded = VeepooEcgWaveform.decode(
                toBytes(1, 0xFFFFFF, 2, 0x7FFFFFFF, 3, 0x020202, 4, 0x080808));
        assertArrayEquals(new int[]{1, 2, 3, 4}, decoded);
    }

    @Test
    public void testDecodeIgnoresTrailingPartialSample() {
        byte[] waveform = toBytes(7);
        byte[] withTail = new byte[waveform.length + 2];
        System.arraycopy(waveform, 0, withTail, 0, waveform.length);
        assertArrayEquals(new int[]{7}, VeepooEcgWaveform.decode(withTail));
    }

    @Test
    public void testDecodeNullAndShort() {
        assertEquals(0, VeepooEcgWaveform.decode(null).length);
        assertEquals(0, VeepooEcgWaveform.decode(new byte[]{1, 2}).length);
    }

    @Test
    public void testDropWarmupDefaultSixSeconds() {
        int[] raw = new int[4000];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = i;
        }
        // ecgType 11 is the KR96 Pro value: six second warm-up at 500 Hz
        int[] result = VeepooEcgWaveform.dropWarmup(11, 500, raw);
        assertEquals(1000, result.length);
        assertEquals(3000, result[0]);
        assertEquals(3999, result[result.length - 1]);
    }

    @Test
    public void testDropWarmupFourSecondsForType1And3() {
        int[] raw = new int[500];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = i;
        }
        int[] type1 = VeepooEcgWaveform.dropWarmup(1, 100, raw);
        assertEquals(100, type1.length);
        assertEquals(400, type1[0]);
        int[] type3 = VeepooEcgWaveform.dropWarmup(3, 100, raw);
        assertEquals(100, type3.length);
        assertEquals(400, type3[0]);
    }

    @Test
    public void testDropWarmupNothingLeft() {
        int[] raw = new int[100];
        assertEquals(0, VeepooEcgWaveform.dropWarmup(11, 500, raw).length);
        assertEquals(0, VeepooEcgWaveform.dropWarmup(11, 0, raw).length);
        assertEquals(0, VeepooEcgWaveform.dropWarmup(11, 500, null).length);
    }

    @Test
    public void testNormalizeScalesToWindow() {
        float[] normalized = VeepooEcgWaveform.normalize(new int[]{100, 110, 90, 100});
        assertEquals(4, normalized.length);
        assertEquals(0.0f, normalized[0], 1e-4);
        assertEquals(1.8f, normalized[1], 1e-4);
        assertEquals(-1.8f, normalized[2], 1e-4);
        assertEquals(0.0f, normalized[3], 1e-4);
    }

    @Test
    public void testNormalizeAlreadyCentered() {
        float[] normalized = VeepooEcgWaveform.normalize(new int[]{0, 10, -10});
        assertEquals(0.0f, normalized[0], 1e-4);
        assertEquals(1.8f, normalized[1], 1e-4);
        assertEquals(-1.8f, normalized[2], 1e-4);
    }

    @Test
    public void testNormalizeFlatSignalStaysOnBaseline() {
        float[] flat = VeepooEcgWaveform.normalize(new int[]{5, 5, 5, 5});
        assertEquals(4, flat.length);
        for (float v : flat) {
            assertEquals(0.0f, v, 1e-6);
        }
        // near-flat (std below threshold) also maps to the baseline
        float[] noisy = VeepooEcgWaveform.normalize(new int[]{5, 5, 5, 4});
        for (float v : noisy) {
            assertEquals(0.0f, v, 1e-6);
        }
    }

    @Test
    public void testNormalizeEmpty() {
        assertEquals(0, VeepooEcgWaveform.normalize(null).length);
        assertEquals(0, VeepooEcgWaveform.normalize(new int[0]).length);
    }

    @Test
    public void testDecodeDropWarmupNormalizeChain() {
        // 2500 samples at 500 Hz with a linear ramp, warm-up dropped, then normalized
        int[] raw = new int[2500];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = i;
        }
        int[] decoded = VeepooEcgWaveform.decode(toBytes(raw));
        int[] afterWarmup = VeepooEcgWaveform.dropWarmup(11, 500, decoded);
        float[] normalized = VeepooEcgWaveform.normalize(afterWarmup);
        assertEquals(raw.length, decoded.length);
        assertEquals(0, afterWarmup.length); // 2500 < 6 s warm-up at 500 Hz
        assertEquals(0, normalized.length);
    }
}
