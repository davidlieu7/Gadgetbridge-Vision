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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.*;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.AmbientSoundMode;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.SoundcoreEarbudsCoordinator.TapFunction;
import nodomain.freeyourgadget.gadgetbridge.devices.soundcore.sleep_a30.SoundcoreSleepA30Coordinator.AudioSource;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventSendBytes;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcoreSleepHistory;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.SoundcoreSleepHistoryEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.protocol.impl.v1.SoundcoreProtocolImplV1;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class SoundcoreSleepA30Protocol extends SoundcoreProtocolImplV1 {
    private static final Logger LOG = LoggerFactory.getLogger(SoundcoreSleepA30Protocol.class);
    private static final short CMD_SET_SLEEP_TRACKING = (short) 0x8415;
    private static final short CMD_ACK_SLEEP_SESSIONS = (short) 0x8915;
    private static final short CMD_ACK_SLEEP_STAGES = (short) 0x8315;

    public static final String ACTION_TOGGLE_LOCAL_PLAYBACK = "soundcore_toggle_local_playback";
    private static final Set<Integer> AUTO_STOP_PRESETS = Set.of(10, 20, 30, 60, 90);
    private static final String[] TAP_PREFERENCES = {
            PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT,
            PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT,
            PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_LEFT,
            PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_RIGHT
    };

    private SoundcoreSleepHistory.Pages stagePages;
    private SoundcoreSleepHistory.Pages sessionPages;
    private SoundcoreSleepHistory.Pages snorePages;
    private SoundcoreSleepHistory.Report pendingSleepReport;
    private Long sessionAcknowledgementTimestamp;
    private Long stageAcknowledgementTimestamp;
    private boolean historyReadyForAcknowledgement;

    private List<AlarmRequest> pendingAlarms;
    private final ArrayDeque<byte[]> alarmCommands = new ArrayDeque<>();
    private boolean awaitingAlarmWrite;
    private boolean awaitingAlarmRead;

    private record AlarmRequest(int id, boolean unused, boolean enabled, int minutes,
                                int repetition, boolean snooze) {}

    private final int[] currentFunctions = {-1, -1, -1, -1};
    private Byte localAudioSelector;

    public SoundcoreSleepA30Protocol(@Nullable final GBDevice device) {
        super(device);
    }

    // TODO: Implement encodeSetTime() using command 0xA601 once its timestamp timezone
    // semantics and trailing six-byte field are understood (10-byte payload, LE32 seconds first).

    @Override
    public byte[] encodeDeviceInfoRequest() {
        localAudioSelector = null;
        return super.encodeDeviceInfoRequest();
    }

    @Override
    @Nullable
    public byte[] encodeLocalPlayback(final boolean playing) {
        return localAudioSelector == null ? null : encodeCommand(CMD_SET_LOCAL_PLAYBACK,
                new byte[]{localAudioSelector, encodeBoolean(playing)});
    }

    @Override
    @Nullable
    public GBDeviceEvent[] decodeResponse(@Nullable final byte[] responseData) {
        if (responseData != null && responseData.length >= 7) {
            final short command = (short) BLETypeConversions.toUint16(responseData, 5);
            if (command == CMD_GET_SLEEP_STAGES || command == CMD_GET_SLEEP_SESSIONS ||
                    command == SoundcoreSleepHistory.CMD_SNORES) {
                if (responseData.length < 10) return null;
                int checksum = 0;
                for (int i = 0; i < responseData.length - 1; i++) checksum += responseData[i];
                if (BLETypeConversions.toUint16(responseData, 7) != responseData.length ||
                        (checksum & 255) != (responseData[responseData.length - 1] & 255)) {
                    LOG.warn("Invalid Sleep A30 history packet length or checksum");
                    return null;
                }
            }
        }
        return super.decodeResponse(responseData);
    }

    @Override
    protected GBDeviceEvent[] decodeDeviceInfo(final byte[] payload) {
        if (payload == null || payload.length < 51) return new GBDeviceEvent[0];
        // Sleep A30's version header starts two bytes earlier than Liberty's.
        decodeTapControls(Arrays.copyOfRange(payload, 45, 51));
        final GBDeviceEvent[] batteries = handleBatteryInfo(new byte[]{payload[2], payload[3]});
        final GBDeviceEventVersionInfo version = buildVersionInfo(
                readString(payload, 4, 5), readString(payload, 36, 5), // charging case, fw2
                readString(payload, 14, 16));
        final GBDeviceEventUpdatePreferences preferences = new GBDeviceEventUpdatePreferences();
        final String audioSource = payload.length > 65 ? AudioSource.fromValue(payload[65]) : null;
        if (audioSource != null) preferences.withPreference(PREF_SOUNDCORE_LISTENING_MODE, audioSource);
        decodeBooleanPreference(payload, 69, PREF_SOUNDCORE_BATTERY_LOW_TONE, preferences);
        localAudioSelector = payload[72];
        decodeBooleanPreference(payload, 73, PREF_SOUNDCORE_LOCAL_PLAYBACK, preferences);
        decodeLocalVolume(payload[71], preferences);
        decodeBooleanPreference(payload, 135, PREF_SLEEP_TRACKING, preferences);
        decodeBooleanPreference(payload, 137, PREF_SOUNDCORE_POWER_OFF_TONE, preferences);
        decodeBooleanPreference(payload, 138, PREF_SOUNDCORE_MODE_TONE, preferences);
        preferences.withPreference(PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL,
                (payload[140] == 1 ? AmbientSoundMode.NOISE_CANCELLING : AmbientSoundMode.OFF).toString());
        decodeBooleanPreference(payload, 141, PREF_SOUNDCORE_CALLS_ENABLED, preferences);
        decodeBooleanPreference(payload, 143, PREF_SOUNDCORE_SNORE_MASKING, preferences);
        decodeBooleanPreference(payload, 147, PREF_SOUNDCORE_ANC_TONE, preferences);
        if (version.fwVersion == null || version.fwVersion.chars().allMatch(c ->
                c == 0 || Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            return new GBDeviceEvent[]{batteries[0], batteries[1], version, preferences};
        }
        return new GBDeviceEvent[]{batteries[0], batteries[1], version, preferences,
                new GBDeviceEventSendBytes(encodeRequest(CMD_GET_SLEEP_SETTINGS)),
                new GBDeviceEventSendBytes(encodeRequest(CMD_NOTIFY_LISTENING_MODE))};
    }

    private void decodeLocalVolume(final byte value, final GBDeviceEventUpdatePreferences preferences) {
        preferences.withPreference(PREF_SOUNDCORE_LOCAL_VOLUME, value & 255);
    }

    private void decodeBooleanPreference(final byte[] payload, final int offset, final String key,
                                         final GBDeviceEventUpdatePreferences preferences) {
        if (payload.length > offset && (payload[offset] == 0 || payload[offset] == 1)) {
            preferences.withPreference(key, payload[offset] == 1);
        }
    }

    // Header, four control slots, trailer.
    private void decodeTapControls(final byte[] payload) {
        if (payload == null || payload.length != 6 || payload[0] != 6 || payload[5] != (byte) 0xff) return;
        final SharedPreferences.Editor editor = getDevicePrefs().getPreferences().edit();
        for (int i = 0; i < currentFunctions.length; i++) {
            currentFunctions[i] = payload[i + 1] & 0x0f;
            for (final TapFunction function : TapFunction.values()) {
                if (function.getCode() == currentFunctions[i]) {
                    editor.putString(TAP_PREFERENCES[i], function.name());
                    break;
                }
            }
        }
        editor.apply();
    }

    @Override
    protected GBDeviceEvent[] decodeAcknowledgement(final short command, final byte[] payload) {
        if (command == CMD_SET_TAP_CONTROLS_FUNCTION || command == CMD_RESET_TAP_CONTROLS) {
            decodeTapControls(payload);
        }
        return new GBDeviceEvent[0];
    }

    @Override
    protected GBDeviceEvent[] decodeCommand(final short command, final byte[] payload) {
        if (payload == null) return new GBDeviceEvent[0];
        switch (command) {
            case CMD_GET_SLEEP_STAGES:
            case CMD_GET_SLEEP_SESSIONS:
            case SoundcoreSleepHistory.CMD_SNORES:
                return decodeSleepHistory(command, payload);
            case CMD_GET_ALARMS:
                return decodeAlarms(payload);
            case CMD_GET_SLEEP_SETTINGS:
                return decodeAutoStop(payload);
            case CMD_NOTIFY_LISTENING_MODE:
                if (payload.length == 1) {
                    final String source = AudioSource.fromValue(payload[0]);
                    if (source != null) {
                        return new GBDeviceEvent[]{new GBDeviceEventUpdatePreferences(PREF_SOUNDCORE_LISTENING_MODE, source)};
                    }
                }
                break;
            case CMD_NOTIFY_LOCAL_AUDIO:
                if (payload.length == 11) {
                    localAudioSelector = payload[0];
                    final GBDeviceEventUpdatePreferences preferences = new GBDeviceEventUpdatePreferences();
                    decodeBooleanPreference(payload, 9, PREF_SOUNDCORE_LOCAL_PLAYBACK, preferences);
                    decodeLocalVolume(payload[10], preferences);
                    return new GBDeviceEvent[]{preferences};
                }
                break;
            case CMD_NOTIFY_ANC_ENABLED:
            case CMD_SET_ANC_ENABLED:
                if (payload.length == 1 && (payload[0] == 0 || payload[0] == 1)) {
                    return new GBDeviceEvent[]{new GBDeviceEventUpdatePreferences(
                            PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL,
                            (payload[0] == 1 ? AmbientSoundMode.NOISE_CANCELLING : AmbientSoundMode.OFF).toString())};
                }
                break;
        }
        // Empty setter responses are acknowledgements, not settings readback.
        return new GBDeviceEvent[0];
    }

    @Override
    @Nullable
    public synchronized byte[] encodeFetchRecordedData(final int dataTypes) {
        if ((dataTypes & (RecordedDataTypes.TYPE_ACTIVITY | RecordedDataTypes.TYPE_SLEEP)) == 0) return null;
        cancelSleepHistory();
        stagePages = new SoundcoreSleepHistory.Pages(7, Collections.emptySet());
        return encodeRequest(CMD_GET_SLEEP_STAGES);
    }

    public synchronized void cancelSleepHistory() {
        stagePages = null;
        sessionPages = null;
        snorePages = null;
        pendingSleepReport = null;
        sessionAcknowledgementTimestamp = null;
        stageAcknowledgementTimestamp = null;
        historyReadyForAcknowledgement = false;
    }

    /** Called only after the complete snapshot has been committed to the database. */
    @Nullable
    public synchronized byte[] encodeSleepHistoryAcknowledgements(final boolean keepData) {
        if (!historyReadyForAcknowledgement) return null;
        final Long sessionTimestamp = sessionAcknowledgementTimestamp;
        final Long stageTimestamp = stageAcknowledgementTimestamp;
        cancelSleepHistory();
        if (keepData) return null;
        // Preserve each stream's raw boundary, not a merged summary's start or end.
        final byte[] sessions = sessionTimestamp == null ? new byte[0] : encodeCommand(CMD_ACK_SLEEP_SESSIONS,
                ByteBuffer.allocate(5).put((byte) 0).put(BLETypeConversions.fromUint32(sessionTimestamp.intValue())).array());
        final byte[] stages = stageTimestamp == null ? new byte[0] : encodeCommand(CMD_ACK_SLEEP_STAGES,
                ByteBuffer.allocate(5).put((byte) 1).put(BLETypeConversions.fromUint32(stageTimestamp.intValue())).array());
        return sessions.length + stages.length == 0 ? null : concatenate(sessions, stages);
    }

    private synchronized GBDeviceEvent[] decodeSleepHistory(final short command, final byte[] payload) {
        final SoundcoreSleepHistory.Pages stages = stagePages;
        if (stages == null) return new GBDeviceEvent[0];
        // Once stages are complete, additional stage streams must not replace that snapshot.
        if (command == CMD_GET_SLEEP_STAGES && sessionPages != null) return new GBDeviceEvent[0];
        if (command == CMD_GET_SLEEP_SESSIONS && snorePages != null) return new GBDeviceEvent[0];
        final SoundcoreSleepHistory.Pages pages;
        final int recordSize;
        switch (command) {
            case CMD_GET_SLEEP_STAGES:
                pages = stages;
                recordSize = 7;
                break;
            case CMD_GET_SLEEP_SESSIONS:
                pages = sessionPages;
                recordSize = 14;
                break;
            case SoundcoreSleepHistory.CMD_SNORES:
                pages = snorePages;
                recordSize = 9;
                break;
            default:
                return new GBDeviceEvent[0];
        }
        if (pages == null) return new GBDeviceEvent[0];
        if (!pages.accept(payload)) {
            cancelSleepHistory();
            return new GBDeviceEvent[]{new SoundcoreSleepHistoryEvent(null)};
        }
        final SoundcoreSleepHistory.Page page = SoundcoreSleepHistory.decodePage(payload, recordSize);
        Long timestamp = null;
        for (final byte[] record : page.getRecords()) {
            final long value = BLETypeConversions.toUint32(record, 0) & 0xffffffffL;
            timestamp = timestamp == null ? value : Math.max(timestamp, value);
        }
        if (timestamp != null) {
            if (command == CMD_GET_SLEEP_STAGES && page.getSelector() == 1) {
                stageAcknowledgementTimestamp = Math.max(stageAcknowledgementTimestamp == null ? 0 : stageAcknowledgementTimestamp, timestamp);
            } else if (command == CMD_GET_SLEEP_SESSIONS && page.getSelector() == 0) {
                sessionAcknowledgementTimestamp = Math.max(sessionAcknowledgementTimestamp == null ? 0 : sessionAcknowledgementTimestamp, timestamp);
            }
        }
        if (!pages.getComplete()) return new GBDeviceEvent[0];
        if (command == CMD_GET_SLEEP_STAGES) {
            // Session responses contain two independently paginated selectors, in either order.
            // Completing the first one alone can omit the latest night.
            sessionPages = new SoundcoreSleepHistory.Pages(14, Set.of(0, 1));
            return new GBDeviceEvent[]{new GBDeviceEventSendBytes(encodeRequest(CMD_GET_SLEEP_SESSIONS))};
        }
        if (command == CMD_GET_SLEEP_SESSIONS) {
            final SoundcoreSleepHistory.Report report = SoundcoreSleepHistory.buildReport(stages.records(), pages.records());
            if (report == null) {
                cancelSleepHistory();
                return new GBDeviceEvent[]{new SoundcoreSleepHistoryEvent(null)};
            }
            pendingSleepReport = report;
            snorePages = new SoundcoreSleepHistory.Pages(9, Set.of(0, 1));
            return new GBDeviceEvent[]{new GBDeviceEventSendBytes(encodeRequest(SoundcoreSleepHistory.CMD_SNORES))};
        }
        final List<SoundcoreSleepHistory.Snore> snores = SoundcoreSleepHistory.decodeSnores(pages);
        final SoundcoreSleepHistory.Report report = snores != null && pendingSleepReport != null
                ? pendingSleepReport.copy(pendingSleepReport.getRecords(), pendingSleepReport.getSessions(),
                        pendingSleepReport.getIntervals(), snores) : null;
        stagePages = null;
        sessionPages = null;
        snorePages = null;
        pendingSleepReport = null;
        historyReadyForAcknowledgement = report != null;
        if (report == null) cancelSleepHistory();
        return new GBDeviceEvent[]{new SoundcoreSleepHistoryEvent(report)};
    }

    private GBDeviceEvent[] decodeAutoStop(final byte[] payload) {
        if (payload.length != 8 || (payload[0] != 0 && payload[0] != 1)) return new GBDeviceEvent[0];
        final int minutes = BLETypeConversions.toUint16(payload, 1);
        if (minutes < 1 || minutes > 240) return new GBDeviceEvent[0];
        return new GBDeviceEvent[]{new GBDeviceEventUpdatePreferences()
                .withPreference(PREF_SOUNDCORE_AUTO_STOP, payload[0] == 1)
                .withPreference(PREF_SOUNDCORE_AUTO_STOP_TIMER, AUTO_STOP_PRESETS.contains(minutes) ? Integer.toString(minutes) : "custom")
                .withPreference(PREF_SOUNDCORE_AUTO_STOP_MINUTES, minutes)};
    }

    @Nullable
    public byte[] encodeAutoStop(final boolean enabled, final int minutes) {
        if (minutes < 1 || minutes > 240) return null;
        // Timer writes use action 0 (stop audio), not the auto-switch sentinel ff ff ff.
        return concatenate(encodeCommand(CMD_SET_SLEEP_AUDIO,
                        ByteBuffer.allocate(4).put(encodeBoolean(enabled)).put(BLETypeConversions.fromUint16(minutes)).put((byte) 0).array()),
                encodeRequest(CMD_GET_SLEEP_SETTINGS));
    }

    private static byte[] concatenate(final byte[] first, final byte[] second) {
        final byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    public byte[] encodeTapFunction(final boolean right, final boolean triple, final int previous, final TapFunction function) {
        return encodeControlFunction(right, (byte) (triple ? 5 : 0), (byte) ((previous << 4) | function.getCode()));
    }

    @Override
    public byte[] encodeAlarmsRequest() {
        awaitingAlarmRead = true;
        return encodeRequest(CMD_GET_ALARMS);
    }

    @Override
    @Nullable
    public byte[] encodeSetAlarms(final ArrayList<? extends Alarm> alarms) {
        final List<AlarmRequest> requests = new ArrayList<>(alarms.size());
        final Set<Integer> ids = new HashSet<>();
        final int slotCount = alarms.isEmpty() ? 0 : getDevice().getDeviceCoordinator().getAlarmSlotCount(getDevice());
        for (final Alarm alarm : alarms) {
            if (!alarm.getUnused() && (alarm.getHour() < 0 || alarm.getHour() > 23 ||
                    alarm.getMinute() < 0 || alarm.getMinute() > 59)) return null;
            final int minutes = alarm.getHour() * 60 + alarm.getMinute();
            if (alarm.getPosition() < 0 || alarm.getPosition() >= slotCount || !ids.add(alarm.getPosition()) ||
                    (!alarm.getUnused() && (minutes < 0 || minutes > 1439 ||
                            alarm.getRepetition() < 0 || alarm.getRepetition() > 127))) return null;
            requests.add(new AlarmRequest(alarm.getPosition(), alarm.getUnused(), alarm.getEnabled(),
                    minutes, alarm.getRepetition(), alarm.getSnooze()));
        }
        pendingAlarms = requests;
        // Always read before writing: volume, tune and snooze duration are not represented by GB's alarm UI.
        return awaitingAlarmWrite || awaitingAlarmRead ? null : encodeAlarmsRequest();
    }

    private GBDeviceEvent[] decodeAlarms(final byte[] payload) {
        final List<SoundcoreSleepA30Alarm> records = SoundcoreSleepA30Alarm.Companion.decodeList(
                payload, getDevice().getDeviceCoordinator().getAlarmSlotCount(getDevice()));
        if (records == null) return new GBDeviceEvent[0];
        awaitingAlarmRead = false;
        if (awaitingAlarmWrite && !alarmCommands.isEmpty()) {
            return new GBDeviceEvent[]{new GBDeviceEventSendBytes(alarmCommands.removeFirst())};
        }
        awaitingAlarmWrite = false;
        final List<AlarmRequest> requested = pendingAlarms;
        if (requested != null) {
            pendingAlarms = null;
            final Map<Integer, SoundcoreSleepA30Alarm> byId = new HashMap<>();
            for (final SoundcoreSleepA30Alarm record : records) byId.put(record.getId(), record);
            // No one-shot alarm was established. Do not guess its wire repeat mask.
            for (final AlarmRequest alarm : requested) {
                final SoundcoreSleepA30Alarm previous = byId.get(alarm.id);
                if (!alarm.unused && alarm.repetition == 0 && (previous == null || previous.getRepetition() != 0)) {
                    LOG.warn("Sleep A30 one-shot alarm encoding has not been established");
                    return new GBDeviceEvent[]{new SoundcoreSleepA30AlarmEvent(records)};
                }
            }
            for (final AlarmRequest alarm : requested) {
                final SoundcoreSleepA30Alarm previous = byId.get(alarm.id);
                if (alarm.unused) {
                    if (previous != null) alarmCommands.addLast(encodeByteCommand(CMD_DELETE_ALARM, alarm.id));
                } else {
                    // Tunes: Nature=0, Glow=1, Rhythm=2, Bloom=3; volume is 0..100.
                    // New GB alarms default to Nature/50%; snooze defaults to 10 minutes when enabled.
                    // Existing values, including unknown tune IDs, are preserved rather than exposed in new UI.
                    final int snoozeMinutes = !alarm.snooze ? 0
                            : previous != null && previous.getSnoozeMinutes() > 0 ? previous.getSnoozeMinutes() : 10;
                    final SoundcoreSleepA30Alarm updated = new SoundcoreSleepA30Alarm(
                            alarm.id, alarm.enabled, alarm.minutes,
                            SoundcoreSleepA30Alarm.Companion.encodeRepetition(alarm.repetition) | (previous == null ? 0 : previous.getDayMask() & 1),
                            previous == null ? 0 : previous.getTune(), previous == null ? 50 : previous.getVolume(),
                            snoozeMinutes);
                    if (!updated.equals(previous)) alarmCommands.addLast(encodeCommand(CMD_SET_ALARM, updated.encode()));
                }
            }
            if (!alarmCommands.isEmpty()) {
                awaitingAlarmWrite = true;
                return new GBDeviceEvent[]{new GBDeviceEventSendBytes(alarmCommands.removeFirst())};
            }
        }
        return new GBDeviceEvent[]{new SoundcoreSleepA30AlarmEvent(records)};
    }

    @Override
    @Nullable
    public byte[] encodeSendConfiguration(final String config) {
        final Prefs prefs = getDevicePrefs();
        switch (config) {
            case PREF_SLEEP_TRACKING:
                return encodeBooleanCommand(CMD_SET_SLEEP_TRACKING, prefs.getBoolean(config, true));
            case ACTION_TOGGLE_LOCAL_PLAYBACK:
                if (!AudioSource.LOCAL.toString().equals(prefs.getString(
                        PREF_SOUNDCORE_LISTENING_MODE, AudioSource.BLUETOOTH.toString()))) return null;
                return encodeLocalPlayback(!prefs.getBoolean(PREF_SOUNDCORE_LOCAL_PLAYBACK, false));
            case PREF_SOUNDCORE_AMBIENT_SOUND_CONTROL:
                return encodeAncEnabled(AmbientSoundMode.NOISE_CANCELLING.toString().equals(
                        prefs.getString(config, AmbientSoundMode.OFF.toString())));
            case PREF_SOUNDCORE_LISTENING_MODE:
                return encodeListeningMode(AudioSource.LOCAL.toString().equals(
                        prefs.getString(config, AudioSource.BLUETOOTH.toString())));
            case PREF_SOUNDCORE_DEFAULT_LISTENING_MODE:
                return encodeBooleanCommand(CMD_SET_DEFAULT_LISTENING_MODE, AudioSource.LOCAL.toString().equals(
                        prefs.getString(config, AudioSource.BLUETOOTH.toString())));
            case PREF_SOUNDCORE_LOCAL_PLAYBACK:
                return encodeLocalPlayback(prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_LOCAL_VOLUME:
                return encodeLocalVolume(prefs.getInt(config, 0));
            case PREF_SOUNDCORE_AUTO_STOP:
            case PREF_SOUNDCORE_AUTO_STOP_TIMER:
            case PREF_SOUNDCORE_AUTO_STOP_MINUTES:
                final String timer = prefs.getString(PREF_SOUNDCORE_AUTO_STOP_TIMER, "30");
                // Selecting Custom only reveals the duration control; it must not restart the timer.
                if (PREF_SOUNDCORE_AUTO_STOP_TIMER.equals(config) && "custom".equals(timer)) return null;
                final int minutes;
                if ("custom".equals(timer)) {
                    minutes = prefs.getInt(PREF_SOUNDCORE_AUTO_STOP_MINUTES, 30);
                } else {
                    try {
                        minutes = Integer.parseInt(timer);
                    } catch (final NumberFormatException e) {
                        return null;
                    }
                    if (!AUTO_STOP_PRESETS.contains(minutes)) return null;
                }
                return encodeAutoStop(prefs.getBoolean(PREF_SOUNDCORE_AUTO_STOP, false), minutes);
            case PREF_SOUNDCORE_SLEEP_AUDIO:
                if (prefs.getBoolean(PREF_SOUNDCORE_AUTO_STOP, false)) return null;
                return encodeSleepAudio(prefs.getString(config, "keep"));
            case PREF_SOUNDCORE_SNORE_MASKING:
                return encodeBooleanCommand(CMD_SET_SNORE_MASKING, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_CONTROL_TOUCH_DISABLED:
                return encodeBooleanCommand(CMD_SET_TAP_CONTROLS_ENABLE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_BATTERY_LOW_TONE:
                return encodeBooleanCommand(CMD_SET_BATTERY_LOW_TONE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_CALLS_ENABLED:
                return encodeBooleanCommand(CMD_SET_CALLS_ENABLED, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_POWER_OFF_TONE:
                return encodeBooleanCommand(CMD_SET_POWER_OFF_TONE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_ANC_TONE:
                return encodeBooleanCommand(CMD_SET_ANC_TONE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_MODE_TONE:
                return encodeBooleanCommand(CMD_SET_MODE_TONE, prefs.getBoolean(config, false));
            case PREF_SOUNDCORE_CONTROL_RESET:
                return encodeRequest(CMD_RESET_TAP_CONTROLS);
            case PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_LEFT:
            case PREF_SOUNDCORE_CONTROL_DOUBLE_TAP_ACTION_RIGHT:
            case PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_LEFT:
            case PREF_SOUNDCORE_CONTROL_TRIPLE_TAP_ACTION_RIGHT:
                final TapFunction function;
                try {
                    function = TapFunction.valueOf(prefs.getString(config, "NONE"));
                } catch (final IllegalArgumentException e) {
                    return null;
                }
                final boolean right = config.endsWith("right");
                final boolean triple = config.contains("triple");
                final int previous = currentFunctions[(triple ? 2 : 0) + (right ? 1 : 0)];
                // Do not build a transition from a locally selected but unconfirmed function.
                return previous < 0 ? null : encodeTapFunction(right, triple, previous, function);
            default:
                return super.encodeSendConfiguration(config);
        }
    }
}
