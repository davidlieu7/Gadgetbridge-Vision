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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A captured native audio package, not an arbitrary SBC recording. */
public final class SoundcoreSleepA30Audio {
    // Conservative implementation policy, not established hardware limits.
    public static final int MAX_BYTES = 960256;
    private final byte[] bytes;
    public final String title;
    public final long trackId;

    public SoundcoreSleepA30Audio(byte[] input) throws IOException {
        bytes = input.clone();
        if (bytes.length < 320 || bytes.length > MAX_BYTES || !Arrays.equals(Arrays.copyOf(bytes, 4), "1166".getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Select a native 1166 audio package (up to 960256 bytes), not raw audio");
        }
        if (u32(bytes, 104) != 256 || u32(bytes, 88) != bytes.length - 256 || u32(bytes, 92) != 128) {
            throw new IOException("Invalid native audio body offset, size or bitrate");
        }
        int end = 24;
        while (end < 88 && bytes[end] != 0) {
            if (bytes[end] < 32 || bytes[end] > 126) throw new IOException("Unsupported native audio title");
            end++;
        }
        if (end == 24 || end == 88) throw new IOException("Invalid native audio title");
        for (int i = end; i < 88; i++) if (bytes[i] != 0) throw new IOException("Invalid native audio title padding");
        title = new String(bytes, 24, end - 24, StandardCharsets.US_ASCII);
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
    }

    public static SoundcoreSleepA30Audio read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > MAX_BYTES) throw new IOException("Audio package exceeds upload size policy");
            output.write(buffer, 0, n);
        }
        return new SoundcoreSleepA30Audio(output.toByteArray());
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
            return MessageDigest.getInstance("SHA-256").digest(bytes);
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

    public static long crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    public static long u32(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 4; i++) value |= (long) (data[offset + i] & 255) << (i * 8);
        return value;
    }

    public static void put32(byte[] data, int offset, long value) {
        for (int i = 0; i < 4; i++) data[offset + i] = (byte) (value >> (i * 8));
    }
}
