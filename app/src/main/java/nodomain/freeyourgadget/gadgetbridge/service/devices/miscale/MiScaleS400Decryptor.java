/*  Copyright (C) 2026 Viktor Karpov

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.miscale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.bouncycastle.shaded.crypto.engines.AESEngine;
import org.bouncycastle.shaded.crypto.modes.CCMBlockCipher;
import org.bouncycastle.shaded.crypto.params.AEADParameters;
import org.bouncycastle.shaded.crypto.params.KeyParameter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Decrypts and parses the MiBeacon (service data UUID 0xFE95) advertisements of the
 * Xiaomi Body Composition Scale S400.
 * <p>
 * Frame layout (MiBeacon v5, encrypted):
 * <pre>
 * frame control (u16 LE) | product id (u16 LE) | frame counter (u8) | [mac (6, reversed)] |
 * [capability (1-3)] | ciphertext | counter extension (3) | MIC (4)
 * </pre>
 * The plaintext is a list of objects (id u16 LE, length u8, value). The scale emits object
 * 0x6E16 with a 9-byte value: profile (u8), packed measurement (u32 LE), unix timestamp (u32 LE).
 * The packed measurement holds the weight in bits 0-10 (0.1 kg), the heart rate in bits 11-17
 * (offset 50 bpm) and the impedance in bits 18-31 (0.1 Ω). A frame with weight 0 carries a
 * second impedance reading for the same weigh-in, matched by its timestamp.
 * <p>
 * The scale measures at two frequencies, which it does not document. Across three weigh-ins
 * from three different people, the reading sent with the mass was always the higher of the
 * two, by 7 to 13 %. Impedance falls as frequency rises, so the reading sent with the mass is
 * the lower-frequency one and the second frame carries the higher-frequency reading. The
 * fields are therefore named after the frame that carries them rather than after a frequency
 * that has not been established. The reading sent with the mass is the one used for the body
 * composition estimate: its magnitude matches the range the 50 kHz equations expect.
 */
public class MiScaleS400Decryptor {
    private static final int BIND_KEY_HEX_LENGTH = 32;
    private static final int MAC_TAG_BITS = 32;
    private static final byte[] ASSOCIATED_DATA = new byte[]{0x11};
    private static final int HEADER_LENGTH = 5;

    private static final int FRAME_CONTROL_ENCRYPTED = 1 << 3;
    private static final int FRAME_CONTROL_MAC_INCLUDED = 1 << 4;
    private static final int FRAME_CONTROL_CAPABILITY = 1 << 5;
    private static final int CAPABILITY_IO = 1 << 5;

    public static final int OBJECT_BODY_COMPOSITION = 0x6E16;
    public static final int PRODUCT_ID_S400 = 0x3BD5;

    public static class Measurement {
        private final int profile;
        private final long timestamp;
        private final Float weightKg;
        private final Integer heartRate;
        private final Float impedanceOhm;
        private final Float secondImpedanceOhm;

        public Measurement(final int profile,
                           final long timestamp,
                           @Nullable final Float weightKg,
                           @Nullable final Integer heartRate,
                           @Nullable final Float impedanceOhm,
                           @Nullable final Float secondImpedanceOhm) {
            this.profile = profile;
            this.timestamp = timestamp;
            this.weightKg = weightKg;
            this.heartRate = heartRate;
            this.impedanceOhm = impedanceOhm;
            this.secondImpedanceOhm = secondImpedanceOhm;
        }

        /** User profile slot on the scale, 1-based. */
        public int getProfile() {
            return profile;
        }

        /** Unix timestamp (seconds) of the weigh-in, as reported by the scale. */
        public long getTimestamp() {
            return timestamp;
        }

        /** Weight, or null for a frame that only carries the second impedance reading. */
        @Nullable
        public Float getWeightKg() {
            return weightKg;
        }

        @Nullable
        public Integer getHeartRate() {
            return heartRate;
        }

        /** High-frequency impedance, present on weight frames when the scale measured it. */
        @Nullable
        public Float getImpedanceOhm() {
            return impedanceOhm;
        }

        /** The second impedance reading, only present on the frame that carries no weight. */
        @Nullable
        public Float getSecondImpedanceOhm() {
            return secondImpedanceOhm;
        }

        /** True when the frame carries no measurement (scale idle / standing on it). */
        public boolean isIdle() {
            return weightKg == null && secondImpedanceOhm == null;
        }

        public boolean isSecondImpedanceFrame() {
            return weightKg == null && secondImpedanceOhm != null;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "profile=%d timestamp=%d weightKg=%s heartRate=%s impedanceOhm=%s secondImpedanceOhm=%s",
                    profile, timestamp, weightKg, heartRate, impedanceOhm, secondImpedanceOhm);
        }
    }

    /**
     * Decrypts an advertisement and returns the body composition measurement it carries,
     * or null when it cannot be decrypted or carries no such object.
     */
    @Nullable
    public static Measurement decrypt(final byte[] advertisementData,
                                      final String macAddress,
                                      final String bindKey) {
        final byte[] payload = decryptPayload(advertisementData, macAddress, bindKey);
        if (payload == null) {
            return null;
        }
        return parsePayload(payload);
    }

    /**
     * Decrypts an advertisement and returns the plaintext object list, or null when the frame
     * is malformed or the MIC does not verify (wrong bind key or MAC).
     */
    @Nullable
    public static byte[] decryptPayload(final byte[] advertisementData,
                                        final String macAddress,
                                        final String bindKey) {
        if (!isValidBindKey(bindKey) || advertisementData == null || macAddress == null) {
            return null;
        }

        // Tolerate the service data UUID being included in front of the frame
        final byte[] data;
        if (advertisementData.length >= HEADER_LENGTH + 2
                && advertisementData[0] == (byte) 0x95 && advertisementData[1] == (byte) 0xfe) {
            data = new byte[advertisementData.length - 2];
            System.arraycopy(advertisementData, 2, data, 0, data.length);
        } else {
            data = advertisementData;
        }

        if (data.length < HEADER_LENGTH) {
            return null;
        }

        try {
            final byte[] keyBytes = GB.hexStringToByteArray(bindKey);
            if (keyBytes.length != 16) {
                return null;
            }

            final int frameControl = (data[0] & 0xff) | ((data[1] & 0xff) << 8);
            final boolean encrypted = (frameControl & FRAME_CONTROL_ENCRYPTED) != 0;
            final boolean macIncluded = (frameControl & FRAME_CONTROL_MAC_INCLUDED) != 0;
            final boolean capability = (frameControl & FRAME_CONTROL_CAPABILITY) != 0;

            int offset = HEADER_LENGTH;
            final byte[] macReversed = new byte[6];
            if (macIncluded) {
                if (data.length < offset + 6) {
                    return null;
                }
                System.arraycopy(data, offset, macReversed, 0, 6);
                offset += 6;
            } else {
                final byte[] macBytes = GB.hexStringToByteArray(macAddress.replace(":", ""));
                if (macBytes.length != 6) {
                    return null;
                }
                for (int i = 0; i < 6; i++) {
                    macReversed[i] = macBytes[5 - i];
                }
            }

            if (capability) {
                if (data.length < offset + 1) {
                    return null;
                }
                final int capabilities = data[offset] & 0xff;
                offset += 1;
                if ((capabilities & CAPABILITY_IO) != 0) {
                    offset += 2;
                }
            }

            if (!encrypted) {
                if (data.length < offset) {
                    return null;
                }
                final byte[] payload = new byte[data.length - offset];
                System.arraycopy(data, offset, payload, 0, payload.length);
                return payload;
            }

            // ciphertext | counter extension (3) | MIC (4)
            final int cipherLength = data.length - offset - 7;
            if (cipherLength < 0) {
                return null;
            }

            // Nonce: reversed MAC, product id, frame counter, counter extension
            final byte[] nonce = new byte[12];
            System.arraycopy(macReversed, 0, nonce, 0, 6);
            System.arraycopy(data, 2, nonce, 6, 3);
            System.arraycopy(data, data.length - 7, nonce, 9, 3);

            final byte[] cipherText = new byte[cipherLength + 4];
            System.arraycopy(data, offset, cipherText, 0, cipherLength);
            System.arraycopy(data, data.length - 4, cipherText, cipherLength, 4);

            final CCMBlockCipher blockCipher = new CCMBlockCipher(new AESEngine());
            blockCipher.init(false, new AEADParameters(new KeyParameter(keyBytes), MAC_TAG_BITS, nonce, ASSOCIATED_DATA));

            final byte[] decrypted = new byte[blockCipher.getOutputSize(cipherText.length)];
            final int outLength = blockCipher.processBytes(cipherText, 0, cipherText.length, decrypted, 0);
            blockCipher.doFinal(decrypted, outLength);

            return decrypted;
        } catch (final Exception ignored) {
            return null;
        }
    }

    /**
     * Returns the product id from the frame header, or -1 for a malformed frame.
     */
    public static int getProductId(final byte[] advertisementData) {
        if (advertisementData == null || advertisementData.length < 4) {
            return -1;
        }
        return (advertisementData[2] & 0xff) | ((advertisementData[3] & 0xff) << 8);
    }

    /**
     * Walks the plaintext object list and parses the first body composition object.
     */
    @Nullable
    public static Measurement parsePayload(final byte[] payload) {
        int offset = 0;
        while (offset + 3 <= payload.length) {
            final int objectId = (payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8);
            final int length = payload[offset + 2] & 0xff;
            offset += 3;
            if (offset + length > payload.length) {
                return null;
            }
            if (objectId == OBJECT_BODY_COMPOSITION && length == 9) {
                return parseBodyComposition(payload, offset);
            }
            offset += length;
        }
        return null;
    }

    private static Measurement parseBodyComposition(final byte[] payload, final int offset) {
        final ByteBuffer buf = ByteBuffer.wrap(payload, offset, 9).order(ByteOrder.LITTLE_ENDIAN);
        final int profile = buf.get() & 0xff;
        final int packed = buf.getInt();
        final long timestamp = buf.getInt() & 0xffffffffL;

        final int weightRaw = packed & 0x7ff;
        final int heartRateRaw = (packed >>> 11) & 0x7f;
        final int impedanceRaw = packed >>> 18;

        if (packed == 0) {
            return new Measurement(profile, timestamp, null, null, null, null);
        }

        final Float impedance = impedanceRaw > 0 ? impedanceRaw / 10.0f : null;

        if (weightRaw == 0) {
            return new Measurement(profile, timestamp, null, null, null, impedance);
        }

        final Integer heartRate = heartRateRaw >= 1 && heartRateRaw <= 126 ? heartRateRaw + 50 : null;

        return new Measurement(profile, timestamp, weightRaw / 10.0f, heartRate, impedance, null);
    }

    public static boolean isValidBindKey(final String bindKey) {
        if (bindKey == null || bindKey.length() != BIND_KEY_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < bindKey.length(); i++) {
            if (Character.digit(bindKey.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
