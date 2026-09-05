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

import android.graphics.Bitmap;

/**
 * A picture in the shape the watch reads them.
 * <p>
 * A short header names the size and the format the watch itself asked for, the colours follow it
 * as big-endian 5-6-5, and behind them comes half a byte of transparency per pixel — which is why
 * the header repeats how many bytes the colours took, as that is where the second plane starts.
 */
public final class VeryFitPicture {
    private VeryFitPicture() {
    }

    private static final byte[] MAGIC = {'R', 'A', 'W', 0};
    private static final int HEADER_LEN = 16;
    /** Says a transparency plane follows the colours; without it the picture is opaque. */
    private static final byte WITH_ALPHA = 0x66;

    public static byte[] encode(final Bitmap bitmap, final byte format) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final int colours = width * height * 2;
        final int pairs = width / 2;

        final byte[] picture = new byte[HEADER_LEN + colours + pairs * height];
        System.arraycopy(MAGIC, 0, picture, 0, MAGIC.length);
        picture[4] = (byte) width;
        picture[5] = (byte) (width >> 8);
        picture[6] = (byte) height;
        picture[7] = (byte) (height >> 8);
        picture[8] = format;
        picture[9] = WITH_ALPHA;
        picture[12] = (byte) colours;
        picture[13] = (byte) (colours >> 8);
        picture[14] = (byte) (colours >> 16);
        picture[15] = (byte) (colours >> 24);

        final int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            final int pixel = pixels[i];
            final int value = (((pixel >> 16) & 0xf8) << 8)
                    | (((pixel >> 8) & 0xfc) << 3) | ((pixel & 0xff) >> 3);
            picture[HEADER_LEN + i * 2] = (byte) (value >> 8);
            picture[HEADER_LEN + i * 2 + 1] = (byte) value;
        }

        final int plane = HEADER_LEN + colours;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < pairs; x++) {
                final int left = pixels[y * width + x * 2] >>> 24;
                final int right = pixels[y * width + x * 2 + 1] >>> 24;
                picture[plane + y * pairs + x] = (byte) ((left & 0xf0) | (right >> 4));
            }
        }
        return picture;
    }
}
