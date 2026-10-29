package nodomain.freeyourgadget.gadgetbridge.activities.dashboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.ColorInt;

import com.google.android.material.color.MaterialColors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;

public class GaugeDrawer {
    private static final Logger LOG = LoggerFactory.getLogger(GaugeDrawer.class);
    protected @ColorInt int color_unknown = Color.argb(25, 128, 128, 128);

    private static final float RING_DEFAULT_SIZE_DP = 150f;
    private static final float RING_DESIGN_SIZE = 220f;
    private static final float RING_RADIUS = 92f;
    private static final float RING_STROKE = RING_DESIGN_SIZE / 15;
    private static final float RING_MARKER_RADIUS = 10f;
    private static final float RING_MARKER_GAP = 3f;
    private static final float RING_SEGMENT_GAP = 0.01f;

    public enum Shape {
        RING(135f, 270f),
        HALF_CIRCLE(180f, 180f);

        private final float startDegrees;
        private final float sweepDegrees;

        Shape(final float startDegrees, final float sweepDegrees) {
            this.startDegrees = startDegrees;
            this.sweepDegrees = sweepDegrees;
        }
    }

    private final Shape shape;

    public GaugeDrawer() {
        this(Shape.RING);
    }

    public GaugeDrawer(final Shape shape) {
        this.shape = shape;
    }

    /**
     * Draws a simple gauge, filled from the start up to the value.
     *
     * @param color the gauge color
     * @param value the gauge value. Range: [0, 1], or -1 for an empty gauge
     */
    public void drawSimpleGauge(final ImageView gaugeBar, final int color, final float value) {
        final Ring ring = new Ring(gaugeBar, shape);
        ring.drawTrack(color_unknown);
        if (value > 0) {
            ring.drawSection(0, Math.min(value, 1), color);
        }
        ring.show(gaugeBar);
    }

    /**
     * Draws a segmented gauge, with a marker at the value.
     *
     * @param colors             the colors of each segment
     * @param segments           the size of each segment. The sum of all segments should be at most 1
     * @param value              the gauge value, in range [0, 1], or -1 for no value and only segments
     * @param fadeOutsideDot     whether to fade out colors outside the dot value
     * @param gapBetweenSegments whether to introduce a small gap between the segments
     */
    public void drawSegmentedGauge(final ImageView gaugeBar,
                                   final int[] colors,
                                   final float[] segments,
                                   final float value,
                                   final boolean fadeOutsideDot,
                                   final boolean gapBetweenSegments) {
        if (colors.length != segments.length) {
            LOG.error("Colors length {} differs from segments length {}", colors.length, segments.length);
            return;
        }

        final Ring ring = new Ring(gaugeBar, shape);
        ring.drawTrack(color_unknown);
        final float halfGap = gapBetweenSegments ? RING_SEGMENT_GAP / 2 : 0;

        int firstSegment = -1;
        int lastSegment = -1;
        for (int i = 0; i < segments.length; i++) {
            if (segments[i] > 0) {
                if (firstSegment < 0) {
                    firstSegment = i;
                }
                lastSegment = i;
            }
        }

        int markerColor = MaterialColors.getColor(gaugeBar.getContext(), R.attr.textColorPrimary, Color.WHITE);
        float segmentStart = 0;
        for (int i = 0; i < segments.length; i++) {
            final float segmentEnd = segmentStart + segments[i];
            if (segments[i] > 0) {
                final boolean containsValue = value >= segmentStart && Math.min(value, 1) <= segmentEnd;
                if (containsValue) {
                    markerColor = colors[i];
                }
                final int color = fadeOutsideDot && value >= 0 && !containsValue ? colors[i] - 0xB0000000 : colors[i];
                // A sum just under 1 is a rounding error, so the last segment still reaches the end
                final float end = i == lastSegment && segmentEnd >= 1 - RING_SEGMENT_GAP ? 1 : segmentEnd;
                ring.drawSection(
                    i == firstSegment ? segmentStart : segmentStart + halfGap,
                    i == lastSegment ? Math.min(end, 1) : segmentEnd - halfGap,
                    color,
                    i == firstSegment,
                    i == lastSegment
                );
            }
            segmentStart = segmentEnd;
        }

        if (value >= 0) {
            ring.drawMarker(Math.min(value, 1), markerColor);
        }

        ring.show(gaugeBar);
    }

    /**
     * An arc of a {@link Shape} sized to the gauge view's width, drawn into a reused bitmap.
     */
    private static class Ring {
        private final Shape shape;
        private final Bitmap bitmap;
        private final Canvas canvas;
        private final boolean reused;
        private final float scale;
        private final float center;
        private final float radius;
        private final RectF oval;

        Ring(final ImageView gaugeBar, final Shape shape) {
            this.shape = shape;
            final ViewGroup.LayoutParams params = gaugeBar.getLayoutParams();
            final int size;
            if (params != null && params.width > 0) {
                size = params.width;
            } else if (gaugeBar.getWidth() > 0) {
                size = gaugeBar.getWidth();
            } else {
                size = Math.round(RING_DEFAULT_SIZE_DP * gaugeBar.getResources().getDisplayMetrics().density);
            }
            scale = size / RING_DESIGN_SIZE;
            center = size / 2f;
            radius = RING_RADIUS * scale;
            oval = new RectF(center - radius, center - radius, center + radius, center + radius);
            final int height = shape == Shape.HALF_CIRCLE
                ? (int) Math.ceil(center + (RING_MARKER_RADIUS + RING_MARKER_GAP) * scale)
                : size;

            Bitmap previous = null;
            if (gaugeBar.getDrawable() instanceof BitmapDrawable drawable) {
                previous = drawable.getBitmap();
            }
            reused = previous != null && previous.isMutable() && previous.getWidth() == size && previous.getHeight() == height;
            if (reused) {
                bitmap = previous;
                bitmap.eraseColor(Color.TRANSPARENT);
            } else {
                bitmap = Bitmap.createBitmap(size, height, Bitmap.Config.ARGB_8888);
            }
            canvas = new Canvas(bitmap);
        }

        void drawTrack(@ColorInt final int color) {
            drawSection(0, 1, color);
        }

        void drawSection(final float start, final float end, @ColorInt final int color) {
            drawSection(start, end, color, true, true);
        }

        /**
         * Draws the part of the arc between two fractions of the sweep, with rounded ends where asked.
         */
        void drawSection(final float start, final float end, @ColorInt final int color, final boolean roundStart, final boolean roundEnd) {
            if (end <= start) {
                return;
            }
            final Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeWidth(RING_STROKE * scale);
            paint.setColor(color);
            canvas.drawArc(oval, shape.startDegrees + shape.sweepDegrees * start, shape.sweepDegrees * (end - start), false, paint);
            if (roundStart) {
                drawCap(start, true, color);
            }
            if (roundEnd) {
                drawCap(end, false, color);
            }
        }

        /**
         * Rounds off the arc's start or end at a fraction of the sweep with a half disc, which does not
         * overlap the arc itself.
         */
        private void drawCap(final float at, final boolean start, @ColorInt final int color) {
            final float degrees = shape.startDegrees + shape.sweepDegrees * at;
            final double angle = Math.toRadians(degrees);
            final float x = center + radius * (float) Math.cos(angle);
            final float y = center + radius * (float) Math.sin(angle);
            final float capRadius = RING_STROKE * scale / 2;

            final Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawArc(x - capRadius, y - capRadius, x + capRadius, y + capRadius,
                start ? degrees - 180 : degrees, 180, true, paint);
        }

        void drawMarker(final float value, @ColorInt final int color) {
            final double angle = Math.toRadians(shape.startDegrees + shape.sweepDegrees * value);
            final float x = center + radius * (float) Math.cos(angle);
            final float y = center + radius * (float) Math.sin(angle);

            final Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            canvas.drawCircle(x, y, (RING_MARKER_RADIUS + RING_MARKER_GAP) * scale, paint);

            paint.setXfermode(null);
            paint.setColor(color);
            canvas.drawCircle(x, y, RING_MARKER_RADIUS * scale, paint);
        }

        void show(final ImageView gaugeBar) {
            if (reused) {
                gaugeBar.invalidate();
            } else {
                gaugeBar.setImageBitmap(bitmap);
            }
        }
    }

    public static Bitmap drawCircleGaugeSegmented(int width,
                                                  int barWidth,
                                                  final int[] colors,
                                                  final float[] segments,
                                                  final boolean gapBetweenSegments,
                                                  String text,
                                                  String lowerText,
                                                  Context context) {
        final Bitmap bitmap = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final CircleGauge gauge = new CircleGauge(width, barWidth);
        gauge.drawTrack(canvas, MaterialColors.getColor(context, R.attr.gauge_track, context.getResources().getColor(R.color.gauge_line_color)));

        int validSegments = 0;
        for (final float segment : segments) {
            if (segment > 0) {
                validSegments++;
            }
        }
        // With gaps, the segments get rounded ends: the gap is what is left between the round caps
        final float gapDegrees = gapBetweenSegments ? CIRCLE_SEGMENT_GAP_DEGREES : 0;
        final float remainingAngle = 360 - validSegments * gapDegrees;

        if (gapBetweenSegments) {
            float angle = 270;
            for (int i = 0; i < segments.length; i++) {
                if (segments[i] <= 0) {
                    continue;
                }
                final float sweep = segments[i] * remainingAngle;
                gauge.drawRoundedArc(canvas, angle + gapDegrees / 2, sweep, colors[i]);
                angle += sweep + gapDegrees;
            }
        } else {
            gauge.drawJoinedSegments(canvas, colors, segments);
        }

        gauge.drawTexts(canvas, context, text, lowerText);
        return bitmap;
    }

    public static Bitmap drawCircleGauge(int width,
                                         int barWidth,
                                         @ColorInt int filledColor,
                                         int value,
                                         int maxValue,
                                         Context context) {
        final float filledFactor = maxValue > 0 ? Math.max(0f, (float) value / maxValue) : 0f;

        final Bitmap bitmap = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final CircleGauge gauge = new CircleGauge(width, barWidth);
        // Like the goal rings: a neutral track, the filled part in the gauge color with round ends
        gauge.drawTrack(canvas, MaterialColors.getColor(context, R.attr.gauge_track, context.getResources().getColor(R.color.gauge_line_color)));
        if (filledFactor >= 1) {
            gauge.drawArc(canvas, 0, 360, filledColor);
        } else if (filledFactor > 0) {
            gauge.drawRoundedArc(canvas, 270, 360 * filledFactor, filledColor);
        }

        final NumberFormat format = NumberFormat.getInstance();
        gauge.drawTexts(canvas, context, format.format(value), format.format(maxValue));
        return bitmap;
    }

    private static final float CIRCLE_SEGMENT_GAP_DEGREES = 6f;

    /**
     * A full circle gauge of {@link #drawCircleGauge} and {@link #drawCircleGaugeSegmented}, starting at the top.
     */
    private static class CircleGauge {
        private final float width;
        private final float barWidth;
        private final RectF oval;
        /**
         * The angle covered by a round cap, half the bar width along the circle.
         */
        private final float capDegrees;

        CircleGauge(final int width, final int barWidth) {
            this.width = width;
            this.barWidth = barWidth;
            final float margin = barWidth / 2f;
            oval = new RectF(margin, margin, width - margin, width - margin);
            final float radius = (width - barWidth) / 2f;
            capDegrees = radius > 0 ? (float) Math.toDegrees(margin / radius) : 0;
        }

        private Paint stroke(final Paint.Cap cap) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(cap);
            paint.setStrokeWidth(barWidth);
            return paint;
        }

        void drawTrack(final Canvas canvas, @ColorInt final int color) {
            final Paint paint = stroke(Paint.Cap.BUTT);
            paint.setColor(color);
            canvas.drawArc(oval, 0, 360, false, paint);
        }

        void drawArc(final Canvas canvas, final float start, final float sweep, @ColorInt final int color) {
            final Paint paint = stroke(Paint.Cap.BUTT);
            paint.setColor(color);
            canvas.drawArc(oval, start, sweep, false, paint);
        }

        /**
         * An arc whose round caps stay within [start, start + sweep]; a segment too short for both caps is a dot.
         */
        void drawRoundedArc(final Canvas canvas, final float start, final float sweep, @ColorInt final int color) {
            final Paint paint = stroke(Paint.Cap.ROUND);
            paint.setColor(color);
            if (sweep > 2 * capDegrees) {
                canvas.drawArc(oval, start + capDegrees, sweep - 2 * capDegrees, false, paint);
            } else {
                drawDot(canvas, start + sweep / 2, color);
            }
        }

        /**
         * Segments one after the other from the top, like {@link #drawRoundedArc} as a whole: round where the filled
         * part starts and ends, square where two segments meet. A full circle has no ends.
         */
        void drawJoinedSegments(final Canvas canvas, final int[] colors, final float[] segments) {
            float total = 0;
            int first = -1;
            int last = -1;
            for (int i = 0; i < segments.length; i++) {
                if (segments[i] > 0) {
                    total += segments[i];
                    if (first < 0) {
                        first = i;
                    }
                    last = i;
                }
            }
            if (first < 0) {
                return;
            }
            final float totalSweep = 360 * Math.min(total, 1f);
            // Sums just under 1 are rounding errors: still a full circle
            final boolean full = totalSweep >= 359.5f;
            if (!full && totalSweep <= 2 * capDegrees) {
                drawDot(canvas, 270 + totalSweep / 2, colors[first]);
                return;
            }
            final float from = full ? 270 : 270 + capDegrees;
            final float to = full ? 270 + 360 : 270 + totalSweep - capDegrees;
            float angle = 270;
            for (int i = 0; i < segments.length; i++) {
                if (segments[i] <= 0) {
                    continue;
                }
                final float end = i == last && full ? to : angle + segments[i] * 360;
                final float start = Math.max(angle, from);
                final float clippedEnd = Math.min(end, to);
                if (clippedEnd > start) {
                    drawArc(canvas, start, clippedEnd - start, colors[i]);
                }
                angle = end;
            }
            if (!full) {
                drawDot(canvas, from, colors[first]);
                drawDot(canvas, to, colors[last]);
            }
        }

        private void drawDot(final Canvas canvas, final float angle, @ColorInt final int color) {
            final double radians = Math.toRadians(angle);
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            canvas.drawCircle(
                oval.centerX() + oval.width() / 2 * (float) Math.cos(radians),
                oval.centerY() + oval.height() / 2 * (float) Math.sin(radians),
                barWidth / 2f, paint);
        }

        void drawTexts(final Canvas canvas, final Context context, final String text, final String lowerText) {
            final float center = width / 2f;
            final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(GBApplication.getTextColor(context));
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, width * 0.06f, context.getResources().getDisplayMetrics()));
            textPaint.setTextAlign(Paint.Align.CENTER);
            final float yPos = center - (textPaint.descent() + textPaint.ascent()) / 2;
            canvas.drawText(String.valueOf(text), center, yPos, textPaint);

            final Paint textLowerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textLowerPaint.setColor(GBApplication.getSecondaryTextColor(context));
            textLowerPaint.setTextAlign(Paint.Align.CENTER);
            textLowerPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, width * 0.025f, context.getResources().getDisplayMetrics()));
            canvas.drawText(String.valueOf(lowerText), center, center - textPaint.ascent(), textLowerPaint);
        }
    }

    public static double normalize(final double value, final double min, final double max) {
        return normalize(value, min, max, 0, 1);
    }

    public static double normalize(final double value, final double minSource, final double maxSource, final double minTarget, final double maxTarget) {
        return ((value - minSource) * (maxTarget - minTarget)) / (maxSource - minSource) + minTarget;
    }

}
