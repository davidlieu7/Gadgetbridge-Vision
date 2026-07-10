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

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventCallControl;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3BatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3CallState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3InitSetting;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3LicenseAgreement;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Notification;


public class SamsungDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(SamsungDeviceSupport.class);

    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
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
            if (SapHello.isBandHello(frame)) {
                // Answer the band's "SAMSUNG_ACCESSARY" HELLO with our "SAP_<token>"
                // reply. Without this the band stays on "Continue on the phone".
                final String helloToken = loadOrCreateToken("sap_hello_token");

                byte[] reply = SapHello.buildPhoneHello(helloToken, PHONE_MODEL, PHONE_VENDOR);
                writeRaw(reply, "sap-hello-reply");
                sendPostHelloInit();
            }
        }
    });


    public SamsungDeviceSupport() {
        super(LOG);
        addSupportedService(UUID_SERVICE);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice.State.INITIALIZING);

        // 1) Grow the MTU so most SAP frames fit in a single write.
        builder.requestMtu(MTU);

        // 2) Subscribe to the notify characteristic (this writes 0x0100 to the
        //    CCCD 0x0032 under the hood).
        BluetoothGattCharacteristic notify = getCharacteristic(UUID_CHAR_NOTIFY);
        builder.notify(notify, true);

        // 3) The band opens with a plaintext HELLO ("SAMSUNG_ACCESSARY" ...).
        //    Implement the HELLO reply, then mark INITIALIZED there instead of
        //    here. Marking it here is fine to get a first connection working.
        builder.setDeviceState(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice.State.INITIALIZED);
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

        // Parsing band responses here. Ussually has payload with data.
        if (isResponse) {
            switch (channel) {
                case SapProtocol.CH_CAPABILITY_0F:       // capability table -> ack 0x00
                    //sendRawMessage(SapProtocol.CH_CAPABILITY_0F, new byte[]{0x00}, "cap-ack"); //WARN: loops
                    return;
                case SapProtocol.CH_NOTIFICATION:        // 0x07: /16,/22 notification config
                    return;
                case SapProtocol.CH_MEDIA:               // 0x09: /1,/8 media state
                    return;
                case SapProtocol.CH_SETTINGS:            // 0x0b: /16,/35 settings read-back
                    if(msgId == Fit3BatteryInfo.MSG_ID_BATTERY) //battery
                        handleBatteryInfo(Fit3BatteryInfo.parse(body));
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
    private void onHealthRequest(int msgId, byte[] req) {
        // /9,/4 = config (locale, feature enable); /2,/3,/32,/34,/62,/63 = the band
        // uploading stored steps/sleep/workout records - ack them so it advances.
        LOG.debug("TODO respond/ack health request MSG_ID={}", msgId);
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



    /**
     * The minimal proactive init the vendor app sends after the HELLO reply, as
     * observed in the capture(s). This gets the band into the same state the
     * vendor app reaches; see the note below about the license gate.
     */

    private void sendPostHelloInit() {
        // 1) device-info request (band replies on 0x01 with model/serial)
//        sendRawMessage(SapProtocol.CH_CONTROL, hex("01 04 02 05 00 0e"), "dev-info-req");

        // 2) INIT_SETTING - locale, roaming, current time/timezone, 24h-format.
        //    TODO: wire roaming / is24HourFormat to real device state once a
        //    Context is available here; false/false is a safe placeholder.
        sendRawMessage(Fit3InitSetting.CHANNEL,
                Fit3InitSetting.buildRequest(Locale.getDefault(), false, false),
                "init-setting"); //required
        // start configuration requests


        // 3) push canned quick-reply lists (0x12/0 notifications, 0x12/1 calls)
//        sendRawMessage(SapProtocol.CH_QUICK_REPLY, hex("02 03 01"), "quickreply-init");
        // (send your localized canned-reply lists here: 0x12 MSG_ID 0 and 1)

        // 4) identity + BINDING TOKEN on 0x1a. The 16-byte token registers this
        //    host with the band; a random value likely works (like the HELLO
        //    token). Capture prefix 00:24:88 is Samsung's OUI.
//        sendRawMessage(SapProtocol.CH_IDENTITY, hex("01 09 01"), "identity");
//        sendRawMessage(SapProtocol.CH_IDENTITY, buildBindingToken(), "binding-token");

        // 5) capability handshake tail
//        sendRawMessage(SapProtocol.CH_CAP2_06, hex("01"), "cap06-1");
//        sendRawMessage(SapProtocol.CH_CAP2_06, hex("00"), "cap06-0");
        // end configuration requests

        // 6) LICENSE_AGREEMENT - closes out the setup handshake on this channel.
        //    TODO: only send once the user has actually agreed in-app, rather
        //    than unconditionally accepting on their behalf.
        sendRawMessage(Fit3LicenseAgreement.CHANNEL,
                Fit3LicenseAgreement.buildRequest(true),
                "license-agreement"); // required

        sendRawMessage(Fit3BatteryInfo.CHANNEL, Fit3BatteryInfo.buildRequest(), "battery-request");
        // Beyond this the app pushes calendar (0x08/8,9), health config (0x0a/4),
        // settings (0x0b), etc. Those enable features but the steps above are the
        // ones that get the band off "Continue on the phone".
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

    /** Send an already-built inner payload on a channel. */
    public void sendMessage(int channel, byte[] innerBody, String reason) {
        byte[] frame = sap.buildSingle(channel, innerBody, negotiatedMtu);
        writeRaw(frame, reason);
    }

    private void writeRaw(byte[] frame, String reason) {
        try {
            TransactionBuilder builder = performInitialized(reason);
            builder.write(getCharacteristic(UUID_CHAR_WRITE), frame);
            builder.queue();
        } catch (IOException e) {
            LOG.error("failed to write '{}'", reason, e);
        }
    }

    @Override
    public boolean useAutoConnect() {
        return false;
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
        sendMessage(Fit3Notification.CHANNEL, Fit3Notification.buildRequest(spec), "notification");
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

    private void handleBatteryInfo(Fit3BatteryInfo info) {
        LOG.info("Samsung Fit 3 battery info: " + info);
        batteryCmd.level = info.level;
        batteryCmd.state = info.charging ? BatteryState.BATTERY_CHARGING
                : BatteryState.BATTERY_NORMAL;
        evaluateGBDeviceEvent(batteryCmd);
    }

    private void handleInitSettingInfo(Fit3InitSetting info) {
        if (info == null) {
            LOG.warn("Samsung Fit 3 init-setting reply could not be parsed");
            return;
        }
        LOG.info("Samsung Fit 3 init-setting reply: " + info);
        // Coarse 1-6 level code here, not a percentage - not fed into
        // batteryCmd; use Fit3BatteryInfo (CH_SETTINGS) for the real reading.
    }

}
