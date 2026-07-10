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

import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Two request shapes on {@code CH_CALL} (0x03) that together drive the band's
 * incoming-call UI: the caller-id push, and the call-state notification.
 *
 * <p>{@link #buildCallState} is fixed format with a bare value byte - no
 * param id at all, unlike almost everything else in this codebase:
 * <pre>
 *   xx    fixed, request, MSG_ID 3
 *      xx     raw value = call state (see STATE_* constants)
 * </pre>
 *
 * <p>{@link #buildCallerIdPush} is variable format WITH a leading
 * param-count byte:
 * <pre>
 *   xx 07                        variable, request, MSG_ID 2, 7 params
 *      00 01                        param 0x00 = CALL_TYPE (byte: 0=video, 1=voice)
 *      07 01 00 00 00               param 0x07 = SEQUENCE_ID = 1 (int32 LE, constant on this
 *                                                 channel - note the 4-byte width, easy to get
 *                                                 wrong since the value itself is tiny)
 *      05 len ascii...              param 0x05 = APP_ID (string, fixed literal)
 *      06 xx*8                      param 0x06 = TIME (int64 LE, epoch ms)
 *      04 len utf8...               param 0x04 = CONTACT_NAME (string, 1-byte len)
 *      03 len utf8...               param 0x03 = PHONE_NUMBER (string, 1-byte len)
 *      09 01/00                     param 0x09 = DND_FLAG (bool)
 * </pre>
 *
 * <p>Only the incoming-call path is implemented here: the source app never
 * sends a call-state notification for a call the phone itself placed (only
 * for one that started ringing in), and this channel has no wire concept of
 * a video call - {@code CALL_TYPE} is always sent as voice.
 *
 * <p>The band also sends two fixed, paramless requests back on this
 * channel, both handled by acting on their MSG_ID alone (no body to parse):
 * {@link #MSG_ID_SILENCE_RINGER} when the ringer-silence button is pressed,
 * and {@link #MSG_ID_REJECT_CALL} when the call is dismissed/rejected from
 * the band.
 */
public final class Fit3CallState {

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_CALL;

    public static final int MSG_ID_CALLER_ID  = 0x02;
    public static final int MSG_ID_CALL_STATE = 0x03;

    /** Band -> phone, fixed, no params: ringer-silence button pressed. */
    public static final int MSG_ID_SILENCE_RINGER = 0x01;
    /** Band -> phone, fixed, no params: call dismissed/rejected from the band. */
    public static final int MSG_ID_REJECT_CALL = 0x05;

    public static final int STATE_IDLE    = 0;
    public static final int STATE_RINGING = 1;
    public static final int STATE_OFFHOOK = 2;

    private static final int TYPE_VOICE = 1;

    private static final int PARAM_CALL_TYPE   = 0x00;
    private static final int PARAM_PHONE_NUM   = 0x03;
    private static final int PARAM_CONTACT     = 0x04;
    private static final int PARAM_APP_ID      = 0x05;
    private static final int PARAM_TIME        = 0x06;
    private static final int PARAM_SEQUENCE_ID = 0x07;
    private static final int PARAM_DND_FLAG    = 0x09;

    /** Fixed literal the source app tags every caller-id push with - not app-specific. */
    private static final String APP_ID = "com.samsung.android.providers.sacall.SACallHandlerService";

    private Fit3CallState() {
    }

    /** Bare call-state byte; pass one of {@link #STATE_IDLE}, {@link #STATE_RINGING}, {@link #STATE_OFFHOOK}. */
    public static byte[] buildCallState(int state) {
        return SapPayloadBuilder.fixed(MSG_ID_CALL_STATE).raw(state).build();
    }

    /** Caller-id push for a ringing call, built from a {@link CallSpec}. */
    public static byte[] buildCallerIdPush(CallSpec spec) {
        String number = spec.getNumber() != null ? spec.getNumber() : "";
        String name = spec.getName() != null ? spec.getName() : "";
        long whenMillis = System.currentTimeMillis();

        return SapPayloadBuilder.variable(MSG_ID_CALLER_ID)
                .addByte(PARAM_CALL_TYPE, TYPE_VOICE)
                .addInt32(PARAM_SEQUENCE_ID, 1)
                .addString(PARAM_APP_ID, APP_ID)
                .addLong(PARAM_TIME, whenMillis)
                .addString(PARAM_CONTACT, name)
                .addString(PARAM_PHONE_NUM, number)
                .addByte(PARAM_DND_FLAG, spec.getDndSuppressed() != 0 ? 1 : 0)
                .build();
    }
}
