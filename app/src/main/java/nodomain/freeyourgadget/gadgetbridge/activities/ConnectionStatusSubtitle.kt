package nodomain.freeyourgadget.gadgetbridge.activities

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.adapter.DeviceStatusDot
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice

object ConnectionStatusSubtitle {
    /**
     * Returns the subtitle, or null when there are no devices.
     */
    @JvmStatic
    fun build(view: View, devices: List<GBDevice>): CharSequence? {
        if (devices.isEmpty()) {
            return null
        }
        val context = view.context
        val initialized = devices.filter { it.isInitialized }
        val text = when {
            initialized.size == 1 -> initialized[0].aliasOrName
            initialized.size > 1 -> context.getString(R.string.info_connected_count, initialized.size)
            devices.any { it.state.equalsOrHigherThan(GBDevice.State.CONNECTING) } ->
                context.getString(R.string.controlcenter_snackbar_connecting)

            else -> context.getString(R.string.not_connected)
        }
        val color = DeviceStatusDot.color(view, devices.maxBy { DeviceStatusDot.rank(it) })
        val density = context.resources.displayMetrics.density
        return SpannableStringBuilder(" ").apply {
            setSpan(DotSpan(color, 7 * density, 6 * density), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(text)
        }
    }

    private class DotSpan(
        private val color: Int,
        private val size: Float,
        private val gap: Float
    ) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (size + gap).toInt()

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val textColor = paint.color
            val metrics = paint.fontMetrics
            paint.color = color
            canvas.drawCircle(x + size / 2, y + (metrics.ascent + metrics.descent) / 2, size / 2, paint)
            paint.color = textColor
        }
    }
}
