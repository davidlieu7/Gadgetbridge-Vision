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
package nodomain.freeyourgadget.gadgetbridge.activities

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.jaredrummler.android.colorpicker.MaterialColorPickerDialog
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.theme.CustomTheme
import nodomain.freeyourgadget.gadgetbridge.util.theme.CustomThemes

/**
 * The easy, in-app way to make a theme: a name, light/dark, the few defining colours, and the image slots with an
 * opacity each. The full set of colours and the chart colours stay in theme.json (the power path), so the two ways
 * complement each other. "Save" writes the theme and applies it right away.
 */
class ThemeEditorActivity : AbstractGBActivity() {
    // The colours the editor exposes; the other required colours are derived, the rest fall back to the base theme
    private val colorRows = linkedMapOf(
        CustomTheme.COLOR_PRIMARY to R.string.theme_editor_color_accent,
        CustomTheme.COLOR_BACKGROUND to R.string.theme_editor_color_background,
        CustomTheme.COLOR_SURFACE to R.string.theme_editor_color_surface,
    )
    private val slotRows = linkedMapOf(
        CustomTheme.SLOT_BACKGROUND to R.string.theme_editor_slot_background,
        CustomTheme.SLOT_DRAWER_HEADER to R.string.theme_editor_slot_drawer_header,
        CustomTheme.SLOT_DASHBOARD_HERO to R.string.theme_editor_slot_hero,
    )

    private val editedColors = HashMap<String, Int>()
    private val opacity = HashMap<String, Float>()
    private val newImages = HashMap<String, ByteArray>()
    private val hasImage = HashMap<String, Boolean>()
    // A slot whose image changes with the time of day (period -> image bytes), kept so editing doesn't drop it
    private val timedSlots = HashMap<String, LinkedHashMap<String, ByteArray>>()
    private val swatches = HashMap<String, View>()
    private val previews = HashMap<String, ImageView>()

    private var pendingSlot: String? = null
    private var editingId: String? = null

    private lateinit var nameEdit: TextInputEditText
    private lateinit var darkSwitch: MaterialSwitch

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = pendingSlot ?: return@registerForActivityResult
        if (uri == null) {
            return@registerForActivityResult
        }
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                GB.toast(getString(R.string.theme_editor_image_failed), Toast.LENGTH_LONG, GB.WARN)
                return@registerForActivityResult
            }
            if (bytes.size > MAX_IMAGE_BYTES) {
                GB.toast(getString(R.string.theme_editor_image_too_large), Toast.LENGTH_LONG, GB.WARN)
                return@registerForActivityResult
            }
            newImages[slot] = bytes
            hasImage[slot] = true
            timedSlots.remove(slot) // a hand-picked image replaces a time-of-day cycle
            showPreview(slot, bytes)
        } catch (e: Exception) {
            GB.toast(getString(R.string.theme_editor_image_failed), Toast.LENGTH_LONG, GB.WARN, e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_editor)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        nameEdit = findViewById(R.id.theme_editor_name)
        darkSwitch = findViewById(R.id.theme_editor_dark)

        // Defaults for a new theme, from the current app theme
        editedColors[CustomTheme.COLOR_PRIMARY] = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF3F51B5.toInt())
        editedColors[CustomTheme.COLOR_BACKGROUND] = MaterialColors.getColor(this, android.R.attr.colorBackground, 0xFF121212.toInt())
        editedColors[CustomTheme.COLOR_SURFACE] = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFF1E1E1E.toInt())
        darkSwitch.isChecked = GBApplication.isDarkThemeEnabled()

        editingId = intent.getStringExtra(EXTRA_THEME_ID)
        editingId?.let { loadForEditing(it) }

        buildColorRows()
        buildSlotRows()
        findViewById<MaterialButton>(R.id.theme_editor_save).setOnClickListener { save() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadForEditing(id: String) {
        val theme = CustomThemes.getTheme(this, id) ?: return
        nameEdit.setText(theme.name)
        darkSwitch.isChecked = theme.isDark
        for (key in colorRows.keys) {
            theme.colors[key]?.let { editedColors[key] = it }
        }
        for (slot in slotRows.keys) {
            opacity[slot] = theme.getSlotOpacity(slot)
            val times = theme.getSlotTimes(slot)
            if (times != null) {
                // A time-of-day cycle: keep every period's image so editing doesn't drop the banner
                val periods = LinkedHashMap<String, ByteArray>()
                for (period in times.keys) {
                    CustomThemes.readSlotImage(this, id, "${slot}_$period")?.let { periods[period] = it }
                }
                if (periods.isNotEmpty()) {
                    timedSlots[slot] = periods
                    hasImage[slot] = true
                }
            } else if (theme.slots.containsKey(slot)) {
                // Keep the existing image so saving (even under a new name) does not lose it
                CustomThemes.readSlotImage(this, id, slot)?.let {
                    newImages[slot] = it
                    hasImage[slot] = true
                }
            }
        }
    }

    private fun buildColorRows() {
        val container = findViewById<LinearLayout>(R.id.theme_editor_colors)
        for ((key, label) in colorRows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
            }
            row.addView(TextView(this).apply {
                text = getString(label)
                textSize = 16f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener { openColorPicker(key) }
            }
            applySwatch(swatch, editedColors.getValue(key))
            swatches[key] = swatch
            row.addView(swatch)
            container.addView(row)
        }
    }

    private fun buildSlotRows() {
        val container = findViewById<LinearLayout>(R.id.theme_editor_images)
        for ((slot, label) in slotRows) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text = getString(label)
                textSize = 16f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.theme_editor_choose_image)
                setOnClickListener {
                    pendingSlot = slot
                    pickImage.launch(arrayOf("image/*"))
                }
            })
            col.addView(header)

            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)).apply {
                    topMargin = dp(4)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
            }
            previews[slot] = preview
            col.addView(preview)
            (newImages[slot] ?: timedSlots[slot]?.let { it["day"] ?: it.values.firstOrNull() })
                ?.let { showPreview(slot, it) }

            val opacityRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            opacityRow.addView(TextView(this).apply {
                text = getString(R.string.theme_editor_opacity)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
            })
            val slider = Slider(this).apply {
                valueFrom = 0f
                valueTo = 100f
                stepSize = 1f
                value = (opacity[slot] ?: 1f) * 100f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addOnChangeListener { _, v, _ -> opacity[slot] = v / 100f }
            }
            opacityRow.addView(slider)
            col.addView(opacityRow)
            container.addView(col)
        }
    }

    private fun showPreview(slot: String, bytes: ByteArray) {
        val preview = previews[slot] ?: return
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
            preview.setImageBitmap(it)
            preview.visibility = View.VISIBLE
        }
    }

    private fun applySwatch(view: View, color: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(color)
            setStroke(dp(1), 0x33888888)
        }
    }

    private fun openColorPicker(key: String) {
        val builder = ColorPickerDialog.newBuilder()
            .setColor(editedColors.getValue(key))
            .setShowAlphaSlider(false)
            .setAllowCustom(true)
            .setAllowPresets(true)
        val dialog = MaterialColorPickerDialog.create(builder)
        dialog.setColorPickerDialogListener(object : ColorPickerDialogListener {
            override fun onColorSelected(dialogId: Int, color: Int) {
                val opaque = color or (0xFF shl 24)
                editedColors[key] = opaque
                swatches[key]?.let { applySwatch(it, opaque) }
            }

            override fun onDialogDismissed(dialogId: Int) {}
        })
        dialog.show(supportFragmentManager, "theme_color_$key")
    }

    private fun save() {
        val name = nameEdit.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            GB.toast(getString(R.string.theme_editor_name_required), Toast.LENGTH_LONG, GB.WARN)
            return
        }
        val dark = darkSwitch.isChecked
        val colors = deriveColors(
            editedColors.getValue(CustomTheme.COLOR_PRIMARY),
            editedColors.getValue(CustomTheme.COLOR_BACKGROUND),
            editedColors.getValue(CustomTheme.COLOR_SURFACE),
            dark,
        )
        val slots = LinkedHashMap<String, String>()
        val opacityOut = LinkedHashMap<String, Float>()
        val images = HashMap<String, ByteArray>()
        val slotTimesOut = LinkedHashMap<String, Map<String, String>>()
        for (slot in slotRows.keys) {
            if (hasImage[slot] != true) continue
            opacityOut[slot] = opacity[slot] ?: 1f
            val times = timedSlots[slot]
            val periodImages = times?.values?.toList()
            // A cycle is only worth keeping with more than one distinct image; otherwise it's a plain banner
            val isCycle = periodImages != null && periodImages.size > 1 &&
                periodImages.any { !it.contentEquals(periodImages[0]) }
            if (isCycle) {
                val periodPaths = LinkedHashMap<String, String>()
                for ((period, bytes) in times!!) {
                    periodPaths[period] = "${slot}_$period.png"
                    images["${slot}_$period"] = bytes
                }
                slotTimesOut[slot] = periodPaths
                slots[slot] = periodPaths["day"] ?: periodPaths.values.first()
            } else {
                slots[slot] = "$slot.png"
                (newImages[slot] ?: times?.let { it["day"] ?: it.values.firstOrNull() })?.let { images[slot] = it }
            }
        }
        val theme = CustomTheme.of(name, "", dark, colors, slots, opacityOut, slotTimesOut)
        try {
            CustomThemes.saveTheme(this, theme, images)
        } catch (e: Exception) {
            GB.toast(getString(R.string.theme_editor_save_failed, e.message), Toast.LENGTH_LONG, GB.ERROR, e)
            return
        }
        // Apply & see: make it the active theme and re-theme the open activities
        GBApplication.getPrefs().preferences.edit().putString(CustomThemes.PREF_CUSTOM_THEME, theme.id).apply()
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(GBApplication.ACTION_THEME_CHANGE))
        GB.toast(getString(R.string.theme_editor_saved, theme.name), Toast.LENGTH_SHORT, GB.INFO)
        finish()
    }

    private fun deriveColors(primary: Int, background: Int, surface: Int, dark: Boolean): Map<String, Int> {
        fun opaque(c: Int) = c or (0xFF shl 24)
        fun contrastOn(c: Int) = if (ColorUtils.calculateLuminance(c) > 0.5) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
        val p = opaque(primary)
        val bg = opaque(background)
        val sf = opaque(surface)
        val primaryContainer = ColorUtils.blendARGB(p, sf, if (dark) 0.55f else 0.78f)
        return linkedMapOf(
            CustomTheme.COLOR_PRIMARY to p,
            CustomTheme.COLOR_ON_PRIMARY to contrastOn(p),
            CustomTheme.COLOR_PRIMARY_CONTAINER to primaryContainer,
            CustomTheme.COLOR_ON_PRIMARY_CONTAINER to contrastOn(primaryContainer),
            CustomTheme.COLOR_BACKGROUND to bg,
            CustomTheme.COLOR_SURFACE to sf,
            CustomTheme.COLOR_ON_SURFACE to if (dark) 0xFFE6E6E6.toInt() else 0xFF1A1A1A.toInt(),
            CustomTheme.COLOR_ON_SURFACE_VARIANT to if (dark) 0xFFB0B0B0.toInt() else 0xFF5A5A5A.toInt(),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_THEME_ID = "theme_id"
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    }
}
