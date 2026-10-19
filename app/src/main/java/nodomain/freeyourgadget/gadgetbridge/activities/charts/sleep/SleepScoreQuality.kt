package nodomain.freeyourgadget.gadgetbridge.activities.charts.sleep

import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R

/**
 * How good a night's sleep was, by its sleep score.
 */
enum class SleepScoreQuality(@StringRes val label: Int) {
    EXCELLENT(R.string.sleep_score_quality_excellent),
    GOOD(R.string.sleep_score_quality_good),
    FAIR(R.string.sleep_score_quality_fair),
    POOR(R.string.sleep_score_quality_poor),
    ;

    companion object {
        /**
         * The bands from the lowest score up, as shares of a 0-100 gauge.
         */
        @JvmField
        val GAUGE_ORDER = arrayOf(POOR, FAIR, GOOD, EXCELLENT)

        @JvmField
        val GAUGE_SEGMENTS = floatArrayOf(0.60f, 0.20f, 0.10f, 0.10f)

        /**
         * The band of [score], or null when there is no score.
         */
        @JvmStatic
        fun of(score: Int): SleepScoreQuality? = when {
            score <= 0 -> null
            score >= 90 -> EXCELLENT
            score >= 80 -> GOOD
            score >= 60 -> FAIR
            else -> POOR
        }
    }
}
