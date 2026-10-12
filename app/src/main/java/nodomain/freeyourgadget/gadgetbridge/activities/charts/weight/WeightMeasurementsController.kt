package nodomain.freeyourgadget.gadgetbridge.activities.charts.weight

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.WeightEntryBottomSheet
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericWeightSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit
import nodomain.freeyourgadget.gadgetbridge.util.UserWeight
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Adds, edits and deletes the weight measurements of [device], and shows them as rows.
 */
class WeightMeasurementsController(
    private val context: Context,
    private val fragmentManager: FragmentManager,
    lifecycleOwner: LifecycleOwner,
    private val device: GBDevice,
    private val unit: WeightUnit,
    private val snackbarParent: () -> View?,
    private val snackbarAnchor: () -> View?,
    private val onChanged: Runnable,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unitLabel = context.getString(
        when (unit) {
            WeightUnit.KILOGRAM -> R.string.unit_kg
            WeightUnit.POUND -> R.string.unit_lb
            WeightUnit.STONE -> R.string.unit_st
            WeightUnit.JIN -> R.string.unit_jin
        }
    )

    init {
        fragmentManager.setFragmentResultListener(WeightEntryBottomSheet.REQUEST_KEY, lifecycleOwner) { _, result -> onResult(result) }
    }

    fun add(currentKg: Double?) {
        WeightEntryBottomSheet.newInstance(unit, currentKg).show(fragmentManager, WeightEntryBottomSheet.TAG)
    }

    fun edit(row: WeightMeasurementRow) {
        WeightEntryBottomSheet.newEditInstance(unit, row.weightKg, row.timestamp).show(fragmentManager, WeightEntryBottomSheet.TAG)
    }

    fun delete(timestamp: Long) {
        var deleted: GenericWeightSample? = null
        change({ session -> deleted = UserWeight.deleteRecord(session, device, timestamp) }) {
            val sample = deleted ?: return@change
            val parent = snackbarParent() ?: return@change
            Snackbar.make(parent, R.string.weight_measurement_deleted, Snackbar.LENGTH_LONG)
                .setDuration(UNDO_DURATION_MS)
                .setAnchorView(snackbarAnchor())
                .setAction(R.string.weight_measurement_undo) { change({ session -> UserWeight.restoreRecord(session, sample) }) }
                .show()
        }
    }

    /**
     * Fills a row inflated from item_weight_measurement. Only [editable] rows can be edited and deleted.
     */
    fun bind(view: View, row: WeightMeasurementRow, editable: Boolean) {
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val date = WeightMeasurements.dateText(
            row.timestamp, LocalDate.now(zone), zone, locale,
            context.getString(R.string.weight_measurement_today), context.getString(R.string.weight_measurement_yesterday),
        )
        val time = Instant.ofEpochMilli(row.timestamp).atZone(zone).toLocalTime()
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        val delta = WeightMeasurements.deltaText(row.deltaKg, unit)
        val (value, valueUnit) = WeightMeasurements.valueParts(row.weightKg, unit, unitLabel)

        view.findViewById<TextView>(R.id.weight_measurement_date).text = date
        view.findViewById<TextView>(R.id.weight_measurement_time).text = time
        view.findViewById<TextView>(R.id.weight_measurement_delta).text = delta
        view.findViewById<TextView>(R.id.weight_measurement_value).text = value
        view.findViewById<TextView>(R.id.weight_measurement_unit).text = valueUnit

        val rowView = view.findViewById<View>(R.id.weight_measurement_row)
        val more = view.findViewById<View>(R.id.weight_measurement_more)
        rowView.contentDescription = listOf(date, time, "$value $valueUnit".trim(), delta).filter { it.isNotEmpty() }.joinToString(", ")
        (rowView.getTag(R.id.weight_measurement_row) as? IntArray)?.forEach { ViewCompat.removeAccessibilityAction(rowView, it) }
        rowView.setTag(R.id.weight_measurement_row, null)
        more.visibility = if (editable) View.VISIBLE else View.INVISIBLE
        rowView.isClickable = editable
        if (!editable) {
            rowView.setOnClickListener(null)
            return
        }
        rowView.setOnClickListener { edit(row) }
        more.setOnClickListener { anchor ->
            PopupMenu(context, anchor).apply {
                menu.add(Menu.NONE, MENU_EDIT, Menu.NONE, R.string.weight_measurement_edit)
                menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.delete)
                setOnMenuItemClickListener { item ->
                    if (item.itemId == MENU_EDIT) edit(row) else delete(row.timestamp)
                    true
                }
            }.show()
        }
        rowView.setTag(
            R.id.weight_measurement_row,
            intArrayOf(
                ViewCompat.addAccessibilityAction(rowView, context.getString(R.string.weight_measurement_edit)) { _, _ -> edit(row); true },
                ViewCompat.addAccessibilityAction(rowView, context.getString(R.string.delete)) { _, _ -> delete(row.timestamp); true },
            )
        )
    }

    private fun onResult(result: Bundle) {
        val original = result.getLong(WeightEntryBottomSheet.RESULT_ORIGINAL_TIMESTAMP, WeightEntryBottomSheet.NO_TIMESTAMP)
        if (result.getBoolean(WeightEntryBottomSheet.RESULT_DELETE)) {
            delete(original)
            return
        }
        val kg = result.getDouble(WeightEntryBottomSheet.RESULT_KG)
        val timestamp = result.getLong(WeightEntryBottomSheet.RESULT_TIMESTAMP)
        change({ session ->
            if (original != WeightEntryBottomSheet.NO_TIMESTAMP) {
                UserWeight.deleteRecord(session, device, original)
            }
            UserWeight.addRecord(session, device, kg, timestamp)
        })
    }

    /**
     * Runs [action] in the background. When the newest measurement changes, it is sent to the devices.
     */
    private fun change(action: (DaoSession) -> Unit, after: (() -> Unit)? = null) {
        EXECUTOR.execute {
            try {
                GBApplication.acquireDB().use { db ->
                    val newestChanged = UserWeight.changeRecords(db.daoSession, device) { action(db.daoSession) }
                    if (newestChanged) {
                        GBApplication.deviceService().onSendConfiguration(ActivityUser.PREF_USER_WEIGHT_KG)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to change weight measurements", e)
                return@execute
            }
            mainHandler.post {
                after?.invoke()
                onChanged.run()
            }
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(WeightMeasurementsController::class.java)!!
        val EXECUTOR = Executors.newSingleThreadExecutor()!!
        const val MENU_EDIT = 1
        const val MENU_DELETE = 2
        const val UNDO_DURATION_MS = 5000
    }
}
