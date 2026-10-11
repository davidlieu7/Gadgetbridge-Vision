/* Copyright 2026 Gadgetbridge Contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package nodomain.freeyourgadget.gadgetbridge.util;

import android.text.InputFilter;
import android.text.Spanned;
import java.nio.charset.StandardCharsets;

/** Limits UTF-8 bytes without splitting a surrogate pair. */
public final class Utf8ByteLengthFilter implements InputFilter {
    private final int maximum;
    public Utf8ByteLengthFilter(final int maximum) { this.maximum = maximum; }

    @Override public CharSequence filter(final CharSequence source, final int start, final int end,
                                         final Spanned dest, final int dstart, final int dend) {
        if (maximum == Integer.MAX_VALUE) { return null; }
        final int remaining = maximum - bytes(dest.subSequence(0, dstart)) - bytes(dest.subSequence(dend, dest.length()));
        final String insertion = source.subSequence(start, end).toString();
        if (bytes(insertion) <= remaining) { return null; }
        int accepted = 0;
        int length = 0;
        while (accepted < insertion.length()) {
            final int next = accepted + Character.charCount(insertion.codePointAt(accepted));
            length += bytes(insertion.substring(accepted, next));
            if (length > remaining) { break; }
            accepted = next;
        }
        return source.subSequence(start, start + accepted);
    }

    private static int bytes(final CharSequence text) { return text.toString().getBytes(StandardCharsets.UTF_8).length; }
}
