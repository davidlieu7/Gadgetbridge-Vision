package nodomain.freeyourgadget.gadgetbridge.activities;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * A page of the main screen that has a header of its own below the toolbar.
 */
public interface HeaderShadePage {
    /**
     * The shade below the header, which replaces the shade below the toolbar. Null when the page
     * has none, or while the page has no view.
     */
    @Nullable
    View getHeaderShade();
}
