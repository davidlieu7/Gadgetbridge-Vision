package nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartTheme

internal class ChartTooltipPainter(context: Context, theme: ChartTheme) {
    private val metrics = context.resources.displayMetrics
    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)

    private val paddingHorizontal = dp(10f)
    private val paddingVertical = dp(8f)
    private val lineGap = dp(5f)
    private val cornerRadius = dp(8f)
    private val swatchSize = dp(8f)
    private val swatchRadius = dp(2f)
    private val swatchGap = dp(6f)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.markerBackgroundColor
        setShadowLayer(dp(SHADOW_BLUR_DP) / 2f, 0f, dp(SHADOW_OFFSET_DP), SHADOW_COLOR)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.markerTitleColor
        textSize = sp(11f)
        typeface = Typeface.DEFAULT
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.markerValueColor
        textSize = sp(13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun lineHeight(paint: Paint) = paint.fontMetrics.let { it.descent - it.ascent }

    private fun rowHeight() = maxOf(swatchSize, lineHeight(valuePaint))

    fun width(selection: ChartSelection): Float {
        val title = titlePaint.measureText(selection.title)
        val rows = selection.rows.maxOfOrNull { swatchSize + swatchGap + valuePaint.measureText(it.text) } ?: 0f
        return maxOf(title, rows) + paddingHorizontal * 2
    }

    fun height(selection: ChartSelection): Float =
        paddingVertical * 2 + lineHeight(titlePaint) + selection.rows.size * (lineGap + rowHeight())

    fun draw(canvas: Canvas, selection: ChartSelection, left: Float, top: Float) {
        rect.set(left, top, left + width(selection), top + height(selection))
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, boxPaint)

        val x = left + paddingHorizontal
        var y = top + paddingVertical
        canvas.drawText(selection.title, x, y - titlePaint.fontMetrics.ascent, titlePaint)
        y += lineHeight(titlePaint)

        val rowHeight = rowHeight()
        for (row in selection.rows) {
            y += lineGap
            val centerY = y + rowHeight / 2f
            swatchPaint.color = row.color
            rect.set(x, centerY - swatchSize / 2f, x + swatchSize, centerY + swatchSize / 2f)
            canvas.drawRoundRect(rect, swatchRadius, swatchRadius, swatchPaint)
            val baseline = centerY - (valuePaint.fontMetrics.ascent + valuePaint.fontMetrics.descent) / 2f
            canvas.drawText(row.text, x + swatchSize + swatchGap, baseline, valuePaint)
            y += rowHeight
        }
    }

    private companion object {
        val SHADOW_COLOR = Color.argb(153, 0, 0, 0)
        const val SHADOW_BLUR_DP = 18f
        const val SHADOW_OFFSET_DP = 6f
    }
}
