package nodomain.freeyourgadget.gadgetbridge.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;

import androidx.annotation.AttrRes;

import com.google.android.material.color.MaterialColors;

import nodomain.freeyourgadget.gadgetbridge.R;

public final class BarShade {
    private BarShade() {
    }

    public static boolean continuesToolbar(final Context context, @AttrRes final int rowColorAttr) {
        return MaterialColors.getColor(context, rowColorAttr, Color.TRANSPARENT)
                == MaterialColors.getColor(context, R.attr.toolbar_bg, Color.TRANSPARENT);
    }

    public static boolean moveBelowRow(final Activity activity, @AttrRes final int rowColorAttr) {
        if (!continuesToolbar(activity, rowColorAttr)) {
            return false;
        }
        activity.getTheme().applyStyle(R.style.ThemeOverlay_App_NoBarShade, true);
        return true;
    }
}
