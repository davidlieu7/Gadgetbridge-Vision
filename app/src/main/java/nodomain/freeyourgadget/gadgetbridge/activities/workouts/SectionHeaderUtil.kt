package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R

/**
 * Adds a section header, styled consistently across the workout Details/Charts tabs (and meant to
 * match the Training Load tab's headers, styled directly in fragment_load.xml since those are
 * static). Pass `showDivider = false` only for the first header on a screen. [meta], when set, is
 * a muted line directly under the title (e.g. a date range and a count).
 */
fun addSectionHeader(container: LinearLayout, context: Context, title: String, showDivider: Boolean, meta: String? = null) {
    if (showDivider) {
        container.addView(buildSectionHeaderDivider(context))
    }
    container.addView(TextView(context).apply {
        id = View.generateViewId()
        textSize = 18f
        gravity = Gravity.START
        setPaddingRelative(dpToPx(context, 16), dpToPx(context, 16), dpToPx(context, 16), dpToPx(context, if (meta != null) 0 else 16))
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(GBApplication.getTextColor(context))
        text = title
    })
    if (meta != null) {
        container.addView(TextView(context).apply {
            id = View.generateViewId()
            textSize = 13f
            gravity = Gravity.START
            setPaddingRelative(dpToPx(context, 16), dpToPx(context, 3), dpToPx(context, 16), dpToPx(context, 16))
            setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, "SectionHeader"))
            text = meta
        })
    }
}

private fun buildSectionHeaderDivider(context: Context): View {
    return View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(context, 1)
        )
        val typedValue = TypedValue()
        context.theme.resolveAttribute(R.attr.row_separator, typedValue, true)
        setBackgroundColor(ContextCompat.getColor(context, typedValue.resourceId))
    }
}

private fun dpToPx(context: Context, dp: Int): Int {
    return (dp * context.resources.displayMetrics.density).toInt()
}
