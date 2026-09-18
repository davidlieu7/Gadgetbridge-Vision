package nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries

import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries

private const val BPM_COLUMN_WEIGHT = 0.8f

/**
 * Units that are the same in every row of a column (heart rate and pace), so showing them in each
 * cell only adds noise. Distance is deliberately not here: it rolls over between m and km per
 * cell, so its unit has to stay.
 */
private val IMPLIED_UNITS = setOf(
    ActivitySummaryEntries.UNIT_BPM,
    ActivitySummaryEntries.UNIT_SECONDS_PER_KM,
    ActivitySummaryEntries.UNIT_SECONDS_PER_M,
    ActivitySummaryEntries.UNIT_SECONDS_PER_100_METERS,
    ActivitySummaryEntries.UNIT_SECONDS_PER_100_YARDS,
    ActivitySummaryEntries.UNIT_SECONDS_PER_500_METERS,
    ActivitySummaryEntries.UNIT_MINUTES_PER_KM,
    ActivitySummaryEntries.UNIT_MINUTES_PER_MILE,
    ActivitySummaryEntries.UNIT_MINUTES_PER_100_METERS,
    ActivitySummaryEntries.UNIT_MINUTES_PER_100_YARDS,
    ActivitySummaryEntries.UNIT_MINUTES_PER_500_METERS,
)

/**
 * How one column of a workout table is laid out.
 *
 * @property numeric right-aligned with tabular figures; other columns are text, start-aligned.
 * @property fixedWidth the narrow lap/set number column, a fixed width instead of a weight.
 * @property weight share of the remaining width, ignored when [fixedWidth].
 * @property dropUnit leave the unit off the cells, because it never changes within the column.
 */
data class TableColumnSpec(
    val numeric: Boolean,
    val fixedWidth: Boolean,
    val weight: Float,
    val dropUnit: Boolean,
)

/**
 * Column layout for a whole table, worked out from all of its rows at once so every row lines up
 * and the decisions (alignment, unit dropping) are the same for each cell in a column.
 */
class TableSpec private constructor(private val columns: List<TableColumnSpec>) {

    fun column(index: Int): TableColumnSpec = columns.getOrElse(index) { DEFAULT_COLUMN }

    companion object {
        private val DEFAULT_COLUMN = TableColumnSpec(numeric = false, fixedWidth = false, weight = 1f, dropUnit = false)

        /** [rows] are the table's header and data rows; only the data rows decide the column kinds. */
        @JvmStatic
        fun from(rows: List<ActivitySummaryTableRowEntry>): TableSpec {
            val dataRows = rows.filter { !it.isHeader }
            val columnCount = rows.maxOfOrNull { it.columns.size } ?: 0

            val columns = (0 until columnCount).map { index ->
                // Missing values (shown as "-") don't tell us anything about the column.
                val cells = dataRows.mapNotNull { it.columns.getOrNull(index) }.filter { it.value() != null }
                val numeric = cells.isNotEmpty() && cells.all { it.value() is Number }
                val units = cells.map { it.unit() }.distinct()

                val dropUnit = numeric && units.size == 1 && units[0] in IMPLIED_UNITS
                val fixedWidth = index == 0 && numeric && cells.all { it.value() is Int || it.value() is Long }
                val weight = if (dropUnit && units[0] == ActivitySummaryEntries.UNIT_BPM) BPM_COLUMN_WEIGHT else 1f

                TableColumnSpec(numeric, fixedWidth, weight, dropUnit)
            }
            return TableSpec(columns)
        }
    }
}
