/*  Copyright (C) 2026 Gadgetbridge Contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 or later;
    see the LICENSE file for details.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.veepoo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static rendering of a stored Veepoo ECG waveform. The geometry and colors are ported verbatim
 * from the vendor's VpEcgDetailsChatView: 24 pt left margin, 20 pt bottom axis, 30 grid rows,
 * 25 columns per second (one column = 40 ms), baseline at 2/3 of the plot height with one display
 * unit spanning 10 grid rows, minor grid #E47C87 / major grid #CD0A20, black trace 1.5 pt wide,
 * second labels and horizontal drag + fling scrolling.
 */
public class VeepooEcgChartView extends View {

    private static final Logger LOG = LoggerFactory.getLogger(VeepooEcgChartView.class);

    private static final int GRID_ROWS = 30;
    private static final int COLUMNS_PER_SECOND = 25;
    private static final int MINOR_GRID_COLOR = Color.parseColor("#E47C87");
    private static final int MAJOR_GRID_COLOR = Color.parseColor("#CD0A20");
    private static final int TRACE_COLOR = Color.parseColor("#000000");
    private static final int LABEL_COLOR = Color.parseColor("#8A000000");

    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTracePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mTracePath = new Path();

    private final float mLeftMargin;
    private final float mBottomAxis;
    private final int mMaxFlingVelocity;
    private final int mMinFlingVelocity;
    private final FlingRunnable mFlingRunnable;

    private float mViewHeight;
    private float mRowHeightPx;

    private int mSampleRate;
    private float[] mNormalized;

    private VelocityTracker mVelocityTracker;
    private float mLastMoveX;

    public VeepooEcgChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLeftMargin = pt2Px(24);
        mBottomAxis = pt2Px(20);

        mGridPaint.setAntiAlias(true);

        mLabelPaint.setTextSize(pt2Px(12));
        mLabelPaint.setColor(LABEL_COLOR);
        mLabelPaint.setTextAlign(Paint.Align.LEFT);

        mTracePaint.setStyle(Paint.Style.STROKE);
        mTracePaint.setStrokeWidth(pt2Px(1.5f));
        mTracePaint.setColor(TRACE_COLOR);

        ViewConfiguration vc = ViewConfiguration.get(context);
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        mFlingRunnable = new FlingRunnable(context);
    }

    /**
     * Sets the waveform to render. Phase 1 renders stored recordings only; {@code liveMode} is
     * reserved for phase 2 and currently falls back to static rendering.
     *
     * @param sampleRateHz sample rate of the recording (Hz)
     * @param normalized   baseline-centered samples in display units (see VeepooEcgWaveform.normalize)
     * @param liveMode     false for stored recordings; ignored in phase 1
     */
    public void setData(int sampleRateHz, float[] normalized, boolean liveMode) {
        if (liveMode) {
            LOG.warn("Live ECG rendering is not implemented yet; falling back to static mode");
        }
        mSampleRate = sampleRateHz;
        mNormalized = normalized;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mViewHeight = MeasureSpec.getSize(heightMeasureSpec);
        mRowHeightPx = (mViewHeight - mBottomAxis) / GRID_ROWS;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int totalColumns = 0;
        if (mNormalized != null && mSampleRate > 0 && mRowHeightPx > 0) {
            totalColumns = (int) Math.ceil(mNormalized.length / (float) mSampleRate) * COLUMNS_PER_SECOND;
        }

        // Horizontal grid lines: every row, every 5th one dark red.
        for (int row = 0; row <= GRID_ROWS; row++) {
            mGridPaint.setStrokeWidth(pt2Px(0.5f));
            mGridPaint.setColor(row % 5 == 0 ? MAJOR_GRID_COLOR : MINOR_GRID_COLOR);
            canvas.drawLine(mLeftMargin, row * mRowHeightPx, mLeftMargin + totalColumns * mRowHeightPx,
                    row * mRowHeightPx, mGridPaint);
        }

        // Vertical grid lines: minor per column, sub-major every 5, major every 25 with a label.
        for (int col = 0; col <= totalColumns; col++) {
            float x = col * mRowHeightPx + mLeftMargin;
            if (col % COLUMNS_PER_SECOND == 0) {
                mGridPaint.setStrokeWidth(pt2Px(1.0f));
                mGridPaint.setColor(MAJOR_GRID_COLOR);
                canvas.drawLine(x, 0, x, mViewHeight, mGridPaint);
                if (col != totalColumns) {
                    canvas.drawText((col / COLUMNS_PER_SECOND) + "s",
                            x + pt2Px(4), mViewHeight - pt2Px(4), mLabelPaint);
                }
            } else {
                mGridPaint.setStrokeWidth(pt2Px(0.5f));
                mGridPaint.setColor(col % 5 == 0 ? MAJOR_GRID_COLOR : MINOR_GRID_COLOR);
                canvas.drawLine(x, 0, x, mViewHeight - mBottomAxis, mGridPaint);
            }
        }

        if (mNormalized != null && mNormalized.length > 1 && mSampleRate > 0) {
            float plotHeight = mViewHeight - mBottomAxis;
            float xStep = COLUMNS_PER_SECOND * mRowHeightPx / mSampleRate;
            mTracePath.reset();
            for (int i = 0; i < mNormalized.length; i++) {
                float x = mLeftMargin + pt2Px(0.5f) + i * xStep;
                float y = mapY(mNormalized[i], plotHeight);
                if (i == 0) {
                    mTracePath.moveTo(x, y);
                } else {
                    mTracePath.lineTo(x, y);
                }
            }
            canvas.drawPath(mTracePath, mTracePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mNormalized == null || mSampleRate <= 0) {
            return super.onTouchEvent(event);
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                }
                mVelocityTracker.addMovement(event);
                removeCallbacks(mFlingRunnable);
                mFlingRunnable.mScroller.abortAnimation();
                mLastMoveX = event.getX();
                return true;
            case MotionEvent.ACTION_MOVE: {
                mVelocityTracker.addMovement(event);
                float dx = event.getX() - mLastMoveX;
                int maxScroll = getMaxScroll();
                if (dx > 0) {
                    if (getScrollX() - dx <= 0) {
                        scrollTo(0, 0);
                    } else {
                        scrollBy(-((int) dx), 0);
                    }
                } else if (dx < 0) {
                    if (getScrollX() - dx >= maxScroll) {
                        scrollTo(maxScroll, 0);
                    } else {
                        scrollBy(-((int) dx), 0);
                    }
                }
                mLastMoveX = event.getX();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mVelocityTracker.addMovement(event);
                mVelocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity);
                int velocityX = (int) mVelocityTracker.getXVelocity();
                if (Math.abs(velocityX) > mMinFlingVelocity && getMaxScroll() > 0) {
                    mFlingRunnable.start(getScrollX(), velocityX, getMaxScroll());
                    post(mFlingRunnable);
                }
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * Maps a normalized sample to canvas y: the baseline (0) sits at 2/3 of the plot height and
     * one display unit spans 10 grid rows, clamped to the plot window.
     */
    private float mapY(float sample, float plotHeight) {
        float y = (plotHeight / 3) * 2 - sample * 10 * mRowHeightPx;
        if (y < 0) {
            return 0;
        }
        return y > plotHeight ? plotHeight : y;
    }

    private int getMaxScroll() {
        int totalColumns = 0;
        if (mNormalized != null && mSampleRate > 0 && mRowHeightPx > 0) {
            totalColumns = (int) Math.ceil(mNormalized.length / (float) mSampleRate) * COLUMNS_PER_SECOND;
        }
        float contentWidth = 2 * mLeftMargin + totalColumns * mRowHeightPx;
        return Math.max(0, (int) (contentWidth - getMeasuredWidth()));
    }

    private float pt2Px(float pt) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PT, pt, getResources().getDisplayMetrics());
    }

    /** Drives the fling animation with a Scroller clamped to [0, maxScroll]. */
    private final class FlingRunnable implements Runnable {
        final Scroller mScroller;

        FlingRunnable(Context context) {
            mScroller = new Scroller(context);
        }

        void start(int startX, int velocityX, int maxScroll) {
            if (!mScroller.isFinished()) {
                mScroller.abortAnimation();
            }
            mScroller.fling(startX, 0, velocityX, 0, 0, maxScroll, 0, 0);
        }

        @Override
        public void run() {
            if (!mScroller.computeScrollOffset()) {
                return;
            }
            int target = mScroller.getCurrX();
            if (target < 0) {
                target = 0;
            }
            int maxScroll = getMaxScroll();
            if (target > maxScroll) {
                target = maxScroll;
            }
            if (target != getScrollX()) {
                scrollTo(target, 0);
            }
            if (!mScroller.isFinished()) {
                post(this);
            }
        }
    }
}
