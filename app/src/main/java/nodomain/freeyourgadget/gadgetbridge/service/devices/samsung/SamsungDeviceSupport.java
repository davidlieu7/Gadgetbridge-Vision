package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.UUID;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;


public class SamsungDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(SamsungDeviceSupport.class);

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
        boolean isRequest = ((body[0] >> 6) & 1) == 0;

        // The init is BAND-DRIVEN: the band REQUESTS the config it needs and we
        // must answer, or it stalls. (Verified from full both-sides captures.)
        if (isRequest) {
            switch (channel) {
                case SapProtocol.CH_CAPABILITY_0F:       // capability table -> ack 0x00
                    //sendRawMessage(SapProtocol.CH_CAPABILITY_0F, new byte[]{0x00}, "cap-ack"); //WARN: loops
                    return;
                case SapProtocol.CH_TIME_CAL:            // 0x08: /12,/13 timezone, /7 alarms, /9 calendar
                    onTimeCalendarRequest(msgId, body);
                    return;
                case SapProtocol.CH_HEALTH:              // 0x0a: /9,/4 config; /2,/3,/32,/34,/62,/63 data upload
                    onHealthRequest(msgId, body);
                    return;
                case SapProtocol.CH_NOTIFICATION:        // 0x07: /16,/22 notification config
                case SapProtocol.CH_MEDIA:               // 0x09: /1,/8 media state
                case SapProtocol.CH_SETTINGS:            // 0x0b: /16,/35 settings read-back
                case SapProtocol.CH_CONTROL:
                    LOG.debug("band request ch=0x{} msgId={} (needs a response - see capture)",
                            Integer.toHexString(channel), msgId);
                    return;
                default:
                    LOG.debug("band request ch=0x{} msgId={} unhandled",
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



    /**
     * The minimal proactive init the vendor app sends after the HELLO reply, as
     * observed in the capture(s). This gets the band into the same state the
     * vendor app reaches; see the note below about the license gate.
     */

    private void sendPostHelloInit() {
        // 1) device-info request (band replies on 0x01 with model/serial)
//        sendRawMessage(SapProtocol.CH_CONTROL, hex("01 04 02 05 00 0e"), "dev-info-req");

        // 2) TIME SYNC (dynamic!) - current epoch as a decimal string + timezone.
        //    Template from the capture; only the 10-digit epoch is swapped in.
        //    The trailing 80 70 encodes the UTC offset (GMT+8 here) - adjust for
        //    the user's timezone; 05 01 = flags.
        sendRawMessage(SapProtocol.CH_CONTROL, buildTimeSync(), "time-sync"); //required
        // start configuration requests

        // 3) push canned quick-reply lists (0x12/0 notifications, 0x12/1 calls)
//        sendRawMessage(SapProtocol.CH_QUICK_REPLY, hex("02 03 01"), "quickreply-init");
        // (send your localized canned-reply lists here: 0x12 MSG_ID 0 and 1)

        // 4) identity + BINDING TOKEN on 0x1a. The 16-byte token registers this
        //    host with the band; a random value likely works (like the HELLO
        //    token). Capture prefix 00:24:88 is Samsung's OUI.
//        sendRawMessage(SapProtocol.CH_IDENTITY, hex("01 09 01"), "identity");
//        sendRawMessage(SapProtocol.CH_IDENTITY, buildBindingToken(), "binding-token");

        // 5) capability handshake tail + control enable
//        sendRawMessage(SapProtocol.CH_CAP2_06, hex("01"), "cap06-1");
//        sendRawMessage(SapProtocol.CH_CAP2_06, hex("00"), "cap06-0");
        // end configuration requests
        sendRawMessage(SapProtocol.CH_CONTROL, hex("84 01 01"), "control-enable"); // required

        // Beyond this the app pushes calendar (0x08/8,9), health config (0x0a/4),
        // settings (0x0b), etc. Those enable features but the steps above are the
        // ones that get the band off "Continue on the phone".
    }

    /** Time sync: [83][01][39 00][02 00][03][len][epoch ascii][04 00 80 70][05 01] */
    private byte[] buildTimeSync() {
        String epoch = Long.toString(System.currentTimeMillis() / 1000L);   // 10 digits
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x83); o.write(0x01); o.write(0x39); o.write(0x00);
        o.write(0x02); o.write(0x00);
        o.write(0x03); o.write(epoch.length());
        byte[] e = epoch.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        o.write(e, 0, e.length);
        // 04 <tz>  - 00 80 70 = GMT+8 in the capture; replace per user timezone
        o.write(0x04); o.write(0x00); o.write(0x80); o.write(0x70);
        o.write(0x05); o.write(0x01);
        return o.toByteArray();
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
        String title = firstNonEmpty(spec.getTitle(), spec.getSender(), spec.getSourceName(), "Notification");
        String body  = firstNonEmpty(spec.getBody(), spec.getTitle(), "");
        String label = firstNonEmpty(spec.getSourceName(), "Message");           // 0x0e category/app label
        String pkg   = spec.getSourceAppId() != null ? spec.getSourceAppId() : "com.android.messaging";
        long when    = spec.getWhen() > 0 ? spec.getWhen() : System.currentTimeMillis();

        byte[] payload = SapPayloadBuilder.variable(0)   // hdr 0x80 = variable/request/MSG_ID 0
                .addByte(0x00, 1) // NOTI_TYPE
                .addInt32 (0x01, spec.getId())   // SEQUENCE_ID — reply/dismiss reference this later
                .addLong  (0x02, when)           // TIME  post time, epoch millis (8-B LE)
                .addString(0x03, title)          // NOTIFY_HEADING sender / title
                .addString16(0x04, body)           // NOTIFY_BODY body text
                .addByte(0x0d, 1) // NEED_QUICK_RESPONSE
                .addString(0x0e, spec.getSourceName()) //APP_NAME
                .addByte(0x0f, 1) // ALERT_TYPE
                .addByte(0x10, 1) // IS_URGENT
                //.addInt32(0x11, 5) // WINDOW_ID
                .addString(0x12, pkg) // APP_PACKAGE_NAME
                .build();

        sendMessage(SapProtocol.CH_NOTIFICATION, payload, "notification");
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.isEmpty()) return s;
        return "";
    }

}
