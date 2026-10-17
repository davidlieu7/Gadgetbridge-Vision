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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ATT writes and notifications of an Android btsnoop log (RFC 1761 framing, HCI H4 packets), one
 * PDU per ACL packet, continuation packets skipped.
 */
final class BtSnoopAtt {
    private static final byte[] MAGIC = "btsnoop\0".getBytes(StandardCharsets.US_ASCII);
    private static final int ATT_CID = 0x0004;
    private static final int ATT_WRITE_REQUEST = 0x12;
    private static final int ATT_WRITE_COMMAND = 0x52;
    private static final int ATT_NOTIFICATION = 0x1b;

    static final class Pdu {
        /** True for ring to phone. */
        final boolean received;
        final int opcode;
        final byte[] value;

        Pdu(final boolean received, final int opcode, final byte[] value) {
            this.received = received;
            this.opcode = opcode;
            this.value = value;
        }

        boolean isWrite() {
            return !received && (opcode == ATT_WRITE_REQUEST || opcode == ATT_WRITE_COMMAND);
        }

        boolean isNotification() {
            return received && opcode == ATT_NOTIFICATION;
        }
    }

    static List<Pdu> read(final byte[] log) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(log));
        final byte[] magic = new byte[8];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("not a btsnoop log");
        }
        in.skipBytes(8);
        final List<Pdu> out = new ArrayList<>();
        while (true) {
            final int included;
            final int flags;
            try {
                in.readInt();
                included = in.readInt();
                flags = in.readInt();
                in.skipBytes(12);
            } catch (final EOFException e) {
                return out;
            }
            final byte[] packet = new byte[included];
            try {
                in.readFully(packet);
            } catch (final EOFException e) {
                return out;
            }
            final Pdu pdu = parse(packet, (flags & 1) != 0);
            if (pdu != null) {
                out.add(pdu);
            }
        }
    }

    private static Pdu parse(final byte[] p, final boolean received) {
        // H4 ACL: [02][handle+flags u16][length u16][L2CAP length u16][CID u16][ATT...]
        if (p.length < 12 || p[0] != 0x02 || ((p[2] >> 4) & 0x3) == 0x1) {
            return null;
        }
        final int l2capLength = (p[5] & 0xff) | (p[6] & 0xff) << 8;
        final int cid = (p[7] & 0xff) | (p[8] & 0xff) << 8;
        if (cid != ATT_CID || l2capLength < 3) {
            return null;
        }
        final int opcode = p[9] & 0xff;
        return new Pdu(received, opcode, Arrays.copyOfRange(p, 12, Math.min(p.length, 9 + l2capLength)));
    }

    private BtSnoopAtt() {
    }
}
