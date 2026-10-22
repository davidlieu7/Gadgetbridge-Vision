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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.util.CheckSums;
import nodomain.freeyourgadget.gadgetbridge.util.CryptoUtils;

/** A validated native audio package, including packages generated from supported raw SBC. */
public final class SoundcoreSleepA30Audio {
    // Conservative implementation policy, not established hardware limits.
    public static final int MAX_BYTES = 960256;
    private final byte[] bytes;
    public final String title;
    public final long trackId;

    public SoundcoreSleepA30Audio(byte[] input) throws IOException {
        bytes = input.clone();
        if (bytes.length < 320 || bytes.length > MAX_BYTES || !isNative(bytes)) {
            throw new IOException("Invalid native 1166 audio package (policy: up to 960256 bytes)");
        }
        if (u32(bytes, 104) != 256 || u32(bytes, 88) != bytes.length - 256 || u32(bytes, 92) != 128) {
            throw new IOException("Invalid native audio body offset, size or bitrate");
        }
        int end = 24;
        while (end < 88 && bytes[end] != 0) end++;
        if (end == 24 || end == 88) throw new IOException("Invalid native audio title");
        for (int i = end; i < 88; i++) if (bytes[i] != 0) throw new IOException("Invalid native audio title padding");
        try {
            title = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 24, end - 24)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8 audio title", e);
        }
        if (containsControlCharacters(title)) throw new IOException("Invalid audio title");
        trackId = u32(bytes, 8);
        int parameter = bytes[257] & 255;
        int bitpool = bytes[258] & 255;
        int rate, frameSize;
        if (parameter == 0x31 && bitpool == 60) { rate = 16000; frameSize = 128; }
        else if (parameter == 0x71 && bitpool == 28) { rate = 32000; frameSize = 64; }
        else throw new IOException("Unsupported SBC parameters; expected captured mono 16/32 kHz profile");
        int bodySize = bytes.length - 256;
        if (u32(bytes, 96) != rate || u32(bytes, 16) != (rate == 16000 ? 2 : 3) || bodySize % frameSize != 0 ||
                (long) (bodySize / frameSize) * 128 > (long) rate * 60) throw new IOException("Invalid SBC metadata, truncation or duration (policy: at most 60 seconds)");
        for (int p = 256; p < bytes.length; p += frameSize) {
            if ((bytes[p] & 255) != 0x9c || (bytes[p + 1] & 255) != parameter || (bytes[p + 2] & 255) != bitpool) throw new IOException("Invalid or inconsistent SBC frame");
            int crc = 0x0f;
            for (int i : new int[]{p + 1, p + 2, p + 4, p + 5, p + 6, p + 7}) {
                for (int bit = 7; bit >= 0; bit--) {
                    int feedback = ((crc >> 7) ^ ((bytes[i] & 255) >> bit)) & 1;
                    crc = (crc << 1) & 255;
                    if (feedback != 0) crc ^= 0x1d;
                }
            }
            if (crc != (bytes[p + 3] & 255)) throw new IOException("SBC frame CRC mismatch");
        }
        if (u16(bytes, 108) != crc16(bytes, 256, bodySize) || u16(bytes, 110) != crc16(bytes, 0, 110)) {
            throw new IOException("Native audio body or header CRC16 mismatch");
        }
    }

    public static SoundcoreSleepA30Audio fromSbc(byte[] body, String title, long trackId) throws IOException {
        if (body.length < 64 || body.length > MAX_BYTES - 256) throw new IOException("Raw SBC exceeds audio upload size policy or is empty");
        if (trackId < 0 || trackId > 0xffffffffL) throw new IOException("Track ID must fit the control-service readback");
        byte[] titleBytes = encodeTitle(title);
        byte[] packageBytes = new byte[256 + body.length];
        System.arraycopy("1166".getBytes(StandardCharsets.US_ASCII), 0, packageBytes, 0, 4);
        packageBytes[5] = 0x10;
        packageBytes[6] = 0x01;
        put32(packageBytes, 8, trackId);
        int rate = (body[1] & 255) == 0x31 ? 16000 : 32000;
        packageBytes[16] = (byte) (rate == 16000 ? 2 : 3);
        System.arraycopy(titleBytes, 0, packageBytes, 24, titleBytes.length);
        put32(packageBytes, 88, body.length);
        put32(packageBytes, 92, 128);
        put32(packageBytes, 96, rate);
        put32(packageBytes, 104, 256);
        System.arraycopy(body, 0, packageBytes, 256, body.length);
        put16(packageBytes, 108, crc16(body, 0, body.length));
        put16(packageBytes, 110, crc16(packageBytes, 0, 110));
        return new SoundcoreSleepA30Audio(packageBytes);
    }

    /** Gadgetbridge metadata policy; the official native function receives title and ID from its caller. */
    public static SoundcoreSleepA30Audio fromFileBytes(byte[] input, String displayName) throws IOException {
        if (input.length > MAX_BYTES) throw new IOException("Audio exceeds upload size policy");
        if (isNative(input)) return new SoundcoreSleepA30Audio(input);
        String title = displayName;
        if (title != null && title.toLowerCase(Locale.ROOT).endsWith(".sbc")) title = title.substring(0, title.length() - 4);
        long trackId = crc32(input, 0, input.length);
        // Zero can also describe an empty slot in ID-only control readback.
        return fromSbc(input, title, trackId == 0 ? 1 : trackId);
    }

    private static boolean isNative(byte[] input) {
        return input.length >= 4 && input[0] == '1' && input[1] == '1' && input[2] == '6' && input[3] == '6';
    }

    private static boolean containsControlCharacters(String title) {
        for (int i = 0; i < title.length(); i++) if (Character.isISOControl(title.charAt(i))) return true;
        return false;
    }

    private static byte[] encodeTitle(String input) throws IOException {
        String title = input == null || input.trim().isEmpty() ? "Local audio" : input.trim();
        if (containsControlCharacters(title)) throw new IOException("Invalid audio title");
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(title));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8 audio title", e);
        }
        int length = Math.min(63, bytes.length);
        // The first excluded byte tells whether the boundary splits a multi-byte code point.
        while (length > 0 && length < bytes.length && (bytes[length] & 0xc0) == 0x80) length--;
        return Arrays.copyOf(bytes, length);
    }

    public static SoundcoreSleepA30Audio read(InputStream input) throws IOException {
        return read(input, null);
    }

    public static SoundcoreSleepA30Audio read(InputStream input, String displayName) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > MAX_BYTES) throw new IOException("Audio package exceeds upload size policy");
            output.write(buffer, 0, n);
        }
        return fromFileBytes(output.toByteArray(), displayName);
    }

    public static boolean matchesTarget(byte[] payload, long track) {
        if (payload.length != 63 || payload[0] != 6) return false;
        for (int i = 0; i < 6; i++) {
            if ((payload[1 + i * 10] & 255) != i) return false;
        }
        return u32(payload, 12) == track;
    }

    public byte[] bytes() { return bytes.clone(); }

    public byte[] sha256() {
        try {
            return CryptoUtils.digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public void requireApprovedDigest(byte[] approvedDigest) throws IOException {
        if (approvedDigest == null || approvedDigest.length != 32 ||
                !MessageDigest.isEqual(approvedDigest, sha256())) {
            throw new IOException("Audio package changed after confirmation; select and confirm it again");
        }
    }

    public byte[] configuration() {
        byte[] result = new byte[92];
        put32(result, 0, 88);
        System.arraycopy(bytes, bytes.length - 4, result, 4, 3);
        put32(result, 88, crc32(result, 0, 88));
        return result;
    }

    public static int crc16(byte[] data, int offset, int length) {
        // The shared helper takes an exclusive end offset, not a byte count.
        return CheckSums.crc16_ccitt(data, offset, offset + length, 0);
    }

    private static int u16(byte[] data, int offset) {
        return BLETypeConversions.toUint16(data, offset);
    }

    private static void put16(byte[] data, int offset, int value) {
        BLETypeConversions.writeUint16(data, offset, value);
    }

    public static long crc32(byte[] data, int offset, int length) {
        return BLETypeConversions.toUnsigned(CheckSums.getCRC32(data, offset, length));
    }

    public static long u32(byte[] data, int offset) {
        return BLETypeConversions.toUnsigned(BLETypeConversions.toUint32(data, offset));
    }

    public static void put32(byte[] data, int offset, long value) {
        BLETypeConversions.writeUint32(data, offset, (int) value);
    }
}
