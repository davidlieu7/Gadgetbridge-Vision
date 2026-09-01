/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.devices.veryfit;

import java.io.ByteArrayOutputStream;

/**
 * The packing a file takes on its way to the watch.
 * <p>
 * A packed file is a run of blocks, each one at most {@link #BLOCK_LEN} bytes of the original with
 * its packed length in front of it, big endian. The blocks themselves are FastLZ streams, and the
 * watch reads them back one at a time, which is why a block never refers to anything before its
 * own start.
 * <p>
 * Only the part of that format the two FastLZ revisions read alike is written here: matches stay
 * inside a block, and a length that needs a second byte never fills it, so it makes no difference
 * which of the two the watch runs.
 */
public final class VeryFitPacker {
    private VeryFitPacker() {
    }

    /** As much of the original as one block carries. */
    public static final int BLOCK_LEN = 4096;

    private static final int MIN_MATCH = 3;
    private static final int MAX_MATCH = 263;
    private static final int MAX_LITERALS = 32;
    /** Marks the stream as the later of the two revisions, the way the watch is fed it. */
    private static final int REVISION_MARK = 0x20;
    private static final int LENGTH_ESCAPE = 6;
    private static final int HASH_BITS = 13;

    /** Splits the data into blocks and packs each of them on its own. */
    public static byte[] pack(final byte[] data) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int offset = 0; offset < data.length; offset += BLOCK_LEN) {
            final byte[] block = packBlock(data, offset, Math.min(BLOCK_LEN, data.length - offset));
            out.write((block.length >> 24) & 0xff);
            out.write((block.length >> 16) & 0xff);
            out.write((block.length >> 8) & 0xff);
            out.write(block.length & 0xff);
            out.write(block, 0, block.length);
        }
        return out.toByteArray();
    }

    /** What the packed data unpacks to, or null when it is not packed at all. */
    public static byte[] unpack(final byte[] packed) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset + 4 <= packed.length) {
            final int length = ((packed[offset] & 0xff) << 24) | ((packed[offset + 1] & 0xff) << 16)
                    | ((packed[offset + 2] & 0xff) << 8) | (packed[offset + 3] & 0xff);
            offset += 4;
            if (length <= 0 || offset + length > packed.length) {
                return null;
            }
            final byte[] block = unpackBlock(packed, offset, length);
            if (block == null) {
                return null;
            }
            out.write(block, 0, block.length);
            offset += length;
        }
        return offset == packed.length && out.size() > 0 ? out.toByteArray() : null;
    }

    private static byte[] packBlock(final byte[] data, final int start, final int length) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int[] recent = new int[1 << HASH_BITS];
        java.util.Arrays.fill(recent, -1);

        int anchor = 0;
        int position = 0;
        while (position + MIN_MATCH <= length) {
            final int slot = hash(data, start + position);
            final int candidate = recent[slot];
            recent[slot] = position;

            final int match = candidate < 0 ? 0
                    : matchLength(data, start + candidate, start + position, length - position);
            if (match < MIN_MATCH) {
                position++;
                continue;
            }

            writeLiterals(out, data, start + anchor, position - anchor);
            writeMatch(out, position - candidate, match);
            for (int i = position + 1; i + MIN_MATCH <= position + match; i++) {
                recent[hash(data, start + i)] = i;
            }
            position += match;
            anchor = position;
        }

        writeLiterals(out, data, start + anchor, length - anchor);
        final byte[] block = out.toByteArray();
        block[0] |= REVISION_MARK;
        return block;
    }

    private static byte[] unpackBlock(final byte[] packed, final int start, final int length) {
        final byte[] out = new byte[BLOCK_LEN];
        int size = 0;
        int offset = start;
        int control = packed[offset++] & 0x1f;
        while (true) {
            if (control >= MAX_LITERALS) {
                int match = (control >> 5) - 1;
                int distance = (control & 0x1f) << 8;
                // A length can run over as many bytes as it needs, though nothing packed here
                // ever asks for more than one.
                if (match == LENGTH_ESCAPE) {
                    int extra;
                    do {
                        if (offset >= start + length) {
                            return null;
                        }
                        extra = packed[offset++] & 0xff;
                        match += extra;
                    } while (extra == 0xff);
                }
                if (offset >= start + length) {
                    return null;
                }
                distance += packed[offset++] & 0xff;

                int from = size - distance - 1;
                if (from < 0 || size + match + MIN_MATCH > out.length) {
                    return null;
                }
                for (int i = 0; i < match + MIN_MATCH; i++) {
                    out[size++] = out[from++];
                }
            } else {
                final int count = control + 1;
                if (offset + count > start + length || size + count > out.length) {
                    return null;
                }
                System.arraycopy(packed, offset, out, size, count);
                offset += count;
                size += count;
            }

            if (offset >= start + length) {
                break;
            }
            control = packed[offset++] & 0xff;
        }

        final byte[] block = new byte[size];
        System.arraycopy(out, 0, block, 0, size);
        return block;
    }

    private static void writeLiterals(final ByteArrayOutputStream out, final byte[] data,
                                      final int offset, final int count) {
        for (int written = 0; written < count; written += MAX_LITERALS) {
            final int run = Math.min(MAX_LITERALS, count - written);
            out.write(run - 1);
            out.write(data, offset + written, run);
        }
    }

    private static void writeMatch(final ByteArrayOutputStream out, final int distance, final int length) {
        final int offset = distance - 1;
        final int match = length - MIN_MATCH;
        if (match < LENGTH_ESCAPE) {
            out.write(((match + 1) << 5) | (offset >> 8));
        } else {
            out.write((7 << 5) | (offset >> 8));
            out.write(match - LENGTH_ESCAPE);
        }
        out.write(offset & 0xff);
    }

    private static int matchLength(final byte[] data, final int candidate, final int position,
                                   final int limit) {
        final int max = Math.min(limit, MAX_MATCH);
        int length = 0;
        while (length < max && data[candidate + length] == data[position + length]) {
            length++;
        }
        return length;
    }

    private static int hash(final byte[] data, final int offset) {
        final int value = ((data[offset] & 0xff) << 16) | ((data[offset + 1] & 0xff) << 8)
                | (data[offset + 2] & 0xff);
        return (value * 0x9e3779b1) >>> (32 - HASH_BITS);
    }
}
