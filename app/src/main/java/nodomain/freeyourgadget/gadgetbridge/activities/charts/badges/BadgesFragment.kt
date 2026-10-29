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

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.Chart
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.AbstractChartFragment
import nodomain.freeyourgadget.gadgetbridge.activities.charts.ChartsData
import nodomain.freeyourgadget.gadgetbridge.activities.charts.ChartsHost
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.SectionHeaderView
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.StatTileData
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.addStatTileGrid
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.applyStatTileStyle
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Badges earned from the history, like the medals of the vendor apps: a card per [BadgeFamily] with its highest
 * tier and the progress to the next one. Tiers earned since the badges were last shown are marked as new.
 */
class BadgesFragment : AbstractChartFragment<BadgesFragment.BadgesData>() {
    private lateinit var badgesContainer: LinearLayout

    /**
     * Tiers marked as new while this fragment lives, so that a refresh doesn't drop the marks.
     */
    private val newIds = mutableSetOf<String>()

    override fun getTitle(): String = getString(R.string.badges_title)

    override fun init() {
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_badges, container, false)
        badgesContainer = view.findViewById(R.id.badges_container)
        view.setOnScrollChangeListener { _, _, scrollY, _, _ -> chartsHost.enableSwipeRefresh(scrollY == 0) }
        refresh()
        return view
    }

    override fun showDateBar(show: Boolean) {
        // Badges cover the whole history
        super.showDateBar(false)
    }

    override fun refreshInBackground(chartsHost: ChartsHost, db: DBHandler, device: GBDevice): BadgesData {
        val user = ActivityUser()
        val days = BadgeHistory.days(db, device)
        val workouts = BadgeHistory.workouts(db, device)
        val badges = BadgeCalculator.compute(
            days,
            workouts.map { it.date },
            user.stepsGoal,
            user.sleepDurationGoal,
            WeekFields.of(Locale.getDefault()).firstDayOfWeek,
        )
        val records = PersonalRecords.compute(days, workouts, user.stepsGoal, user.sleepDurationGoal)
        return BadgesData(device, badges, records)
    }

    override fun updateChartsnUIThread(chartsData: BadgesData) {
        val badges = chartsData.badges
        val earnedIds = badges.flatMap { badge ->
            badge.earned.indices.filter { badge.earned[it] != null }.map { badge.family.id(it) }
        }.toSet()
        val total = badges.sumOf { it.family.thresholds.size }

        // The first time, all badges come from the history: none is new
        val prefs = GBApplication.getDeviceSpecificSharedPrefs(chartsData.device.address)
        val seen = prefs.getStringSet(PREF_SEEN, null)
        val justEarned = if (seen == null) emptySet() else earnedIds - seen
        newIds += justEarned
        val earnedDates = badges.flatMap { badge ->
            badge.earned.mapIndexedNotNull { tier, date -> date?.let { "${badge.family.id(tier)}|$it" } }
        }.toSet()
        prefs.edit()
            .putStringSet(PREF_SEEN, earnedIds)
            .putStringSet(PREF_DATES, earnedDates)
            .putLong(PREF_BEST_STREAK, badges.first { it.family == BadgeFamily.STREAK }.value)
            .putInt(PREF_EARNED, earnedIds.size)
            .putInt(PREF_TOTAL, total)
            .apply()

        render(badges, chartsData.records, earnedIds.size, total)

        latestOf(badges.filter { badge -> badge.earned.indices.any { badge.family.id(it) in justEarned } })?.let { (badge, tier, _) ->
            Toast.makeText(
                requireContext(),
                getString(R.string.badges_new_earned, getString(badge.family.tier(tier).label), getString(badge.family.title)),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun renderCharts() {
    }

    override fun setupLegend(chart: Chart<*>) {
    }

    private fun render(badges: List<BadgeProgress>, records: List<PersonalRecord>, earned: Int, total: Int) {
        val context = requireContext()
        badgesContainer.removeAllViews()

        badgesContainer.addView(text(context, getString(R.string.badges_summary, earned, total), 20f, bold = true))
        badgesContainer.addView(LinearProgressIndicator(context).apply {
            max = total.coerceAtLeast(1)
            progress = earned
            trackCornerRadius = dp(context, 4)
            trackThickness = dp(context, 8)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 8)
            bottomMargin = dp(context, 16)
        })

        latestOf(badges)?.let { (badge, tier, date) -> badgesContainer.addView(latestCard(context, badge, tier, date)) }

        if (records.isNotEmpty()) {
            badgesContainer.addView(SectionHeaderView(context).apply { title = getString(R.string.records_title) })
            val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            addStatTileGrid(badgesContainer, context, records.map { record ->
                StatTileData(recordValue(record), "${getString(record.kind.label)}\n${record.date.format(dateFormat)}")
            })
            badgesContainer.addView(SectionHeaderView(context).apply { title = getString(R.string.badges_title) })
        }

        badges.chunked(COLUMNS).forEach { rowBadges ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            rowBadges.forEachIndexed { index, badge ->
                // Wrapped, not stretched to the row (see addStatTileGrid); titles take 2 lines so cards match
                row.addView(familyCard(context, badge), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(context, GAP_DP)
                })
            }
            repeat(COLUMNS - rowBadges.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(context, GAP_DP) })
            }
            badgesContainer.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, GAP_DP) })
        }
    }

    /**
     * The most recently earned tier of [badges]: the badge, the tier index and the date.
     */
    private fun latestOf(badges: List<BadgeProgress>): Triple<BadgeProgress, Int, LocalDate>? = badges
        .flatMap { badge -> badge.earned.mapIndexedNotNull { tier, date -> date?.let { Triple(badge, tier, it) } } }
        .maxWithOrNull(compareBy<Triple<BadgeProgress, Int, LocalDate>> { it.third }.thenBy { it.second })

    private fun latestCard(context: Context, badge: BadgeProgress, tier: Int, date: LocalDate): View {
        val card = MaterialCardView(context).apply { applyStatTileStyle(this) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padding = dp(context, 14)
            setPadding(padding, padding, padding, padding)
        }
        content.addView(medal(context, badge.family, tier), LinearLayout.LayoutParams(dp(context, 64), dp(context, 64)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(context, getString(R.string.badges_latest), 12f, color = secondaryColor(context)))
        texts.addView(text(context, "${getString(badge.family.tier(tier).label)} · ${getString(badge.family.title)}", 17f, bold = true))
        texts.addView(text(context, description(badge.family, tier), 13f, color = secondaryColor(context)))
        texts.addView(text(context, getString(R.string.badges_earned_on, formatDate(date)), 12f, color = tierColor(context, badge.family.tier(tier))))
        if (badge.family.id(tier) in newIds) {
            texts.addView(newLabel(context))
        }
        content.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(context, 14)
        })
        card.addView(content)
        card.setOnClickListener { showTiers(badge) }
        return card
    }

    private fun familyCard(context: Context, badge: BadgeProgress): View {
        val card = MaterialCardView(context).apply { applyStatTileStyle(this) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padding = dp(context, 12)
            setPadding(padding, dp(context, 14), padding, dp(context, 14))
        }
        val tier = badge.highestTier
        content.addView(medal(context, badge.family, tier), LinearLayout.LayoutParams(dp(context, 72), dp(context, 72)))
        if (badge.earned.indices.any { badge.family.id(it) in newIds }) {
            content.addView(newLabel(context))
        }
        content.addView(text(context, getString(badge.family.title), 15f, bold = true, center = true).apply {
            setLines(2)
            gravity = Gravity.CENTER
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(context, 6)
        })
        content.addView(if (tier >= 0) {
            text(context, getString(badge.family.tier(tier).label), 12f, color = tierColor(context, badge.family.tier(tier)), center = true)
        } else {
            text(context, getString(R.string.badges_not_earned), 12f, color = secondaryColor(context), center = true)
        })

        val next = badge.nextThreshold
        if (next != null) {
            val previous = if (tier >= 0) badge.family.thresholds[tier] else 0L
            content.addView(LinearProgressIndicator(context).apply {
                max = PROGRESS_STEPS
                progress = (((badge.value - previous).toDouble() / (next - previous)) * PROGRESS_STEPS).toInt().coerceIn(0, PROGRESS_STEPS)
                trackCornerRadius = dp(context, 3)
                trackThickness = dp(context, 6)
                setIndicatorColor(tierColor(context, badge.family.tier(tier + 1)))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 10)
            })
            val format = NumberFormat.getInstance()
            content.addView(text(context, getString(R.string.badges_progress, format.format(badge.value), format.format(next)), 12f,
                color = secondaryColor(context), center = true).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(context, 4)
            })
        } else {
            content.addView(text(context, getString(R.string.badges_all_earned), 12f, color = secondaryColor(context), center = true).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(context, 10)
            })
        }

        card.addView(content)
        card.setOnClickListener { showTiers(badge) }
        return card
    }

    /**
     * All tiers of a family, with the date each was earned or what it takes.
     */
    private fun showTiers(badge: BadgeProgress) {
        val context = requireContext()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(context, 20)
            setPadding(padding, dp(context, 8), padding, 0)
        }
        badge.family.thresholds.indices.forEach { tier ->
            val date = badge.earned[tier]
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 6), 0, dp(context, 6))
            }
            row.addView(medal(context, badge.family, if (date != null) tier else -1), LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)))
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(context, getString(badge.family.tier(tier).label), 15f, bold = true,
                color = if (date != null) tierColor(context, badge.family.tier(tier)) else secondaryColor(context)))
            texts.addView(text(context, description(badge.family, tier), 13f))
            texts.addView(text(context, if (date != null) getString(R.string.badges_earned_on, formatDate(date)) else getString(R.string.badges_not_earned),
                12f, color = secondaryColor(context)))
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 14)
            })
            list.addView(row)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(badge.family.title)
            .setView(android.widget.ScrollView(context).apply { addView(list) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * The medal of a family at [tier], grey when no tier is earned (-1).
     */
    private fun medal(context: Context, family: BadgeFamily, tier: Int): ImageView = ImageView(context).apply {
        val color = if (tier >= 0) tierColor(context, family.tier(tier)) else ContextCompat.getColor(context, R.color.badge_locked)
        setImageDrawable(MedalDrawable(color, ContextCompat.getDrawable(context, family.icon)))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (tier < 0) alpha = LOCKED_ALPHA
    }

    private fun newLabel(context: Context): TextView = text(context, getString(R.string.badges_new), 10f, bold = true).apply {
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, "Badges")
        setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, "Badges"))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 8).toFloat()
            setColor(accent)
        }
        setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 4)
        }
    }

    private fun recordValue(record: PersonalRecord): String = when (record.kind) {
        RecordKind.BEST_DAY -> NumberFormat.getInstance().format(record.value)
        RecordKind.LONGEST_STREAK -> resources.getQuantityString(R.plurals.records_days, record.value.toInt(), record.value.toInt())
        RecordKind.LONGEST_NIGHT, RecordKind.LONGEST_WORKOUT -> DateTimeUtils.formatDurationHoursMinutes(record.value, TimeUnit.MINUTES)
        RecordKind.BEST_SLEEP_SCORE -> record.value.toString()
    }

    private fun description(family: BadgeFamily, tier: Int): String {
        val threshold = family.thresholds[tier]
        return resources.getQuantityString(family.description, threshold.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            NumberFormat.getInstance().format(threshold))
    }

    private fun text(context: Context, value: String, sizeSp: Float, bold: Boolean = false, @ColorInt color: Int? = null,
                     center: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(color ?: MaterialColors.getColor(context, R.attr.textColorPrimary, "Badges"))
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            if (center) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun secondaryColor(context: Context) =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, "Badges")

    private fun tierColor(context: Context, tier: BadgeTier) = ContextCompat.getColor(context, tier.color)

    private fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    class BadgesData(val device: GBDevice, val badges: List<BadgeProgress>, val records: List<PersonalRecord>) : ChartsData()

    companion object {
        private const val COLUMNS = 2
        private const val GAP_DP = 12
        private const val PROGRESS_STEPS = 1000
        private const val LOCKED_ALPHA = 0.55f

        /**
         * Ids of the tiers already shown, to tell which ones are new.
         */
        const val PREF_SEEN = "badges_seen"

        /**
         * "id|date" of each earned tier, for the reports.
         */
        const val PREF_DATES = "badges_dates"

        /**
         * The longest streak of the history, for the streak widget.
         */
        const val PREF_BEST_STREAK = "badges_best_streak"

        /**
         * The counts of the last computation, shown in the charts overview.
         */
        const val PREF_EARNED = "badges_earned"
        const val PREF_TOTAL = "badges_total"
    }
}
