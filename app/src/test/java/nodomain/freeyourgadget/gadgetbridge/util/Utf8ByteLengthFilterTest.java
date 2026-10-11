/* Copyright 2026 Gadgetbridge Contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package nodomain.freeyourgadget.gadgetbridge.util;

import android.text.SpannableString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 23, application = android.app.Application.class)
public class Utf8ByteLengthFilterTest {
    @Test public void emojiIsNeverSplitAndReplacementReclaimsItsBytes() {
        final Utf8ByteLengthFilter filter = new Utf8ByteLengthFilter(5);
        assertEquals("a", filter.filter("a😀b", 0, 4, new SpannableString("x"), 1, 1).toString());
        assertNull(filter.filter("a😀", 0, 3, new SpannableString("é"), 0, 1));
        assertEquals("éé", filter.filter("ééé", 0, 3, new SpannableString("😀"), 0, 2).toString());
    }

    @Test public void deletionAndUnlimitedLegacyInputRemainAllowed() {
        assertNull(new Utf8ByteLengthFilter(Integer.MAX_VALUE).filter("😀é", 0, 3,
                new SpannableString("legacy"), 6, 6));
        assertNull(new Utf8ByteLengthFilter(5).filter("", 0, 0,
                new SpannableString("😀é"), 0, 2));
    }

    @Test public void truncationPreservesSpansAndHonoursTheSourceSlice() {
        final SpannableString source = new SpannableString("xé😀z");
        final android.text.style.StyleSpan style = new android.text.style.StyleSpan(android.graphics.Typeface.BOLD);
        source.setSpan(style, 1, 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        final CharSequence result = new Utf8ByteLengthFilter(2).filter(source, 1, 4,
                new SpannableString(""), 0, 0);
        assertEquals("é", result.toString());
        assertTrue(result instanceof android.text.Spanned);
        assertEquals(1, ((android.text.Spanned) result).getSpans(0, result.length(),
                android.text.style.StyleSpan.class).length);
    }
}
