/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraAuth;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class OuraAuthTest {

    /** Published test vector of github.com/Th0rgal/open_oura (MIT). */
    @Test
    public void proof_matchesThePublishedVector() {
        final byte[] key = GB.hexStringToByteArray("4431967d8bacc2659743142b68391d9a");
        final byte[] nonce = GB.hexStringToByteArray("0e2d6a0a08c99b4365f458e6e97382");

        assertArrayEquals(GB.hexStringToByteArray("a38a8772d3acb6db5c2b516dd56987c8"), OuraAuth.proof(nonce, key));
    }

    @Test(expected = IllegalArgumentException.class)
    public void proof_rejectsAShortNonce() {
        OuraAuth.proof(new byte[14], new byte[16]);
    }

    @Test
    public void parseHexKey_acceptsSeparatorsAndEitherCase() {
        final byte[] key = OuraAuth.parseHexKey("00 01:02 03 04 05 06 07 08 09 0a 0B 0c 0d 0e 0f");
        assertNotNull(key);
        for (int i = 0; i < 16; i++) {
            assertEquals(i, key[i]);
        }
    }

    @Test
    public void parseHexKey_rejectsWrongLengthOrDigits() {
        assertNull(OuraAuth.parseHexKey("00112233"));
        assertNull(OuraAuth.parseHexKey("000102030405060708090A0B0C0D0E0F00"));
        assertNull(OuraAuth.parseHexKey("000102030405060708090A0B0C0D0E0G"));
        assertNull(OuraAuth.parseHexKey(null));
    }
}
