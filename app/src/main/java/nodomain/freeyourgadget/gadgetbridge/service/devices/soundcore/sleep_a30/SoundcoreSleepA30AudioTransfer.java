/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sleep_a30;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Blocking, ACK-driven BESOTA transfer; socket ownership and deadlines belong to the caller. */
public final class SoundcoreSleepA30AudioTransfer {
    private static final byte[] BEST = "BEST".getBytes(StandardCharsets.US_ASCII);
    private final InputStream input;
    private final OutputStream output;
    private final Runnable beforeWait;

    public interface Progress { void acknowledged(int bytes, int total); }

    public SoundcoreSleepA30AudioTransfer(InputStream input, OutputStream output, Runnable beforeWait) {
        this.input = input;
        this.output = output;
        this.beforeWait = beforeWait;
    }

    public void upload(SoundcoreSleepA30Audio audio, Progress progress) throws IOException {
        exchange(0x99, new byte[]{0, 0, 0, 1}, 0x9a, new byte[]{0, 0, 0, 1});
        exchange(0x97, new byte[]{0x0b}, 0x98, new byte[]{1});
        exchange(0x90, new byte[]{0}, 0x91, new byte[]{1});
        byte[] handshake = new byte[44];
        System.arraycopy(BEST, 0, handshake, 0, 4);
        System.arraycopy(new byte[]{1, 2, 3, 4, 4, (byte) 0x96, (byte) 0xce, (byte) 0xfd}, 0, handshake, 36, 8);
        send(0x8c, handshake);
        byte[] response = receive(0x8d);
        if (response.length != 40 || SoundcoreSleepA30Audio.u32(response, 0) != 0) throw new IOException("Unsupported BESOTA handshake response");
        byte[] bytes = audio.bytes();
        byte[] start = Arrays.copyOf(BEST, 12);
        SoundcoreSleepA30Audio.put32(start, 4, bytes.length);
        SoundcoreSleepA30Audio.put32(start, 8, SoundcoreSleepA30Audio.crc32(bytes, 0, bytes.length));
        exchange(0x80, start, 0x81, new byte[]{'B', 'E', 'S', 'T', 1, 0, 1, 0, (byte) 0x9d, 2});
        exchange(0x86, audio.configuration(), 0x87, new byte[]{1});
        for (int offset = 0; offset < bytes.length; ) {
            int end = Math.min(offset + 32768, bytes.length);
            for (int p = offset; p < end; p += 656) send(0x85, Arrays.copyOfRange(bytes, p, Math.min(p + 656, end)));
            long crc = SoundcoreSleepA30Audio.crc32(bytes, offset, end - offset);
            byte[] checkpoint = Arrays.copyOf(BEST, 8);
            SoundcoreSleepA30Audio.put32(checkpoint, 4, crc);
            byte[] acknowledgement = new byte[5];
            acknowledgement[0] = 1;
            SoundcoreSleepA30Audio.put32(acknowledgement, 1, crc);
            exchange(0x82, checkpoint, 0x83, acknowledgement);
            offset = end;
            progress.acknowledged(offset, bytes.length);
        }
        exchange(0x88, new byte[0], 0x84, new byte[]{1});
        exchange(0x92, BEST, 0x93, new byte[]{1});
    }

    private void exchange(int opcode, byte[] payload, int expectedOpcode, byte[] expected) throws IOException {
        send(opcode, payload);
        if (!Arrays.equals(receive(expectedOpcode), expected)) throw new IOException("BESOTA response rejected or unsupported for opcode " + Integer.toHexString(opcode));
    }

    private void send(int opcode, byte[] payload) throws IOException {
        beforeWait.run();
        byte[] header = new byte[5];
        header[0] = (byte) opcode;
        SoundcoreSleepA30Audio.put32(header, 1, payload.length);
        output.write(header);
        output.write(payload);
        output.flush();
    }

    private byte[] receive(int expectedOpcode) throws IOException {
        beforeWait.run();
        byte[] header = readFully(5);
        long length = SoundcoreSleepA30Audio.u32(header, 1);
        if ((header[0] & 255) != expectedOpcode || length > 1024) throw new IOException("Unexpected BESOTA response opcode or length");
        return readFully((int) length);
    }

    private byte[] readFully(int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new EOFException("BESOTA socket closed before acknowledgement");
            offset += count;
        }
        return result;
    }
}
