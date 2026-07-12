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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests;

import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * {@code SYNC_DATA} on {@code CH_HEALTH} (0x0a), MSG_ID 2 - the band's main
 * channel for pushing individual health/fitness records (as opposed to
 * {@link Fit3HealthSync}, the bulk-transfer chunking used when a whole
 * sync's worth of records is too big for one message). The request
 * header is 0x82 and the response header 0x42, like {@link
 * Fit3HealthRequestData}'s pair. Every push starts with a {@code SEQUENCE_NUMBER} (marker {@code
 * 0xe0}, 2-byte value, same param id/width as {@link
 * Fit3HealthCapabilityCheck}'s) that the reply must echo - confirmed from a
 * live push/ack pair. What follows the sequence number is one or more
 * "feature data type" records - only {@link Fit3HealthPedometerRecord} (marker {@code 0x07}) is
 * decoded here so far.
 *
 * <p>The real app's ack also carries a large additional block after the
 * echoed sequence number (a recurring status/timestamp bundle seen
 * attached to several unrelated exchanges in captures, not specific to this
 * one) - omitted here since it appears to just be incidentally-bundled
 * chatter, not something this specific exchange depends on; unconfirmed
 * whether the band minds its absence.
 *
 * <pre>
 *   request (band -&gt; phone): 82 [count:1] e0 [SEQUENCE_NUMBER:2 LE] ...one or more records...
 *   response (phone -&gt; band): 42 01 e0 [SEQUENCE_NUMBER, echoed]
 * </pre>
 */
public final class Fit3HealthSyncData {

    public static final int CHANNEL = SapProtocol.CH_HEALTH;
    public static final int MSG_ID = 0x02;
    private static final int RES_HEADER = 0x42;
    private static final int PARAM_SEQUENCE_NUMBER = 0xe0;
    private static final int RECORDS_START = 5; // header(1) + count(1) + e0(1) + value(2)

    private Fit3HealthSyncData() {
    }

    /** @return the ack to send back, or {@code null} if the expected sequence-number param wasn't found. */
    public static byte[] buildAck(byte[] request) {
        if (request.length < RECORDS_START || (request[2] & 0xFF) != PARAM_SEQUENCE_NUMBER) {
            return null;
        }
        return new byte[]{
                (byte) RES_HEADER,
                1, // param count
                (byte) PARAM_SEQUENCE_NUMBER,
                request[3],
                request[4],
        };
    }

    /** @return every pedometer record in this push (usually one, but the same list format as bulk blobs). */
    public static List<Fit3HealthPedometerRecord> findPedometerRecords(byte[] request) {
        if (request.length <= RECORDS_START) {
            return Collections.emptyList();
        }
        return Fit3HealthPedometerRecord.findAll(request, RECORDS_START);
    }
}
