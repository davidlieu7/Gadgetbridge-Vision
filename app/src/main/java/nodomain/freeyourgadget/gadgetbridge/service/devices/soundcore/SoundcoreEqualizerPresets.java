/*  Copyright (C) 2026 David Giron

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public final class SoundcoreEqualizerPresets {
    private static final String PRESET_CHANNEL_SEPARATOR = "00";
    private static final String PRESET_SUFFIX = "0000";
    private static final String PRESET_COMMON_REPEATED_BLOCK = "9fa18a998a8077643c3c";
    private static final String PRESET_COMMON_PREFIX = "00000200";
    private static final String PRESET_COMMON_MIDDLE = "69d64e6a00";
    private static final String PRESET_COMMON_SECTION =
        PRESET_COMMON_PREFIX
            + PRESET_COMMON_REPEATED_BLOCK
            + PRESET_COMMON_REPEATED_BLOCK
            + PRESET_COMMON_MIDDLE
            + PRESET_COMMON_REPEATED_BLOCK
            + PRESET_COMMON_REPEATED_BLOCK;

    private SoundcoreEqualizerPresets() {
    }

    public enum Preset {
        SIGNATURE(0x00, R.string.soundcore_equalizer_preset_signature, new int[]{0, 0, 0, 0, 0, 0, 0, 0}, new int[]{0, 0, 0, 0, 0, 0, 0, 0}),
        ACOUSTIC(0x01, R.string.soundcore_equalizer_preset_acoustic, new int[]{4, 1, 2, 2, 4, 4, 4, 2}, new int[]{5, -2, 3, 0, 4, 2, 4, 1}),
        BASS_BOOST(0x02, R.string.sony_equalizer_preset_bass_boost, new int[]{4, 3, 1, 0, 0, 0, 0, 0}, new int[]{3, 2, 0, 0, 0, 0, 0, 0}),
        BASS_REDUCER(0x03, R.string.soundcore_equalizer_preset_bass_reducer, new int[]{-4, -3, -1, 0, 0, 0, 0, 0}, new int[]{-3, -2, 0, 0, 0, 0, 0, 0}),
        CLASSICAL(0x04, R.string.nothing_equalizer_classical, new int[]{3, 3, -2, -2, 0, 2, 3, 4}, new int[]{2, 4, -4, -1, 0, 2, 1, 5}),
        PODCAST(0x05, R.string.soundcore_equalizer_preset_podcast, new int[]{-3, 2, 4, 4, 3, 2, 0, -2}, new int[]{-4, 3, 2, 3, 1, 2, 0, -3}),
        DANCE(0x06, R.string.soundcore_equalizer_preset_dance, new int[]{2, -3, -1, 1, 2, 2, 1, -3}, new int[]{4, -5, 0, 0, 2, 1, 3, -5}),
        DEEP(0x07, R.string.soundcore_equalizer_preset_deep, new int[]{2, 1, 3, 3, 2, -2, -4, -5}, new int[]{2, -1, 3, 1, 3, -2, -2, -5}),
        ELECTRONIC(0x08, R.string.nothing_equalizer_electronic, new int[]{3, 2, -2, 2, 1, 2, 3, 3}, new int[]{2, 3, -5, 5, -1, 2, 2, 3}),
        FLAT(0x09, R.string.soundcore_equalizer_preset_flat, new int[]{-2, -2, -1, 0, 0, 0, -2, -2}, new int[]{-2, -1, -1, 0, 0, 1, -2, -2}),
        HIP_HOP(0x0a, R.string.soundcore_equalizer_preset_hip_hop, new int[]{2, 3, -1, -1, 2, -1, 2, 3}, new int[]{1, 4, -2, -2, 5, -4, 3, 3}),
        JAZZ(0x0b, R.string.soundcore_equalizer_preset_jazz, new int[]{2, 2, -2, -2, 0, 2, 3, 4}, new int[]{1, 3, -3, -1, 0, 2, 1, 5}),
        LATIN(0x0c, R.string.soundcore_equalizer_preset_latin, new int[]{0, 0, -2, -2, -2, 0, 3, 5}, new int[]{0, 1, -2, -1, -2, 0, 2, 6}),
        LOUNGE(0x0d, R.string.soundcore_equalizer_preset_lounge, new int[]{-1, 2, 4, 3, 0, -2, 2, 1}, new int[]{-2, 2, 3, 2, 0, -4, 4, 0}),
        PIANO(0x0e, R.string.soundcore_equalizer_preset_piano, new int[]{0, 3, 3, 2, 4, 5, 3, 4}, new int[]{-1, 3, 2, 0, 3, 4, 0, 5}),
        POP(0x0f, R.string.nothing_equalizer_pop, new int[]{-1, 1, 3, 3, 1, -1, -2, -3}, new int[]{-1, 1, 2, 2, 1, -1, -1, -3}),
        RNB(0x10, R.string.soundcore_equalizer_preset_rnb, new int[]{6, 2, -2, -2, 2, 3, 3, 4}, new int[]{6, 1, -2, -3, 3, 2, 1, 5}),
        ROCK(0x11, R.string.nothing_equalizer_rock, new int[]{3, 2, -1, -1, 1, 3, 4, 5}, new int[]{2, 2, -2, -1, 1, 2, 2, 6}),
        SMALL_SPEAKERS(0x12, R.string.soundcore_equalizer_preset_small_speakers, new int[]{4, 3, 1, 0, -2, -3, -4, -4}, new int[]{3, 2, 0, 1, -2, -2, -3, -4}),
        SPOKEN_WORD(0x13, R.string.soundcore_equalizer_preset_spoken_word, new int[]{-3, -2, 1, 2, 2, 1, 0, -3}, new int[]{-2, -2, 2, 1, 2, 0, 1, -4}),
        TREBLE_BOOST(0x14, R.string.sony_equalizer_preset_treble_boost, new int[]{-2, -2, -2, -1, 1, 2, 2, 4}, new int[]{-2, -1, -1, -1, 1, 2, 0, 5}),
        TREBLE_REDUCER(0x15, R.string.soundcore_equalizer_preset_treble_reducer, new int[]{0, 0, 0, -2, -3, -4, -4, -6}, new int[]{0, 0, 1, -2, -1, -3, -1, -7}),
        CUSTOM(0xfe, R.string.custom, null, null);

        private final int index;
        @StringRes
        private final int label;
        // Band gains in dB; wire values use 0x78 + 10 * gain.
        @Nullable
        private final int[] bands;
        // Tail offsets use single wire steps, not dB. They are not a per-band gain transform.
        @Nullable
        private final int[] tails;

        Preset(final int index, @StringRes final int label,
               @Nullable final int[] bands, @Nullable final int[] tails) {
            this.index = index;
            this.label = label;
            this.bands = bands != null ? normalizeBands(bands) : null;
            this.tails = tails != null ? normalizeBands(tails) : null;
        }

        public int getIndex() {
            return index;
        }

        @StringRes
        public int getLabel() {
            return label;
        }

        /** Returns nine signed dB gains, or null for the dynamic custom preset. */
        @Nullable
        public int[] getBands() {
            return bands != null ? bands.clone() : null;
        }

        /** Returns nine signed tail offsets in wire steps, not dB. */
        @Nullable
        public int[] getTails() {
            return tails != null ? tails.clone() : null;
        }

        @Nullable
        public byte[] getEncodedBands() {
            return bands != null ? encodeBands(bands) : null;
        }

        @Nullable
        public byte[] getEncodedTails() {
            return tails != null ? encodeValues(tails, 1) : null;
        }

        // Custom presets are built from the current band values instead.
        @Nullable
        public byte[] getPayload() {
            if (bands == null || tails == null) {
                return null;
            }
            return buildPayload(index, bands, tails);
        }

        /** Encodes eight or nine signed dB gains, defaulting the ninth to 0 dB. */
        public static byte[] encodeBands(final int[] bands) {
            return encodeValues(bands, 10);
        }

        private static int[] normalizeBands(final int[] values) {
            if (values.length != 8 && values.length != 9) {
                throw new IllegalArgumentException("Expected eight or nine equalizer bands");
            }
            return Arrays.copyOf(values, 9);
        }

        private static byte[] encodeValues(final int[] values, final int scale) {
            final int[] bands = normalizeBands(values);
            final byte[] encoded = new byte[bands.length];
            for (int i = 0; i < bands.length; i++) {
                final long value = 0x78L + (long) bands[i] * scale;
                if (value < 0 || value > 0xff) {
                    throw new IllegalArgumentException("Equalizer band value is outside the wire range");
                }
                encoded[i] = (byte) value;
            }
            return encoded;
        }

        @Nullable
        public static Preset fromIndex(final int index) {
            for (final Preset preset : values()) {
                if (preset.index == index) {
                    return preset;
                }
            }
            return null;
        }
    }

    /** Builds a payload from signed dB gains and signed tail offsets in wire steps. */
    public static byte[] buildPayload(final int presetId, final int[] bands, final int[] tails) {
        return buildPayload(presetId,
                StringUtils.bytesToHex(Preset.encodeBands(bands)),
                StringUtils.bytesToHex(Preset.encodeValues(tails, 1)));
    }

    public static byte[] buildPayload(final int presetId, final String bands, final String tails) {
        return StringUtils.hexToBytes(
                toHexByte(presetId)
                        + "000000"
                        + bands
                        + PRESET_CHANNEL_SEPARATOR
                        + bands
                        + PRESET_COMMON_SECTION
                        + tails
                        + PRESET_CHANNEL_SEPARATOR
                        + tails
                        + PRESET_SUFFIX
        );
    }

    private static String toHexByte(final int value) {
        final String hex = Integer.toHexString(value & 0xff);
        return hex.length() < 2 ? "0" + hex : hex;
    }
}
