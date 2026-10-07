/*  Copyright (C) 2026 Vitaliy Tomin

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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.SettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventAppInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventCallControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.devices.samsung.SamsungSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.SamsungFit3ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.SamsungFit3ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceApp;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3BatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3CallState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3ExerciseType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3FullSettings;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthCapabilityCheck;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthDeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthDeviceInfoRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthHeartRateRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthPedometerRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthRecordType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthRequestData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthSleepRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthSyncData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthUserProfile;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthWearableMsg;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthWorkout;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3InitSetting;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3LicenseAgreement;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3MediaButton;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3MediaInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3MediaVolume;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Notification;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3NotificationCapability;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3NotificationIcon;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Settings;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3WeatherInfo;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;


public class SamsungDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(SamsungDeviceSupport.class);

    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private final Fit3BigData bigData = new Fit3BigData();
    private final Fit3HealthSync healthSync = new Fit3HealthSync();
    /** Phone-side SEQUENCE_NUMBER counter for health-channel requests we originate (see Fit3HealthRequestData#buildRequest). */
    private int healthRequestSequence = 1;
    /**
     * The sync watermark this connection claims to the band, in seconds since
     * the health channel's 1990 epoch: everything up to here has been
     * consumed, don't send it again. Every ack on {@code CH_HEALTH} carries it
     * (see {@link Fit3HealthDeviceInfoRecord}); without it the band re-sends
     * the same object a few times and then stops syncing for the rest of the
     * connection.
     *
     * <p>Advanced to the band's own clock, taken from the device-info record
     * it appends to each message, once that message has been handled - so it
     * only moves past data we have actually seen, and only ever forwards.
     * Starts at 0 ("send me everything"): the band re-offers what we already
     * have on a fresh connection, which is cheap and self-correcting, since
     * samples are keyed by timestamp and a re-insert just overwrites.
     */
    private int healthSyncWatermark;
    /** Whether this connection has already pushed the user profile (see {@link #sendUserProfile}). */
    private boolean userProfileSent;

    /** Pulls the band's diagnostic log files on CH_DEBUG; see {@link Fit3LogDump}. */
    private final Fit3LogDump logDump = new Fit3LogDump(new Fit3LogDump.Listener() {
        @Override
        public void sendDebugMessage(byte[] body, String reason) {
            sendMessage(SapProtocol.CH_DEBUG, body, reason);
        }

        @Override
        public void onLogFile(int fileId, byte[] data) {
            saveLogFile(fileId, data);
        }

        @Override
        public void onLogDumpFinished(int fileCount) {
            logDumpStamp = null;
            GB.toast("Samsung Fit 3: fetched " + fileCount + " log file(s)", Toast.LENGTH_LONG, GB.INFO);
        }
    });
    /** Shared timestamp for one dump session's file names; reset each run. */
    private String logDumpStamp;

    /** Watchface list/select/delete/install; see {@link Fit3WatchfaceManager}. */
    private final Fit3WatchfaceManager watchfaceManager = new Fit3WatchfaceManager(new Fit3WatchfaceManager.Listener() {
        @Override
        public void sendMessage(int channel, byte[] body, String reason) {
            SamsungDeviceSupport.this.sendMessage(channel, body, reason);
        }

        @Override
        public Context getContext() {
            return SamsungDeviceSupport.this.getContext();
        }

        @Override
        public GBDevice getDevice() {
            return SamsungDeviceSupport.this.getDevice();
        }

        @Override
        public BluetoothDevice getBluetoothDevice() {
            BluetoothManager manager = SamsungDeviceSupport.this.getContext().getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            return adapter != null ? adapter.getRemoteDevice(getDevice().getAddress()) : null;
        }

        @Override
        public void onWatchfaceList(List<GBDeviceApp> apps) {
            GBDeviceEventAppInfo appInfo = new GBDeviceEventAppInfo();
            appInfo.apps = apps.toArray(new GBDeviceApp[0]);
            evaluateGBDeviceEvent(appInfo);
        }

        @Override
        public void onProgress(int textRes, boolean ongoing, int percent) {
            try {
                createTransactionBuilder("watchface-progress")
                        .setProgress(textRes, ongoing, percent)
                        .queue();
            } catch (Exception e) {
                LOG.error("Samsung Fit 3: failed to update progress notification", e);
            }
        }
    });
    // Package for each id the band might later ask for an icon by, keyed on
    // the exact IMAGE_URL string (see Fit3NotificationIcon); populated as
    // notifications are pushed, since the fetch request only carries the id.
    private final Map<String, String> notificationPackagesByIconUrl = new HashMap<>();

    /** Pushes the icons of the user-picked apps ahead of time; see {@link Fit3AppIconUploader}. */
    private final Fit3AppIconUploader iconUploader = new Fit3AppIconUploader(new Fit3AppIconUploader.Listener() {
        @Override
        public int sendNotificationPayload(byte[] body, String reason) {
            for (byte[] chunk : bigData.chunk(body, Fit3BigData.DEFAULT_CHUNK_CAP)) {
                sendMessage(SapProtocol.CH_NOTIFICATION, chunk, reason);
            }
            return bigData.lastVersion();
        }

        @Override
        public Context getContext() {
            return SamsungDeviceSupport.this.getContext();
        }

        @Override
        public String getDeviceAddress() {
            return getDevice().getAddress();
        }
    });
    // TODO: replace with the real UUIDs discovered from the band.
    public static final UUID UUID_SERVICE =
            UUID.fromString("00001a1a-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHAR_NOTIFY =   // handle 0x0031, band -> phone
            UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b");
    public static final UUID UUID_CHAR_WRITE =    // handle 0x0034, phone -> band
            UUID.fromString("63e30bad-4206-4596-839f-e47cbf7a4b5d");

    private static final int MTU = 512;
    private static final String PHONE_MODEL = "SM-S918B";   // announced in HELLO; any works
    private static final String PHONE_VENDOR = "samsung";
    private String deviceMac; //get it from GB

    private int negotiatedMtu = 23;

    // The band's call UI only tracks calls that started ringing in; a call the
    // phone itself places is never pushed as a call-state notification. This
    // mirrors that: set on CALL_INCOMING, gates every later state push, and
    // clears on CALL_END/CALL_REJECT.
    private boolean callStartedByIncoming = false;

    // SAP HELLO session token and the 0x1a binding/registration token. IMPORTANT:
    // both are STABLE per bond - captures across sessions showed byte-identical
    // values, so the vendor app persists them and replays them every connect.
    // Generate ONCE at first pairing and PERSIST (e.g. device props /
    // SharedPreferences); do NOT randomize per connection. Reusing the vendor
    // app's existing bond would instead require its exact tokens - a fresh
    // Gadgetbridge pairing (band unpaired from the phone / reset) is cleaner.


    private String loadOrCreateToken(String key) {
        SharedPreferences sharedPrefs = GBApplication.getDeviceSpecificSharedPrefs(deviceMac);

        String token = sharedPrefs.getString(key, null);
        // TODO: load from persistent storage keyed by device; create+store if absent.
        // Placeholder generates a fresh 32-hex value each app run - REPLACE with a
        // persisted value or the band will see a new identity on every launch.
        if (token == null || token.isEmpty()) {
            token =SapHello.randomToken().toLowerCase();
            SharedPreferences.Editor editor = sharedPrefs.edit();
            editor.putString(key,token);
            editor.apply();
        }
        return token;
    }

    private final SapProtocol sap = new SapProtocol(new SapProtocol.Listener() {
        @Override
        public void onMessage(int channel, byte[] body) {
            handleAppMessage(channel, body);
        }

        @Override
        public void onHello(byte[] frame) {
            LOG.debug("SAP HELLO received ({} bytes)", frame.length);
            if (!SapHello.isBandHello(frame)) {
                return;
            }
            SapHello.BandHello bandHello = SapHello.parseBandHello(frame);
            if (bandHello == null) {
                LOG.warn("SAP HELLO from band is too short to parse ({} bytes)", frame.length);
                return;
            }
            LOG.debug("band identifies as '{}' / '{}'", bandHello.modelName, bandHello.vendorName);

            // Answer the band's "SAMSUNG_ACCESSARY" HELLO with our "SAP_<token>"
            // reply, echoing back its own param block. Without this the band
            // stays on "Continue on the phone".
            final String helloToken = loadOrCreateToken("sap_hello_token");
            byte[] reply = SapHello.buildPhoneHello(bandHello, helloToken, PHONE_MODEL, PHONE_VENDOR, null);
            writeRaw(reply, "sap-hello-reply");
            // The handshake is what actually completes initialization (see
            // initializeDevice); only now is the device usable, and only now may
            // anything go out through performInitialized().
            gbDevice.setUpdateState(GBDevice.State.INITIALIZED, getContext());
            userProfileSent = false;
            healthSyncWatermark = 0;
            sendPostHelloInit();
        }
    });


    public SamsungDeviceSupport() {
        super(LOG);
        addSupportedService(UUID_SERVICE);
        // Let the reassembler finish CH_DEBUG log packets by their declared
        // length instead of waiting for the next packet (see Fit3LogDump).
        sap.setReassemblyLengthEstimator(new SapReassembler.LengthEstimator() {
            @Override
            public int expectedLength(int channel, byte[] soFar) {
                return Fit3LogDump.expectedBodyLength(channel, soFar);
            }
        });
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // 1) Grow the MTU so most SAP frames fit in a single write.
        builder.requestMtu(MTU);

        // 2) Subscribe to the notify characteristic (this writes 0x0100 to the
        //    CCCD 0x0032 under the hood).
        BluetoothGattCharacteristic notify = getCharacteristic(UUID_CHAR_NOTIFY);
        builder.notify(notify, true);

        // 3) The band opens with a plaintext HELLO ("SAMSUNG_ACCESSARY" ...) once
        //    it is subscribed to. The session is only really up after we answer
        //    that and push the post-HELLO init - until then the band still shows
        //    "Continue on the phone" - so INITIALIZED is set in onHello, not
        //    here. Everything written before that point (the HELLO reply itself
        //    and the frame ACKs) goes out through writeFrames' uninitialised
        //    path, which does not re-enter this method.
        deviceMac = this.gbDevice.getAddress();
        return builder;
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        super.onMtuChanged(gatt, mtu, status);
        this.negotiatedMtu = mtu;
        LOG.debug("MTU negotiated: {}", mtu);
    }


// ------------------------------------------------------------------
    // Receive path
    // ------------------------------------------------------------------

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {
        if (super.onCharacteristicChanged(gatt, characteristic, data)) {
            return true;
        }
        if (!UUID_CHAR_NOTIFY.equals(characteristic.getUuid())) {
            return false;
        }
        byte[] value = characteristic.getValue();   // one complete SAP frame
        if (value == null || value.length == 0) {
            return true;
        }
        byte[] ack = sap.onFrameReceived(value);
        if (ack != null) {
            writeRaw(ack, "sap-ack");
        }
        return true;
    }

    private void handleAppMessage(int channel, byte[] body) {
        if (body.length == 0) {
            return;
        }
        int msgId = body[0] & 0x3F;
        boolean isResponse = ((body[0] >> 6) & 1) == 1;
        boolean isRequest = ((body[0] >> 6) & 1) == 0;

        // Log-file pull (see Fit3LogDump): the band mixes response-typed
        // (count/init) and request-typed (transfer) messages on this channel,
        // so route both to the state machine before the request/response split.
        if (channel == SapProtocol.CH_DEBUG) {
            logDump.onMessage(msgId, body);
            return;
        }
        // Watchface and file-transfer replies also come with either type bit
        // (the band initiates activate/delete too); both belong to one
        // state machine, see Fit3WatchfaceManager.
        if (channel == SapProtocol.CH_WATCHFACE) {
            watchfaceManager.onWatchfaceMessage(body);
            return;
        }
        if (channel == SapProtocol.CH_FILE_TRANSFER) {
            watchfaceManager.onFileTransferMessage(body);
            return;
        }

        // Parsing band responses here. Ussually has payload with data.
        if (isResponse) {
            switch (channel) {
                case SapProtocol.CH_CAPABILITY_0F:       // capability table -> ack 0x00
                    //sendRawMessage(SapProtocol.CH_CAPABILITY_0F, new byte[]{0x00}, "cap-ack"); //WARN: loops
                    return;
                case SapProtocol.CH_NOTIFICATION:        // 0x07: /13 capabilities, /19 icon push ack
                    onNotificationResponse(msgId, body);
                    return;
                case SapProtocol.CH_MEDIA:               // 0x09: /1,/8 media state; /4 transport button
                    onMediaRequest(msgId, body);
                    return;
                case SapProtocol.CH_HEALTH:              // 0x0a: /2 ack of a push of ours
                    if (msgId == Fit3HealthUserProfile.MSG_ID) {
                        // The band answers a phone->band SYNC_DATA with the
                        // same MSG_ID and the type bit set, echoing our
                        // sequence number. Nothing to do with it - the ack is
                        // only useful as evidence the push landed.
                        LOG.debug("Samsung Fit 3: band acked our health push ({} bytes)", body.length);
                    } else if (msgId == Fit3HealthRequestData.MSG_ID) {
                        logBandSyncState(body);
                    } else {
                        LOG.debug("band response ch=0x0a msgId={} unhandled", msgId);
                    }
                    return;
                case SapProtocol.CH_SETTINGS:            // 0x0b: /16,/35 settings read-back
                    if (msgId == Fit3BatteryInfo.MSG_ID_BATTERY) { //battery
                        handleBatteryInfo(Fit3BatteryInfo.parse(body));
                    } else if (msgId == Fit3FullSettings.MSG_ID) {
                        handleFullSettings(Fit3FullSettings.parse(body));
                    }
                    return;
                case SapProtocol.CH_CONTROL:
                    if (msgId == Fit3InitSetting.MSG_ID_INIT_SETTING) {
                        handleInitSettingInfo(Fit3InitSetting.parse(body));
                    } else if (msgId == Fit3LicenseAgreement.MSG_ID_LICENSE_AGREEMENT) {
                        LOG.info("Samsung Fit 3 license-agreement acked: {}", Fit3LicenseAgreement.isAck(body));
                    } else {
                        LOG.debug("band response ch=0x{} msgId={} (needs a response - see capture)",
                                Integer.toHexString(channel), msgId);
                    }
                    return;
                default:
                    LOG.debug("band response ch=0x{} msgId={} unhandled",
                            Integer.toHexString(channel), msgId);
                    return;
            }
        }

        // Pasrsing band request here. It usually waits some data in respone or just ack.
        if (isRequest) {
            switch (channel) {
                case SapProtocol.CH_TIME_CAL:            // 0x08: /12,/13 timezone, /7 alarms, /9 calendar
                    onTimeCalendarRequest(msgId, body);
                    return;
                case SapProtocol.CH_HEALTH:              // 0x0a: /9,/4 config; /2,/3,/32,/34,/62,/63 data upload
                    onHealthRequest(msgId, body);
                    return;
                case SapProtocol.CH_CALL:                // 0x03: /1 silence ringer, /5 reject from band
                    onCallRequest(msgId);
                    return;
                case SapProtocol.CH_MEDIA:               // 0x09: /4 transport button (see the isResponse
                    onMediaRequest(msgId, body);          // branch above - the source app isn't consistent
                    return;                                // about the type bit on this channel, so both are handled)
                case SapProtocol.CH_WEATHER:              // 0x05: /0 add-current-location, /1 refresh request
                    onWeatherRequest(msgId);
                    return;
                case SapProtocol.CH_NOTIFICATION:         // 0x07: /3 icon fetch, /0x3e big-data-chunk upload ack
                    onNotificationRequest(msgId, body);
                    return;

                default:
                    LOG.debug("band response ch=0x{} msgId={} unhandled",
                            Integer.toHexString(channel), msgId);
                    return;
            }
        }

        // Responses / data pushed by the band (health records, now-playing, etc.)
        LOG.debug("band data ch=0x{} msgId={} ({} bytes)",
                Integer.toHexString(channel), msgId, body.length);
    }

    /** Answer the band's 0x08 requests: timezone, alarms, calendar. */
    private void onTimeCalendarRequest(int msgId, byte[] req) {
        // e.g. band sends 0x0c (MSG_ID 12) -> reply with the timezone name+offset,
        // 0x07 (MSG_ID 7) -> alarms, 0x09 (MSG_ID 9) -> calendar events.
        // Build these from the user's timezone/calendar; see
        // the captured 0x08 responses for the exact field layout.
        LOG.debug("TODO respond to time/calendar request MSG_ID={}", msgId);
    }

    /** Answer / ack the band's 0x0a health requests and data uploads. */
    private void onHealthRequest(int msgId, byte[] body) {
        if (msgId == 0x3f) {
            handleHealthBulkChunk(body);
            return;
        }
        if (msgId == Fit3HealthRequestData.MSG_ID) {
            // The ping carries nothing to store, so the watermark can be
            // advanced before replying - the band's copy of it is what tells
            // it where to resume.
            advanceHealthSyncWatermark(body);
            byte[] response = Fit3HealthRequestData.buildResponse(body, healthSyncWatermark);
            if (response == null) {
                LOG.warn("Samsung Fit 3: REQUEST_DATA body too short to echo");
                return;
            }
            sendMessage(Fit3HealthRequestData.CHANNEL, response, "health-request-data-ack");
            // A REQUEST_DATA round trip is the point the band is known to be
            // ready for records on this channel - it's what the real app waits
            // for before pushing a profile - so the once-per-connection push
            // rides on the first one.
            if (!userProfileSent) {
                userProfileSent = true;
                sendUserProfile();
            }
            return;
        }
        if (msgId == Fit3HealthWearableMsg.MSG_ID) {
            byte[] response = Fit3HealthWearableMsg.buildResponse(body);
            if (response == null) {
                LOG.warn("Samsung Fit 3: WEARABLE_MSG body didn't match the expected shape");
                return;
            }
            sendMessage(Fit3HealthWearableMsg.CHANNEL, response, "health-wearable-msg-ack");
            return;
        }
        if (msgId == Fit3HealthDeviceInfo.MSG_ID) {
            sendMessage(Fit3HealthDeviceInfo.CHANNEL, Fit3HealthDeviceInfo.buildResponse(body), "health-device-info-ack");
            return;
        }
        if (msgId == Fit3HealthCapabilityCheck.MSG_ID) {
            byte[] response = Fit3HealthCapabilityCheck.buildResponse(body);
            if (response == null) {
                LOG.warn("Samsung Fit 3: capability-check body missing expected params");
                return;
            }
            sendMessage(Fit3HealthCapabilityCheck.CHANNEL, response, "health-capability-check-ack");
            return;
        }
        if (msgId == Fit3HealthSyncData.MSG_ID) {
            handleHealthSyncData(body);
            return;
        }
        // /9 = periodic locale ping; /62 = the band uploading stored
        // sleep/workout records directly (unchunked).
        LOG.debug("TODO respond/ack health request MSG_ID={}", msgId);
    }

    /**
     * Handle the band's SYNC_DATA push (see {@link Fit3HealthSyncData}):
     * persist whatever records it carries, then ack it with an advanced sync
     * watermark so the band moves on instead of re-sending it.
     */
    private void handleHealthSyncData(byte[] body) {
        processHealthRecords(body, "SYNC_DATA push");
        advanceHealthSyncWatermark(body);
        byte[] ack = Fit3HealthSyncData.buildAck(body, healthSyncWatermark);
        if (ack == null) {
            LOG.warn("Samsung Fit 3: SYNC_DATA body missing expected sequence number");
            return;
        }
        sendMessage(Fit3HealthSyncData.CHANNEL, ack, "health-sync-data-ack");
    }

    /**
     * Persist every record type we can decode out of one SYNC_DATA payload.
     * Shared by the direct-push and bulk-transfer paths: a reassembled bulk
     * object is byte-for-byte an oversized push, same header and all.
     */
    private void processHealthRecords(byte[] payload, String what) {
        int marker = Fit3HealthSyncData.firstRecordMarker(payload);
        LOG.debug("Samsung Fit 3: {} ({} bytes, first record marker 0x{} = {})",
                what, payload.length, Integer.toHexString(marker), Fit3HealthRecordType.name(marker));
        if (!Fit3HealthRecordType.carriesActivityRecords(marker)) {
            return;
        }
        List<Fit3HealthPedometerRecord> records = Fit3HealthSyncData.findPedometerRecords(payload);
        if (!records.isEmpty()) {
            LOG.info("Samsung Fit 3: {} pedometer record(s), first: {}", records.size(), records.get(0));
            storePedometerRecords(records);
        }
        List<Fit3HealthHeartRateRecord.Sample> hrSamples = Fit3HealthSyncData.findHeartRateSamples(payload);
        if (!hrSamples.isEmpty()) {
            LOG.info("Samsung Fit 3: {} heart-rate sample(s), first: {}", hrSamples.size(), hrSamples.get(0));
            storeHeartRateSamples(hrSamples);
        }
        List<Fit3HealthSleepRecord.Session> sleep = Fit3HealthSyncData.findSleepSessions(payload);
        if (!sleep.isEmpty()) {
            LOG.info("Samsung Fit 3: {} sleep session(s), first: {}", sleep.size(), sleep.get(0));
            storeSleepSessions(sleep);
        }
        Fit3HealthWorkout workout = Fit3HealthWorkout.findFirst(payload, 0);
        if (workout != null) {
            LOG.info("Samsung Fit 3: {}", workout);
            storeWorkout(workout);
        }
    }

    /**
     * Move the sync watermark up to the band's own clock as reported by the
     * device-info record trailing the message we just handled. Monotonic: a
     * message without a usable record, or one reporting a time we are already
     * past, leaves it alone.
     */
    private void advanceHealthSyncWatermark(byte[] payload) {
        int bandTime = Fit3HealthDeviceInfoRecord.findTrailingBandTime(payload);
        if (bandTime > healthSyncWatermark) {
            healthSyncWatermark = bandTime;
        }
    }

    /**
     * Log where the band thinks this sync stands, from its {@code REQUEST_DATA}
     * response - the one message that needs no reply from us, so its only value
     * is diagnostic.
     *
     * <p>The interesting field is the band's {@code last_sync_time}: it is the
     * watermark it has recorded for the phone, so seeing it keep up with
     * {@link #healthSyncWatermark} is the direct confirmation that our acks are
     * being accepted.
     *
     * <p>Only a band that has fallen <em>behind</em> is worth raising: that is
     * the state in which it starts re-sending objects it thinks we never took.
     * The band running a second or two ahead is the normal case, not a
     * discrepancy - it records the time it handled the exchange, whereas our
     * watermark is the timestamp of the last object we consumed, so the two
     * are never expected to be equal.
     */
    private void logBandSyncState(byte[] body) {
        int bandsViewOfUs = Fit3HealthDeviceInfoRecord.findTrailingLastSync(body);
        int bandClock = Fit3HealthDeviceInfoRecord.findTrailingBandTime(body);
        if (bandsViewOfUs < 0) {
            LOG.debug("Samsung Fit 3: REQUEST_DATA response carries no device-info record");
        } else if (bandsViewOfUs == 0) {
            LOG.warn("Samsung Fit 3: band has no record of our sync state - acks aren't landing,"
                    + " expect it to re-send and then stall (we claim {})", toUnixTime(healthSyncWatermark));
        } else if (bandsViewOfUs < healthSyncWatermark) {
            LOG.info("Samsung Fit 3: band has us synced only to {}, behind our {} - it may re-send"
                    + " (band clock {})", toUnixTime(bandsViewOfUs), toUnixTime(healthSyncWatermark),
                    toUnixTime(bandClock));
        } else {
            LOG.debug("Samsung Fit 3: band has us synced to {}, we claim {} (band clock {})",
                    toUnixTime(bandsViewOfUs), toUnixTime(healthSyncWatermark), toUnixTime(bandClock));
        }
    }

    /** Render a health-channel 1990-epoch timestamp as a readable instant. */
    private static String toUnixTime(int seconds1990) {
        if (seconds1990 <= 0) {
            return "never";
        }
        return new Date((seconds1990 + Fit3HealthPedometerRecord.EPOCH_1990_OFFSET_SECONDS) * 1000L)
                .toString();
    }

    private void storePedometerRecords(List<Fit3HealthPedometerRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            SamsungSampleProvider provider = new SamsungSampleProvider(getDevice(), session);
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            long userId = DBHelper.getUser(session).getId();
            List<SamsungFit3ActivitySample> samples = new ArrayList<>(records.size());
            for (Fit3HealthPedometerRecord record : records) {
                SamsungFit3ActivitySample sample = provider.createActivitySample();
                sample.setTimestamp(record.timestamp);
                sample.setDeviceId(deviceId);
                sample.setUserId(userId);
                sample.setSteps(record.count);
                sample.setRawKind(ActivityKind.ACTIVITY.getCode());
                sample.setHeartRate(ActivitySample.NOT_MEASURED);
                // The band reports kcal; ActivitySample#getActiveCalories() is in
                // calories (DashboardUtils divides by 1000 for the kcal display).
                sample.setActiveCalories(Math.round(record.calorie * 1000));
                // The band reports metres; getDistanceCm() is in centimetres.
                sample.setDistanceCm(Math.round(record.distance * 100));
                samples.add(sample);
            }
            provider.addGBActivitySamples(samples);
        } catch (Exception e) {
            LOG.error("Samsung Fit 3: failed to store pedometer record(s)", e);
            return;
        }
        // Tell the UI new activity data is in the DB - without this the
        // dashboard keeps showing whatever it computed at startup.
        GB.signalActivityDataFinish(getDevice());
    }

    /**
     * Persist per-minute heart-rate samples (see {@link
     * Fit3HealthHeartRateRecord}) into {@link SamsungSampleProvider}.
     *
     * <p>Heart rate and step data share the same one-minute timeline, and
     * {@code insertOrReplace} keys on {@code (timestamp, device)} and rewrites
     * the whole row - so a naive insert of an HR-only sample would wipe any
     * step/calorie/distance already stored for that minute. This merges into
     * the existing row instead when there is one.
     */
    private void storeHeartRateSamples(List<Fit3HealthHeartRateRecord.Sample> hrSamples) {
        if (hrSamples.isEmpty()) {
            return;
        }
        // Collapse duplicate timestamps (a single sync can repeat a minute); keep the last.
        Map<Integer, Fit3HealthHeartRateRecord.Sample> byTimestamp = new HashMap<>();
        for (Fit3HealthHeartRateRecord.Sample s : hrSamples) {
            byTimestamp.put(s.timestamp, s);
        }
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            SamsungSampleProvider provider = new SamsungSampleProvider(getDevice(), session);
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            long userId = DBHelper.getUser(session).getId();

            int minTs = Collections.min(byTimestamp.keySet());
            int maxTs = Collections.max(byTimestamp.keySet());
            Map<Integer, SamsungFit3ActivitySample> existing = new HashMap<>();
            for (SamsungFit3ActivitySample sample : session.getSamsungFit3ActivitySampleDao().queryBuilder()
                    .where(SamsungFit3ActivitySampleDao.Properties.DeviceId.eq(deviceId),
                            SamsungFit3ActivitySampleDao.Properties.Timestamp.ge(minTs),
                            SamsungFit3ActivitySampleDao.Properties.Timestamp.le(maxTs))
                    .list()) {
                existing.put(sample.getTimestamp(), sample);
            }

            List<SamsungFit3ActivitySample> samples = new ArrayList<>(byTimestamp.size());
            for (Fit3HealthHeartRateRecord.Sample s : byTimestamp.values()) {
                SamsungFit3ActivitySample sample = existing.get(s.timestamp);
                if (sample == null) {
                    sample = provider.createActivitySample();
                    sample.setTimestamp(s.timestamp);
                    sample.setDeviceId(deviceId);
                    sample.setUserId(userId);
                    sample.setSteps(ActivitySample.NOT_MEASURED);
                    sample.setActiveCalories(ActivitySample.NOT_MEASURED);
                    sample.setDistanceCm(ActivitySample.NOT_MEASURED);
                    sample.setRawKind(ActivityKind.ACTIVITY.getCode());
                }
                sample.setHeartRate(s.heartRate);
                samples.add(sample);
            }
            provider.addGBActivitySamples(samples);
        } catch (Exception e) {
            LOG.error("Samsung Fit 3: failed to store heart-rate sample(s)", e);
            return;
        }
        GB.signalActivityDataFinish(getDevice());
    }

    /**
     * Persist sleep sessions (see {@link Fit3HealthSleepRecord}) twice over,
     * because Gadgetbridge reads sleep from two unrelated places.
     *
     * <p>The <b>summary</b> ({@link BaseActivitySummary}, kind
     * {@link ActivityKind#SLEEP_ANY}) is the faithful record: a start, an end
     * and a duration, which is genuinely all the band sends. The stage
     * breakdown is scored on the phone by the vendor app from a raw per-minute
     * record we can't interpret, so there is no hypnogram to store.
     *
     * <p>The <b>per-minute samples</b> exist only to drive the UI.
     * {@link nodomain.freeyourgadget.gadgetbridge.activities.charts.SleepAnalysis}
     * and {@link nodomain.freeyourgadget.gadgetbridge.model.DailyTotals} both
     * reconstruct sleep by summing minute samples whose kind is one of the four
     * stages, and neither counts {@code SLEEP_ANY} - so without these the sleep
     * chart and the daily total read zero. They are written as
     * {@link ActivityKind#LIGHT_SLEEP}, the conventional bucket for a device
     * that reports no staging; read it as "asleep", not as a claim that the
     * sleep was light. Anything derived from the split between stages is
     * therefore meaningless for this device - only the total is real, and the
     * summary is where that total is recorded honestly.
     *
     * <p>The minute rows are merged into existing ones rather than inserted,
     * for the same reason {@link #storeHeartRateSamples} does it: a session
     * overlaps minutes that may already carry heart rate, and
     * {@code insertOrReplace} rewrites the whole row.
     */
    private void storeSleepSessions(List<Fit3HealthSleepRecord.Session> sessions) {
        if (sessions.isEmpty()) {
            return;
        }
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            SamsungSampleProvider provider = new SamsungSampleProvider(getDevice(), session);
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            long userId = DBHelper.getUser(session).getId();

            for (Fit3HealthSleepRecord.Session s : sessions) {
                BaseActivitySummary summary = new BaseActivitySummary();
                summary.setDeviceId(deviceId);
                summary.setUserId(userId);
                summary.setName(ActivityKind.SLEEP_ANY.getLabel(getContext()));
                summary.setStartTime(new Date(s.startTime * 1000L));
                summary.setEndTime(new Date(s.endTime * 1000L));
                summary.setActivityKind(ActivityKind.SLEEP_ANY.getCode());
                ActivitySummaryData data = new ActivitySummaryData();
                data.add(ActivitySummaryEntries.ACTIVE_SECONDS, s.durationSeconds(),
                        ActivitySummaryEntries.UNIT_SECONDS);
                summary.setSummaryData(data.toString());
                // De-dupe on (device, start), as storeWorkout does: the band
                // re-sends the same session on later syncs, and it grows as the
                // night goes on, so the newest copy must replace the older one.
                List<BaseActivitySummary> existing = session.getBaseActivitySummaryDao().queryBuilder()
                        .where(BaseActivitySummaryDao.Properties.DeviceId.eq(deviceId),
                                BaseActivitySummaryDao.Properties.StartTime.eq(summary.getStartTime()))
                        .list();
                if (!existing.isEmpty()) {
                    summary.setId(existing.get(0).getId());
                }
                session.getBaseActivitySummaryDao().insertOrReplace(summary);
            }

            int minTs = Integer.MAX_VALUE;
            int maxTs = Integer.MIN_VALUE;
            for (Fit3HealthSleepRecord.Session s : sessions) {
                minTs = Math.min(minTs, s.startTime);
                maxTs = Math.max(maxTs, s.endTime);
            }
            Map<Integer, SamsungFit3ActivitySample> existingSamples = new HashMap<>();
            for (SamsungFit3ActivitySample sample : session.getSamsungFit3ActivitySampleDao().queryBuilder()
                    .where(SamsungFit3ActivitySampleDao.Properties.DeviceId.eq(deviceId),
                            SamsungFit3ActivitySampleDao.Properties.Timestamp.ge(minTs),
                            SamsungFit3ActivitySampleDao.Properties.Timestamp.le(maxTs))
                    .list()) {
                existingSamples.put(sample.getTimestamp(), sample);
            }

            Map<Integer, SamsungFit3ActivitySample> samples = new HashMap<>();
            for (Fit3HealthSleepRecord.Session s : sessions) {
                for (int ts = s.startTime; ts < s.endTime; ts += 60) {
                    SamsungFit3ActivitySample sample = existingSamples.get(ts);
                    if (sample == null) {
                        sample = provider.createActivitySample();
                        sample.setTimestamp(ts);
                        sample.setDeviceId(deviceId);
                        sample.setUserId(userId);
                        sample.setSteps(ActivitySample.NOT_MEASURED);
                        sample.setHeartRate(ActivitySample.NOT_MEASURED);
                        sample.setActiveCalories(ActivitySample.NOT_MEASURED);
                        sample.setDistanceCm(ActivitySample.NOT_MEASURED);
                    }
                    sample.setRawKind(ActivityKind.LIGHT_SLEEP.getCode());
                    samples.put(ts, sample);
                }
            }
            provider.addGBActivitySamples(new ArrayList<>(samples.values()));
        } catch (Exception e) {
            LOG.error("Samsung Fit 3: failed to store sleep session(s)", e);
            return;
        }
        GB.signalActivityDataFinish(getDevice());
    }

    /**
     * Collect the band's channel-0x0a bulk-transfer chunks (see {@link
     * Fit3HealthSync}), ack the object once its last chunk is in, and handle
     * the reassembled object exactly like a direct SYNC_DATA push - which is
     * what it is, just too big to have fitted in one message.
     *
     * <p>That includes the message-level ack. The bulk ack only covers the
     * transport: without the SYNC_DATA ack on top, the band treats the whole
     * object as undelivered and re-sends it.
     */
    private void handleHealthBulkChunk(byte[] body) {
        Fit3HealthSync.Chunk chunk = Fit3HealthSync.parseChunk(body);
        if (chunk == null) {
            LOG.warn("Samsung Fit 3: malformed health bulk-transfer chunk");
            return;
        }
        byte[] reassembled = healthSync.onChunk(chunk);
        byte[] bulkAck = healthSync.takeAck();
        if (bulkAck != null) {
            sendMessage(SapProtocol.CH_HEALTH, bulkAck, "health-bulk-ack");
        }
        if (reassembled == null) {
            return;
        }
        processHealthRecords(reassembled, "health sync data reassembled");
        advanceHealthSyncWatermark(reassembled);
        byte[] ack = Fit3HealthSyncData.buildAck(reassembled, healthSyncWatermark);
        if (ack == null) {
            LOG.warn("Samsung Fit 3: reassembled bulk object missing expected sequence number");
            return;
        }
        sendMessage(Fit3HealthSyncData.CHANNEL, ack, "health-sync-data-ack");
    }

    /**
     * Persist a parsed workout (see {@link Fit3HealthWorkout}) as a
     * {@link BaseActivitySummary}, plus its {@code LIVE_DATA} samples (heart
     * rate / speed / step count over time) as activity samples in the workout
     * window so the workout detail view can chart them.
     */
    private void storeWorkout(Fit3HealthWorkout workout) {
        ActivityKind kind = Fit3ExerciseType.toActivityKind(workout.exerciseType);
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            Device device = DBHelper.getDevice(getDevice(), session);
            User user = DBHelper.getUser(session);
            long deviceId = device.getId();
            long userId = user.getId();

            BaseActivitySummary summary = new BaseActivitySummary();
            summary.setDeviceId(deviceId);
            summary.setUserId(userId);
            summary.setName(kind.getLabel(getContext()));
            summary.setStartTime(new Date(workout.startTime * 1000L));
            summary.setEndTime(new Date(workout.endTime * 1000L));
            summary.setActivityKind(kind.getCode());
            summary.setSummaryData(buildWorkoutSummaryData(workout));
            // De-dupe on (device, start): a re-sync of the same workout replaces it.
            List<BaseActivitySummary> existing = session.getBaseActivitySummaryDao().queryBuilder()
                    .where(BaseActivitySummaryDao.Properties.DeviceId.eq(deviceId),
                            BaseActivitySummaryDao.Properties.StartTime.eq(summary.getStartTime()))
                    .list();
            if (!existing.isEmpty()) {
                summary.setId(existing.get(0).getId());
            }
            session.getBaseActivitySummaryDao().insertOrReplace(summary);

            if (!workout.samples.isEmpty()) {
                SamsungSampleProvider provider = new SamsungSampleProvider(getDevice(), session);
                List<SamsungFit3ActivitySample> samples = new ArrayList<>(workout.samples.size());
                for (Fit3HealthWorkout.LiveSample ls : workout.samples) {
                    SamsungFit3ActivitySample sample = provider.createActivitySample();
                    sample.setTimestamp(ls.timestamp);
                    sample.setDeviceId(deviceId);
                    sample.setUserId(userId);
                    sample.setRawKind(kind.getCode());
                    sample.setSteps(ActivitySample.NOT_MEASURED);
                    // Not carried per live sample - only as a workout total.
                    sample.setActiveCalories(ActivitySample.NOT_MEASURED);
                    sample.setHeartRate(ls.heartRate != null ? Math.round(ls.heartRate) : ActivitySample.NOT_MEASURED);
                    // Live samples carry metres; getDistanceCm() is in centimetres.
                    sample.setDistanceCm(ls.distance != null
                            ? Math.round(ls.distance * 100) : ActivitySample.NOT_MEASURED);
                    samples.add(sample);
                }
                provider.addGBActivitySamples(samples);
            }
        } catch (Exception e) {
            LOG.error("Samsung Fit 3: failed to store workout", e);
            return;
        }
        GB.signalActivityDataFinish(getDevice());
    }

    private static String buildWorkoutSummaryData(Fit3HealthWorkout workout) {
        ActivitySummaryData data = new ActivitySummaryData();
        data.add(ActivitySummaryEntries.DISTANCE_METERS, workout.distanceMeters, ActivitySummaryEntries.UNIT_METERS);
        data.add(ActivitySummaryEntries.CALORIES_BURNT, workout.calories, ActivitySummaryEntries.UNIT_KCAL);
        data.add(ActivitySummaryEntries.STEPS, workout.count, ActivitySummaryEntries.UNIT_STEPS);
        data.add(ActivitySummaryEntries.ACTIVE_SECONDS, workout.durationMs / 1000f, ActivitySummaryEntries.UNIT_SECONDS);
        if (workout.meanHr != null) {
            data.add(ActivitySummaryEntries.HR_AVG, Math.round(workout.meanHr), ActivitySummaryEntries.UNIT_BPM);
        }
        if (workout.maxHr != null) {
            data.add(ActivitySummaryEntries.HR_MAX, Math.round(workout.maxHr), ActivitySummaryEntries.UNIT_BPM);
        }
        if (workout.minHr != null) {
            data.add(ActivitySummaryEntries.HR_MIN, Math.round(workout.minHr), ActivitySummaryEntries.UNIT_BPM);
        }
        return data.toString();
    }

    /** Act on the band's 0x03 requests: ringer silence and call reject are both fixed, paramless. */
    private void onCallRequest(int msgId) {
        GBDeviceEventCallControl callCmd = new GBDeviceEventCallControl();
        if (msgId == Fit3CallState.MSG_ID_REJECT_CALL) {
            LOG.info("Samsung Fit 3: call rejected from band");
            callCmd.event = GBDeviceEventCallControl.Event.REJECT;
            evaluateGBDeviceEvent(callCmd);
        } else if (msgId == Fit3CallState.MSG_ID_SILENCE_RINGER) {
            LOG.info("Samsung Fit 3: ringer silenced from band");
            callCmd.event = GBDeviceEventCallControl.Event.IGNORE;
            evaluateGBDeviceEvent(callCmd);
        } else {
            LOG.debug("TODO handle call request MSG_ID={}", msgId);
        }
    }

    /** Act on the band's 0x09 requests: transport button (MSG_ID 4) and volume control (MSG_ID 7). */
    private void onMediaRequest(int msgId, byte[] body) {
        if (msgId == Fit3MediaButton.MSG_ID_KEY_EVENT) {
            handleMediaButton(Fit3MediaButton.parse(body));
        } else if (msgId == Fit3MediaVolume.MSG_ID_VOLUME_CONTROL) {
            handleVolumeControl(Fit3MediaVolume.parse(body));
        } else {
            LOG.debug("TODO handle media request MSG_ID={}", msgId);
        }
    }

    /**
     * Act on the band's 0x05 requests: "add current location" (MSG_ID 0, the
     * band asking to search using its own GPS fix - no Gadgetbridge
     * equivalent) and "weather info request" (MSG_ID 1, wants a refresh).
     * Gadgetbridge has no privacy-policy/city-search flow to replicate, so
     * either request is answered the same way: push whatever's cached.
     */
    private void onWeatherRequest(int msgId) {
        if (msgId == Fit3WeatherInfo.MSG_ID_WEATHER_INFO || msgId == Fit3WeatherInfo.MSG_ID_ADD_CURRENT_LOCATION) {
            onSendWeather();
        } else {
            LOG.debug("TODO handle weather request MSG_ID={}", msgId);
        }
    }

    /**
     * Act on the band's 0x07 requests: an icon fetch (MSG_ID 3) or the
     * big-data-chunk upload ack (MSG_ID 0x3e) for an icon response already
     * sent - see {@link Fit3NotificationIcon} and {@link Fit3BigData}.
     */
    /**
     * The band's answers on {@code CH_NOTIFICATION}: the capability reply
     * (icon size, then push any picked icons it lacks) and the per-icon ack
     * of those pushes.
     */
    private void onNotificationResponse(int msgId, byte[] body) {
        if (msgId == Fit3NotificationCapability.MSG_ID) {
            Fit3NotificationCapability cap = Fit3NotificationCapability.parse(body);
            if (cap == null) {
                LOG.warn("Samsung Fit 3: notification capability reply could not be parsed");
                return;
            }
            LOG.info("Samsung Fit 3: {}", cap);
            iconUploader.setIconSize(cap.iconSize());
            iconUploader.sync();
        } else if (msgId == Fit3NotificationIcon.MSG_ID_PUSH) {
            Fit3NotificationIcon.PushAck ack = Fit3NotificationIcon.parsePushAck(body);
            if (ack == null) {
                LOG.warn("Samsung Fit 3: malformed icon push ack");
                return;
            }
            iconUploader.onAck(ack);
        } else {
            LOG.debug("Samsung Fit 3: notification response msgId={} unhandled", msgId);
        }
    }

    private void onNotificationRequest(int msgId, byte[] body) {
        if (msgId == Fit3NotificationIcon.MSG_ID_ADDITIONAL_INFO) {
            handleNotificationIconRequest(body);
        } else if (msgId == 0x3e) {
            Fit3BigData.Ack ack = Fit3BigData.parseAck(body);
            LOG.debug("Samsung Fit 3: notification icon upload ack: {}", ack);
            if (ack != null) {
                iconUploader.onTransferAck(ack);
            }
        } else {
            LOG.debug("TODO handle notification request MSG_ID={}", msgId);
        }
    }

    private void handleNotificationIconRequest(byte[] body) {
        Fit3NotificationIcon.Request request = Fit3NotificationIcon.parseRequest(body);
        if (request == null) {
            LOG.warn("Samsung Fit 3: could not parse notification icon request");
            return;
        }
        // Only picked apps' icons are ever referenced, so only those are served.
        String pkg = notificationPackagesByIconUrl.get(request.iconUrl);
        if (pkg == null) {
            pkg = iconUploader.packageForIconUrl(request.iconUrl);
        }
        if (pkg == null) {
            LOG.warn("Samsung Fit 3: no picked app for icon request '{}'", request.iconUrl);
            return;
        }
        byte[] rgba = Fit3NotificationIcon.renderIcon(getContext(), pkg, request.size);
        if (rgba == null) {
            LOG.warn("Samsung Fit 3: failed to render icon for {}", pkg);
            return;
        }
        byte[] response = Fit3NotificationIcon.buildResponse(request.iconUrl, request.size, rgba);
        for (byte[] chunk : bigData.chunk(response, Fit3BigData.DEFAULT_CHUNK_CAP)) {
            sendMessage(Fit3NotificationIcon.CHANNEL, chunk, "notification-icon");
        }
    }

    /**
     * A tap sends a press and a release; only act on the press to avoid
     * double-triggering next/previous. There's no STOP event on the phone
     * side, so that key is mapped to PAUSE instead of being dropped.
     */
    private void handleMediaButton(Fit3MediaButton button) {
        if (button == null || button.keyAction != Fit3MediaButton.ACTION_PRESSED) {
            return;
        }
        GBDeviceEventMusicControl musicCmd = new GBDeviceEventMusicControl();
        switch (button.keyCode) {
            case Fit3MediaButton.KEY_PLAYPAUSE:
                musicCmd.event = GBDeviceEventMusicControl.Event.PLAYPAUSE;
                evaluateGBDeviceEvent(musicCmd);
                break;
            case Fit3MediaButton.KEY_NEXT:
                musicCmd.event = GBDeviceEventMusicControl.Event.NEXT;
                evaluateGBDeviceEvent(musicCmd);
                break;
            case Fit3MediaButton.KEY_PREVIOUS:
                musicCmd.event = GBDeviceEventMusicControl.Event.PREVIOUS;
                evaluateGBDeviceEvent(musicCmd);
                break;
            case Fit3MediaButton.KEY_STOP:
                musicCmd.event = GBDeviceEventMusicControl.Event.PAUSE;
                evaluateGBDeviceEvent(musicCmd);
                break;
            default:
                LOG.debug("Samsung Fit 3: unhandled media key code {}", button.keyCode);
        }
    }

    /**
     * Only up/down have a Gadgetbridge equivalent; set-to-value and mute
     * on/off are logged rather than approximated, since there's no
     * absolute-volume or mute event on {@link GBDeviceEventMusicControl}.
     */
    private void handleVolumeControl(Fit3MediaVolume volume) {
        if (volume == null) {
            return;
        }
        GBDeviceEventMusicControl deviceEventMusicControl = new GBDeviceEventMusicControl();
        if (volume.command == Fit3MediaVolume.CMD_UP) {
            deviceEventMusicControl.event = GBDeviceEventMusicControl.Event.VOLUMEUP;
            evaluateGBDeviceEvent(deviceEventMusicControl);
        } else if (volume.command == Fit3MediaVolume.CMD_DOWN) {
            deviceEventMusicControl.event = GBDeviceEventMusicControl.Event.VOLUMEDOWN;
            evaluateGBDeviceEvent(deviceEventMusicControl);
        } else {
            LOG.debug("Samsung Fit 3: unhandled volume command {} (value={})", volume.command, volume.value);
        }
    }



    /**
     * The minimal proactive init the vendor app sends after the HELLO reply, as
     * observed in the capture(s). This gets the band into the same state the
     * vendor app reaches; see the note below about the license gate.
     */

    private void sendPostHelloInit() {
        // 1) device-info request (band replies on 0x01 with model/serial)
//        sendRawMessage(SapProtocol.CH_CONTROL, hex("01 04 02 05 00 0e"), "dev-info-req");

        // 2) INIT_SETTING - locale, roaming, current time/timezone, 24h-format.
        sendInitSetting();
        // start configuration requests


        // 3) push canned quick-reply lists (0x12/0 notifications, 0x12/1 calls)
//        sendRawMessage(SapProtocol.CH_QUICK_REPLY, hex("02 03 01"), "quickreply-init");
        // (send your localized canned-reply lists here: 0x12 MSG_ID 0 and 1)

        // 4) identity + BINDING TOKEN on 0x1a. The 16-byte token registers this
        //    host with the band; a random value likely works (like the HELLO
        //    token). Capture prefix 00:24:88 is Samsung's OUI.
//        sendRawMessage(SapProtocol.CH_IDENTITY, hex("01 09 01"), "identity");
//        sendRawMessage(SapProtocol.CH_IDENTITY, buildBindingToken(), "binding-token");

        // 5) watchface list (01) and all-info (00) reads - see Fit3Watchface;
        //    the list is fetched on demand by the app manager instead.
//        sendRawMessage(SapProtocol.CH_WATCHFACE, hex("01"), "watchface-list");
//        sendRawMessage(SapProtocol.CH_WATCHFACE, hex("00"), "watchface-all-info");
        // end configuration requests

        // 6) LICENSE_AGREEMENT - closes out the setup handshake on this channel.
        //    TODO: only send once the user has actually agreed in-app, rather
        //    than unconditionally accepting on their behalf.
        sendRawMessage(Fit3LicenseAgreement.CHANNEL,
                Fit3LicenseAgreement.buildRequest(true),
                "license-agreement"); // required

        sendRawMessage(Fit3BatteryInfo.CHANNEL, Fit3BatteryInfo.buildRequest(), "battery-request");

        // Notification capabilities - the icon size to render at. The reply
        // starts the push of any picked app icons the band doesn't hold yet.
        sendRawMessage(Fit3NotificationCapability.CHANNEL, Fit3NotificationCapability.buildRequest(),
                "notification-capability");

        // 7) full settings snapshot - the band tells us what it is actually set
        //    to, so the settings screen starts from its state and not ours.
        sendRawMessage(Fit3FullSettings.CHANNEL, Fit3FullSettings.buildRequest(), "full-settings-request");

        // Beyond this the app pushes calendar (0x08/8,9), settings (0x0b),
        // etc. Those enable features but the steps above are the ones that
        // get the band off "Continue on the phone". Health-sync data isn't
        // requested here - the band pings REQUEST_DATA (0x0a MSG_ID 3) on
        // its own schedule and the reply to that (see Fit3HealthRequestData,
        // wired up in onHealthRequest) is what lets it proceed.
    }

    /**
     * Push INIT_SETTING (locale, roaming, clock, timezone, 24-hour format). Sent
     * once after the HELLO reply, and again whenever the clock-format preference
     * changes - the band has no separate message for that, the format only ever
     * travels in this one.
     *
     * <p>TODO: roaming is still a placeholder; it needs TelephonyManager.
     */
    private void sendInitSetting() {
        boolean is24Hour = DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H
                .equals(getDevicePrefs().getTimeFormat());
        sendRawMessage(Fit3InitSetting.CHANNEL,
                Fit3InitSetting.buildRequest(Locale.getDefault(), false, is24Hour),
                "init-setting"); //required
    }

    /** Binding token on 0x1a MSG_ID 2: 02 0a 01 0b 00 0c 20 <32 hex ASCII>. */
    private byte[] buildBindingToken() {
        final String bindingToken = loadOrCreateToken("sap_binding_token");
        byte[] hexAscii = bindingToken.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x02); o.write(0x0a); o.write(0x01); o.write(0x0b); o.write(0x00);
        o.write(0x0c); o.write(hexAscii.length);           // 0x20 = 32
        o.write(hexAscii, 0, hexAscii.length);
        return o.toByteArray();
    }

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
        
    /** Send a pre-built inner body on a channel (alias of sendMessage). */
    private void sendRawMessage(int channel, byte[] body, String reason) {
        sendMessage(channel, body, reason);
    }


    // ------------------------------------------------------------------
    // Send path
    // ------------------------------------------------------------------

    /** Send an already-built inner payload on a channel. Fragmented over several frames if too large for one ATT write. */
    public void sendMessage(int channel, byte[] innerBody, String reason) {
        writeFrames(sap.buildFrames(channel, innerBody, negotiatedMtu), reason);
    }

    /**
     * Write a complete frame that is itself part of the handshake - the HELLO
     * reply and the per-frame ACKs. These are answers to something the band just
     * sent, so the link is up by definition, and they must NOT go through
     * {@link #performInitialized(String)}: the device is still INITIALIZING at
     * that point, which would queue another initializeDevice() run per write.
     */
    private void writeRaw(byte[] frame, String reason) {
        writeFrames(Collections.singletonList(frame), reason, false);
    }

    private void writeFrames(List<byte[]> frames, String reason) {
        writeFrames(frames, reason, true);
    }

    /** Write one or more frames back-to-back in a single transaction, so a fragmented message can't be interleaved with anything else queued. */
    private void writeFrames(List<byte[]> frames, String reason, boolean requiresInitialized) {
        try {
            TransactionBuilder builder = requiresInitialized
                    ? performInitialized(reason)
                    : createTransactionBuilder(reason);
            BluetoothGattCharacteristic characteristic = getCharacteristic(UUID_CHAR_WRITE);
            for (byte[] frame : frames) {
                builder.write(characteristic, frame);
            }
            if (requiresInitialized) {
                builder.queue();
            } else {
                builder.queueConnected();
            }
        } catch (IOException e) {
            LOG.error("failed to write '{}'", reason, e);
        }
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }


    /**
     * Gadgetbridge's manual "fetch activity data" trigger (pull-to-sync,
     * the dashboard sync button). The band otherwise only pushes health
     * data on its own schedule (see {@link #handleHealthSyncData}); this
     * pings it to start a cycle now instead of waiting.
     *
     * <p>Asks from what the database actually holds, not from
     * {@link #healthSyncWatermark} - the point of the manual trigger is "send
     * me anything I'm missing", whereas the watermark is a per-connection
     * flow-control value that also covers record types we receive but can't
     * decode. The band answering with nothing but a device-info record means
     * it has nothing outstanding, which is a normal outcome and matches what
     * the vendor app gets for the same request.
     *
     * <p>Note the request carries a single {@code last_sync_time} covering
     * every data type at once (see {@link #getLastSyncedTimestamp}), so it can
     * only ever be as recent as the least-recently-synced type.
     */
    @Override
    public void onFetchRecordedData(int dataTypes) {
        int since = getLastSyncedTimestamp();
        LOG.debug("Samsung Fit 3: manual sync, asking for everything after {}", toUnixTime(since));
        sendMessage(Fit3HealthRequestData.CHANNEL,
                Fit3HealthRequestData.buildRequest(healthRequestSequence++, since),
                "health-request-data-trigger");
    }

    /**
     * Push the user's profile - gender, birth date, height, weight, unit
     * preferences - to the band (see {@link Fit3HealthUserProfile}). Sent once
     * per connection and again whenever one of those preferences changes; the
     * band keeps no copy it can tell us about, so re-sending is the only way
     * to know it is current.
     *
     * <p>The band uses this for its own on-device calorie and distance
     * figures, which is why it wants height/weight at all - Gadgetbridge's own
     * displays don't depend on the band having it.
     */
    private void sendUserProfile() {
        ActivityUser user = new ActivityUser();
        LocalDate dateOfBirth = user.getDateOfBirth();
        boolean metric = !"imperial".equals(
                GBApplication.getPrefs().getString(SettingsActivity.PREF_UNIT_DISTANCE, "metric"));
        int now = (int) (System.currentTimeMillis() / 1000L)
                - Fit3HealthPedometerRecord.EPOCH_1990_OFFSET_SECONDS;
        LOG.debug("Samsung Fit 3: pushing user profile ({} cm, {} kg, born {})",
                user.getHeightCm(), user.getWeightKg(), dateOfBirth);
        sendMessage(Fit3HealthUserProfile.CHANNEL,
                Fit3HealthUserProfile.buildRequest(healthRequestSequence++, now, user.getGender(),
                        dateOfBirth, user.getHeightCm(), user.getWeightKg(), metric),
                "health-user-profile");
    }

    /**
     * Newest stored sample <em>that carries steps</em>, converted to the health
     * channel's 1990 epoch - so a fetch can ask the band only for what's
     * missing. 0 (= "send everything") if nothing is stored yet or the DB is
     * unavailable.
     *
     * <p>Deliberately not the newest sample of any kind. The request has room
     * for exactly one {@code last_sync_time} covering every data type, so
     * claiming the newest row in the table makes the phone assert it has step
     * data it never received: heart rate is stored per minute and sleep fills
     * every minute of a session, so either can easily sit hours ahead of the
     * last step record, and the band then correctly withholds the steps in
     * between. Seen live - the phone claimed 11:30 off a non-step row while
     * its newest step record was 10:57, and the ~57 steps the band had in that
     * window were never sent, on three consecutive manual syncs.
     *
     * <p>Steps are the type to key on because they are the one users check,
     * and because over-requesting the others is free: samples are keyed by
     * timestamp, so a re-sent heart-rate or sleep record just overwrites.
     */
    private int getLastSyncedTimestamp() {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            DaoSession session = dbHandler.getDaoSession();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            List<SamsungFit3ActivitySample> latest = session.getSamsungFit3ActivitySampleDao().queryBuilder()
                    .where(SamsungFit3ActivitySampleDao.Properties.DeviceId.eq(deviceId),
                            SamsungFit3ActivitySampleDao.Properties.Steps.gt(0))
                    .orderDesc(SamsungFit3ActivitySampleDao.Properties.Timestamp)
                    .limit(1)
                    .list();
            if (!latest.isEmpty()) {
                return Math.max(0, latest.get(0).getTimestamp() - Fit3HealthPedometerRecord.EPOCH_1990_OFFSET_SECONDS);
            }
        } catch (Exception e) {
            LOG.warn("Samsung Fit 3: couldn't look up last synced sample, requesting everything", e);
        }
        return 0;
    }

    @Override
    public void onFindDevice(boolean start) {
        LOG.debug("Samsung find device");
        /** Ring the band to find it (channel 0x02, MSG_ID 3). */
        byte[] body = SapPayloadBuilder.fixed(3).raw(0x01).rawLe16(0x02b4).raw(0x00).build();
        sendMessage(SapProtocol.CH_FIND_BAND, body, "find-band");

    }

    @Override
    public void onNotification(NotificationSpec spec) {
        String pkg = Fit3Notification.resolvePackage(spec, getContext());
        // Only apps the user picked get an icon; the rest show the band's generic one.
        boolean withIcon = !pkg.isEmpty() && iconUploader.isSelected(pkg);
        boolean iconNewlyAdded = false;
        if (withIcon) {
            // Remembered so a later icon-fetch request (which only carries this
            // id, see Fit3NotificationIcon) can be resolved back to a package.
            String iconUrl = Fit3NotificationIcon.iconUrlFor(pkg);
            boolean firstThisConnection = notificationPackagesByIconUrl.put(iconUrl, pkg) == null;
            // Ask the band to fetch the icon itself while the push hasn't been
            // confirmed - once per connection, so it isn't re-sent every time.
            iconNewlyAdded = firstThisConnection && !iconUploader.isOnBand(pkg);
        }
        sendMessage(Fit3Notification.CHANNEL,
                Fit3Notification.buildRequest(spec, pkg, withIcon, iconNewlyAdded), "notification");
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        switch (callSpec.getCommand()) {
            case CallSpec.CALL_INCOMING:
                callStartedByIncoming = true;
                sendMessage(Fit3CallState.CHANNEL, Fit3CallState.buildCallerIdPush(callSpec), "call-caller-id");
                sendMessage(Fit3CallState.CHANNEL, Fit3CallState.buildCallState(Fit3CallState.STATE_RINGING), "call-state-ringing");
                break;
            case CallSpec.CALL_ACCEPT:
            case CallSpec.CALL_START:
                if (callStartedByIncoming) {
                    sendMessage(Fit3CallState.CHANNEL, Fit3CallState.buildCallState(Fit3CallState.STATE_OFFHOOK), "call-state-offhook");
                }
                break;
            case CallSpec.CALL_END:
            case CallSpec.CALL_REJECT:
                if (callStartedByIncoming) {
                    sendMessage(Fit3CallState.CHANNEL, Fit3CallState.buildCallState(Fit3CallState.STATE_IDLE), "call-state-idle");
                }
                callStartedByIncoming = false;
                break;
            case CallSpec.CALL_OUTGOING:
            default:
                // The band's call UI only reacts to calls that started ringing in;
                // nothing to push for a call the phone itself placed.
                break;
        }
    }

    @Override
    public void onSetMusicInfo(MusicSpec musicSpec) {
        sendMessage(Fit3MediaInfo.CHANNEL, Fit3MediaInfo.buildNowPlaying(musicSpec), "media-now-playing");
    }

    @Override
    public void onSetMusicState(MusicStateSpec stateSpec) {
        sendMessage(Fit3MediaInfo.CHANNEL, Fit3MediaInfo.buildPlaybackState(stateSpec), "media-playback-state");
    }

    /**
     * Push one changed device-specific preference to the band. The keys are the
     * {@code android:key}s of devicesettings_samsung_fit3.xml, mirrored as
     * constants on {@link Fit3Settings} - generic Gadgetbridge keys, since only
     * those are reported here at all (see the note on those constants).
     */
    @Override
    public void onSendConfiguration(String config) {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        switch (config) {
            case Fit3Settings.PREF_VIBRATION_ENABLE: {
                // Gadgetbridge asks "vibrations enabled?", the band wants "muted?".
                boolean enabled = prefs.getBoolean(Fit3Settings.PREF_VIBRATION_ENABLE, true);
                sendMessage(Fit3Settings.CHANNEL, Fit3Settings.mute(!enabled), "settings-mute-vibration");
                break;
            }
            case Fit3Settings.PREF_VIBRATION_INTENSITY: {
                boolean strong = Fit3Settings.VIBRATION_INTENSITY_ENHANCED.equals(
                        prefs.getString(Fit3Settings.PREF_VIBRATION_INTENSITY,
                                Fit3Settings.VIBRATION_INTENSITY_NORMAL));
                sendMessage(Fit3Settings.CHANNEL, Fit3Settings.vibrationIntensityStrong(strong),
                        "settings-vibration-intensity");
                break;
            }
            case Fit3Settings.PREF_KEY_VIBRATION: {
                boolean on = prefs.getBoolean(Fit3Settings.PREF_KEY_VIBRATION, false);
                sendMessage(Fit3Settings.CHANNEL, Fit3Settings.touchVibration(on),
                        "settings-touch-vibration");
                break;
            }
            case Fit3Settings.PREF_SCREEN_AUTO_BRIGHTNESS: {
                boolean on = prefs.getBoolean(Fit3Settings.PREF_SCREEN_AUTO_BRIGHTNESS, true);
                sendMessage(Fit3Settings.CHANNEL, Fit3Settings.autoBrightness(on),
                        "settings-auto-brightness");
                break;
            }
            case Fit3Settings.PREF_SCREEN_BRIGHTNESS: {
                // Steps 1..10, not a percentage - see Fit3Settings#brightness.
                int level = prefs.getInt(Fit3Settings.PREF_SCREEN_BRIGHTNESS, 5);
                sendMessage(Fit3Settings.CHANNEL, Fit3Settings.brightness(level),
                        "settings-brightness");
                break;
            }
            case Fit3Settings.PREF_ALWAYS_ON_DISPLAY: {
                boolean on = prefs.getBoolean(Fit3Settings.PREF_ALWAYS_ON_DISPLAY, false);
                sendMessage(Fit3Settings.CHANNEL, Fit3Settings.alwaysOnDisplay(on),
                        "settings-always-on-display");
                break;
            }
            case Fit3Settings.PREF_LIFTWRIST_NOSHED: {
                boolean on = prefs.getBoolean(Fit3Settings.PREF_LIFTWRIST_NOSHED, false);
                sendMessage(Fit3Settings.CHANNEL, Fit3Settings.raiseWristWake(on),
                        "settings-raise-wrist-wake");
                break;
            }
            case Fit3Settings.PREF_SCREEN_TIMEOUT: {
                // ListPreference values are strings; the entries come from
                // samsung_fit3_screen_timeout_values (seconds).
                String seconds = prefs.getString(Fit3Settings.PREF_SCREEN_TIMEOUT, "10");
                try {
                    sendMessage(Fit3Settings.CHANNEL,
                            Fit3Settings.screenTimeout(Integer.parseInt(seconds)),
                            "settings-screen-timeout");
                } catch (NumberFormatException e) {
                    LOG.warn("Samsung Fit 3: bad screen-timeout preference '{}'", seconds);
                }
                break;
            }
            case ActivityUser.PREF_USER_GENDER:
            case ActivityUser.PREF_USER_DATE_OF_BIRTH:
            case ActivityUser.PREF_USER_HEIGHT_CM:
            case ActivityUser.PREF_USER_WEIGHT_KG:
            case SettingsActivity.PREF_UNIT_DISTANCE:
                // Not device preferences but app-wide ones, and the band takes
                // all of them in a single message - so any of them re-sends the
                // whole profile.
                sendUserProfile();
                break;
            case DeviceSettingsPreferenceConst.PREF_UPLOAD_NOTIFICATIONS_APP_ICON:
                // The picked-apps list changed: push whatever is new.
                iconUploader.sync();
                break;
            case DeviceSettingsPreferenceConst.PREF_TIMEFORMAT:
                // Not a CH_SETTINGS param - the band only learns the clock
                // format from INIT_SETTING, so re-send that whole message.
                sendInitSetting();
                break;
            default:
                super.onSendConfiguration(config);
        }
    }

    @Override
    public void onSendWeather() {
        WeatherSpec weatherSpec = Weather.getWeatherSpec();
        if (weatherSpec == null) {
            LOG.warn("Samsung Fit 3: no weather found in singleton");
            return;
        }
        sendMessage(Fit3WeatherInfo.CHANNEL, Fit3WeatherInfo.buildRequest(weatherSpec), "weather");
    }

    private void handleBatteryInfo(Fit3BatteryInfo info) {
        LOG.info("Samsung Fit 3 battery info: " + info);
        batteryCmd.level = info.level;
        batteryCmd.state = info.charging ? BatteryState.BATTERY_CHARGING
                : BatteryState.BATTERY_NORMAL;
        evaluateGBDeviceEvent(batteryCmd);
    }

    /**
     * Adopt the band's own settings state (see {@link Fit3FullSettings}) into
     * the device preferences, so the settings screen shows what the band is
     * really set to instead of Gadgetbridge's XML defaults. Only the params
     * that have a preference are taken; the rest are logged and dropped.
     *
     * <p>The band is authoritative here: this runs once per connection, before
     * the user can have changed anything in this session.
     */
    private void handleFullSettings(Fit3FullSettings settings) {
        if (settings == null) {
            LOG.warn("Samsung Fit 3: full-settings reply could not be parsed");
            return;
        }
        LOG.info("Samsung Fit 3 settings snapshot: {}", settings);

        GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();

        Boolean mute = settings.getBoolean(Fit3FullSettings.PARAM_MUTE);
        if (mute != null) {
            event.withPreference(Fit3Settings.PREF_VIBRATION_ENABLE, !mute);
        }
        Boolean strong = settings.getBoolean(Fit3FullSettings.PARAM_VIBRATION_INTENSITY);
        if (strong != null) {
            event.withPreference(Fit3Settings.PREF_VIBRATION_INTENSITY,
                    strong ? Fit3Settings.VIBRATION_INTENSITY_ENHANCED
                            : Fit3Settings.VIBRATION_INTENSITY_NORMAL);
        }
        Boolean touchVibration = settings.getBoolean(Fit3FullSettings.PARAM_TOUCH_VIBRATION);
        if (touchVibration != null) {
            event.withPreference(Fit3Settings.PREF_KEY_VIBRATION, touchVibration);
        }
        Integer brightness = settings.get(Fit3FullSettings.PARAM_BRIGHTNESS);
        if (brightness != null) {
            event.withPreference(Fit3Settings.PREF_SCREEN_BRIGHTNESS,
                    Math.max(Fit3Settings.BRIGHTNESS_MIN,
                            Math.min(Fit3Settings.BRIGHTNESS_MAX, brightness)));
        }
        Boolean autoBrightness = settings.getBoolean(Fit3FullSettings.PARAM_AUTO_BRIGHTNESS);
        if (autoBrightness != null) {
            event.withPreference(Fit3Settings.PREF_SCREEN_AUTO_BRIGHTNESS, autoBrightness);
        }
        Boolean alwaysOn = settings.getBoolean(Fit3FullSettings.PARAM_ALWAYS_ON_DISPLAY);
        if (alwaysOn != null) {
            event.withPreference(Fit3Settings.PREF_ALWAYS_ON_DISPLAY, alwaysOn);
        }
        Boolean raiseWrist = settings.getBoolean(Fit3FullSettings.PARAM_RAISE_WRIST_WAKE);
        if (raiseWrist != null) {
            event.withPreference(Fit3Settings.PREF_LIFTWRIST_NOSHED, raiseWrist);
        }
        Integer screenTimeout = settings.get(Fit3FullSettings.PARAM_SCREEN_TIMEOUT);
        if (screenTimeout != null) {
            // The preference is a ListPreference, so its value is a string -
            // storing an int here would blow up when the screen reads it back.
            event.withPreference(Fit3Settings.PREF_SCREEN_TIMEOUT, String.valueOf(screenTimeout));
        }

        if (event.preferences.isEmpty()) {
            LOG.warn("Samsung Fit 3: settings snapshot carried nothing we track");
            return;
        }
        evaluateGBDeviceEvent(event);
    }

    private void handleInitSettingInfo(Fit3InitSetting info) {
        if (info == null) {
            LOG.warn("Samsung Fit 3 init-setting reply could not be parsed");
            return;
        }
        LOG.info("Samsung Fit 3 init-setting reply: " + info);
        // The battery percentage here matches the CH_SETTINGS reading, but it
        // says nothing about charging - reporting it would mean guessing that
        // half of the event and could overwrite a correct state, so the real
        // reading (Fit3BatteryInfo) stays the only source.
    }

    /**
     * Pull the band's on-device diagnostic log files (see {@link Fit3LogDump}).
     * Wired to Gadgetbridge's "test new feature" debug action; the files land
     * under the app's external files dir as {@code fit3log_<time>_<id>.bin}
     * (usually gzip-compressed).
     */
    @Override
    public void onTestNewFunction(@Nullable Bundle options) {
        if (logDump.isActive()) {
            GB.toast("Samsung Fit 3: log dump already running", Toast.LENGTH_SHORT, GB.INFO);
            return;
        }
        logDumpStamp = null;
        GB.toast("Samsung Fit 3: fetching device logs...", Toast.LENGTH_SHORT, GB.INFO);
        logDump.start();
    }

    /**
     * Install a watchface package picked in the installer (see
     * {@link nodomain.freeyourgadget.gadgetbridge.devices.samsung.SamsungFit3InstallHandler},
     * which has already validated it).
     */
    @Override
    public void onInstallApp(Uri uri, @NonNull Bundle options) {
        Fit3WatchfaceFile file = Fit3WatchfaceFile.fromUri(uri, getContext());
        if (file == null) {
            LOG.warn("Samsung Fit 3: {} is not a watchface package", uri);
            GB.toast(getContext(), R.string.fwinstaller_file_not_compatible_to_device, Toast.LENGTH_LONG, GB.ERROR);
            return;
        }
        watchfaceManager.install(file);
    }

    @Override
    public void onAppInfoReq() {
        watchfaceManager.requestList();
    }

    @Override
    public void onAppStart(UUID uuid, boolean start) {
        if (start && !watchfaceManager.activate(uuid)) {
            LOG.warn("Samsung Fit 3: {} is not a watchface", uuid);
        }
    }

    @Override
    public void onAppDelete(UUID uuid) {
        if (!watchfaceManager.delete(uuid)) {
            LOG.warn("Samsung Fit 3: {} is not a watchface", uuid);
        }
    }

    @Override
    public void dispose() {
        // A dump in progress can never complete once the link is gone; drop its
        // state here so the next connection starts from a clean slate.
        logDump.cancel();
        logDumpStamp = null;
        // Same for a watchface install: the band forgets it on disconnect.
        watchfaceManager.cancel();
        iconUploader.cancel();
        super.dispose();
    }

    /** Write one reassembled log file to external storage. */
    private void saveLogFile(int fileId, byte[] data) {
        if (logDumpStamp == null) {
            logDumpStamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        }
        try {
            File out = FileUtils.getExternalFile("samsung_fit3_logs/fit3log_" + logDumpStamp + "_" + fileId + ".bin");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(data);
            }
            LOG.info("Samsung Fit 3: wrote log file {} ({} bytes) to {}", fileId, data.length, out.getAbsolutePath());
        } catch (IOException e) {
            LOG.error("Samsung Fit 3: failed to write log file {}", fileId, e);
        }
    }

}
