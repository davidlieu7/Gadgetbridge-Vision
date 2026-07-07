package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.fitpro.FitProDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

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

    private int negotiatedMtu = 23;

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
                byte[] reply = SapHello.buildPhoneHello("SM-S918B", "samsung");
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
        // Dispatch reassembled application messages. Decode per channel using the
        // field maps (health 0x0A, media 0x09, etc.).
        int msgId = body.length > 0 ? (body[0] & 0x3F) : -1;
        switch (channel) {
            case SapProtocol.CH_CAPABILITY_0F:
                // The band pushes its capability table (MSG_ID 0) repeatedly during
                // setup; the vendor app answers with a single 0x00. Mirror that.
                sendRawMessage(SapProtocol.CH_CAPABILITY_0F, new byte[]{0x00}, "cap-ack");
                break;
            case SapProtocol.CH_MEDIA:
                LOG.debug("media message ({} bytes)", body.length);
                break;
            case SapProtocol.CH_HEALTH:
                LOG.debug("health record ({} bytes)", body.length);
                break;
            case SapProtocol.CH_FIND_BAND:
                LOG.debug("find-band status");
                break;
            default:
                LOG.debug("channel 0x{} msgId {} ({} bytes)",
                        Integer.toHexString(channel), msgId, body.length);
        }
    }


    /**
     * The minimal proactive init the vendor app sends after the HELLO reply, as
     * observed in the capture(s). This gets the band into the same state the
     * vendor app reaches; see the note below about the license gate.
     */
    private void sendPostHelloInit() {
        // Ask the band for its device info (model/serial). Band replies on 0x01.
        sendRawMessage(SapProtocol.CH_CONTROL, new byte[]{0x01, 0x04, 0x02, 0x05, 0x00, 0x0D}, "dev-info-req");
        // Quick-reply channel init.
        sendRawMessage(SapProtocol.CH_QUICK_REPLY, new byte[]{0x02, 0x03, 0x01}, "quickreply-init");
        // Time request/sync trigger.
        sendRawMessage(SapProtocol.CH_TIME_CAL, new byte[]{0x07}, "time-req");
        
        // NOTE (license gate): the band shows "Continue on the phone" until the
        // user accepts the terms in the vendor app. The message that advances it
        // is sent by the app *after* acceptance and is NOT in the init captures
        // (they all end before acceptance). Capture a full pairing that includes
        // tapping "accept" to obtain it, then send it here.
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
}
