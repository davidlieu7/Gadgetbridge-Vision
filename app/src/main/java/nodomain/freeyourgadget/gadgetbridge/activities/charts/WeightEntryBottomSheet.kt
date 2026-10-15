package nodomain.freeyourgadget.gadgetbridge.activities.charts

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.databinding.BottomsheetWeightEntryBinding
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit
import nodomain.freeyourgadget.gadgetbridge.util.UserWeight
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Asks for a weight measurement to add or edit, or for the weight goal. Returns the measurement under [REQUEST_KEY],
 * or the goal in kg under [GOAL_REQUEST_KEY], NaN for no goal, with whether to show the goal line.
 */
class WeightEntryBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomsheetWeightEntryBinding? = null
    private val binding get() = _binding!!

    private lateinit var unit: WeightUnit
    private var goal = false
    private var originalTimestamp = NO_TIMESTAMP
    private var dateTime: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)

    override fun getTheme() = R.style.ThemeOverlay_App_ChartConfigSheet

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomsheetWeightEntryBinding.inflate(inflater, container, false)
        val args = requireArguments()
        unit = WeightUnit.valueOf(args.getString(ARG_UNIT, WeightUnit.KILOGRAM.name))
        goal = args.getBoolean(ARG_GOAL)
        originalTimestamp = args.getLong(ARG_TIMESTAMP, NO_TIMESTAMP)

        when {
            goal -> {
                binding.weightEntryTitle.setText(R.string.weight_goal_title)
                binding.weightEntrySubtitle.setText(R.string.weight_goal_subtitle)
                binding.weightEntryWhen.visibility = View.GONE
                binding.weightEntryShowGoalLine.visibility = View.VISIBLE
                if (savedInstanceState == null) {
                    binding.weightEntryShowGoalLine.isChecked = args.getBoolean(ARG_SHOW_GOAL_LINE, true)
                }
            }
            originalTimestamp != NO_TIMESTAMP -> {
                binding.weightEntryTitle.setText(R.string.weight_entry_edit_title)
                binding.weightEntryDelete.visibility = View.VISIBLE
                dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(originalTimestamp), ZoneId.systemDefault())
            }
        }

        binding.weightEntryLayout.suffixText = getString(unitLabel(unit))
        if (savedInstanceState == null) {
            val currentKg = args.getDouble(ARG_CURRENT_KG, Double.NaN)
            if (!currentKg.isNaN()) {
                binding.weightEntryValue.setText(String.format(Locale.ROOT, "%.1f", WeightUnit.convertWeight(currentKg, unit)))
            }
        } else {
            savedInstanceState.getString(STATE_DATE_TIME)?.let { dateTime = LocalDateTime.parse(it) }
        }

        binding.weightEntryDate.setOnClickListener { pickDate() }
        binding.weightEntryDateLayout.setEndIconOnClickListener { pickDate() }
        binding.weightEntryTime.setOnClickListener { pickTime() }
        binding.weightEntryTimeLayout.setEndIconOnClickListener { pickTime() }
        @Suppress("UNCHECKED_CAST")
        (childFragmentManager.findFragmentByTag(DATE_PICKER_TAG) as? MaterialDatePicker<Long>)?.let { listenToDatePicker(it) }
        (childFragmentManager.findFragmentByTag(TIME_PICKER_TAG) as? MaterialTimePicker)?.let { listenToTimePicker(it) }

        binding.weightEntryValue.doAfterTextChanged { updateSave() }
        binding.weightEntryValue.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && binding.weightEntrySave.isEnabled) {
                save()
            }
            true
        }
        binding.weightEntryCancel.setOnClickListener { dismiss() }
        binding.weightEntrySave.setOnClickListener { save() }
        binding.weightEntryDelete.setOnClickListener {
            setFragmentResult(REQUEST_KEY, bundleOf(RESULT_ORIGINAL_TIMESTAMP to originalTimestamp, RESULT_DELETE to true))
            dismiss()
        }
        showDateTime()
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DATE_TIME, dateTime.toString())
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun showDateTime() {
        binding.weightEntryDate.setText(dateTime.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
        binding.weightEntryTime.setText(dateTime.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
        updateSave()
    }

    private fun pickDate() {
        if (childFragmentManager.findFragmentByTag(DATE_PICKER_TAG) != null) return
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(dateTime.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointBackward.now()).build())
            .build()
        listenToDatePicker(picker)
        picker.show(childFragmentManager, DATE_PICKER_TAG)
    }

    private fun listenToDatePicker(picker: MaterialDatePicker<Long>) {
        picker.addOnPositiveButtonClickListener { selection ->
            val date = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
            dateTime = date.atTime(dateTime.toLocalTime())
            showDateTime()
        }
    }

    private fun pickTime() {
        if (childFragmentManager.findFragmentByTag(TIME_PICKER_TAG) != null) return
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .setHour(dateTime.hour)
            .setMinute(dateTime.minute)
            .build()
        listenToTimePicker(picker)
        picker.show(childFragmentManager, TIME_PICKER_TAG)
    }

    private fun listenToTimePicker(picker: MaterialTimePicker) {
        picker.addOnPositiveButtonClickListener {
            dateTime = dateTime.toLocalDate().atTime(LocalTime.of(picker.hour, picker.minute))
            showDateTime()
        }
    }

    private fun enteredText() = binding.weightEntryValue.text?.toString().orEmpty()

    private fun enteredKg() = UserWeight.parseKg(enteredText(), unit)

    private fun timestamp() = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun updateSave() {
        val valid = if (goal) {
            enteredKg() != null || enteredText().isBlank()
        } else {
            enteredKg() != null && timestamp() <= System.currentTimeMillis()
        }
        binding.weightEntrySave.isEnabled = valid
        binding.weightEntrySave.alpha = if (valid) 1f else DISABLED_ALPHA
    }

    private fun save() {
        if (goal) {
            val goalKg = if (enteredText().isBlank()) Double.NaN else enteredKg() ?: return
            setFragmentResult(
                GOAL_REQUEST_KEY,
                bundleOf(RESULT_KG to goalKg, RESULT_SHOW_GOAL_LINE to binding.weightEntryShowGoalLine.isChecked)
            )
            dismiss()
            return
        }
        val kg = enteredKg() ?: return
        setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_KG to kg, RESULT_TIMESTAMP to timestamp(), RESULT_ORIGINAL_TIMESTAMP to originalTimestamp)
        )
        dismiss()
    }

    companion object {
        const val TAG = "WeightEntryBottomSheet"
        const val REQUEST_KEY = "weight_entry"
        const val GOAL_REQUEST_KEY = "weight_goal"
        const val RESULT_KG = "kg"
        const val RESULT_TIMESTAMP = "timestamp"
        const val RESULT_ORIGINAL_TIMESTAMP = "original_timestamp"
        const val RESULT_DELETE = "delete"
        const val RESULT_SHOW_GOAL_LINE = "show_goal_line"
        const val NO_TIMESTAMP = -1L
        private const val STATE_DATE_TIME = "date_time"
        private const val DATE_PICKER_TAG = "date_picker"
        private const val TIME_PICKER_TAG = "time_picker"
        private const val ARG_UNIT = "unit"
        private const val ARG_CURRENT_KG = "current_kg"
        private const val ARG_GOAL = "goal"
        private const val ARG_TIMESTAMP = "timestamp"
        private const val ARG_SHOW_GOAL_LINE = "show_goal_line"
        private const val DISABLED_ALPHA = 0.38f

        @JvmStatic
        fun newInstance(unit: WeightUnit, currentKg: Double?) = WeightEntryBottomSheet().apply {
            arguments = bundleOf(ARG_UNIT to unit.name, ARG_CURRENT_KG to (currentKg ?: Double.NaN))
        }

        @JvmStatic
        fun newEditInstance(unit: WeightUnit, weightKg: Double, timestamp: Long) = WeightEntryBottomSheet().apply {
            arguments = bundleOf(ARG_UNIT to unit.name, ARG_CURRENT_KG to weightKg, ARG_TIMESTAMP to timestamp)
        }

        @JvmStatic
        fun newGoalInstance(unit: WeightUnit, goalKg: Double?, showGoalLine: Boolean) = WeightEntryBottomSheet().apply {
            arguments = bundleOf(
                ARG_UNIT to unit.name,
                ARG_CURRENT_KG to (goalKg ?: Double.NaN),
                ARG_GOAL to true,
                ARG_SHOW_GOAL_LINE to showGoalLine,
            )
        }

        private fun unitLabel(unit: WeightUnit) = when (unit) {
            WeightUnit.KILOGRAM -> R.string.unit_kg
            WeightUnit.POUND -> R.string.unit_lb
            WeightUnit.STONE -> R.string.unit_st
            WeightUnit.JIN -> R.string.unit_jin
        }
    }
}
