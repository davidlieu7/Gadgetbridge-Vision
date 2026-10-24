package nodomain.freeyourgadget.gadgetbridge.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.Menu
import nodomain.freeyourgadget.gadgetbridge.R

object ToolbarIcons {
    /** Draws the icons of [menu] at the toolbar icon size. */
    @JvmStatic
    fun resize(context: Context, menu: Menu) {
        val size = context.resources.getDimensionPixelSize(R.dimen.toolbar_icon_size)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val icon = item.icon ?: continue
            if (icon is SizedIcon) continue
            item.icon = SizedIcon(icon, size)
        }
    }

    private class SizedIcon(icon: Drawable, private val size: Int) : DrawableWrapper(icon) {
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
    }
}
