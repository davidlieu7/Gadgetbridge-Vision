/*  Copyright (C) 2026 theupsidedownabyss

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
package nodomain.freeyourgadget.gadgetbridge.activities.charts.badges

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * A medal: a disc of [color] with a lighter center and an inner ring, the white [icon] on it, and two ribbon tails
 * below. Fills a square in the middle of its bounds.
 */
class MedalDrawable(@ColorInt private val color: Int, private val icon: Drawable?) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ribbon = Path()
    private var drawAlpha = 0xFF

    init {
        icon?.mutate()?.setTint(Color.WHITE)
    }

    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        if (size <= 0f) return
        val left = bounds.exactCenterX() - size / 2
        val top = bounds.exactCenterY() - size / 2
        val cx = left + size / 2
        val cy = top + size * DISC_CENTER
        val radius = size * DISC_RADIUS

        // Ribbon tails, behind the disc, each with a notch at its end
        paint.shader = null
        paint.color = withAlpha(ColorUtils.blendARGB(color, Color.BLACK, RIBBON_DARKEN))
        for (side in intArrayOf(-1, 1)) {
            val inner = cx + side * size * RIBBON_INNER
            val outer = cx + side * size * RIBBON_OUTER
            val bottom = top + size * RIBBON_BOTTOM
            ribbon.reset()
            ribbon.moveTo(inner, cy)
            ribbon.lineTo(outer, cy)
            ribbon.lineTo(outer, bottom)
            ribbon.lineTo((inner + outer) / 2, bottom - size * RIBBON_NOTCH)
            ribbon.lineTo(inner, bottom)
            ribbon.close()
            canvas.drawPath(ribbon, paint)
        }

        // Disc: a darker edge, then the face fading from a lighter center
        paint.color = withAlpha(ColorUtils.blendARGB(color, Color.BLACK, EDGE_DARKEN))
        canvas.drawCircle(cx, cy, radius, paint)
        val faceRadius = radius * FACE_SHARE
        paint.shader = RadialGradient(
            cx - faceRadius * HIGHLIGHT_OFFSET, cy - faceRadius * HIGHLIGHT_OFFSET, faceRadius * 1.4f,
            withAlpha(ColorUtils.blendARGB(color, Color.WHITE, CENTER_LIGHTEN)), withAlpha(color), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, faceRadius, paint)
        paint.shader = null

        ringPaint.strokeWidth = radius * RING_WIDTH
        ringPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, RING_ALPHA * drawAlpha / 0xFF)
        canvas.drawCircle(cx, cy, faceRadius - ringPaint.strokeWidth, ringPaint)

        icon?.let {
            val half = radius * ICON_SHARE / 2
            it.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            it.draw(canvas)
        }
    }

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        icon?.alpha = alpha
        invalidateSelf()
    }

    private fun withAlpha(@ColorInt c: Int) = ColorUtils.setAlphaComponent(c, Color.alpha(c) * drawAlpha / 0xFF)

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        ringPaint.colorFilter = colorFilter
        icon?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        // Shares of the size of the square
        const val DISC_CENTER = 0.42f
        const val DISC_RADIUS = 0.38f
        const val RIBBON_INNER = 0.04f
        const val RIBBON_OUTER = 0.25f
        const val RIBBON_BOTTOM = 0.99f
        const val RIBBON_NOTCH = 0.09f

        // Shares of the disc radius
        const val FACE_SHARE = 0.86f
        const val RING_WIDTH = 0.05f
        const val ICON_SHARE = 0.95f
        const val HIGHLIGHT_OFFSET = 0.35f

        const val RIBBON_DARKEN = 0.30f
        const val EDGE_DARKEN = 0.22f
        const val CENTER_LIGHTEN = 0.40f
        const val RING_ALPHA = 0x66
    }
}
