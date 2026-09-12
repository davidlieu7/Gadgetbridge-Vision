/*  Copyright (C) 2026 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.veryfit;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.activities.SettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventAppInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitCapabilities;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitFeature;
import nodomain.freeyourgadget.gadgetbridge.devices.veryfit.VeryFitWatchfaceFile;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceApp;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.CannedMessagesSpec;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.model.weather.WeatherMapper;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.MediaManager;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

/**
 * Connection handling for the VeryFit family.
 * <p>
 * Newer watches challenge the host on every connect and stay silent until they get the right
 * answer back, so the settings are only pushed once the feature tables have arrived. Older bands
 * answer neither the bind nor the feature queries, so the same flow just falls through to pushing
 * the settings straight away.
 */
public class VeryFitSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(VeryFitSupport.class);

    private static final int TARGET_MTU = 247;
    private static final String PREF_BIND_AUTH = "veryfit_bind_auth";
    private static final int QUERY_TIMEOUT_MILLIS = 2000;
    private static final int LINK_EVENT_ECHO_LEN = 12;
    private static final int MIN_CHUNK_LEN = 20;
    private static final int WATCHFACE_SETTLE_MILLIS = 1000;

    private final VeryFitProtocol protocol = new VeryFitProtocol();
    private final VeryFitHealthSync healthSync = new VeryFitHealthSync(this);
    private final VeryFitWatchfaces watchfaces = new VeryFitWatchfaces();
    private final VeryFitFileUpload fileUpload = new VeryFitFileUpload(this);
    private final VeryFitAppIcons appIcons = new VeryFitAppIcons(this);
    private final VeryFitCannedReplies cannedReplies = new VeryFitCannedReplies(this);

    private MediaManager mediaManager;
    private boolean musicOpen;
    private byte[] lastMusic;
    private byte[] lastBrightness;
    private int lastVolume = -1;
    private ByteArrayOutputStream pending;
    private int pendingLength;
    private byte[] pendingAuth;
    private byte[] features;
    private byte[] featuresExtra;
    private final Map<Integer, Boolean> apps = new HashMap<>();

    public VeryFitSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ATTRIBUTE);
        addSupportedService(VeryFitConstants.UUID_SERVICE);
    }

    @Override
    public void setContext(final GBDevice device, final BluetoothAdapter adapter, final Context context) {
        super.setContext(device, adapter, context);
        mediaManager = new MediaManager(context);
    }

    @Override
    protected TransactionBuilder initializeDevice(@NonNull final TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.requestMtu(TARGET_MTU);
        builder.notify(VeryFitConstants.UUID_CHARACTERISTIC_NOTIFY, true);

        pending = null;
        pendingAuth = null;
        if (healthSync.reset()) {
            GB.updateTransferNotification(null, "", false, 100, getContext());
        }
        fileUpload.reset();
        features = null;
        featuresExtra = null;
        apps.clear();
        appIcons.reset();
        musicOpen = false;
        lastMusic = null;
        lastBrightness = null;
        lastVolume = -1;

        final byte[] storedAuth = getStoredAuth();
        if (storedAuth != null) {
            pendingAuth = storedAuth;
            write(builder, protocol.bindAuth(storedAuth));
        } else {
            write(builder, protocol.bindRequest());
        }

        // Older devices ignore both the bind and the queries, so give the answers a moment to
        // arrive and then carry on with whatever we learned.
        queryDevice(builder);
        builder.sleep(QUERY_TIMEOUT_MILLIS);
        builder.run(this::finishInitialization);
        return builder;
    }

    private void queryDevice(final TransactionBuilder builder) {
        write(builder, VeryFitProtocol.query(VeryFitConstants.QUERY_DEVICE));
        write(builder, VeryFitProtocol.query(VeryFitConstants.QUERY_FEATURES));
        write(builder, VeryFitProtocol.query(VeryFitConstants.QUERY_FEATURES_EXTRA));
        write(builder, VeryFitProtocol.query(VeryFitConstants.QUERY_FIRMWARE));
        write(builder, VeryFitProtocol.query(VeryFitConstants.QUERY_BATTERY));
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public boolean getImplicitCallbackModify() {
        return true;
    }

    @Override
    public boolean getSendWriteRequestResponse() {
        return false;
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        if (!VeryFitConstants.UUID_CHARACTERISTIC_NOTIFY.equals(characteristic.getUuid())) {
            return false;
        }

        // The file channel runs beside the command packets and is put together by whoever asked
        // for it, not here.
        if (value.length > 0 && value[0] == VeryFitConstants.FILE_MARKER) {
            fileUpload.onPacket(value);
            return true;
        }

        final byte[] complete = reassemble(value);
        if (complete == null) {
            return true;
        }

        final VeryFitProtocol.Packet packet = protocol.parse(complete);
        if (packet.kind == VeryFitProtocol.Packet.Kind.SHORT) {
            handleShort(packet);
        } else if (packet.kind == VeryFitProtocol.Packet.Kind.FRAMED) {
            handleFramed(packet);
        }
        return true;
    }

    /**
     * Long frames arrive split across notifications, every piece marked but only the first one
     * carrying the header. Short packets can turn up between two pieces, so only a marked,
     * headerless notification counts as a continuation of what is being collected.
     */
    private byte[] reassemble(final byte[] value) {
        if (value.length == 0) {
            return value;
        }

        if (pending != null) {
            if (value[0] != VeryFitConstants.FRAME_MARKER) {
                return value;
            }
            if (!VeryFitProtocol.isFramed(value)) {
                pending.write(value, 1, value.length - 1);
                if (pending.size() < pendingLength) {
                    return null;
                }
                final byte[] complete = pending.toByteArray();
                pending = null;
                return complete;
            }
            LOG.warn("Dropping {} bytes of an unfinished frame", pending.size());
            pending = null;
        }

        if (!VeryFitProtocol.isFramed(value)) {
            return value;
        }
        pendingLength = ((value[6] & 0xff) | ((value[7] & 0xff) << 8)) + 3;
        if (value.length >= pendingLength) {
            return value;
        }
        pending = new ByteArrayOutputStream();
        pending.write(value, 0, value.length);
        return null;
    }

    private void handleFramed(final VeryFitProtocol.Packet packet) {
        if (packet.cmd == VeryFitConstants.FRAMED_ALARMS_QUERY) {
            handleAlarms(packet.payload);
            return;
        }
        if (packet.cmd == VeryFitConstants.FRAMED_APP_REGISTRY) {
            handleApps(packet.payload);
            return;
        }
        if (packet.cmd == VeryFitConstants.FRAMED_WATCHFACE_LIST) {
            handleWatchfaces(packet.payload);
            return;
        }
        if (packet.cmd == VeryFitConstants.FRAMED_WATCHFACE) {
            handleWatchfaceOperation(packet.payload);
            return;
        }
        if (packet.cmd == VeryFitConstants.FRAMED_HEALTH
                || packet.cmd == VeryFitConstants.FRAMED_HEALTH_TYPES) {
            healthSync.onPacket(packet.cmd, packet.payload);
            return;
        }
        LOG.debug("Unhandled framed packet cmd=0x{} crc={} payload={}",
                Integer.toHexString(packet.cmd), packet.crcValid, GB.hexdump(packet.payload));
    }

    private void handleShort(final VeryFitProtocol.Packet packet) {
        switch (packet.group) {
            case VeryFitConstants.GROUP_QUERY:
                handleQueryResponse(packet);
                break;
            case VeryFitConstants.GROUP_BIND:
                handleBind(packet);
                break;
            case VeryFitConstants.GROUP_LINK:
                handleLink(packet);
                break;
            default:
                LOG.debug("Unhandled packet {} {}", Integer.toHexString(packet.group),
                        Integer.toHexString(packet.key));
        }
    }

    private void handleQueryResponse(final VeryFitProtocol.Packet packet) {
        switch ((byte) packet.key) {
            case VeryFitConstants.QUERY_FEATURES:
                features = packet.payload;
                storeFeatures();
                break;
            case VeryFitConstants.QUERY_FEATURES_EXTRA:
                featuresExtra = packet.payload;
                storeFeatures();
                break;
            case VeryFitConstants.QUERY_BATTERY:
                handleBattery(packet.payload);
                break;
            case VeryFitConstants.QUERY_FIRMWARE:
                handleFirmware(packet.payload);
                break;
            case VeryFitConstants.QUERY_BRIGHTNESS:
                handleBrightness(packet.payload);
                break;
            case VeryFitConstants.QUERY_AUTO_WORKOUT:
                handleAutoWorkout(packet.payload);
                break;
            case VeryFitConstants.QUERY_APP_ICON:
                appIcons.onParameters(packet.payload);
                break;
            default:
                LOG.debug("Unhandled query response {}: {}", Integer.toHexString(packet.key),
                        GB.hexdump(packet.payload));
        }
    }

    /** Battery level, and the cell voltage in millivolts ahead of it. */
    private void handleBattery(final byte[] payload) {
        if (payload.length < 5) {
            return;
        }
        final GBDeviceEventBatteryInfo event = new GBDeviceEventBatteryInfo();
        event.level = payload[4] & 0xff;
        event.voltage = ((payload[1] & 0xff) | ((payload[2] & 0xff) << 8)) / 1000f;
        evaluateGBDeviceEvent(event);
    }

    /** The nine switches as the watch holds them, behind the status byte its answer starts with. */
    private void handleAutoWorkout(final byte[] payload) {
        if (payload.length < VeryFitConstants.AUTO_WORKOUT_SWITCHES + 1) {
            return;
        }
        LOG.info("Watch workout detection: {}",
                GB.hexdump(payload, 1, VeryFitConstants.AUTO_WORKOUT_SWITCHES));
    }

    /** What the watch settled on: it keeps its own values for the fields it does not like. */
    private void handleBrightness(final byte[] payload) {
        if (payload.length < 10) {
            return;
        }
        LOG.info("Watch brightness: level {}, night dimming {} from {}:{} to {}:{} at level {}",
                payload[0] & 0xff, payload[3] & 0xff, payload[4] & 0xff, payload[5] & 0xff,
                payload[6] & 0xff, payload[7] & 0xff, payload[8] & 0xff);
    }

    private void handleFirmware(final byte[] payload) {
        if (payload.length < 3) {
            return;
        }
        final GBDeviceEventVersionInfo event = new GBDeviceEventVersionInfo();
        event.fwVersion = String.format("%d.%d.%d", payload[0] & 0xff, payload[1] & 0xff, payload[2] & 0xff);
        evaluateGBDeviceEvent(event);
    }

    private void storeFeatures() {
        if (features == null || featuresExtra == null) {
            return;
        }
        final SharedPreferences.Editor editor =
                GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress()).edit();
        editor.putString(VeryFitCapabilities.PREF_FEATURES, GB.hexdump(features));
        editor.putString(VeryFitCapabilities.PREF_FEATURES_EXTRA, GB.hexdump(featuresExtra));
        editor.apply();

        final VeryFitCapabilities capabilities = getCapabilities();
        LOG.info("Device reports {} alarm slots and features {}",
                capabilities.getAlarmSlots(), capabilities.getFeatures());
    }

    private void finishInitialization() {
        applyAllSettings();
        setInitialized();
        if (getCapabilities().supports(VeryFitFeature.FRAMED_PROTOCOL)) {
            queryAlarms();
        }
        if (getCapabilities().supports(VeryFitFeature.FRAMED_PROTOCOL)) {
            queryApps();
        }
        if (getCapabilities().isKnown()) {
            queryBrightness();
            send("veryfit workout detection read",
                    VeryFitProtocol.query(VeryFitConstants.QUERY_AUTO_WORKOUT));
        }
        mediaManager.refresh();
        sendMusic();
    }

    private void handleBind(final VeryFitProtocol.Packet packet) {
        if (packet.key == VeryFitConstants.BIND_START) {
            handleBindChallenge(packet.payload);
            return;
        }
        if (packet.key != VeryFitConstants.BIND_AUTH && packet.key != VeryFitConstants.BIND_PLAIN_AUTH) {
            return;
        }

        final boolean accepted = packet.payload.length >= 1 && packet.payload[0] == 0x00;
        LOG.info("Bind {}", accepted ? "accepted" : "rejected");
        if (accepted) {
            if (pendingAuth != null) {
                storeAuth(pendingAuth);
                pendingAuth = null;
            }
            return;
        }

        // The watch was re-bound elsewhere; drop the stored credential and start over.
        clearAuth();
        pendingAuth = null;
        final TransactionBuilder builder = createTransactionBuilder("veryfit rebind");
        write(builder, protocol.bindRequest());
        builder.queue();
    }

    private void handleBindChallenge(final byte[] payload) {
        if (payload.length < 3 || payload[0] != 0x00) {
            LOG.warn("Unusable bind challenge {}", GB.hexdump(payload));
            return;
        }
        final int length = (payload[1] & 0xff) | ((payload[2] & 0xff) << 8);
        if (length < 12 || payload.length < 15) {
            LOG.warn("Bind challenge too short ({})", length);
            return;
        }

        pendingAuth = VeryFitConstants.bindResponse(Arrays.copyOfRange(payload, 3, 15));
        // Still INITIALIZING here, so this cannot go through performInitialized.
        final TransactionBuilder builder = createTransactionBuilder("veryfit bind");
        write(builder, protocol.bindAuth(pendingAuth));
        builder.queue();
    }

    private void handleLink(final VeryFitProtocol.Packet packet) {
        switch ((byte) packet.key) {
            case VeryFitConstants.LINK_EVENT:
                acknowledgeLinkEvent(packet.payload);
                if (packet.payload.length > 2
                        && packet.payload[0] == VeryFitConstants.LINK_EVENT_CHANGED
                        && packet.payload[2] == VeryFitConstants.LINK_EVENT_CHANGED_ALARMS
                        && getCapabilities().supports(VeryFitFeature.FRAMED_PROTOCOL)) {
                    queryAlarms();
                }
                break;
            case VeryFitConstants.LINK_MEDIA:
                handleMedia(packet.payload);
                break;
            case VeryFitConstants.LINK_FIND_PHONE:
                handleFindPhone(packet.payload);
                break;
            case VeryFitConstants.LINK_REPLY:
                cannedReplies.onPick(packet.payload);
                break;
            default:
                LOG.debug("Unhandled link event {}", Integer.toHexString(packet.key));
        }
    }

    /**
     * The watch's own slider reports a percentage rather than a direction, so anything but the
     * level it already has becomes a step in that direction.
     */
    private void handleMedia(final byte[] payload) {
        if (payload.length < 2) {
            return;
        }

        final GBDeviceEventMusicControl event = new GBDeviceEventMusicControl();
        switch (payload[0]) {
            case VeryFitConstants.MEDIA_PLAY:
                event.event = GBDeviceEventMusicControl.Event.PLAY;
                break;
            case VeryFitConstants.MEDIA_PAUSE:
                event.event = GBDeviceEventMusicControl.Event.PAUSE;
                break;
            case VeryFitConstants.MEDIA_PREVIOUS:
                event.event = GBDeviceEventMusicControl.Event.PREVIOUS;
                break;
            case VeryFitConstants.MEDIA_NEXT:
                event.event = GBDeviceEventMusicControl.Event.NEXT;
                break;
            case VeryFitConstants.MEDIA_VOLUME:
                final int requested = payload[1] & 0xff;
                if (requested == mediaManager.getPhoneVolume()) {
                    return;
                }
                event.event = requested > mediaManager.getPhoneVolume()
                        ? GBDeviceEventMusicControl.Event.VOLUMEUP
                        : GBDeviceEventMusicControl.Event.VOLUMEDOWN;
                break;
            default:
                LOG.debug("Unhandled media event {}", GB.hexdump(payload));
                return;
        }
        evaluateGBDeviceEvent(event);
    }

    /** Sent as a start/stop pair, one pair per press of the watch's find-my-phone button. */
    private void handleFindPhone(final byte[] payload) {
        final boolean stop = payload.length > 0 && payload[0] == VeryFitConstants.LINK_FIND_PHONE_STOP;
        final GBDeviceEventFindPhone event = new GBDeviceEventFindPhone();
        event.event = stop ? GBDeviceEventFindPhone.Event.STOP : GBDeviceEventFindPhone.Event.START;
        evaluateGBDeviceEvent(event);
    }

    /**
     * The list of apps the watch keeps a notification switch for. Its own settings screen is what
     * turns them off, and it says so here rather than anywhere else, so what it reports wins.
     */
    private void handleApps(final byte[] payload) {
        if (payload.length < 5 || payload[2] != VeryFitConstants.REGISTRY_LIST) {
            return;
        }

        apps.clear();
        final int count = payload[4] & 0xff;
        for (int i = 0; i < count; i++) {
            final int offset = 5 + i * VeryFitConstants.REGISTRY_ENTRY_LEN;
            if (offset + VeryFitConstants.REGISTRY_ENTRY_LEN > payload.length) {
                break;
            }
            apps.put((payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8),
                    payload[offset + 3] == VeryFitConstants.APP_READY);
        }
        LOG.info("Watch holds {} notification apps, {} of them still pending", apps.size(),
                Collections.frequency(apps.values(), Boolean.FALSE));
    }

    private void handleWatchfaces(final byte[] payload) {
        final List<GBDeviceApp> faces = watchfaces.parse(payload);
        if (faces.isEmpty()) {
            return;
        }
        final GBDeviceEventAppInfo event = new GBDeviceEventAppInfo();
        event.apps = faces.toArray(new GBDeviceApp[0]);
        evaluateGBDeviceEvent(event);
    }

    /** The watch has its own alarm UI, so what it reports wins over what we last pushed. */
    private void handleAlarms(final byte[] payload) {
        LOG.info("Watch alarms: {}", VeryFitProtocol.describeAlarms(payload));

        int updated = 0;
        for (final nodomain.freeyourgadget.gadgetbridge.entities.Alarm alarm
                : DBHelper.getAlarms(gbDevice)) {
            final int offset = 2 + alarm.getPosition() * VeryFitConstants.ALARM_RECORD_LEN;
            if (offset + VeryFitConstants.ALARM_RECORD_LEN > payload.length) {
                continue;
            }

            final boolean unused = payload[offset + 1] != VeryFitConstants.ALARM_IN_USE;
            final int repeat = payload[offset + 5] & 0xff;
            final boolean enabled = !unused && (repeat & 1) != 0;
            final int repetition = (repeat >> 1) & 0x7f;
            final int hour = payload[offset + 3] & 0xff;
            final int minute = payload[offset + 4] & 0xff;
            final String title = new String(payload, offset + 10, VeryFitConstants.ALARM_NAME_LEN,
                    StandardCharsets.UTF_8).split("\u0000", 2)[0];

            if (alarm.getUnused() == unused && alarm.getEnabled() == enabled
                    && alarm.getHour() == hour && alarm.getMinute() == minute
                    && alarm.getRepetition() == repetition) {
                continue;
            }

            LOG.debug("Alarm {} was unused={} on={} {}:{} repeat={}, watch says unused={} on={} {}:{} repeat={}",
                    alarm.getPosition(), alarm.getUnused(), alarm.getEnabled(), alarm.getHour(),
                    alarm.getMinute(), alarm.getRepetition(), unused, enabled, hour, minute, repetition);

            alarm.setUnused(unused);
            alarm.setEnabled(enabled);
            alarm.setHour(hour);
            alarm.setMinute(minute);
            alarm.setRepetition(repetition);
            if (!title.isEmpty()) {
                alarm.setTitle(title);
            }
            DBHelper.store(alarm);
            updated++;
        }

        if (updated > 0) {
            LOG.info("Took {} alarm changes from the watch", updated);
            LocalBroadcastManager.getInstance(getContext())
                    .sendBroadcast(new Intent(DeviceService.ACTION_SAVE_ALARMS));
        }
    }

    /** The watch wants the event echoed back without the device address it appends. */
    private void acknowledgeLinkEvent(final byte[] payload) {
        final byte[] echo = Arrays.copyOf(payload, Math.min(payload.length, LINK_EVENT_ECHO_LEN));
        final TransactionBuilder builder = createTransactionBuilder("veryfit link event");
        write(builder, VeryFitProtocol.command(VeryFitConstants.GROUP_LINK, VeryFitConstants.LINK_EVENT, echo));
        builder.queue();
    }

    private void setInitialized() {
        if (gbDevice.getState() == GBDevice.State.INITIALIZED) {
            return;
        }
        gbDevice.setState(GBDevice.State.INITIALIZED);
        gbDevice.sendDeviceUpdateIntent(getContext());
    }

    private void applyAllSettings() {
        final VeryFitCapabilities capabilities = getCapabilities();
        final DevicePrefs prefs = getDevicePrefs();
        final TransactionBuilder builder = createTransactionBuilder("veryfit settings");

        write(builder, VeryFitSettings.hostOs());
        write(builder, VeryFitSettings.time(capabilities));
        write(builder, VeryFitSettings.user(capabilities));
        write(builder, VeryFitSettings.units(prefs));
        write(builder, VeryFitSettings.stepGoal(capabilities));
        write(builder, VeryFitSettings.findPhone(prefs));
        write(builder, VeryFitSettings.heartRate(prefs));
        if (capabilities.isKnown()) {
            write(builder, VeryFitSettings.music());
            write(builder, VeryFitSettings.wristWake(prefs));
            write(builder, VeryFitSettings.autoWorkout(prefs));
            lastBrightness = VeryFitSettings.brightness(prefs);
            write(builder, lastBrightness);
            write(builder, VeryFitSettings.inactivity(prefs));
            write(builder, VeryFitSettings.hydration(prefs));
        }
        builder.queue();
    }

    @Override
    public void onSendConfiguration(final String config) {
        final VeryFitCapabilities capabilities = getCapabilities();
        final DevicePrefs prefs = getDevicePrefs();
        final byte[] command;

        switch (config) {
            case ActivityUser.PREF_USER_HEIGHT_CM:
            case ActivityUser.PREF_USER_WEIGHT_KG:
            case ActivityUser.PREF_USER_GENDER:
            case ActivityUser.PREF_USER_DATE_OF_BIRTH:
                command = VeryFitSettings.user(capabilities);
                break;
            case ActivityUser.PREF_USER_STEPS_GOAL:
                command = VeryFitSettings.stepGoal(capabilities);
                break;
            case SettingsActivity.PREF_UNIT_DISTANCE:
            case SettingsActivity.PREF_UNIT_WEIGHT:
            case SettingsActivity.PREF_UNIT_TEMPERATURE:
            case DeviceSettingsPreferenceConst.PREF_TIMEFORMAT:
            case DeviceSettingsPreferenceConst.PREF_LANGUAGE:
                command = VeryFitSettings.units(prefs);
                break;
            case DeviceSettingsPreferenceConst.PREF_FIND_PHONE:
                command = VeryFitSettings.findPhone(prefs);
                break;
            case DeviceSettingsPreferenceConst.PREF_UPLOAD_NOTIFICATIONS_APP_ICON:
                sendAppIcons();
                return;
            case DeviceSettingsPreferenceConst.PREF_WORKOUT_DETECTION_CATEGORIES:
            case DeviceSettingsPreferenceConst.PREF_WORKOUT_AUTO_PAUSE:
            case DeviceSettingsPreferenceConst.PREF_WORKOUT_AUTO_END:
                sendAutoWorkout();
                return;
            case DeviceSettingsPreferenceConst.PREF_LIFTWRIST_NOSHED:
                command = VeryFitSettings.wristWake(prefs);
                break;
            case DeviceSettingsPreferenceConst.PREF_SCREEN_BRIGHTNESS:
            case MiBandConst.PREF_NIGHT_MODE:
            case MiBandConst.PREF_NIGHT_MODE_START:
            case MiBandConst.PREF_NIGHT_MODE_END:
                sendBrightness();
                return;
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_START:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_END:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_MO:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_TU:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_WE:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_TH:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_FR:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_SA:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_SU:
                command = VeryFitSettings.inactivity(prefs);
                break;
            case DeviceSettingsPreferenceConst.PREF_HYDRATION_SWITCH:
            case DeviceSettingsPreferenceConst.PREF_HYDRATION_PERIOD:
            case DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_START:
            case DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_END:
                command = VeryFitSettings.hydration(prefs);
                break;
            case DeviceSettingsPreferenceConst.PREF_AUTOHEARTRATE_SWITCH:
            case DeviceSettingsPreferenceConst.PREF_AUTOHEARTRATE_START:
            case DeviceSettingsPreferenceConst.PREF_AUTOHEARTRATE_END:
                command = VeryFitSettings.heartRate(prefs);
                break;
            default:
                LOG.debug("Unhandled configuration {}", config);
                return;
        }

        send("veryfit " + config, command);
    }

    @Override
    public void onSendWeather() {
        final WeatherSpec spec = Weather.getWeatherSpec();
        if (spec == null || !getCapabilities().supports(VeryFitFeature.FRAMED_PROTOCOL)) {
            return;
        }

        LOG.debug("Weather for {} at {}: condition {}, {} K, today {}/{} K",
                spec.getLocation(), spec.getTimestamp(), spec.getCurrentConditionCode(),
                spec.getCurrentTemp(), spec.getTodayMaxTemp(), spec.getTodayMinTemp());
        for (int i = 0; i < spec.getForecasts().size() && i < 5; i++) {
            final WeatherSpec.Daily day = spec.getForecasts().get(i);
            LOG.debug("Weather day {}: condition {} -> {}, {}/{} K", i, day.getConditionCode(),
                    WeatherMapper.mapToVeryFitCondition(day.getConditionCode()),
                    day.getMaxTemp(), day.getMinTemp());
        }

        try {
            final TransactionBuilder builder = performInitialized("veryfit weather");
            write(builder, VeryFitProtocol.setting(VeryFitConstants.SETTING_WEATHER,
                    VeryFitConstants.ON, (byte) 0, (byte) 0, (byte) 0));
            write(builder, protocol.framed(VeryFitConstants.FRAMED_WEATHER,
                    VeryFitProtocol.weather(spec)));
            builder.queue();
        } catch (final IOException e) {
            LOG.error("Failed to send the weather", e);
        }
    }

    @Override
    public void onFetchRecordedData(final int dataTypes) {
        if (!getCapabilities().supports(VeryFitFeature.FRAMED_PROTOCOL) || healthSync.isRunning()) {
            return;
        }

        GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data),
                "", true, 0, getContext());
        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        healthSync.start();
    }

    /** Nothing else runs while a round is in flight, so the device is free again once it ends. */
    void onHealthSyncFinished() {
        GB.signalActivityDataFinish(getDevice());
        GB.updateTransferNotification(null, "", false, 100, getContext());
        if (getDevice().isBusy()) {
            getDevice().unsetBusyTask();
            getDevice().sendDeviceUpdateIntent(getContext());
        }
    }

    void sendFramed(final String task, final int cmd, final byte[] payload) {
        send(task, protocol.framed(cmd, payload));
    }

    @Override
    public void onFindDevice(final boolean start) {
        // The vendor app only ever sends the start; the alert otherwise ends on the watch itself.
        final byte selector = start
                ? VeryFitConstants.APP_FIND_DEVICE_START : VeryFitConstants.APP_FIND_DEVICE_STOP;
        send("veryfit find device", VeryFitProtocol.command(VeryFitConstants.GROUP_APP,
                VeryFitConstants.APP_FIND_DEVICE, selector, (byte) 0, (byte) 0, (byte) 0));
    }

    @Override
    public void onSetAlarms(final ArrayList<? extends Alarm> alarms) {
        send("veryfit alarms", protocol.framed(VeryFitConstants.FRAMED_ALARMS,
                VeryFitProtocol.alarms(alarms)));
        queryAlarms();
    }

    @Override
    public void onAppInfoReq() {
        queryWatchfaces();
    }

    /**
     * A face goes out over the file channel under a name of the watch's own making, and the watch
     * shows it as soon as it has it.
     */
    @Override
    public void onInstallApp(final Uri uri, @NonNull final Bundle options) {
        if (fileUpload.isRunning()) {
            LOG.warn("A watchface is already on its way");
            return;
        }

        final VeryFitWatchfaceFile face = new VeryFitWatchfaceFile(uri, getContext());
        if (!face.isValid()) {
            LOG.warn("{} is not a watchface", uri);
            return;
        }

        final String file = watchfaces.nextFile();
        if (file == null) {
            LOG.warn("The watch has not said which faces it holds yet");
            queryWatchfaces();
            return;
        }
        if (!watchfaces.hasRoom(face.getOriginalSize())) {
            LOG.warn("No room left on the watch for {} bytes", face.getOriginalSize());
            return;
        }

        gbDevice.setBusyTask(R.string.uploading_watchface, getContext());
        gbDevice.sendDeviceUpdateIntent(getContext());
        fileUpload.start(file + VeryFitConstants.WATCHFACE_PACKED_SUFFIX, face.getPacked(),
                face.getOriginalSize());
    }

    /** The watch shows a face it has just taken, so the list is read back to see which one. */
    void onFileUploadFinished(final boolean success) {
        if (appIcons.isRunning()) {
            appIcons.onUploaded(success);
            return;
        }

        LOG.info("Watchface upload {}", success ? "done" : "failed");
        try {
            final TransactionBuilder builder = performInitialized("veryfit file done");
            builder.setProgress(success ? R.string.uploadwatchfaceoperation_complete
                    : R.string.uploadwatchfaceoperation_failed, false, 100);
            if (success) {
                // It takes the watch about a second to put the face away and start showing it.
                builder.sleep(WATCHFACE_SETTLE_MILLIS);
                write(builder, protocol.framed(VeryFitConstants.FRAMED_WATCHFACE_LIST, new byte[0]));
            }
            builder.queue();
        } catch (final IOException e) {
            LOG.error("Failed to close the upload", e);
        }

        if (gbDevice.isBusy()) {
            gbDevice.unsetBusyTask();
            gbDevice.sendDeviceUpdateIntent(getContext());
        }
    }

    /** File channel packets are a stream of their own, so they go out as they are. */
    void sendFile(final String task, final List<byte[]> packets, final int percent, final int label) {
        try {
            final TransactionBuilder builder = performInitialized(task);
            builder.setProgress(label, true, percent);
            for (final byte[] packet : packets) {
                builder.write(VeryFitConstants.UUID_CHARACTERISTIC_WRITE, packet);
            }
            builder.queue();
        } catch (final IOException e) {
            LOG.error("Failed to run {}", task, e);
        }
    }

    /**
     * The watch answers this for an app it holds a switch for, so one it has never heard of is
     * registered first — the same registration a notification from it would have made.
     */
    void queryAppIcon(final int appId) {
        if (!apps.containsKey(appId)) {
            apps.put(appId, true);
            sendFramed("veryfit app register", VeryFitConstants.FRAMED_APP_REGISTRY,
                    VeryFitProtocol.appRegistry(VeryFitConstants.REGISTRY_ADD, appId));
        }
        send("veryfit app icon read", VeryFitProtocol.appIcon(appId));
    }

    void sendAppIcon(final byte[] picture) {
        fileUpload.start(VeryFitConstants.ICON_FILE, picture, VeryFitConstants.FILE_ICON_TYPE,
                R.string.notifications_app_icon_upload);
    }

    void onAppIconsFinished() {
        try {
            final TransactionBuilder builder = performInitialized("veryfit app icons done");
            builder.setProgress(R.string.notifications_app_icon_upload, false, 100);
            builder.queue();
        } catch (final IOException e) {
            LOG.error("Failed to close the icon upload", e);
        }
    }

    /** The settings screen keeps what was ticked, and the button asks for it to be sent. */
    private void sendAppIcons() {
        if (fileUpload.isRunning()) {
            LOG.warn("A file is already on its way");
            return;
        }
        appIcons.upload(getDevicePrefs().getStringSet(
                DeviceSettingsPreferenceConst.PREF_UPLOAD_NOTIFICATIONS_APP_ICON,
                Collections.emptySet()));
    }

    int getFileChunkLen() {
        return Math.min(VeryFitConstants.FILE_CHUNK_LEN, getMTU() - 6);
    }

    @Override
    public void onAppStart(final UUID uuid, final boolean start) {
        if (!start) {
            return;
        }
        operateWatchface("veryfit watchface", VeryFitConstants.WATCHFACE_SELECT, uuid);
    }

    /** A face is deleted by the name it is stored under, which only the list knows. */
    @Override
    public void onAppDelete(final UUID uuid) {
        operateWatchface("veryfit watchface delete", VeryFitConstants.WATCHFACE_DELETE, uuid);
    }

    private void operateWatchface(final String task, final byte operate, final UUID uuid) {
        final String file = watchfaces.fileOf(uuid);
        if (file == null) {
            LOG.warn("No watchface known as {}", uuid);
            return;
        }
        send(task, protocol.framed(VeryFitConstants.FRAMED_WATCHFACE,
                VeryFitProtocol.watchface(operate, file)));
    }

    /** The watch only echoes what it was asked to do, so the list is what shows the result. */
    private void handleWatchfaceOperation(final byte[] payload) {
        if (payload.length < 2) {
            return;
        }
        if (payload[0] != VeryFitConstants.WATCHFACE_OK) {
            LOG.warn("Watch refused a watchface operation: {}", GB.hexdump(payload));
            return;
        }
        if (payload[1] == VeryFitConstants.WATCHFACE_SELECT
                || payload[1] == VeryFitConstants.WATCHFACE_DELETE) {
            queryWatchfaces();
        }
    }

    /** Read after every change, which is the only confirmation one landed. */
    private void queryWatchfaces() {
        send("veryfit watchface list",
                protocol.framed(VeryFitConstants.FRAMED_WATCHFACE_LIST, new byte[0]));
    }

    /** The slider fires once per step, and neighbouring steps round onto the same level. */
    private void sendBrightness() {
        final byte[] command = VeryFitSettings.brightness(getDevicePrefs());
        if (Arrays.equals(command, lastBrightness)) {
            return;
        }
        lastBrightness = command;
        send("veryfit brightness", command);
        queryBrightness();
    }

    private void sendAutoWorkout() {
        send("veryfit workout detection", VeryFitSettings.autoWorkout(getDevicePrefs()));
        send("veryfit workout detection read",
                VeryFitProtocol.query(VeryFitConstants.QUERY_AUTO_WORKOUT));
    }

    /** The vendor reads this one straight back after every write, and so do we. */
    private void queryBrightness() {
        send("veryfit brightness read", VeryFitProtocol.query(VeryFitConstants.QUERY_BRIGHTNESS));
    }

    private void queryApps() {
        sendFramed("veryfit app list", VeryFitConstants.FRAMED_APP_REGISTRY,
                VeryFitProtocol.appRegistry(VeryFitConstants.REGISTRY_LIST));
    }

    /** Reads the list straight back, which is the only way to see whether the watch took it. */
    private void queryAlarms() {
        send("veryfit alarm list", protocol.framed(VeryFitConstants.FRAMED_ALARMS_QUERY,
                new byte[]{0x00}));
    }

    @Override
    public void onSetMusicInfo(final MusicSpec musicSpec) {
        if (mediaManager.onSetMusicInfo(musicSpec)) {
            sendMusic();
        }
    }

    @Override
    public void onSetMusicState(final MusicStateSpec stateSpec) {
        if (mediaManager.onSetMusicState(stateSpec)) {
            sendMusic();
        }
    }

    /** Every change is announced twice, and the watch is told about it once. */
    @Override
    public void onSetPhoneVolume(final float volume) {
        if (watchVolume() == lastVolume) {
            return;
        }
        lastVolume = watchVolume();
        send("veryfit volume", VeryFitSettings.volume(lastVolume));
    }

    /** The watch only listens once the screen has been opened, and stops when it is closed. */
    private void sendMusic() {
        if (!getCapabilities().supports(VeryFitFeature.FRAMED_PROTOCOL)) {
            return;
        }

        final MusicSpec spec = mediaManager.getBufferMusicSpec();
        final MusicStateSpec state = mediaManager.getBufferMusicStateSpec();
        final boolean playing = spec != null && state != null;

        // The track and its state arrive as two separate updates, which often say the same thing.
        final byte[] info = VeryFitProtocol.musicInfo(spec, state, watchVolume());
        if (Arrays.equals(info, lastMusic)) {
            return;
        }
        lastMusic = info;

        try {
            final TransactionBuilder builder = performInitialized("veryfit music");
            if (playing != musicOpen) {
                musicOpen = playing;
                write(builder, VeryFitProtocol.command(VeryFitConstants.GROUP_APP,
                        VeryFitConstants.APP_MUSIC,
                        playing ? VeryFitConstants.APP_MUSIC_START : VeryFitConstants.APP_MUSIC_STOP,
                        (byte) 0, (byte) 0, (byte) 0));
            }
            write(builder, protocol.framed(VeryFitConstants.FRAMED_MUSIC, info));
            builder.queue();
        } catch (final IOException e) {
            LOG.error("Failed to send music info", e);
        }
    }

    private int watchVolume() {
        return Math.round(mediaManager.getPhoneVolume() * VeryFitConstants.MUSIC_VOLUME_STEPS / 100f);
    }

    @Override
    public void onSetTime() {
        send("veryfit set time", VeryFitSettings.time(getCapabilities()));
    }

    @Override
    public void onNotification(final NotificationSpec notificationSpec) {
        // The watch shows the source as the notification's header, so it wants the app's own name.
        final String source = StringUtils.getFirstOf(notificationSpec.getSourceName(),
                notificationSpec.getType() != null ? notificationSpec.getType().name() : "");
        final String title = StringUtils.getFirstOf(notificationSpec.getTitle(), notificationSpec.getSender());
        final String body = StringUtils.ensureNotNull(notificationSpec.getBody());
        final int appId = appId(notificationSpec.getSourceAppId());

        // An app the watch has only just been told about is not one it will quote back, so the
        // two go out as separate writes rather than back to back in one.
        if (!apps.containsKey(appId)) {
            apps.put(appId, true);
            sendFramed("veryfit app register", VeryFitConstants.FRAMED_APP_REGISTRY,
                    VeryFitProtocol.appRegistry(VeryFitConstants.REGISTRY_ADD, appId));
        }

        cannedReplies.remember(notificationSpec);
        send("veryfit notification", protocol.framed(VeryFitConstants.FRAMED_NOTIFICATION,
                VeryFitProtocol.notification(notificationSpec.getId(), appId, source, title, body)));
    }

    /** The call itself is the watch's own business over classic Bluetooth; only the number is kept. */
    @Override
    public void onSetCallState(final CallSpec callSpec) {
        cannedReplies.onCallState(callSpec);
    }

    @Override
    public void onSetCannedMessages(final CannedMessagesSpec cannedMessagesSpec) {
        if (cannedMessagesSpec.getType() != CannedMessagesSpec.TYPE_GENERIC
                || !getCapabilities().supports(VeryFitFeature.FRAMED_PROTOCOL)) {
            return;
        }
        final List<String> replies = Arrays.asList(cannedMessagesSpec.getCannedMessages());
        final int count = Math.min(replies.size(), VeryFitConstants.REPLY_SLOTS);
        LOG.info("Sending {} canned replies", count);
        send("veryfit canned replies", protocol.framed(VeryFitConstants.FRAMED_CANNED_REPLIES,
                VeryFitProtocol.cannedReplies(VeryFitConstants.REPLIES_FOR_MESSAGES, replies.subList(0, count))));
    }

    void sendReplyOutcome(final boolean sent, final byte[] pick) {
        send("veryfit reply outcome", VeryFitProtocol.replyOutcome(sent, pick));
    }

    @Override
    public void onReboot() {
        send("veryfit reboot", VeryFitProtocol.command(VeryFitConstants.GROUP_RESTART,
                VeryFitConstants.RESTART_REBOOT));
    }

    /**
     * The watch keys its per-app switch on an id the phone picks for itself; both vendor apps use
     * a different one for the same app, so the package name is folded into one of our own.
     */
    static int appId(final String packageName) {
        if (packageName == null) {
            return 1;
        }
        return Math.abs(packageName.hashCode() % 0x7ffe) + 1;
    }

    private VeryFitCapabilities getCapabilities() {
        if (features != null) {
            return new VeryFitCapabilities(features, featuresExtra);
        }
        return VeryFitCapabilities.fromPreferences(getDevicePrefs());
    }

    private void send(final String task, final byte[] command) {
        try {
            final TransactionBuilder builder = performInitialized(task);
            write(builder, command);
            builder.queue();
        } catch (final IOException e) {
            LOG.error("Failed to run {}", task, e);
        }
    }

    /** A frame too long for one write is split, and every piece carries the marker again. */
    private void write(final TransactionBuilder builder, final byte[] command) {
        final int max = Math.max(MIN_CHUNK_LEN, getMTU() - 3);
        if (command.length <= max) {
            builder.write(VeryFitConstants.UUID_CHARACTERISTIC_WRITE, command);
            return;
        }

        final int chunk = max - 1;
        for (int offset = 1; offset < command.length; offset += chunk) {
            final int size = Math.min(chunk, command.length - offset);
            final byte[] packet = new byte[size + 1];
            packet[0] = VeryFitConstants.FRAME_MARKER;
            System.arraycopy(command, offset, packet, 1, size);
            builder.write(VeryFitConstants.UUID_CHARACTERISTIC_WRITE, packet);
        }
    }

    private byte[] getStoredAuth() {
        final String hex = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress())
                .getString(PREF_BIND_AUTH, "");
        if (StringUtils.isNullOrEmpty(hex)) {
            return null;
        }
        try {
            return GB.hexStringToByteArray(hex);
        } catch (final Exception e) {
            return null;
        }
    }

    private void storeAuth(final byte[] auth) {
        GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress()).edit()
                .putString(PREF_BIND_AUTH, GB.hexdump(auth))
                .apply();
    }

    private void clearAuth() {
        GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress()).edit()
                .remove(PREF_BIND_AUTH)
                .apply();
    }
}
