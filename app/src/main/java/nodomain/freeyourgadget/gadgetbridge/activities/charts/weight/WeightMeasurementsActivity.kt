package nodomain.freeyourgadget.gadgetbridge.activities.charts.weight

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.databinding.ActivityWeightMeasurementsBinding
import nodomain.freeyourgadget.gadgetbridge.entities.ManualWeightSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.util.UserWeight
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getParcelableCompat
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * All weight measurements of a device, newest first and grouped by month.
 */
class WeightMeasurementsActivity : AbstractGBActivity() {
    private lateinit var binding: ActivityWeightMeasurementsBinding
    private lateinit var device: GBDevice
    private lateinit var controller: WeightMeasurementsController
    private val adapter = Adapter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var deviceRecordsEditable = false
    private var latestKg: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWeightMeasurementsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        device = intent.getParcelableCompat<GBDevice>(GBDevice.EXTRA_DEVICE)!!

        controller = WeightMeasurementsController(
            this,
            supportFragmentManager,
            this,
            device,
            GBApplication.getPrefs().weightUnit,
            { binding.root },
            { binding.weightMeasurementsAdd },
            { load() },
        )

        binding.weightMeasurementsList.layoutManager = LinearLayoutManager(this)
        binding.weightMeasurementsList.adapter = adapter
        ItemTouchHelper(SwipeToDelete()).attachToRecyclerView(binding.weightMeasurementsList)
        binding.weightMeasurementsAdd.setOnClickListener {
            controller.add(latestKg ?: ActivityUser().weightKg.toDouble())
        }
        load()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        EXECUTOR.execute {
            try {
                GBApplication.acquireDB().use { db ->
                    val canAdd = UserWeight.supportsManualEntry(device)
                    val deviceRecordsEditable = UserWeight.supportsDeviceRecordEdits(device, db.daoSession)
                    val samples = UserWeight.provider(device, db.daoSession)
                        ?.getAllSamples(0, System.currentTimeMillis()).orEmpty()
                    val rows = WeightMeasurements.rows(
                        samples.map { it.timestamp }.toLongArray(),
                        samples.map { it.weightKg.toDouble() }.toDoubleArray(),
                        samples.map { it is ManualWeightSample }.toBooleanArray(),
                    )
                    mainHandler.post { show(rows, canAdd, deviceRecordsEditable) }
                }
            } catch (e: Exception) {
                LOG.error("Failed to load weight measurements", e)
            }
        }
    }

    private fun show(rows: List<WeightMeasurementRow>, canAdd: Boolean, deviceRecordsEditable: Boolean) {
        if (isDestroyed) return
        this.deviceRecordsEditable = deviceRecordsEditable
        latestKg = rows.firstOrNull()?.weightKg
        binding.weightMeasurementsAdd.visibility = if (canAdd) View.VISIBLE else View.GONE
        binding.weightMeasurementsListEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val items = mutableListOf<Item>()
        var month: String? = null
        for (row in rows) {
            val rowMonth = WeightMeasurements.monthText(row.timestamp, zone, locale)
            if (rowMonth != month) {
                items += Item.Month(rowMonth, first = month == null)
                month = rowMonth
            }
            items += Item.Measurement(row)
        }
        adapter.submit(items)
    }

    private sealed interface Item {
        data class Month(val label: String, val first: Boolean) : Item
        data class Measurement(val row: WeightMeasurementRow) : Item
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Item> = emptyList()

        fun submit(newItems: List<Item>) {
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        fun itemAt(position: Int) = items.getOrNull(position)

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Month) TYPE_MONTH else TYPE_MEASUREMENT

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_MONTH) R.layout.item_weight_measurement_month else R.layout.item_weight_measurement
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Month -> (holder.itemView as TextView).apply {
                    text = item.label
                    setPaddingRelative(paddingStart, dp(if (item.first) 12f else 16f), paddingEnd, paddingBottom)
                }
                is Item.Measurement -> controller.bind(holder.itemView, item.row, deviceRecordsEditable)
            }
        }
    }

    private inner class SwipeToDelete : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START) {
        private val background = Paint().apply { color = ContextCompat.getColor(this@WeightMeasurementsActivity, R.color.weight_measurement_delete) }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            isFakeBoldText = true
        }
        private val icon = ContextCompat.getDrawable(this@WeightMeasurementsActivity, R.drawable.ic_delete)!!.mutate().apply { setTint(Color.WHITE) }
        private val label = getString(R.string.delete)
        private var pastThreshold = false

        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            when (val item = adapter.itemAt(viewHolder.bindingAdapterPosition)) {
                is Item.Measurement -> if (item.row.manual || deviceRecordsEditable) super.getSwipeDirs(recyclerView, viewHolder) else 0
                else -> 0
            }

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = SWIPE_THRESHOLD

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            pastThreshold = false
            val item = adapter.itemAt(viewHolder.bindingAdapterPosition) as? Item.Measurement ?: return
            controller.delete(item.row.timestamp)
        }

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
        ) {
            val view = viewHolder.itemView
            if (dX != 0f) {
                val left = if (dX < 0) view.right + dX else view.left.toFloat()
                val right = if (dX < 0) view.right.toFloat() else view.left + dX
                c.drawRect(left, view.top.toFloat(), right, view.bottom.toFloat(), background)

                val iconSize = dp(22f)
                val gap = dp(8f)
                val endPadding = dp(24f)
                val textWidth = textPaint.measureText(label).toInt()
                val centerY = (view.top + view.bottom) / 2
                val textRight = view.right - endPadding
                val iconRight = textRight - textWidth - gap
                icon.setBounds(iconRight - iconSize, centerY - iconSize / 2, iconRight, centerY + iconSize / 2)
                c.save()
                c.clipRect(left, view.top.toFloat(), right, view.bottom.toFloat())
                icon.draw(c)
                c.drawText(label, (textRight - textWidth).toFloat(), centerY - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
                c.restore()

                val past = abs(dX) >= view.width * SWIPE_THRESHOLD
                if (isCurrentlyActive && past && !pastThreshold) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                pastThreshold = past
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private companion object {
        val LOG = LoggerFactory.getLogger(WeightMeasurementsActivity::class.java)!!
        val EXECUTOR = Executors.newSingleThreadExecutor()!!
        const val TYPE_MONTH = 0
        const val TYPE_MEASUREMENT = 1
        const val SWIPE_THRESHOLD = 0.4f
    }
}
