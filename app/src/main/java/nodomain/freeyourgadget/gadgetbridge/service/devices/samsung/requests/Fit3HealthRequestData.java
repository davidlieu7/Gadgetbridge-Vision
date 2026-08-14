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
 * The reply must echo back the request's own {@code SEQUENCE_NUMBER} and,
 * like {@link Fit3HealthSyncData}'s ack, carry the phone's sync watermark as
 * a {@link Fit3HealthDeviceInfoRecord}; the band's own copy of the ping
 * always leaves {@code last_sync_time} zero, so the phone's answer is the
 * only place that value comes from.
 *
 * <pre>
 *   request  (band -&gt; phone): 03 [e0 SEQUENCE_NUMBER:2 LE] 04 02 01 01 [device-info record]
 *   response (phone -&gt; band): 43 [e0 SEQUENCE_NUMBER, echoed] 04 02 01 01 [device-info record]
 * </pre>
 *
 * <p>Note the response has no parameter-count byte, unlike
 * {@link Fit3HealthSyncData}'s - matching the captures for both.
 */
public final class Fit3HealthRequestData {

    public static final int CHANNEL = SapProtocol.CH_HEALTH;
    public static final int MSG_ID = 0x03;
    private static final int RES_HEADER = 0x43;
    private static final int ECHO_LEN = 3; // SEQUENCE_NUMBER marker (0xe0) + 2-byte value

    private Fit3HealthRequestData() {
    }

    /**
     * @param lastSyncTime the phone's watermark to claim - seconds since the
     *     1990 epoch of the newest data it has consumed, or 0 for "send
     *     everything".
     * @return the reply to send back, or {@code null} if {@code request} is
     *     too short to echo.
     */
    public static byte[] buildResponse(byte[] request, int lastSyncTime) {
        if (request.length < 1 + ECHO_LEN) {
            return null;
        }
        byte[] deviceInfo = Fit3HealthDeviceInfoRecord.build(lastSyncTime, 0, 0);
        byte[] response = new byte[1 + ECHO_LEN + 4 + deviceInfo.length];
        response[0] = (byte) RES_HEADER;
        System.arraycopy(request, 1, response, 1, ECHO_LEN);
        response[4] = 0x04;
        response[5] = 0x02;
        response[6] = 0x01;
        response[7] = 0x01;
        System.arraycopy(deviceInfo, 0, response, 8, deviceInfo.length);
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
        byte[] deviceInfo = Fit3HealthDeviceInfoRecord.build(lastSyncTime, 0, 0);
        byte[] request = new byte[4 + deviceInfo.length];
        request[0] = (byte) MSG_ID;
        request[1] = (byte) 0xe0;
        request[2] = (byte) (sequenceNumber & 0xFF);
        request[3] = (byte) ((sequenceNumber >> 8) & 0xFF);
        System.arraycopy(deviceInfo, 0, request, 4, deviceInfo.length);
        return request;
    }
}
