/*  Copyright (C) 2026 Viktor Karpov

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.miscale;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MiScaleS400DecryptorTest {
    // Synthetic frames: made-up address, key and readings, encrypted outside Gadgetbridge
    private static final String TEST_MAC = "02:00:00:00:00:01";
    private static final String TEST_BIND_KEY = "00112233445566778899aabbccddeeff";
    private static final String TEST_FRAME = "4859d53b014a72648a1aed07db4abd4a4c0000009928b8e2";

    // Public test vectors (xiaomi-ble test suite)
    private static final String VECTOR_MAC = "8C:D0:B2:F6:BE:EF";
    private static final String VECTOR_BIND_KEY = "0728974d657a4b60964c1b1677f35f7c";

    @Test
    public void decrypt24BytePayload() {
        final byte[] data = hexToBytes(TEST_FRAME);

        final MiScaleS400Decryptor.Measurement measurement = MiScaleS400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY);

        assertNotNull(measurement);
        assertEquals(70.0f, measurement.getWeightKg(), 0.01f);
        assertEquals(500.0f, measurement.getImpedanceOhm(), 0.01f);
        assertEquals(Integer.valueOf(70), measurement.getHeartRate());
        assertEquals(1, measurement.getProfile());
        assertEquals(1767225600L, measurement.getTimestamp());
        assertNull(measurement.getSecondImpedanceOhm());
        assertFalse(measurement.isIdle());
    }

    @Test
    public void decrypt26BytePayloadWithHeader() {
        final byte[] data = hexToBytes("95fe4859d53b023251f98cedfd4b6d55f851bf000000c288ede4");

        final MiScaleS400Decryptor.Measurement measurement = MiScaleS400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY);

        assertNotNull(measurement);
        assertEquals(80.0f, measurement.getWeightKg(), 0.01f);
        assertEquals(450.0f, measurement.getImpedanceOhm(), 0.01f);
        assertEquals(Integer.valueOf(80), measurement.getHeartRate());
        assertEquals(1767225660L, measurement.getTimestamp());
        assertEquals(2, measurement.getProfile());
    }

    @Test
    public void decryptPublicWeightVector() {
        final byte[] data = hexToBytes("4859d53b0abc078ff2348c844138e930220000009e538599");

        assertEquals(MiScaleS400Decryptor.PRODUCT_ID_S400, MiScaleS400Decryptor.getProductId(data));

        final MiScaleS400Decryptor.Measurement measurement = MiScaleS400Decryptor.decrypt(data, VECTOR_MAC, VECTOR_BIND_KEY);

        assertNotNull(measurement);
        assertEquals(69.9f, measurement.getWeightKg(), 0.01f);
        assertEquals(543.2f, measurement.getImpedanceOhm(), 0.01f);
        assertEquals(Integer.valueOf(92), measurement.getHeartRate());
        assertEquals(1, measurement.getProfile());
        assertEquals(1744250605L, measurement.getTimestamp());
    }

    @Test
    public void decryptPublicSecondImpedanceVector() {
        final byte[] data = hexToBytes("4859d53b0bd6ef0b25db72785e7e2f46d6000000d8642df6");

        final MiScaleS400Decryptor.Measurement measurement = MiScaleS400Decryptor.decrypt(data, VECTOR_MAC, VECTOR_BIND_KEY);

        assertNotNull(measurement);
        assertTrue(measurement.isSecondImpedanceFrame());
        assertNull(measurement.getWeightKg());
        assertNull(measurement.getHeartRate());
        assertNull(measurement.getImpedanceOhm());
        assertEquals(497.6f, measurement.getSecondImpedanceOhm(), 0.01f);
        assertEquals(1744250605L, measurement.getTimestamp());
    }

    @Test
    public void identityBeaconCarriesNoMeasurement() {
        // Unencrypted frame with the MAC included and no object: the scale's idle beacon
        final byte[] data = hexToBytes("1059D53B12EFBEF6B2D08C");

        assertEquals(MiScaleS400Decryptor.PRODUCT_ID_S400, MiScaleS400Decryptor.getProductId(data));
        final byte[] payload = MiScaleS400Decryptor.decryptPayload(data, VECTOR_MAC, VECTOR_BIND_KEY);
        assertNotNull(payload);
        assertEquals(0, payload.length);
        assertNull(MiScaleS400Decryptor.decrypt(data, VECTOR_MAC, VECTOR_BIND_KEY));
    }

    @Test
    public void parseIdlePayload() {
        final MiScaleS400Decryptor.Measurement measurement = MiScaleS400Decryptor.parsePayload(hexToBytes("166e090100000000ed26f767"));

        assertNotNull(measurement);
        assertTrue(measurement.isIdle());
        assertEquals(1744250605L, measurement.getTimestamp());
    }

    @Test
    public void rejectWrongBindKey() {
        assertNull(MiScaleS400Decryptor.decrypt(hexToBytes("4859d53b0abc078ff2348c844138e930220000009e538599"), VECTOR_MAC, TEST_BIND_KEY));
    }

    @Test
    public void validateBindKey() {
        assertTrue(MiScaleS400Decryptor.isValidBindKey(TEST_BIND_KEY));
        assertTrue(MiScaleS400Decryptor.isValidBindKey(TEST_BIND_KEY.toUpperCase()));
        assertFalse(MiScaleS400Decryptor.isValidBindKey(null));
        assertFalse(MiScaleS400Decryptor.isValidBindKey("invalid"));
        assertFalse(MiScaleS400Decryptor.isValidBindKey("00112233445566778899aabbccddeefz"));
    }

    @Test
    public void rejectInvalidBindKey() {
        assertNull(MiScaleS400Decryptor.decrypt(hexToBytes(TEST_FRAME), TEST_MAC, "invalid"));
    }

    @Test
    public void rejectInvalidPayload() {
        assertNull(MiScaleS400Decryptor.decrypt(null, TEST_MAC, TEST_BIND_KEY));
        assertNull(MiScaleS400Decryptor.decrypt(hexToBytes("4859d53b"), TEST_MAC, TEST_BIND_KEY));
    }

    @Test
    public void rejectInvalidMac() {
        assertNull(MiScaleS400Decryptor.decrypt(hexToBytes(TEST_FRAME), "invalid", TEST_BIND_KEY));
        assertNull(MiScaleS400Decryptor.decrypt(hexToBytes(TEST_FRAME), VECTOR_MAC, TEST_BIND_KEY));
    }

    private static byte[] hexToBytes(final String hex) {
        final String cleanHex = hex.replace(" ", "");
        final byte[] bytes = new byte[cleanHex.length() / 2];
        for (int i = 0; i < cleanHex.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(cleanHex.substring(i, i + 2), 16);
        }
        return bytes;
    }
}
