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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3FileTransfer;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3Watchface;

/**
 * Watchface install path: the {@code CH_WATCHFACE} and {@code CH_FILE_TRANSFER}
 * messages (checked against bytes from a vendor install capture), the RFCOMM
 * upload framing and exchange, and the package parser.
 */
public class Fit3WatchfaceTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    // ---- CH_WATCHFACE ----

    /** The band's list reply after the install: 8 faces, the new one (21) first and current. */
    private static final String LIST_AFTER_INSTALL =
            "c103081808041505053130303032060e77665f6e616d652d303030323100080009000a000b010c00"
            + "1808041605053130303034060e77665f6e616d652d303030323200080009010a000b000c00"
            + "180a04fe05053130303030060e77665f6e616d652d303032353400080009020a000b000c000d011e01"
            + "1808044f05053130303032060e77665f6e616d652d303030373900080409030a040b000c00"
            + "1808041c05053130303034060e77665f6e616d652d303030323800080009040a000b000c00"
            + "1808046c05053130303033060e77665f6e616d652d303031303800080109050a010b000c00"
            + "1808046a05053130303032060e77665f6e616d652d303031303600080109060a010b000c00"
            + "1808042e05053130303038060e77665f6e616d652d303030343600080209070a020b000c00";

    @Test
    public void listRequestMatchesCapture() {
        assertArrayEquals(hex("01"), Fit3Watchface.buildListRequest());
    }

    @Test
    public void parsesCapturedList() {
        List<Fit3Watchface.Entry> entries = Fit3Watchface.parseList(hex(LIST_AFTER_INSTALL));
        assertNotNull(entries);
        assertEquals(8, entries.size());

        Fit3Watchface.Entry first = entries.get(0);
        assertEquals(21, first.id);
        assertEquals(0, first.variant);
        assertEquals(0, first.position);
        assertTrue(first.active);
        assertEquals("10002", first.version);
        assertEquals("the trailing NUL is dropped", "wf_name-00021", first.name);

        // Entry with the extra clock-type / clock-colour params still walks cleanly.
        Fit3Watchface.Entry third = entries.get(2);
        assertEquals(254, third.id);
        assertFalse(third.active);

        Fit3Watchface.Entry styled = entries.get(3);
        assertEquals(79, styled.id);
        assertEquals("listed style is the variant id", 4, styled.variant);

        assertEquals(46, entries.get(7).id);
        assertEquals(7, entries.get(7).position);
    }

    @Test
    public void listWalkStopsOnUnknownParam() {
        // One entry, 2 params: id 5, then an unknown id 0x7f.
        List<Fit3Watchface.Entry> entries = Fit3Watchface.parseList(hex("c1 03 01 18 02 04 05 7f 01"));
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals(5, entries.get(0).id);
    }

    @Test
    public void listRejectsOtherMessages() {
        assertNull(Fit3Watchface.parseList(hex("44160104151d00")));
    }

    @Test
    public void installRequestMatchesCapture() {
        assertArrayEquals(hex("04 04 15 1d 00"), Fit3Watchface.buildInstallRequest(21, 0));
    }

    @Test
    public void parsesCapturedInstallResult() {
        Fit3Watchface.InstallResult r = Fit3Watchface.parseInstallResult(hex("44 16 01 04 15 1d 00"));
        assertNotNull(r);
        assertEquals(21, r.id);
        assertEquals(0, r.variant);
        assertTrue(r.isSuccess());

        Fit3Watchface.InstallResult refused = Fit3Watchface.parseInstallResult(hex("44 16 05 04 15 1d 00"));
        assertNotNull(refused);
        assertEquals(Fit3Watchface.INSTALL_FAILED_LIST_FULL, refused.status);
    }

    @Test
    public void activateAndDeleteShareTheIdPairShape() {
        assertArrayEquals(hex("03 04 4f 1d 04"), Fit3Watchface.buildActivateRequest(0x4f, 4));
        assertArrayEquals(hex("05 04 4f 1d 04"), Fit3Watchface.buildDeleteRequest(0x4f, 4));
        assertArrayEquals(new int[]{0x4f, 4}, Fit3Watchface.parseIdPair(hex("43 04 4f 1d 04")));
        assertEquals(Boolean.TRUE, Fit3Watchface.parseDeleteResult(hex("45 04 4f 1d 04 1a 01")));
        assertEquals(Boolean.FALSE, Fit3Watchface.parseDeleteResult(hex("45 04 4f 1d 04 1a 00")));
        assertNull(Fit3Watchface.parseDeleteResult(hex("45 04 4f 1d 04")));
    }

    @Test
    public void uuidRoundTrip() {
        UUID uuid = Fit3Watchface.toUuid(254, 3);
        assertArrayEquals(new int[]{254, 3}, Fit3Watchface.fromUuid(uuid));
        assertNull(Fit3Watchface.fromUuid(UUID.randomUUID()));
    }

    // ---- CH_FILE_TRANSFER ----

    @Test
    public void fileInfoRequestMatchesCapture() {
        byte[] expected = hex("01 03 06 04 19 534d2d523339305f30303032315f323536783430322e62696e 05 0a5e3c00");
        assertArrayEquals(expected, Fit3FileTransfer.buildOfferFileRequest(
                Fit3FileTransfer.PURPOSE_WATCHFACE, "SM-R390_00021_256x402.bin", 3956234));
    }

    @Test
    public void btOpenAndCloseRequests() {
        assertArrayEquals(hex("02"), Fit3FileTransfer.buildOpenSocketRequest());
        assertArrayEquals(hex("03"), Fit3FileTransfer.buildCloseSocketRequest());
    }

    @Test
    public void parsesCapturedFileTransferReplies() {
        Fit3FileTransfer.Response info = Fit3FileTransfer.parse(hex("41 01 01 02 00 03 06 06 10000000"));
        assertNotNull(info);
        assertEquals(Fit3FileTransfer.MSG_ID_OFFER_FILE, info.msgId);
        assertTrue(info.status);
        assertEquals(0, info.reason);
        assertEquals(Fit3FileTransfer.PURPOSE_WATCHFACE, info.purpose);
        assertEquals(16, info.freeSpace);

        Fit3FileTransfer.Response open = Fit3FileTransfer.parse(hex("42 01 01 02 00"));
        assertNotNull(open);
        assertEquals(Fit3FileTransfer.MSG_ID_OPEN_SOCKET, open.msgId);
        assertTrue(open.status);

        Fit3FileTransfer.Response refused = Fit3FileTransfer.parse(hex("41 01 00 02 02"));
        assertNotNull(refused);
        assertFalse(refused.status);
        assertEquals(Fit3FileTransfer.REASON_LOW_MEMORY, refused.reason);

        assertNull("requests are not replies", Fit3FileTransfer.parse(hex("02")));
    }

    // ---- RFCOMM framing ----

    @Test
    public void headerMatchesCapture() {
        assertEquals("33bin,/user/wf/SM-R390_00021_256x402.bin,3956234",
                Fit3SppFileSender.buildHeader(Fit3SppFileSender.DIR_WATCHFACE, "SM-R390_00021_256x402.bin", 3956234));
        assertEquals("a bare directory is normalised", "33bin,/user/wf/x.bin,1",
                Fit3SppFileSender.buildHeader("user/wf", "x.bin", 1));
    }

    @Test
    public void blocksCarryLittleEndianCrc32() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] block = Fit3SppFileSender.buildBlock(check, 0);
        // CRC-32 check value 0xCBF43926, appended LE.
        assertArrayEquals(hex("313233343536373839 2639f4cb"), block);
    }

    @Test
    public void blockSplitMatchesCapture() {
        // The captured 3956234-byte face went out as 99 full blocks and one of 35834.
        assertEquals(100, Fit3SppFileSender.blockCount(3956234));
        byte[] data = new byte[3956234];
        assertEquals(35834 + 4, Fit3SppFileSender.buildBlock(data, 99).length);
        assertEquals(Fit3SppFileSender.BLOCK_SIZE + 4, Fit3SppFileSender.buildBlock(data, 0).length);
    }

    /**
     * A band stand-in: answers each command written to it as the real one
     * does, checks every block's CRC, and can reject one block once to drive
     * the resend path.
     */
    private static final class FakeBand {
        final ArrayDeque<Byte> replies = new ArrayDeque<>();
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final List<String> commands = new ArrayList<>();
        int rejectBlock = -1;
        int blocksSeen;

        final OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                onWrite(Arrays.copyOfRange(b, off, off + len));
            }
        };

        final InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                if (replies.isEmpty()) {
                    throw new IOException("phone waited for a reply the band never sent");
                }
                return replies.poll() & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (replies.isEmpty()) {
                    throw new IOException("phone waited for a reply the band never sent");
                }
                int n = 0;
                while (n < len && !replies.isEmpty()) {
                    b[off + n++] = replies.poll();
                }
                return n;
            }
        };

        private void reply(String s) {
            for (byte b : s.getBytes(StandardCharsets.US_ASCII)) {
                replies.add(b);
            }
        }

        private void onWrite(byte[] data) {
            String text = new String(data, StandardCharsets.US_ASCII);
            if (data.length <= 64 && text.matches("3[0-4].*")) {
                commands.add(text);
                reply(text.substring(0, 2) + "0");
                return;
            }
            // A data block: payload + CRC-32 LE.
            int len = data.length - 4;
            CRC32 crc = new CRC32();
            crc.update(data, 0, len);
            long expected = crc.getValue();
            long actual = (data[len] & 0xFFL) | ((data[len + 1] & 0xFFL) << 8)
                    | ((data[len + 2] & 0xFFL) << 16) | ((data[len + 3] & 0xFFL) << 24);
            if (expected != actual) {
                reply("311");
                return;
            }
            if (blocksSeen == rejectBlock) {
                rejectBlock = -1;
                reply("311");
                return;
            }
            received.write(data, 0, len);
            blocksSeen++;
            reply("310");
        }
    }

    private static byte[] ramp(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    @Test
    public void transferRunsTheFullExchange() throws IOException {
        byte[] data = ramp(Fit3SppFileSender.BLOCK_SIZE * 2 + 1234);
        FakeBand band = new FakeBand();
        band.rejectBlock = 1;
        final List<Integer> progress = new ArrayList<>();
        Fit3SppFileSender sender = new Fit3SppFileSender(new Fit3SppFileSender.Callback() {
            @Override
            public void onProgress(int percent) {
                progress.add(percent);
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        sender.transfer(band.in, band.out, Fit3SppFileSender.DIR_WATCHFACE, "SM-R390_00021_256x402.bin", data);

        assertEquals(Arrays.asList("30", "33bin,/user/wf/SM-R390_00021_256x402.bin," + data.length, "32", "34"),
                band.commands);
        assertArrayEquals("the band ends up with the exact file, resend included", data, band.received.toByteArray());
        assertEquals(Arrays.asList(33, 66, 100), progress);
    }

    // ---- package parser ----

    /** A minimal valid package: header, entry table, payloads with correct CRCs. */
    private static byte[] buildPackage(String dir, String[] names, byte[][] payloads) {
        int count = names.length;
        int offset = 32 + 74 * count;
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            byte[] name = Arrays.copyOf(("./" + dir + "/" + names[i]).getBytes(StandardCharsets.US_ASCII), 64);
            table.write(name, 0, 64);
            writeLe(table, offset, 4);
            writeLe(table, payloads[i].length, 4);
            writeLe(table, Fit3WatchfaceFile.crc16CcittFalse(payloads[i], 0, payloads[i].length), 2);
            body.write(payloads[i], 0, payloads[i].length);
            offset += payloads[i].length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('o');
        out.write('p');
        out.write('p');
        out.write('o');
        writeLe(out, 4, 4);
        writeLe(out, table.size() + body.size(), 4);
        writeLe(out, count, 4);
        writeLe(out, 0, 4);
        out.write(new byte[12], 0, 12);
        out.write(table.toByteArray(), 0, table.size());
        out.write(body.toByteArray(), 0, body.size());
        return out.toByteArray();
    }

    private static void writeLe(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((int) (value >> (8 * i)) & 0xFF);
        }
    }

    @Test
    public void crc16MatchesCheckValue() {
        // CRC-16/CCITT-FALSE check value.
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x29B1, Fit3WatchfaceFile.crc16CcittFalse(check, 0, check.length));
    }

    @Test
    public void parsesValidPackage() {
        byte[] pkg = buildPackage("SM-R390_00021_256x402", new String[]{"aod.bin", "setting.bin"},
                new byte[][]{ramp(300), ramp(40)});
        Fit3WatchfaceFile file = Fit3WatchfaceFile.parse(pkg);
        assertNotNull(file);
        assertEquals("SM-R390", file.model);
        assertEquals(21, file.id);
        assertEquals(256, file.width);
        assertEquals(402, file.height);
        assertEquals("SM-R390_00021_256x402.bin", file.fileName);
        assertEquals(2, file.entryNames.size());
        assertTrue(file.isForFit3());
        assertArrayEquals(pkg, file.getData());
    }

    @Test
    public void flagsPackageForAnotherDevice() {
        byte[] pkg = buildPackage("SM-R375_00012_240x432", new String[]{"aod.bin"}, new byte[][]{ramp(10)});
        Fit3WatchfaceFile file = Fit3WatchfaceFile.parse(pkg);
        assertNotNull(file);
        assertFalse(file.isForFit3());
    }

    @Test
    public void rejectsCorruptPackages() {
        byte[] pkg = buildPackage("SM-R390_00021_256x402", new String[]{"aod.bin"}, new byte[][]{ramp(100)});

        byte[] badCrc = pkg.clone();
        badCrc[badCrc.length - 1] ^= 0x01;
        assertNull("payload byte flipped", Fit3WatchfaceFile.parse(badCrc));

        byte[] truncated = Arrays.copyOf(pkg, pkg.length - 1);
        assertNull("size no longer matches", Fit3WatchfaceFile.parse(truncated));

        byte[] badMagic = pkg.clone();
        badMagic[0] = 'x';
        assertNull(Fit3WatchfaceFile.parse(badMagic));
    }
}
