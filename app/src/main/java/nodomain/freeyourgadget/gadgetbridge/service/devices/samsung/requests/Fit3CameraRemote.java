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
 * Camera remote on {@code CH_CAMERA} (0x17).
 * The band's Camera controller app asks the phone to open its camera, take a
 * shot and close it again. Fixed format, no param-count byte, one-byte values.
 *
 * <pre>
 *   phone -&gt; band (requests)                band -&gt; phone (replies)
 *   00          capability                     40 [capability]
 *   04          status                         44 [status]
 *   05 05 01    the phone side is visible
 *
 *   band -&gt; phone (requests)                 phone -&gt; band (replies)
 *   01          open the camera                41 03 [1 ok, 0 failed, 2 suspended], once it is open
 *   02          take a picture                 42 01 00 only if it can't
 *   06          close the camera               46 06 [1 ok]
 *   07/08/09    video start/pause/stop         47 07 / 48 08 / 49 09 [0: not supported]
 *   (and unasked, when the camera leaves the foreground: 43 02 00)
 * </pre>
 *
 * <p>The connect order is: capability, then status once the band has
 * answered it, then visibility once the band has answered that.
 *
 * <p>Confidence: confirmed working against a live band from Gadgetbridge's
 * own logs.
 */
public final class Fit3CameraRemote {

    public static final int CHANNEL = SapProtocol.CH_CAMERA;

    public static final int MSG_ID_CAPABILITY = 0;
    public static final int MSG_ID_LAUNCH = 1;
    public static final int MSG_ID_TAKE_SHOT = 2;
    public static final int MSG_ID_BACKGROUND = 3;
    public static final int MSG_ID_STATUS = 4;
    public static final int MSG_ID_VISIBILITY = 5;
    public static final int MSG_ID_EXIT = 6;
    public static final int MSG_ID_VIDEO_START = 7;
    public static final int MSG_ID_VIDEO_PAUSE = 8;
    public static final int MSG_ID_VIDEO_STOP = 9;

    private static final int PARAM_TAKE_SHOT = 1;
    private static final int PARAM_NOT_TOP = 2;
    private static final int PARAM_LAUNCH = 3;
    private static final int PARAM_VISIBILITY = 5;
    private static final int PARAM_EXIT = 6;

    public static final int LAUNCH_FAILED = 0;
    public static final int LAUNCH_OK = 1;

    private static final int RESPONSE = 0x40;

    private Fit3CameraRemote() {
    }

    public static byte[] buildCapabilityRequest() {
        return new byte[]{MSG_ID_CAPABILITY};
    }

    public static byte[] buildStatusRequest() {
        return new byte[]{MSG_ID_STATUS};
    }

    /** The vendor app always sends 1 here. Wire: {@code 05 05 01}. */
    public static byte[] buildVisibility() {
        return new byte[]{MSG_ID_VISIBILITY, PARAM_VISIBILITY, 1};
    }

    /** Answer to an open request. Wire: {@code 41 03 xx}. */
    public static byte[] buildLaunchResult(int result) {
        return new byte[]{(byte) (RESPONSE | MSG_ID_LAUNCH), PARAM_LAUNCH, (byte) result};
    }

    /** A take-shot request that could not be served. Wire: {@code 42 01 00}. */
    public static byte[] buildShotFailed() {
        return new byte[]{(byte) (RESPONSE | MSG_ID_TAKE_SHOT), PARAM_TAKE_SHOT, 0};
    }

    /** The camera went away on the phone. Wire: {@code 43 02 00}. */
    public static byte[] buildBackground() {
        return new byte[]{(byte) (RESPONSE | MSG_ID_BACKGROUND), PARAM_NOT_TOP, 0};
    }

    /** Answer to a close request. Wire: {@code 46 06 xx}. */
    public static byte[] buildExitResult(boolean ok) {
        return new byte[]{(byte) (RESPONSE | MSG_ID_EXIT), PARAM_EXIT, (byte) (ok ? 1 : 0)};
    }

    /** Video requests (7, 8, 9) are refused: their param id equals the MSG_ID. */
    public static byte[] buildVideoUnsupported(int msgId) {
        return new byte[]{(byte) (RESPONSE | msgId), (byte) msgId, 0};
    }
}
