/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol;

import androidx.annotation.Nullable;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Every connection the ring sends a 15-byte nonce; the phone proves it holds the ring's 16-byte key
 * by returning the nonce, padded with one 0x01 byte, encrypted with AES-128 in ECB mode.
 */
public final class OuraAuth {
    private static final int KEY_LEN = 16;
    public static final int NONCE_LEN = 15;

    public static byte[] proof(final byte[] nonce, final byte[] key) {
        if (nonce.length != NONCE_LEN || key.length != KEY_LEN) {
            throw new IllegalArgumentException("nonce must be " + NONCE_LEN + " bytes and key " + KEY_LEN);
        }
        final byte[] block = new byte[16];
        System.arraycopy(nonce, 0, block, 0, NONCE_LEN);
        block[NONCE_LEN] = 0x01;
        try {
            final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(block);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    /** The key from 32 hex digits, spaces and colons allowed, or null. */
    @Nullable
    public static byte[] parseHexKey(@Nullable final String hex) {
        if (hex == null) {
            return null;
        }
        final String clean = hex.trim().replace(" ", "").replace(":", "");
        if (clean.length() != KEY_LEN * 2) {
            return null;
        }
        final byte[] out = new byte[KEY_LEN];
        for (int i = 0; i < KEY_LEN; i++) {
            final int hi = Character.digit(clean.charAt(i * 2), 16);
            final int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    private OuraAuth() {
    }
}
