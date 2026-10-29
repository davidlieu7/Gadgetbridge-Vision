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
package nodomain.freeyourgadget.gadgetbridge.activities.charts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * A tiny trend line of a few values (a week or month of one metric), like the sparklines in the vendor dashboards.
 * It fills the view, scaling the values between their own min and max, with a faint area under the line and a dot on
 * the last point. Zero or one value draws nothing.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.75f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Path()
    private val fill = Path()

    private var values: FloatArray = FloatArray(0)

    fun setValues(values: FloatArray, color: Int) {
        this.values = values
        linePaint.color = color
        dotPaint.color = color
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x24)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2 || width <= 0 || height <= 0) {
            return
        }
        val pad = 2f * density
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = (width - 2 * pad) / (values.size - 1)
        val usableH = height - 2 * pad

        line.reset()
        fill.reset()
        for (i in values.indices) {
            val x = pad + i * stepX
            // Higher value sits higher on screen (smaller y)
            val y = pad + usableH * (1f - (values[i] - min) / span)
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, height - pad)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(pad + (values.size - 1) * stepX, height - pad)
        fill.close()

        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
        val lastX = pad + (values.size - 1) * stepX
        val lastY = pad + usableH * (1f - (values.last() - min) / span)
        canvas.drawCircle(lastX, lastY, 2f * density, dotPaint)
    }
}
