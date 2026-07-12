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

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * {@code REQUEST_DATA} on {@code CH_HEALTH} (0x0a). The request header is
 * 0x03 and the response header 0x43: the same MSG_ID 3 with only the type
 * bit differing, like the other channels here.
 *
 * <p>The band pings this periodically (not tied to any particular user
 * action); confirmed from a live capture, replying correctly is what lets it
 * proceed to its next step - queuing or starting a bulk-transfer sync (see
 * {@code Fit3HealthSync}) rather than repeating the same ping indefinitely.
 * The reply must echo back the request's own {@code SEQUENCE_NUMBER} span
 * verbatim - the band ignores a response that doesn't. Nothing else in the
 * request is independently decoded, so this only extracts what's needed to
 * build a valid reply, not the full request shape.
 *
 * <pre>
 *   request  (band -&gt; phone): 03 [e0 SEQUENCE_NUMBER: 4B LE] ...more bytes, unparsed
 *   response (phone -&gt; band): 43 02 [same 5-byte e0+SEQUENCE_NUMBER span, echoed] 01 05
 * </pre>
 */
public final class Fit3HealthRequestData {

    public static final int CHANNEL = SapProtocol.CH_HEALTH;
    public static final int MSG_ID = 0x03;
    private static final int RES_HEADER = 0x43;
    private static final int ECHO_LEN = 5; // SEQUENCE_NUMBER marker (0xe0) + 4-byte value

    private Fit3HealthRequestData() {
    }

    /** @return the reply to send back, or {@code null} if {@code request} is too short to echo. */
    public static byte[] buildResponse(byte[] request) {
        if (request.length < 1 + ECHO_LEN) {
            return null;
        }
        byte[] response = new byte[1 + 1 + ECHO_LEN + 2];
        response[0] = (byte) RES_HEADER;
        response[1] = 0x02;
        System.arraycopy(request, 1, response, 2, ECHO_LEN);
        response[2 + ECHO_LEN] = 0x01;
        response[2 + ECHO_LEN + 1] = 0x05;
        return response;
    }

    /**
     * Build a phone-initiated {@code REQUEST_DATA} to prompt the band into
     * starting a sync cycle on demand - used for Gadgetbridge's manual
     * "fetch activity data" action. The body is not just the header: it
     * carries a {@code SEQUENCE_NUMBER} and a device-info record (marker
     * {@code 0x05}, same field layout the band uses for its own device-info
     * pushes) whose {@code last_sync_time}/{@code start_time}/{@code
     * end_time} tags tell the band what range to send. All-zero timestamps
     * mean "everything" - byte-for-byte what a real phone sent in a live
     * capture, immediately followed by the band starting a full
     * sync/bulk-transfer cycle. (A minimal header-only ping was tried first
     * and does NOT work: the band replies with a device-info-only SYNC_DATA
     * and sends no activity records.)
     *
     * @param sequenceNumber the phone's own message counter; the band's
     *     reply echoes it.
     * @param lastSyncTime seconds since the channel's 1990 epoch (see {@link
     *     Fit3HealthPedometerRecord#EPOCH_1990_OFFSET_SECONDS}) of the newest
     *     record already synced, so the band can skip what the phone has; 0
     *     requests everything. Only the capture-confirmed all-zero form is
     *     wire-validated - a non-zero value follows the field's meaning from
     *     the band's own device-info records, and the worst case is the band
     *     ignoring it and re-sending everything (harmless: stored samples
     *     are keyed by timestamp, so re-inserts just overwrite).
     */
    public static byte[] buildRequest(int sequenceNumber, int lastSyncTime) {
        return new byte[]{
                (byte) MSG_ID,
                (byte) 0xe0, (byte) (sequenceNumber & 0xFF), (byte) ((sequenceNumber >> 8) & 0xFF),
                0x05, 0x19,             // device-info record, 25 bytes follow
                0x01, 0x00,             // device (value ignored by the parser)
                0x02, 0x00, 0x00,       // device type
                0x03, 0x04, 0x33,       // version (4.51, replayed from the capture)
                0x04,                   // last_sync_time (4-byte LE)
                (byte) (lastSyncTime & 0xFF), (byte) ((lastSyncTime >> 8) & 0xFF),
                (byte) ((lastSyncTime >> 16) & 0xFF), (byte) ((lastSyncTime >> 24) & 0xFF),
                0x05, 0x00, 0x00, 0x00, 0x00, // start_time
                0x06, 0x00, 0x00, 0x00, 0x00, // end_time
                0x07, 0x01,             // is_last_chunk
        };
    }
}
