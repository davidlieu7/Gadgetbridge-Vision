package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

/**
 * CRC-16/ARC (a.k.a. CRC-16/IBM / CRC-16/ANSI).
 *
 * poly 0x8005 reflected (-> 0xA001), init 0x0000, refin/refout = true, xorout 0x0000.
 * Verified against Galaxy Fit3 SAP captures: this reproduces both the header CRC
 * (over the 2 length bytes) and the payload CRC exactly.
 */
public final class Crc16 {

    private Crc16() {
    }

    public static int arc(byte[] data) {
        return arc(data, 0, data.length);
    }

    public static int arc(byte[] data, int offset, int length) {
        int crc = 0x0000;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc = crc >>> 1;
                }
            }
        }
        return crc & 0xFFFF;
    }
}
