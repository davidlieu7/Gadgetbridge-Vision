/* Copyright 2026 Gadgetbridge Contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.services;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class ZeppOsTodoCodecTest {
    static byte[] hex(final String text) {
        final byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) { bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16); }
        return bytes;
    }

    @Test public void versionThreePacketUsesUtf8AndExactFieldOrder() {
        final ZeppOsTodoCodec.Item item = new ZeppOsTodoCodec.Item(0x01020304L, "bread", 0, 1, 2, 3, 1, 0);
        assertArrayEquals(hex("0504030201627265616400000100000002000000030000000100000000000000"), ZeppOsTodoCodec.upsert(item, 3));
        final Map<Long, ZeppOsTodoCodec.Item> list = ZeppOsTodoCodec.list(
                hex("04010004030201627265616400000100000002000000030000000100000000000000"), 3, 10);
        assertEquals(item, list.get(0x01020304L));
        assertArrayEquals(hex("0704030201"), ZeppOsTodoCodec.delete(item.id));
        assertArrayEquals(ZeppOsTodoCodec.upsert(item, 3), ZeppOsTodoCodec.upsert(item, 2));
        assertEquals(list, ZeppOsTodoCodec.list(
                hex("04010004030201627265616400000100000002000000030000000100000000000000"), 2, 10));
    }

    @Test public void versionOneOmitsFirstRemindAndUnsignedValuesSurviveDecoding() {
        final ZeppOsTodoCodec.Item item = new ZeppOsTodoCodec.Item(0xffffffffL, "é", 2, 0, 2, 3, 16, 0xffffffffL);
        assertEquals(item, ZeppOsTodoCodec.list(hex("040100ffffffffc3a90002020000000300000010000000ffffffff"), 1, 1).get(item.id));
        assertArrayEquals(hex("05ffffffffc3a90002020000000300000010000000ffffffff"), ZeppOsTodoCodec.upsert(item, 1));
    }

    @Test public void incompleteOrAmbiguousListsAreNeverPartiallyAccepted() {
        for (final String packet : new String[]{"04", "040100", "04000001", "040001", // 256 items, not zero.
                "04010001000000ff00000000000000000000000000000000000000000000"}) {
            try { ZeppOsTodoCodec.list(hex(packet), 3, 10); fail(packet); }
            catch (final IllegalArgumentException expected) { }
        }
        assertTrue(ZeppOsTodoCodec.list(hex("040000"), 3, 10).isEmpty());
        try { ZeppOsTodoCodec.list(hex("040000"), 4, 10); fail(); }
        catch (final IllegalArgumentException expected) { }
    }

    @Test public void textLimitCountsUtf8BytesAndEmbeddedNullIsRejected() {
        for (final String text : new String[]{"é".repeat(76), "bread\0changed", "", "\ud83d", null}) {
            try { ZeppOsTodoCodec.upsert(new ZeppOsTodoCodec.Item(1, text, 0, 1, 1, 1, 1, 0), 3); fail(); }
            catch (final IllegalArgumentException expected) { }
        }
        ZeppOsTodoCodec.upsert(new ZeppOsTodoCodec.Item(1, "é".repeat(75), 0, 1, 1, 1, 1, 0), 3);
    }

    @Test public void duplicateIdsAndOutOfRangeIntegersAreRejected() {
        final byte[] entry = hex("04010004030201627265616400000100000002000000030000000100000000000000");
        final byte[] duplicate = new byte[3 + (entry.length - 3) * 2];
        duplicate[0] = 4; duplicate[1] = 2;
        System.arraycopy(entry, 3, duplicate, 3, entry.length - 3);
        System.arraycopy(entry, 3, duplicate, entry.length, entry.length - 3);
        assertThrows(IllegalArgumentException.class, () -> ZeppOsTodoCodec.list(duplicate, 3, 2));
        for (final long value : new long[]{-1, 0x100000000L}) {
            assertThrows(IllegalArgumentException.class, () -> ZeppOsTodoCodec.delete(value));
            assertThrows(IllegalArgumentException.class, () -> ZeppOsTodoCodec.upsert(
                    new ZeppOsTodoCodec.Item(1, "bread", 0, value, 1, 1, 1, 0), 3));
        }
    }
}
