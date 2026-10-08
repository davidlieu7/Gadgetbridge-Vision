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
 * Find the phone and find the band, both on {@code CH_FIND} (0x02). The
 * MSG_ID tells them apart: 0-2 find the phone, 3-4 ring the band. Fixed
 * format, no param-count byte, {@code [id][value]} pairs.
 *
 * <pre>
 *   band -&gt; phone (requests)                phone -&gt; band (replies)
 *   00          ring the phone                40 00 [result] 03 [reason:4 LE]
 *   01          stop ringing                  41 00 [result] 03 [reason:4 LE]
 *
 *   phone -&gt; band                            band -&gt; phone
 *   42 00 00    stopped on the phone          42
 *   03 01 b4 02 [in call]   ring the band     43 00 [result] 03 [reason]
 *   04 03 00    stop the band                 (the band sends 04 03 xx itself
 *                                              when stopped on the band)
 * </pre>
 *
 * <p>For finding the phone, result 0 means OK and 1 means it can't ring;
 * reason is always 0. The "stopped on the phone" message carries the
 * response bit although the phone sends it unasked; the band answers it with
 * a bare {@code 42}. It is only sent when the user stops the ring on the
 * phone, not after the band asked to stop.
 *
 * <p>When the band is stopped on the band it reports reason 2.
 *
 * <p>Confidence: find-phone start, stop and stopped-on-phone match a real
 * capture byte for byte and work on a live band; so do the band ring start,
 * its reply and the band's own stop. The phone's band-stop
 * ({@code 04 03 00}) is accepted by the band, but no vendor capture shows it.
 */
public final class Fit3FindDevice {

    public static final int CHANNEL = SapProtocol.CH_FIND;

    public static final int MSG_ID_PHONE_START = 0;
    public static final int MSG_ID_PHONE_STOP = 1;
    public static final int MSG_ID_PHONE_FOUND = 2;
    public static final int MSG_ID_BAND_START = 3;
    public static final int MSG_ID_BAND_STOP = 4;

    private static final int PARAM_RESULT = 0x00;
    private static final int PARAM_RING_SECONDS = 0x01;
    private static final int PARAM_IN_CALL = 0x02;
    private static final int PARAM_REASON = 0x03;

    private static final int RESULT_OK = 0;
    private static final int RESULT_FAILED = 1;

    /** How long the band rings, in seconds (the band stops on its own after it). */
    private static final int BAND_RING_SECONDS = 180;

    private static final int RESPONSE = 0x40;

    private Fit3FindDevice() {
    }

    /** Answer to the band's ring-the-phone request. Wire: {@code 40 00 xx 03 00 00 00 00}. */
    public static byte[] buildPhoneStartReply(boolean ok) {
        return phoneReply(MSG_ID_PHONE_START, ok);
    }

    /** Answer to the band's stop request. Wire: {@code 41 00 xx 03 00 00 00 00}. */
    public static byte[] buildPhoneStopReply(boolean ok) {
        return phoneReply(MSG_ID_PHONE_STOP, ok);
    }

    /** The user stopped the ring on the phone. Wire: {@code 42 00 00}. */
    public static byte[] buildPhoneFound() {
        return new byte[]{(byte) (RESPONSE | MSG_ID_PHONE_FOUND), PARAM_RESULT, RESULT_OK};
    }

    /** Ring the band. Wire: {@code 03 01 b4 02 xx}. */
    public static byte[] buildBandStart(boolean inCall) {
        return new byte[]{MSG_ID_BAND_START,
                PARAM_RING_SECONDS, (byte) BAND_RING_SECONDS,
                PARAM_IN_CALL, (byte) (inCall ? 1 : 0)};
    }

    /** Stop ringing the band. Wire: {@code 04 03 00}. */
    public static byte[] buildBandStop() {
        return new byte[]{MSG_ID_BAND_STOP, PARAM_REASON, 0};
    }

    private static byte[] phoneReply(int msgId, boolean ok) {
        return new byte[]{(byte) (RESPONSE | msgId),
                PARAM_RESULT, (byte) (ok ? RESULT_OK : RESULT_FAILED),
                PARAM_REASON, 0, 0, 0, 0};
    }
}
