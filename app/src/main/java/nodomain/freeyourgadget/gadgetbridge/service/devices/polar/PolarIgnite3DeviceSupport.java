/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.polar.pftp.PftpConstants;
import nodomain.freeyourgadget.gadgetbridge.service.devices.polar.pftp.PftpFraming;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

/**
 * Support for the Polar Ignite 3 over Polar's PFTP protocol (BLE service 0xFEEE).
 *
 * <p>PFTP is a request/response file system protocol: the watch keeps its data, settings and
 * computed results as protobuf files, which the host reads and writes. Requests and queries
 * go out on {@link PftpConstants#UUID_CHARACTERISTIC_MTU} and are answered there, strictly one
 * at a time; notifications are fire-and-forget on the separate H2D/D2H characteristics.
 *
 * <p>Supported: time, firmware version, battery, push notifications with actions and canned
 * replies, incoming calls, music info and control, watch settings and body data.
 */
public class PolarIgnite3DeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(PolarIgnite3DeviceSupport.class);

    /** The watch has four free action buttons, which canned replies share with the notification's own actions */
    public static final int CANNED_REPLY_SLOTS = PolarNotifications.ACTION_SLOT_COUNT;

    private static final String PATH_DEVICE_SETTINGS = "/U/0/S/UDEVSET.BPB";
    private static final String PATH_GENERAL_PREFERENCES = "/U/0/S/PREFS.BPB";
    private static final String PATH_PHYSICAL_DATA = "/U/0/S/PHYSDATA.BPB";

    private static final int REQUESTED_MTU = 517;
    private static final long RESPONSE_TIMEOUT_MILLIS = 30_000;

    private interface ResponseCallback {
        /** @param message the answer, or null if the watch did not answer in time */
        void onResponse(@Nullable PftpFraming.Message message);
    }

    private static final class Operation {
        final String name;
        final UUID characteristic;
        final byte[] message;
        /** null for notifications, which the watch does not answer */
        @Nullable
        final ResponseCallback callback;

        Operation(final String name, final UUID characteristic, final byte[] message,
                  @Nullable final ResponseCallback callback) {
            this.name = name;
            this.characteristic = characteristic;
            this.message = message;
            this.callback = callback;
        }
    }

    private final Deque<Operation> operations = new ArrayDeque<>();
    @Nullable
    private Operation currentOperation;
    private boolean ready;
    private final PftpFraming.Reassembler mtuReassembler = new PftpFraming.Reassembler();
    private final PftpFraming.Reassembler d2hReassembler = new PftpFraming.Reassembler();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable responseTimeout = this::onResponseTimeout;
    private final PolarNotifications notifications = new PolarNotifications();
    @Nullable
    private MusicSpec musicInfo;
    @Nullable
    private MusicStateSpec musicState;
    /** Last known content of the settings files, edited and written back on preference changes */
    @Nullable
    private PolarProto.PbUserDeviceSettings deviceSettings;
    @Nullable
    private PolarProto.PbGeneralPreferences generalPreferences;
    @Nullable
    private PolarProto.PbUserPhysData physicalData;

    public PolarIgnite3DeviceSupport() {
        super(LOG);
        addSupportedService(PftpConstants.UUID_SERVICE_PFTP);
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        // A support instance may outlive a connection, never carry operations into the next one
        synchronized (this) {
            handler.removeCallbacks(responseTimeout);
            operations.clear();
            currentOperation = null;
            ready = false;
            mtuReassembler.reset();
            d2hReassembler.reset();
        }
        notifications.clear();
        synchronized (this) {
            deviceSettings = null;
            generalPreferences = null;
            physicalData = null;
        }

        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.requestMtu(REQUESTED_MTU);

        final BluetoothGattCharacteristic mtuCharacteristic = getCharacteristic(PftpConstants.UUID_CHARACTERISTIC_MTU);
        if (mtuCharacteristic != null) {
            // Frames are acknowledged one by one; this keeps large transfers from overrunning the watch
            mtuCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }
        builder.notify(mtuCharacteristic, true);
        builder.notify(PftpConstants.UUID_CHARACTERISTIC_D2H, true);
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        builder.run(this::onReady);
        return builder;
    }

    private void onReady() {
        synchronized (this) {
            ready = true;
        }
        setTime();
        getFile("/DEVICE.BPB", this::handleDeviceInfo);
        query(PftpConstants.QUERY_GET_BATTERY_STATUS, null, this::handleBatteryStatusResult);
        getFile(PATH_DEVICE_SETTINGS, this::handleDeviceSettings);
        getFile(PATH_GENERAL_PREFERENCES, this::handleGeneralPreferences);
        getFile(PATH_PHYSICAL_DATA, this::handlePhysicalData);
    }

    @Override
    public void dispose() {
        synchronized (this) {
            handler.removeCallbacks(responseTimeout);
            operations.clear();
            currentOperation = null;
            ready = false;
        }
        super.dispose();
    }

    @Override
    public void onSetTime() {
        setTime();
    }

    private void setTime() {
        final GregorianCalendar now = new GregorianCalendar();
        final int offsetMinutes = TimeZone.getDefault().getOffset(now.getTimeInMillis()) / 60_000;

        final PolarProto.PbPFtpSetLocalTimeParams local = PolarProto.PbPFtpSetLocalTimeParams.newBuilder()
                .setDate(pbDate(now))
                .setTime(pbTime(now))
                .setTzOffset(offsetMinutes)
                .build();
        query(PftpConstants.QUERY_SET_LOCAL_TIME, local.toByteArray(), statusLogger("set local time"));

        final GregorianCalendar utc = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(now.getTimeInMillis());
        final PolarProto.PbPFtpSetSystemTimeParams system = PolarProto.PbPFtpSetSystemTimeParams.newBuilder()
                .setDate(pbDate(utc))
                .setTime(pbTime(utc))
                .setTrusted(true)
                .build();
        query(PftpConstants.QUERY_SET_SYSTEM_TIME, system.toByteArray(), statusLogger("set system time"));
    }

    private static PolarProto.PbDate pbDate(final Calendar calendar) {
        return PolarProto.PbDate.newBuilder()
                .setYear(calendar.get(Calendar.YEAR))
                .setMonth(calendar.get(Calendar.MONTH) + 1)
                .setDay(calendar.get(Calendar.DAY_OF_MONTH))
                .build();
    }

    private static PolarProto.PbTime pbTime(final Calendar calendar) {
        return PolarProto.PbTime.newBuilder()
                .setHour(calendar.get(Calendar.HOUR_OF_DAY))
                .setMinute(calendar.get(Calendar.MINUTE))
                .setSeconds(calendar.get(Calendar.SECOND))
                .setMillis(calendar.get(Calendar.MILLISECOND))
                .build();
    }

    private void handleDeviceInfo(@Nullable final PftpFraming.Message message) {
        final byte[] payload = payloadOrLog("/DEVICE.BPB", message);
        if (payload == null) {
            return;
        }
        final PolarProto.PbDeviceInfo info;
        try {
            info = PolarProto.PbDeviceInfo.parseFrom(payload);
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse /DEVICE.BPB", e);
            return;
        }
        final GBDeviceEventVersionInfo versionInfo = new GBDeviceEventVersionInfo();
        versionInfo.fwVersion = version(info.getDeviceVersion());
        versionInfo.hwVersion = info.getHardwareCode();
        evaluateGBDeviceEvent(versionInfo);
    }

    private static String version(final PolarProto.PbVersion version) {
        return String.format(Locale.ROOT, "%d.%d.%d", version.getMajor(), version.getMinor(), version.getPatch());
    }

    private void handleBatteryStatusResult(@Nullable final PftpFraming.Message message) {
        final byte[] payload = payloadOrLog("battery status", message);
        if (payload != null) {
            handleBatteryStatus(payload);
        }
    }

    private void handleBatteryStatus(final byte[] payload) {
        final PolarProto.PbPFtpBatteryStatusResult status;
        try {
            status = PolarProto.PbPFtpBatteryStatusResult.parseFrom(payload);
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse battery status", e);
            return;
        }
        final GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
        batteryInfo.level = status.getBatteryStatus();
        batteryInfo.state = status.getCharging() ? BatteryState.BATTERY_CHARGING : BatteryState.BATTERY_NORMAL;
        evaluateGBDeviceEvent(batteryInfo);
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        final UUID uuid = characteristic.getUuid();
        if (PftpConstants.UUID_CHARACTERISTIC_MTU.equals(uuid)) {
            onMtuFrame(value);
            return true;
        } else if (PftpConstants.UUID_CHARACTERISTIC_D2H.equals(uuid)) {
            onD2hFrame(value);
            return true;
        }
        return false;
    }

    private void onMtuFrame(final byte[] frame) {
        final PftpFraming.Message message;
        try {
            message = mtuReassembler.add(frame);
        } catch (final IllegalStateException e) {
            LOG.warn("Dropping broken PFTP response: {}", e.getMessage());
            completeCurrentOperation(null);
            return;
        }
        if (message != null) {
            completeCurrentOperation(message);
        }
    }

    private void onD2hFrame(final byte[] frame) {
        final PftpFraming.Message message;
        try {
            message = d2hReassembler.add(frame);
        } catch (final IllegalStateException e) {
            LOG.warn("Dropping broken PFTP notification: {}", e.getMessage());
            return;
        }
        if (message == null || message.payload == null || message.payload.length == 0) {
            return;
        }
        final int id = message.payload[0] & 0xFF;
        final byte[] params = new byte[message.payload.length - 1];
        System.arraycopy(message.payload, 1, params, 0, params.length);
        handleDeviceNotification(id, params);
    }

    private void handleDeviceNotification(final int id, final byte[] params) {
        switch (id) {
            case PftpConstants.D2H_BATTERY_STATUS:
                handleBatteryStatus(params);
                return;
            case PftpConstants.D2H_PNS_NOTIFICATION_RESPONSE:
                handleNotificationResponse(params);
                return;
            case PftpConstants.D2H_MEDIA_CONTROL_REQUEST:
                handleMediaControlRequest(params);
                return;
            case PftpConstants.D2H_MEDIA_CONTROL_COMMAND:
                handleMediaControlCommand(params);
                return;
            case PftpConstants.D2H_SYNC_REQUIRED:
                // Data sync is not implemented yet
                LOG.info("Watch requests a sync: {}", GB.hexdump(params));
                return;
            default:
                LOG.debug("Unhandled PFTP notification {}: {}", id, GB.hexdump(params));
        }
    }

    // ---- settings ----

    @Override
    public void onSendConfiguration(final String config) {
        if (PolarSettings.DEVICE_SETTINGS_KEYS.contains(config)) {
            writeDeviceSettings();
        } else if (DeviceSettingsPreferenceConst.PREF_TIMEFORMAT.equals(config)) {
            writeGeneralPreferences();
        } else if (config.startsWith("activity_user_")) {
            writePhysicalData();
        } else {
            super.onSendConfiguration(config);
        }
    }

    private void handleDeviceSettings(@Nullable final PftpFraming.Message message) {
        final byte[] payload = payloadOrLog(PATH_DEVICE_SETTINGS, message);
        if (payload == null) {
            return;
        }
        final PolarProto.PbUserDeviceSettings settings;
        try {
            settings = PolarProto.PbUserDeviceSettings.parseFrom(payload);
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse {}", PATH_DEVICE_SETTINGS, e);
            return;
        }
        synchronized (this) {
            deviceSettings = settings;
        }
        // The watch settings may have been changed on the watch, show them as they are
        final SharedPreferences.Editor editor = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()).edit();
        PolarSettings.importDeviceSettings(settings, editor);
        editor.apply();
        // ...but keep the daylight saving time switch up to date
        writeDeviceSettings();
    }

    private void handleGeneralPreferences(@Nullable final PftpFraming.Message message) {
        final byte[] payload = payloadOrLog(PATH_GENERAL_PREFERENCES, message);
        if (payload == null) {
            return;
        }
        try {
            final PolarProto.PbGeneralPreferences preferences = PolarProto.PbGeneralPreferences.parseFrom(payload);
            synchronized (this) {
                generalPreferences = preferences;
            }
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse {}", PATH_GENERAL_PREFERENCES, e);
            return;
        }
        writeGeneralPreferences();
    }

    private void handlePhysicalData(@Nullable final PftpFraming.Message message) {
        final byte[] payload = payloadOrLog(PATH_PHYSICAL_DATA, message);
        if (payload == null) {
            return;
        }
        try {
            final PolarProto.PbUserPhysData data = PolarProto.PbUserPhysData.parseFrom(payload);
            synchronized (this) {
                physicalData = data;
            }
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse {}", PATH_PHYSICAL_DATA, e);
            return;
        }
        writePhysicalData();
    }

    private void writeDeviceSettings() {
        final PolarProto.PbUserDeviceSettings updated;
        synchronized (this) {
            if (deviceSettings == null) {
                LOG.warn("Device settings not read yet, not writing them");
                return;
            }
            updated = PolarSettings.applyDeviceSettings(deviceSettings, getDevicePrefs());
            if (updated == null) {
                return;
            }
            deviceSettings = updated;
        }
        putFile(PATH_DEVICE_SETTINGS, updated.toByteArray());
    }

    private void writeGeneralPreferences() {
        final PolarProto.PbGeneralPreferences updated;
        synchronized (this) {
            if (generalPreferences == null) {
                LOG.warn("General preferences not read yet, not writing them");
                return;
            }
            updated = PolarSettings.applyGeneralPreferences(generalPreferences, getDevicePrefs(),
                    GBApplication.getPrefs().getDistanceUnit());
            if (updated == null) {
                return;
            }
            generalPreferences = updated;
        }
        putFile(PATH_GENERAL_PREFERENCES, updated.toByteArray());
    }

    private void writePhysicalData() {
        final PolarProto.PbUserPhysData updated;
        synchronized (this) {
            if (physicalData == null) {
                LOG.warn("Physical data not read yet, not writing it");
                return;
            }
            updated = PolarSettings.applyUserProfile(physicalData, new ActivityUser(), GBApplication.getPrefs());
            if (updated == null) {
                return;
            }
            physicalData = updated;
        }
        putFile(PATH_PHYSICAL_DATA, updated.toByteArray());
    }

    // ---- notifications and calls ----

    @Override
    public void onNotification(final NotificationSpec notificationSpec) {
        final PolarProto.PbPftpPnsHDNotification notification = notifications.create(
                notificationSpec, cannedReplies(), getContext().getString(R.string.delete));
        sendNotification(PftpConstants.H2D_PNS_HD_NOTIFICATION, notification.toByteArray());
    }

    @Override
    public void onDeleteNotification(final int id) {
        removeNotification(id, false);
    }

    @Override
    public void onSetCallState(final CallSpec callSpec) {
        if (callSpec.getCommand() == CallSpec.CALL_INCOMING) {
            final PolarProto.PbPftpPnsHDNotification notification = notifications.incomingCall(callSpec,
                    getContext().getString(R.string.call_accept),
                    getContext().getString(R.string.call_rejection_method_reject));
            sendNotification(PftpConstants.H2D_PNS_HD_NOTIFICATION, notification.toByteArray());
        } else {
            removeNotification(PolarNotifications.CALL_NOTIFICATION_ID, false);
        }
    }

    private void removeNotification(final int id, final boolean evenIfUnknown) {
        final PolarProto.PbPftpPnsHDNotification removal = notifications.remove(id, evenIfUnknown);
        if (removal != null) {
            sendNotification(PftpConstants.H2D_PNS_HD_NOTIFICATION, removal.toByteArray());
        }
    }

    private List<String> cannedReplies() {
        final List<String> replies = new ArrayList<>();
        for (int i = 1; i <= CANNED_REPLY_SLOTS; i++) {
            final String reply = getDevicePrefs().getString("canned_reply_" + i, null);
            if (!StringUtils.isNullOrEmpty(reply)) {
                replies.add(reply);
            }
        }
        return replies;
    }

    private void handleNotificationResponse(final byte[] params) {
        final PolarProto.PbPftpPnsDHNotificationResponse response;
        try {
            response = PolarProto.PbPftpPnsDHNotificationResponse.parseFrom(params);
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse notification response", e);
            return;
        }
        LOG.debug("Notification response: {}", response);
        final GBDeviceEvent event = notifications.onResponse(response);
        if (event != null) {
            evaluateGBDeviceEvent(event);
        }
        // Like Polar Flow, take the notification off the watch once a button was pressed on it
        removeNotification(response.getNotificationId(), true);
    }

    // ---- music ----

    @Override
    public void onSetMusicInfo(final MusicSpec musicSpec) {
        synchronized (this) {
            musicInfo = musicSpec;
        }
        sendMediaData();
    }

    @Override
    public void onSetMusicState(final MusicStateSpec stateSpec) {
        final boolean playingChanged;
        synchronized (this) {
            playingChanged = musicState == null || musicState.getState() != stateSpec.getState();
            musicState = stateSpec;
        }
        // The available commands (play or pause) depend on the state
        if (playingChanged) {
            sendMediaData();
        }
        sendPlaybackState();
    }

    @Override
    public void onSetPhoneVolume(final float volume) {
        sendPlaybackState();
    }

    private boolean isPlaying() {
        return musicState != null && musicState.getState() == MusicStateSpec.STATE_PLAYING;
    }

    private void sendMediaData() {
        final PolarProto.PbPFtpHDMediaData.Builder data = PolarProto.PbPFtpHDMediaData.newBuilder();
        synchronized (this) {
            if (musicInfo != null) {
                data.addCommands(PolarProto.MediaControlCommand.TOGGLER_PLAY_PAUSE)
                        .addCommands(isPlaying() ? PolarProto.MediaControlCommand.PAUSE : PolarProto.MediaControlCommand.PLAY)
                        .addCommands(PolarProto.MediaControlCommand.NEXT)
                        .addCommands(PolarProto.MediaControlCommand.PREVIOUS)
                        .addCommands(PolarProto.MediaControlCommand.VOLUME_UP)
                        .addCommands(PolarProto.MediaControlCommand.VOLUME_DOWN);
                if (musicInfo.getArtist() != null) {
                    data.setArtistName(musicInfo.getArtist());
                }
                if (musicInfo.getTrack() != null) {
                    data.setTrackName(musicInfo.getTrack());
                }
                if (musicInfo.getDuration() > 0) {
                    data.setTrackDuration(musicInfo.getDuration());
                }
            }
        }
        sendNotification(PftpConstants.H2D_MEDIA_DATA, data.build().toByteArray());
    }

    private void sendPlaybackState() {
        final PolarProto.PbPFtpHDMediaControlPlaybackState.Builder state = PolarProto.PbPFtpHDMediaControlPlaybackState.newBuilder()
                .setVolumeLevel(volumePercent());
        synchronized (this) {
            if (musicState == null) {
                state.setPlaybackState(PolarProto.PlaybackState.STATE_NONE);
            } else {
                switch (musicState.getState()) {
                    case MusicStateSpec.STATE_PLAYING:
                        state.setPlaybackState(PolarProto.PlaybackState.STATE_PLAYING);
                        break;
                    case MusicStateSpec.STATE_PAUSED:
                        state.setPlaybackState(PolarProto.PlaybackState.STATE_PAUSED);
                        break;
                    case MusicStateSpec.STATE_STOPPED:
                        state.setPlaybackState(PolarProto.PlaybackState.STATE_STOPPED);
                        break;
                    default:
                        state.setPlaybackState(PolarProto.PlaybackState.STATE_NONE);
                }
                state.setPlaybackRate(isPlaying() ? 1f : 0f);
                if (musicState.getPosition() >= 0) {
                    state.setElapsedTime(musicState.getPosition());
                }
            }
        }
        sendNotification(PftpConstants.H2D_MEDIA_PLAYBACK_STATE, state.build().toByteArray());
    }

    private int volumePercent() {
        final AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        final int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max > 0 ? Math.round(100f * audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max) : 0;
    }

    private void handleMediaControlRequest(final byte[] params) {
        final PolarProto.PbPftpDHMediaControlRequest request;
        try {
            request = PolarProto.PbPftpDHMediaControlRequest.parseFrom(params);
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse media control request", e);
            return;
        }
        switch (request.getRequest()) {
            case GET_MEDIA_DATA:
                sendMediaData();
                break;
            case GET_PLAYBACK_STATE:
                sendPlaybackState();
                break;
        }
    }

    private void handleMediaControlCommand(final byte[] params) {
        final PolarProto.PbPftpDHMediaControlCommand command;
        try {
            command = PolarProto.PbPftpDHMediaControlCommand.parseFrom(params);
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Failed to parse media control command", e);
            return;
        }
        final GBDeviceEventMusicControl musicControl = new GBDeviceEventMusicControl();
        switch (command.getCommand()) {
            case PLAY:
                musicControl.event = GBDeviceEventMusicControl.Event.PLAY;
                break;
            case PAUSE:
                musicControl.event = GBDeviceEventMusicControl.Event.PAUSE;
                break;
            case TOGGLER_PLAY_PAUSE:
                musicControl.event = GBDeviceEventMusicControl.Event.PLAYPAUSE;
                break;
            case NEXT:
                musicControl.event = GBDeviceEventMusicControl.Event.NEXT;
                break;
            case PREVIOUS:
                musicControl.event = GBDeviceEventMusicControl.Event.PREVIOUS;
                break;
            case FAST_FORWARD:
                musicControl.event = GBDeviceEventMusicControl.Event.FORWARD;
                break;
            case FAST_BACKWARD:
                musicControl.event = GBDeviceEventMusicControl.Event.REWIND;
                break;
            case VOLUME_UP:
                musicControl.event = GBDeviceEventMusicControl.Event.VOLUMEUP;
                break;
            case VOLUME_DOWN:
                musicControl.event = GBDeviceEventMusicControl.Event.VOLUMEDOWN;
                break;
            default:
                LOG.warn("Unknown media control command {}", command.getCommand());
                return;
        }
        evaluateGBDeviceEvent(musicControl);
    }

    // ---- operation queue ----

    private void sendNotification(final int notificationId, final byte[] params) {
        enqueue(new Operation("notification " + notificationId, PftpConstants.UUID_CHARACTERISTIC_H2D,
                PftpFraming.notification(notificationId, params), null));
    }

    private void getFile(final String path, final ResponseCallback callback) {
        enqueue(new Operation("GET " + path, PftpConstants.UUID_CHARACTERISTIC_MTU,
                PftpFraming.request(PolarProto.PbPFtpOperation.Command.GET, path, null), callback));
    }

    private void putFile(final String path, final byte[] data) {
        enqueue(new Operation("PUT " + path, PftpConstants.UUID_CHARACTERISTIC_MTU,
                PftpFraming.request(PolarProto.PbPFtpOperation.Command.PUT, path, data), statusLogger("PUT " + path)));
    }

    private void query(final int queryId, @Nullable final byte[] params, final ResponseCallback callback) {
        enqueue(new Operation("query " + queryId, PftpConstants.UUID_CHARACTERISTIC_MTU,
                PftpFraming.query(queryId, params), callback));
    }

    private ResponseCallback statusLogger(final String name) {
        return message -> {
            if (message == null) {
                LOG.warn("{}: no answer", name);
            } else if (message.isStatus() && message.error != PftpConstants.ERROR_OPERATION_SUCCEEDED) {
                LOG.warn("{} failed with PFTP error {}", name, message.error);
            }
        };
    }

    @Nullable
    private static byte[] payloadOrLog(final String name, @Nullable final PftpFraming.Message message) {
        if (message == null) {
            LOG.warn("{}: no answer", name);
            return null;
        }
        if (message.isStatus()) {
            LOG.warn("{}: PFTP error {}", name, message.error);
            return null;
        }
        return message.payload;
    }

    private void enqueue(final Operation operation) {
        synchronized (this) {
            operations.add(operation);
        }
        sendNextOperation();
    }

    private void sendNextOperation() {
        final Operation operation;
        synchronized (this) {
            if (!ready || currentOperation != null || operations.isEmpty()) {
                return;
            }
            operation = operations.poll();
            if (operation.callback != null) {
                currentOperation = operation;
                mtuReassembler.reset();
                handler.postDelayed(responseTimeout, RESPONSE_TIMEOUT_MILLIS);
            }
        }

        final List<byte[]> frames = PftpFraming.frames(operation.message, getMTU() - 3);
        LOG.debug("Sending PFTP {} in {} frame(s)", operation.name, frames.size());
        final BluetoothGattCharacteristic characteristic = getCharacteristic(operation.characteristic);
        final TransactionBuilder builder = createTransactionBuilder("pftp " + operation.name);
        for (final byte[] frame : frames) {
            builder.write(characteristic, frame);
        }
        builder.queue();

        if (operation.callback == null) {
            sendNextOperation();
        }
    }

    private void completeCurrentOperation(@Nullable final PftpFraming.Message message) {
        final Operation operation;
        synchronized (this) {
            operation = currentOperation;
            if (operation == null) {
                LOG.warn("PFTP response without a pending request");
                return;
            }
            currentOperation = null;
            handler.removeCallbacks(responseTimeout);
        }
        try {
            operation.callback.onResponse(message);
        } catch (final Exception e) {
            LOG.error("Failed to handle PFTP response to {}", operation.name, e);
        }
        sendNextOperation();
    }

    private void onResponseTimeout() {
        final String name;
        synchronized (this) {
            name = currentOperation != null ? currentOperation.name : null;
        }
        LOG.warn("PFTP {} timed out", name);
        mtuReassembler.reset();
        completeCurrentOperation(null);
    }
}
