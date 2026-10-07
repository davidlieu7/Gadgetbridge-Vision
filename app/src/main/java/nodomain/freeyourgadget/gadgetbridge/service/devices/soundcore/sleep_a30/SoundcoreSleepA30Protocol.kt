/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.*
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.TapFunction
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1

open class SoundcoreSleepA30Protocol(device: GBDevice?) : SoundcoreProtocolImplV1(device) {
    private val currentFunctions = intArrayOf(-1, -1, -1, -1)

    protected override fun getBatteryOffset(): Int = BATTERY_OFFSET

    protected override fun decodeDeviceInfo(payload: ByteArray?): Array<GBDeviceEvent> {
        if (payload == null || payload.size < 51) return emptyArray()
        // Sleep A30's version header starts two bytes earlier than Liberty's.
        decodeTapControls(payload.copyOfRange(45, 51))
        val batteries = handleBatteryInfo(byteArrayOf(payload[2], payload[3]), batteryMultiplier, batteryOffset)
        val version = buildVersionInfo(
            readString(payload, 4, 5),
            readString(payload, 36, 5), // charging case, fw2
            readString(payload, 14, 16)
        )
        val preferences = GBDeviceEventUpdatePreferences()
        decodeBooleanPreference(payload, 65, PREF_SOUNDCORE_LISTENING_MODE, preferences, "0", "1")
        decodeBooleanPreference(payload, 69, PREF_SOUNDCORE_BATTERY_LOW_TONE, preferences)
        decodeBooleanPreference(payload, 137, PREF_SOUNDCORE_POWER_OFF_TONE, preferences)
        decodeBooleanPreference(payload, 138, PREF_SOUNDCORE_MODE_TONE, preferences)
        decodeBooleanPreference(payload, 141, PREF_SOUNDCORE_CALLS_ENABLED, preferences)
        decodeBooleanPreference(payload, 143, PREF_SOUNDCORE_SNORE_MASKING, preferences)
        decodeBooleanPreference(payload, 147, PREF_SOUNDCORE_ANC_TONE, preferences)
        return arrayOf(batteries[0], batteries[1], version, preferences)
    }

    private fun decodeBooleanPreference(
        payload: ByteArray,
        offset: Int,
        key: String,
        preferences: GBDeviceEventUpdatePreferences,
        falseValue: Any = false,
        trueValue: Any = true
    ) {
        if (payload.size > offset && (payload[offset] in 0..1)) {
            preferences.withPreference(key, if (payload[offset] == 1.toByte()) trueValue else falseValue)
        }
    }

    // header, four control slots, trailer.
    private fun decodeTapControls(payload: ByteArray?) {
        if (payload == null || payload.size != 6 || payload[0] != 6.toByte() || payload[5] != 0xff.toByte()) return
        val editor = devicePrefs.preferences.edit()
        for (i in currentFunctions.indices) {
            currentFunctions[i] = payload[i + 1].toInt() and 0x0f
            TapFunction.entries.firstOrNull { it.code == currentFunctions[i] }?.let {
                editor.putString(TAP_PREFERENCES[i], it.name)
            }
        }
        editor.apply()
    }

    protected override fun decodeAcknowledgement(command: Short, payload: ByteArray?): Array<GBDeviceEvent> {
        if (command == CMD_SET_TAP_CONTROLS_FUNCTION || command == CMD_RESET_TAP_CONTROLS) {
            decodeTapControls(payload)
        }
        return emptyArray()
    }

    protected override fun decodeCommand(command: Short, payload: ByteArray?): Array<GBDeviceEvent> {
        if (payload == null) return emptyArray()
        var result: Array<GBDeviceEvent> = emptyArray()
        when (command) {
            CMD_NOTIFY_LISTENING_MODE -> {
                if (payload.size == 1 && payload[0] in 0..1) {
                    result = arrayOf(listeningModeEvent(payload[0]))
                }
            }
            CMD_NOTIFY_LOCAL_AUDIO -> {
                if (payload.size == 11) {
                    val preferences = GBDeviceEventUpdatePreferences()
                    if (payload[9] in 0..1) {
                        preferences.withPreference(PREF_SOUNDCORE_LOCAL_PLAYBACK, payload[9] == 1.toByte())
                    }
                    val volume = payload[10].toInt() and 0xff
                    if (volume <= 65) {
                        preferences.withPreference(PREF_SOUNDCORE_LOCAL_VOLUME, volume)
                    }
                    if (preferences.preferences.isNotEmpty()) {
                        result = arrayOf(preferences)
                    }
                }
            }
            CMD_NOTIFY_ANC_ENABLED, CMD_SET_ANC_ENABLED -> {
                if (payload.size == 1 && payload[0] in 0..1) {
                    result = arrayOf(GBDeviceEventUpdatePreferences(
                        PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL,
                        if (payload[0] == 1.toByte()) "noise_cancelling" else "off"
                    ))
                }
            }
        }
        // Empty setter responses are acknowledgements, not settings readback.
        return result
    }

    private fun listeningModeEvent(mode: Byte) =
        GBDeviceEventUpdatePreferences(PREF_SOUNDCORE_LISTENING_MODE, if (mode == 1.toByte()) "1" else "0")

    open fun encodeTapFunction(right: Boolean, triple: Boolean, previous: Int, function: TapFunction): ByteArray =
        encodeControlFunction(right, (if (triple) 5 else 0).toByte(),
            ((previous shl 4) or function.code).toByte())

    override fun encodeSendConfiguration(config: String): ByteArray? {
        val prefs = devicePrefs
        return when (config) {
            ACTION_TOGGLE_LOCAL_PLAYBACK -> {
                if (prefs.getString(PREF_SOUNDCORE_LISTENING_MODE, "0") != "1") return null
                encodeLocalPlayback(!prefs.getBoolean(PREF_SOUNDCORE_LOCAL_PLAYBACK, false))
            }
            PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL -> encodeAncEnabled(prefs.getString(config, "off") == "noise_cancelling")
            PREF_SOUNDCORE_LISTENING_MODE -> encodeListeningMode(prefs.getString(config, "0") == "1")
            PREF_SOUNDCORE_DEFAULT_LISTENING_MODE -> encodeBooleanCommand(CMD_SET_DEFAULT_LISTENING_MODE, prefs.getString(config, "0") == "1")
            PREF_SOUNDCORE_LOCAL_PLAYBACK -> encodeLocalPlayback(prefs.getBoolean(config, false))
            PREF_SOUNDCORE_LOCAL_VOLUME -> encodeLocalVolume(prefs.getInt(config, 0))
            PREF_SOUNDCORE_SLEEP_AUDIO -> encodeSleepAudio(prefs.getString(config, "keep"))
            PREF_SOUNDCORE_SNORE_MASKING -> encodeBooleanCommand(CMD_SET_SNORE_MASKING, prefs.getBoolean(config, false))
            PREF_SOUNDCORE_CONTROL_TOUCH_DISABLED -> encodeBooleanCommand(CMD_SET_TAP_CONTROLS_ENABLE, prefs.getBoolean(config, false))
            PREF_SOUNDCORE_BATTERY_LOW_TONE -> encodeBooleanCommand(CMD_SET_BATTERY_LOW_TONE, prefs.getBoolean(config, false))
            PREF_SOUNDCORE_CALLS_ENABLED -> encodeBooleanCommand(CMD_SET_CALLS_ENABLED, prefs.getBoolean(config, false))
            PREF_SOUNDCORE_POWER_OFF_TONE -> encodeBooleanCommand(CMD_SET_POWER_OFF_TONE, prefs.getBoolean(config, false))
            PREF_SOUNDCORE_ANC_TONE -> encodeBooleanCommand(CMD_SET_ANC_TONE, prefs.getBoolean(config, false))
            PREF_SOUNDCORE_MODE_TONE -> encodeBooleanCommand(CMD_SET_MODE_TONE, prefs.getBoolean(config, false))
            PREF_SOUNDCORE_CONTROL_RESET -> encodeRequest(CMD_RESET_TAP_CONTROLS)
            PREF_SOUNDCORE_FIND_LEFT_EARBUD, PREF_SOUNDCORE_FIND_RIGHT_EARBUD -> encodeCommand(
                CMD_SET_FIND_DEVICE,
                byteArrayOf(
                    encodeBoolean(prefs.getString(PREF_SOUNDCORE_FIND_LEFT_EARBUD, "0") == "1"),
                    encodeBoolean(prefs.getString(PREF_SOUNDCORE_FIND_RIGHT_EARBUD, "0") == "1"), 0
                )
            )
            PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT, PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT,
            PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_LEFT, PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_RIGHT -> {
                val function = try {
                    TapFunction.valueOf(prefs.getString(config, "NONE"))
                } catch (_: IllegalArgumentException) {
                    return null
                }
                val right = config.endsWith("right")
                val triple = config.contains("triple")
                val previous = currentFunctions[(if (triple) 2 else 0) + (if (right) 1 else 0)]
                // Do not build a transition from a locally selected but unconfirmed function.
                if (previous < 0) return null
                encodeTapFunction(right, triple, previous, function)
            }
            else -> super.encodeSendConfiguration(config)
        }
    }

    companion object {
        const val ACTION_TOGGLE_LOCAL_PLAYBACK = "soundcore_toggle_local_playback"
        private const val BATTERY_OFFSET = 1

        private val TAP_PREFERENCES = arrayOf(
            PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT,
            PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT,
            PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_LEFT,
            PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_RIGHT
        )
    }
}
