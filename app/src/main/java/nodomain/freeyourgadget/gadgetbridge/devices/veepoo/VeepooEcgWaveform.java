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

import java.util.Arrays;

/**
 * Pure-Java decoding helpers for stored Veepoo ECG waveforms (see {@code VeepooEcgSample.waveform}).
 * The stored bytes are big-endian signed int24 samples as captured from the 0x88 light frames;
 * invalid-sample markers may be interleaved and are dropped here.
 */
public final class VeepooEcgWaveform {

    /** Invalid-sample markers sent by the band inside the waveform stream (big-endian int24). */
    private static final int[] MARKERS = {
            0xFFFFFF, 0x7FFFFFFF, 0x020202, 0x080808
    };

    /** Largest display unit magnitude used by {@link #normalize(int[])} (keeps QRS in the chart window). */
    private static final float MAX_UNITS = 1.8f;

    /** Population standard deviation below which a signal counts as flat (in raw sample units). */
    private static final float FLAT_STD_THRESHOLD = 1.0f;

    private VeepooEcgWaveform() {
    }

    /**
     * Decodes stored waveform bytes into signed int24 samples, dropping invalid-sample markers.
     *
     * @param waveform raw samples as stored in {@code VeepooEcgSample.waveform} (3 bytes each);
     *                 a trailing partial sample is ignored
     * @return the valid samples in capture order; empty for null/short input
     */
    public static int[] decode(byte[] waveform) {
        if (waveform == null || waveform.length < 3) {
            return new int[0];
        }
        int count = waveform.length / 3;
        int[] result = new int[count];
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int b0 = waveform[3 * i] & 0xFF;
            int b1 = waveform[3 * i + 1] & 0xFF;
            int b2 = waveform[3 * i + 2] & 0xFF;
            int raw = (b0 << 16) | (b1 << 8) | b2;
            if (isMarker(raw)) {
                continue;
            }
            result[kept++] = raw < 0x800000 ? raw : raw - 0x1000000;
        }
        return kept == count ? result : Arrays.copyOf(result, kept);
    }

    /**
     * Drops the warm-up samples the vendor discards before showing a stored recording.
     * The warm-up lasts 4 s for ecgType 1/3 and 6 s otherwise (vendor {@code splitUselessFloatData}).
     *
     * @param ecgType ECG type byte from the A7 capability report (0 when unknown)
     * @param freq    sample rate in Hz of the stored recording
     * @param raw     decoded samples
     * @return the samples after the warm-up; empty when nothing is left
     */
    public static int[] dropWarmup(int ecgType, int freq, int[] raw) {
        if (raw == null || raw.length == 0 || freq <= 0) {
            return new int[0];
        }
        int warmup = (ecgType == 1 || ecgType == 3 ? 4 : 6) * freq;
        if (warmup >= raw.length) {
            return new int[0];
        }
        return Arrays.copyOfRange(raw, warmup, raw.length);
    }

    /**
     * Normalizes samples into the vendor display window: the median becomes the baseline (0) and
     * the largest deviation is scaled to at most {@link #MAX_UNITS} units, so the trace fits the
     * chart's 2/3-up / 1/3-down layout. A flat signal (std below threshold) maps to all zeros.
     *
     * @param samples decoded, warm-up-dropped samples
     * @return baseline-centered display values in units of one ECG calibration square row
     */
    public static float[] normalize(int[] samples) {
        if (samples == null || samples.length == 0) {
            return new float[0];
        }
        double baseline = median(samples);
        double maxDeviation = 0;
        double sumSquares = 0;
        for (int s : samples) {
            double d = s - baseline;
            if (Math.abs(d) > maxDeviation) {
                maxDeviation = Math.abs(d);
            }
            sumSquares += d * d;
        }
        double std = Math.sqrt(sumSquares / samples.length);
        float[] result = new float[samples.length];
        if (std < FLAT_STD_THRESHOLD || maxDeviation == 0) {
            return result; // flat signal: stay on the baseline
        }
        float scale = (float) (MAX_UNITS / maxDeviation);
        for (int i = 0; i < samples.length; i++) {
            result[i] = (float) ((samples[i] - baseline) * scale);
        }
        return result;
    }

    private static boolean isMarker(int raw) {
        for (int marker : MARKERS) {
            if (raw == marker) {
                return true;
            }
        }
        return false;
    }

    private static double median(int[] samples) {
        int[] sorted = samples.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
