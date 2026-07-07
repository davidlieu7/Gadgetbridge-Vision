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

            writeRaw(GB.hexStringToByteArray("6815040100ff035341505f42444435343931383942423336363141414537443132303941304245393346340101f4000003d44d323030374a335359000000000000005869616f6d69000000000000000000005869616f6d6900000000000000000000ffffffffffff02"), "hello-reply");
            // The phone side answers HELLO with a "SAP_<token>" frame + its
            // model/vendor. Reproduce that handshake here once decoded.
            byte[] payload = sap.buildSingle(0x01, GB.hexStringToByteArray("01040205000e"), MTU );
            byte[] replyFrame = SapFrame.wrap(payload);
            writeRaw(replyFrame, "hello-reply");
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
        switch (channel) {
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
                LOG.debug("channel 0x{} message ({} bytes)",
                        Integer.toHexString(channel), body.length);
        }
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
