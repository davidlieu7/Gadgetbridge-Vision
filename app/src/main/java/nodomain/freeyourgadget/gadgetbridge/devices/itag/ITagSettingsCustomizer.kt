package nodomain.freeyourgadget.gadgetbridge.devices.itag

import android.os.Parcel
import android.os.Parcelable.Creator
import androidx.preference.Preference
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler
import nodomain.freeyourgadget.gadgetbridge.service.devices.itag.ITagSupport
import nodomain.freeyourgadget.gadgetbridge.util.Prefs
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ITagSettingsCustomizer : DeviceSpecificSettingsCustomizer {

    val log: Logger = LoggerFactory.getLogger(ITagSupport::class.java)

    @JvmField
    val CREATOR: Creator<ITagSettingsCustomizer> = object : Creator<ITagSettingsCustomizer> {
        override fun createFromParcel(`in`: Parcel?): ITagSettingsCustomizer {
            return ITagSettingsCustomizer()
        }

        override fun newArray(size: Int): Array<ITagSettingsCustomizer?> {
            return arrayOfNulls(size)
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
    }

    override fun onPreferenceChange(
        preference: Preference?,
        handler: DeviceSpecificSettingsHandler?
    ) {
    }

    override fun customizeSettings(
        handler: DeviceSpecificSettingsHandler?,
        prefs: Prefs?,
        rootKey: String?
    ) {
        if (handler == null) {
            return
        }

        val tryPrefKey = ITagConstants.PREF_ITAG_ALERT_LINK_LOSS
        val tryPref = handler.findPreference<Preference?>(tryPrefKey)
        tryPref?.setOnPreferenceClickListener { _: Preference ->
            GBApplication.deviceService(handler.device).onSendConfiguration(tryPrefKey)
            true
        }

    }

    override fun getPreferenceKeysWithSummary(): Set<String?> {
        return HashSet()
    }
}