package nodomain.freeyourgadget.gadgetbridge.activities.workouts.statistics

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.mpchart.ChartSelection
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.addDetailRows
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.addSectionHeader
import nodomain.freeyourgadget.gadgetbridge.databinding.FragmentSportStatisticsBinding
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Formatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Statistics of one sport over a week, month or year.
 */
class SportStatisticsFragment : Fragment() {
    private val viewModel: WorkoutStatisticsViewModel by activityViewModels()

    private var _binding: FragmentSportStatisticsBinding? = null
    private val binding get() = _binding!!

    private val kindCode: Int get() = requireArguments().getInt(ARG_KIND_CODE)

    private var kind = PeriodKind.MONTH
    private var period: StatisticsPeriod? = null
    private var config: ChartConfig? = null
    private var overview: SportOverview? = null
    private var stats: PeriodStats? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            kind = PeriodKind.valueOf(savedInstanceState.getString(STATE_KIND, kind.name))
            if (savedInstanceState.containsKey(STATE_PERIOD_START)) {
                period = StatisticsPeriod(kind, LocalDate.ofEpochDay(savedInstanceState.getLong(STATE_PERIOD_START)))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSportStatisticsBinding.inflate(inflater, container, false)
        binding.sportChart.dismissSelectionOnTapOutside(binding.sportContent)

        PeriodKind.entries.forEach { periodKind ->
            val tab = binding.sportPeriodTabs.newTab().setText(tabLabel(periodKind)).setTag(periodKind)
            binding.sportPeriodTabs.addTab(tab, periodKind == kind)
        }
        binding.sportPeriodTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                kind = tab.tag as PeriodKind
                if (overview != null) {
                    showPeriod(StatisticsPeriod.containing(kind, today()))
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.sportPeriodPrevious.setOnClickListener { period?.let { showPeriod(it.previous()) } }
        binding.sportPeriodNext.setOnClickListener { period?.let { showPeriod(it.next()) } }
        binding.sportChartEdit.setOnClickListener {
            val available = overview?.availableMetrics ?: return@setOnClickListener
            val current = config ?: return@setOnClickListener
            ChartConfigBottomSheet.newInstance(available, current).show(childFragmentManager, ChartConfigBottomSheet.TAG)
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.setFragmentResultListener(ChartConfigBottomSheet.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            val available = overview?.availableMetrics ?: return@setFragmentResultListener
            val applied = ChartConfig.parse(result.getString(ChartConfigBottomSheet.RESULT_CONFIG))
                ?.restrictedTo(available)
                ?: return@setFragmentResultListener
            config = applied
            GBApplication.getPrefs().preferences.edit().putString(configKey(), applied.serialize()).apply()
            stats?.let { renderChart(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val loaded = viewModel.overview(kindCode)
                overview = loaded
                config = (ChartConfig.parse(GBApplication.getPrefs().getString(configKey(), null))
                    ?: ChartConfig.default(loaded.availableMetrics)).restrictedTo(loaded.availableMetrics)
                showPeriod(period?.takeIf { it.kind == kind } ?: StatisticsPeriod.containing(kind, today()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to load statistics for sport {}", kindCode, e)
                renderError()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val sportName = ActivityKind.fromCode(kindCode).getLabel(requireContext())
        requireActivity().title = getString(R.string.statistics_sport_title, sportName)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_KIND, kind.name)
        period?.let { outState.putLong(STATE_PERIOD_START, it.start.toEpochDay()) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun tabLabel(periodKind: PeriodKind): String = getString(
        when (periodKind) {
            PeriodKind.WEEK -> R.string.calendar_week
            PeriodKind.MONTH -> R.string.calendar_month
            PeriodKind.YEAR -> R.string.calendar_year
        }
    )

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    private fun formatter() = WorkoutValueFormatter(ActivityKind.fromCode(kindCode))

    private fun configKey() = PREF_CHART_CONFIG_PREFIX + kindCode

    /**
     * Cancels any load still running for another period.
     */
    private fun showPeriod(newPeriod: StatisticsPeriod) {
        period = newPeriod
        renderHeader(newPeriod)

        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                render(viewModel.period(kindCode, newPeriod))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to aggregate {} of sport {}", newPeriod, kindCode, e)
                renderError()
            }
        }
    }

    /**
     * Period title and arrows, limited to the range with workouts.
     */
    private fun renderHeader(shown: StatisticsPeriod) {
        binding.sportPeriodTitle.text = periodTitle(shown)

        val overview = overview ?: return
        val earliest = overview.firstDate?.let { StatisticsPeriod.containing(kind, it).start }
        val latestDate = overview.lastDate?.takeIf { it.isAfter(today()) } ?: today()
        val latest = StatisticsPeriod.containing(kind, latestDate).start
        binding.sportPeriodPrevious.isEnabled = earliest != null && shown.start.isAfter(earliest)
        binding.sportPeriodNext.isEnabled = shown.start.isBefore(latest)
    }

    /**
     * Locale-formatted, e.g. "Sep 22 – 28, 2026", "September 2026", "2026".
     */
    private fun periodTitle(shown: StatisticsPeriod): String {
        fun millis(date: LocalDate) = date.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC).toEpochMilli()
        fun range(start: LocalDate, end: LocalDate, flags: Int): String = DateUtils.formatDateRange(
            requireContext(),
            Formatter(StringBuilder(), Locale.getDefault()),
            millis(start),
            millis(end),
            flags or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
            "UTC",
        ).toString()

        return when (shown.kind) {
            PeriodKind.WEEK -> range(shown.start, shown.endInclusive, DateUtils.FORMAT_ABBREV_MONTH)
            PeriodKind.MONTH -> range(shown.start, shown.start, DateUtils.FORMAT_NO_MONTH_DAY)
            PeriodKind.YEAR -> shown.start.year.toString()
        }
    }

    private fun render(newStats: PeriodStats) {
        stats = newStats
        renderChart(newStats)
        renderSummary(newStats)
        showContent()
    }

    private fun renderError() {
        stats = null
        binding.sportChart.showMessage(getString(R.string.statistics_load_failed))
        binding.sportChart.contentDescription = null
        binding.sportChartLegend.setSeries(emptyList())
        binding.sportTiles.removeAllViews()
        showContent()
    }

    private fun showContent() {
        binding.loadingSpinner.visibility = View.GONE
        binding.sportContent.visibility = View.VISIBLE
    }

    private fun renderChart(stats: PeriodStats) {
        val config = config ?: return
        val formatter = formatter()
        val colors = listOf(
            MaterialColors.getColor(binding.sportChart, com.google.android.material.R.attr.colorOnSurface),
            ContextCompat.getColor(requireContext(), R.color.chart_sport_statistics_second),
        )
        val charts = listOfNotNull(config.first, config.second).mapIndexed { index, set ->
            val metric = set.metric
            MetricChart(metric, getString(metric.labelRes()), colors[index], set.type, emptyAxisMaximum(metric)) { raw ->
                displayValue(metric, raw, formatter)
            }
        }

        val spec = SportChartSpecBuilder.build(stats, charts.first(), charts.getOrNull(1))
        binding.sportChart.selectionContent = { x -> selection(stats, x.toInt(), charts, formatter) }
        binding.sportChart.setSpec(spec)
        binding.sportChart.contentDescription =
            charts.joinToString("; ") { chartDescription(stats, it.metric, it.label, formatter) }
        binding.sportChartLegend.setSeries(spec.series.filter { it.points.isNotEmpty() })
    }

    private fun selection(
        stats: PeriodStats,
        slot: Int,
        charts: List<MetricChart>,
        formatter: WorkoutValueFormatter,
    ): ChartSelection {
        val title = slotTitle(stats.period, slot)
        val totals = stats.bars.firstOrNull { it.x == slot.toDouble() }?.totals
        val values = charts.map { chart ->
            val raw = totals?.let { chart.metric.valueOf(it) } ?: 0.0
            if (raw == 0.0) getString(R.string.stats_empty_value) else formatMetric(chart.metric, raw, formatter)
        }
        return ChartSelection(
            title = title,
            rows = charts.zip(values) { chart, value -> ChartSelection.Row(chart.color, value) },
            description = (listOf(title) + charts.zip(values) { chart, value -> "${chart.label} $value" })
                .joinToString(". ", postfix = "."),
        )
    }

    /**
     * Locale-formatted, e.g. "Wed, Sep 24", "Sep 24, 2026", "September 2026".
     */
    private fun slotTitle(period: StatisticsPeriod, slot: Int): String {
        val locale = Locale.getDefault()
        fun format(skeleton: String, date: LocalDate) =
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)

        return when (period.kind) {
            PeriodKind.WEEK -> format("EEEMMMd", period.start.plusDays(slot - 1L))
            PeriodKind.MONTH -> format("yMMMd", period.start.plusDays(slot - 1L))
            PeriodKind.YEAR -> format("yMMMM", period.start.withMonth(slot))
        }
    }

    private fun chartDescription(
        stats: PeriodStats,
        metric: SportMetric,
        label: String,
        formatter: WorkoutValueFormatter,
    ): String = getString(
        when {
            stats.period.kind == PeriodKind.YEAR && metric.average -> R.string.statistics_chart_description_monthly_average
            stats.period.kind == PeriodKind.YEAR -> R.string.statistics_chart_description_monthly
            metric.average -> R.string.statistics_chart_description_daily_average
            else -> R.string.statistics_chart_description_daily
        },
        label,
        periodTitle(stats.period),
        formatTotal(metric, metric.valueOf(stats.totals), formatter),
    )

    private fun displayValue(metric: SportMetric, raw: Double, formatter: WorkoutValueFormatter): Double = when (metric) {
        // From km, as meters would convert to feet on imperial
        SportMetric.DISTANCE ->
            formatter.convert(raw / METERS_PER_KM, ActivitySummaryEntries.UNIT_KILOMETERS, true).value
        SportMetric.DURATION -> raw / SECONDS_PER_HOUR
        SportMetric.AVG_PACE ->
            formatter.convert(raw, ActivitySummaryEntries.UNIT_SECONDS_PER_M, true).value * SECONDS_PER_MINUTE
        SportMetric.AVG_SPEED -> formatter.convert(raw, ActivitySummaryEntries.UNIT_METERS_PER_SECOND, true).value
        SportMetric.AVG_HEART_RATE, SportMetric.AVG_POWER, SportMetric.AVG_SWOLF,
        SportMetric.AVG_AEROBIC_EFFECT, SportMetric.AVG_ANAEROBIC_EFFECT -> raw
        SportMetric.ASCENT, SportMetric.DESCENT ->
            formatter.convert(raw, ActivitySummaryEntries.UNIT_METERS, true).value
        SportMetric.CALORIES, SportMetric.STEPS, SportMetric.TRAINING_LOAD, SportMetric.SETS -> raw
    }

    private fun emptyAxisMaximum(metric: SportMetric): Double = when (metric) {
        SportMetric.DISTANCE -> 10.0
        SportMetric.DURATION -> 1.0
        SportMetric.AVG_PACE, SportMetric.AVG_SPEED, SportMetric.AVG_HEART_RATE, SportMetric.AVG_POWER,
        SportMetric.AVG_SWOLF, SportMetric.AVG_AEROBIC_EFFECT, SportMetric.AVG_ANAEROBIC_EFFECT -> 0.0
        SportMetric.CALORIES -> 500.0
        SportMetric.ASCENT, SportMetric.DESCENT -> 100.0
        SportMetric.STEPS -> 1000.0
        SportMetric.TRAINING_LOAD -> 100.0
        SportMetric.SETS -> 10.0
    }

    private fun formatTotal(metric: SportMetric, raw: Double, formatter: WorkoutValueFormatter): String =
        if (metric.average && raw == 0.0) getString(R.string.stats_empty_value) else formatMetric(metric, raw, formatter)

    private fun formatMetric(metric: SportMetric, raw: Double, formatter: WorkoutValueFormatter): String = when (metric) {
        SportMetric.DISTANCE -> formatter.formatValue(raw / METERS_PER_KM, ActivitySummaryEntries.UNIT_KILOMETERS)
        SportMetric.DURATION -> formatter.formatValue(raw, ActivitySummaryEntries.UNIT_SECONDS)
        SportMetric.AVG_PACE -> formatter.formatValue(raw, ActivitySummaryEntries.UNIT_SECONDS_PER_M)
        SportMetric.AVG_SPEED -> formatter.formatValue(raw, ActivitySummaryEntries.UNIT_METERS_PER_SECOND)
        SportMetric.AVG_HEART_RATE -> formatter.formatValue(raw.roundToLong(), ActivitySummaryEntries.UNIT_BPM)
        SportMetric.AVG_POWER -> formatter.formatValue(raw.roundToLong(), ActivitySummaryEntries.UNIT_WATT)
        SportMetric.AVG_AEROBIC_EFFECT, SportMetric.AVG_ANAEROBIC_EFFECT ->
            String.format(Locale.getDefault(), "%.1f", raw)
        SportMetric.CALORIES -> formatter.formatValue(raw.roundToLong(), ActivitySummaryEntries.UNIT_KCAL)
        SportMetric.ASCENT, SportMetric.DESCENT ->
            formatter.formatValue(raw.roundToLong(), ActivitySummaryEntries.UNIT_METERS)
        SportMetric.STEPS -> formatter.formatValue(raw.roundToLong(), ActivitySummaryEntries.UNIT_STEPS)
        SportMetric.AVG_SWOLF, SportMetric.TRAINING_LOAD, SportMetric.SETS -> raw.roundToLong().toString()
    }

    private fun renderSummary(stats: PeriodStats) {
        val formatter = formatter()
        val empty = getString(R.string.stats_empty_value)
        fun row(label: String, value: String) = label to if (stats.count == 0) empty else value

        val rows = listOf(row(getString(R.string.statistics_sessions), stats.count.toString())) +
            overview?.availableMetrics.orEmpty().map { metric ->
                row(getString(metric.labelRes()), formatTotal(metric, metric.valueOf(stats.totals), formatter))
            }

        binding.sportTiles.removeAllViews()
        addSectionHeader(binding.sportTiles, requireContext(), getString(R.string.statistics_summary), showDivider = true)
        addDetailRows(binding.sportTiles, requireContext(), rows)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SportStatisticsFragment::class.java)
        private const val ARG_KIND_CODE = "kindCode"
        private const val STATE_KIND = "kind"
        private const val STATE_PERIOD_START = "periodStart"
        private const val PREF_CHART_CONFIG_PREFIX = "sport_statistics_chart_"
        private const val METERS_PER_KM = 1000.0
        private const val SECONDS_PER_HOUR = 3600.0
        private const val SECONDS_PER_MINUTE = 60.0

        fun newInstance(kindCode: Int) = SportStatisticsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_KIND_CODE, kindCode) }
        }
    }
}
