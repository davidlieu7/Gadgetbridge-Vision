package nodomain.freeyourgadget.gadgetbridge.activities.debug

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.GB
import java.util.UUID

abstract class AbstractDebugFragment : AbstractPreferenceFragment() {
    protected fun runOnDebugDevices(title: String? = null, forceDialog: Boolean = false, function: (GBDevice) -> Unit) {
        val selectedDevices = GBApplication.app().deviceManager.selectedDevices
            .filter { it.isInitialized }
            .sortedBy { it.aliasOrName }

        if (selectedDevices.isEmpty()) {
            GB.toast(
                requireContext(),
                requireContext().getString(R.string.info_no_devices_connected),
                Toast.LENGTH_LONG,
                GB.ERROR
            )
            return
        }

        if (selectedDevices.size > 1 || forceDialog) {
            val deviceNames = selectedDevices.map { it.aliasOrName }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title ?: getString(R.string.choose_device))
                .setItems(deviceNames) { _, which ->
                    function(selectedDevices[which])
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            function(selectedDevices[0])
        }
    }

    protected fun onClick(prefKey: String, function: () -> Unit) {
        findPreference<Preference>(prefKey)!!.setOnPreferenceClickListener {
            function.invoke()
            return@setOnPreferenceClickListener true
        }
    }

    protected fun setInputTypeNumber(prefKey: String) {
        findPreference<EditTextPreference>(prefKey)!!.setOnBindEditTextListener {
            it.setInputType(InputType.TYPE_CLASS_NUMBER)
        }
    }

    protected fun setListPreferenceEntries(prefKey: String, entries: Array<String>) {
        val statePref = findPreference<ListPreference>(prefKey)!!
        statePref.entries = entries
        statePref.entryValues = entries
    }

    protected fun removeDynamicPrefs(group: PreferenceGroup) {
        var i = 0
        while (i < group.preferenceCount) {
            val preference: Preference = group.getPreference(i)
            if (preference.key.startsWith(PREF_DYNAMIC_PREFIX)) {
                group.removePreference(preference)
                i--
            }
            i++
        }
    }

    protected fun addDynamicPref(
        group: PreferenceGroup? = preferenceScreen,
        title: String,
        summary: String = "",
        icon: Int = 0,
        onClickFunction: (() -> Unit)? = null
    ): Preference {
        val pref = Preference(requireContext())
        pref.setKey("${PREF_DYNAMIC_PREFIX}_${UUID.randomUUID()}")
        pref.isPersistent = false
        pref.isSelectable = onClickFunction != null
        pref.title = title
        pref.summary = summary
        if (icon != 0) {
            pref.setIcon(icon)
        } else if (!title.isEmpty()) {
            pref.isIconSpaceReserved = false
        }
        if (onClickFunction != null) {
            pref.setOnPreferenceClickListener {
                onClickFunction.invoke()
                return@setOnPreferenceClickListener true
            }
        }

        group?.addPreference(pref)

        return pref
    }

    protected fun addDynamicDevicePref(
        group: PreferenceGroup?,
        title: String,
        summary: String,
        @DrawableRes icon: Int,
        connected: Boolean,
        onClickFunction: () -> Unit,
    ): Preference {
        val pref = addDynamicPref(group, title, summary, onClickFunction = onClickFunction)
        pref.layoutResource = R.layout.preference_debug_device
        pref.icon = deviceIcon(icon, connected)
        return pref
    }

    private fun deviceIcon(@DrawableRes iconRes: Int, connected: Boolean): Drawable {
        val iconContext = ContextThemeWrapper(
            requireContext(),
            if (connected) R.style.ThemeOverlay_App_DeviceCardIcon_Connected else R.style.ThemeOverlay_App_DeviceCardIcon
        )
        val tile = AppCompatResources.getDrawable(iconContext, R.drawable.device_card_icon_bg)!!
        val icon = AppCompatResources.getDrawable(iconContext, iconRes)!!
        if (!connected) {
            icon.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }

        val tileSize = resources.getDimensionPixelSize(R.dimen.debug_device_icon_size)
        val iconInset = resources.getDimensionPixelSize(R.dimen.debug_device_icon_inset)
        return LayerDrawable(arrayOf(tile, icon)).apply {
            setLayerSize(0, tileSize, tileSize)
            setLayerInset(1, iconInset, iconInset, 0, 0)
            setLayerSize(1, tileSize - iconInset, tileSize - iconInset)
        }
    }

    protected fun addDynamicCategory(title: String) {
        val pref = PreferenceCategory(requireContext())
        pref.key = "${PREF_DYNAMIC_PREFIX}_category_${UUID.randomUUID()})"
        pref.title = title
        pref.isPersistent = false
        pref.isIconSpaceReserved = false
        preferenceScreen?.addPreference(pref)
    }

    protected fun addDynamicCheckbox(
        group: PreferenceGroup? = preferenceScreen,
        title: String,
        summary: String = "",
        icon: Int = 0,
        checked: Boolean,
    ): Preference {
        val pref = SwitchPreferenceCompat(requireContext())
        pref.setKey("${PREF_DYNAMIC_PREFIX}_${UUID.randomUUID()}")
        pref.layoutResource = R.layout.preference_checkbox
        pref.isPersistent = false
        pref.isSelectable = false
        pref.title = title
        pref.summary = summary
        if (icon != 0) {
            pref.setIcon(icon)
        } else if (!title.isEmpty()) {
            pref.isIconSpaceReserved = false
        }
        pref.isChecked = checked

        group?.addPreference(pref)

        return pref
    }

    protected fun goTo(fragment: AbstractDebugFragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        protected const val PREF_DYNAMIC_PREFIX: String = "pref_debug_dynamic"
    }
}
