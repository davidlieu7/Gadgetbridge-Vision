/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar.pftp;

import java.util.UUID;

/**
 * Constants of Polar's PFTP ("Polar File Transfer Protocol") over BLE service 0xFEEE.
 *
 * <p>Ids are transmitted as raw numbers in the message headers (not protobuf-encoded),
 * so they live here instead of in {@code polar.proto}.
 */
public final class PftpConstants {
    public static final UUID UUID_SERVICE_PFTP = UUID.fromString("0000feee-0000-1000-8000-00805f9b34fb");
    /** Requests and queries (host to watch) and their responses (notifications). */
    public static final UUID UUID_CHARACTERISTIC_MTU = UUID.fromString("fb005c51-02e7-f387-1cad-8acd2d8df0c8");
    /** Notifications from the watch to the host. */
    public static final UUID UUID_CHARACTERISTIC_D2H = UUID.fromString("fb005c52-02e7-f387-1cad-8acd2d8df0c8");
    /** Notifications from the host to the watch. */
    public static final UUID UUID_CHARACTERISTIC_H2D = UUID.fromString("fb005c53-02e7-f387-1cad-8acd2d8df0c8");

    // PbPFtpQuery
    public static final int QUERY_IDENTIFY_DEVICE = 0;
    public static final int QUERY_SET_SYSTEM_TIME = 1;
    public static final int QUERY_GET_SYSTEM_TIME = 2;
    public static final int QUERY_SET_LOCAL_TIME = 3;
    public static final int QUERY_GET_LOCAL_TIME = 4;
    public static final int QUERY_GET_DISK_SPACE = 5;
    public static final int QUERY_GET_BATTERY_STATUS = 8;
    public static final int QUERY_REQUEST_SYNCHRONIZATION = 13;

    // PbPFtpHostToDevNotification
    public static final int H2D_START_SYNC = 0;
    public static final int H2D_STOP_SYNC = 1;
    public static final int H2D_TERMINATE_SYNC = 4;
    public static final int H2D_KEEP_ALIVE = 5;
    public static final int H2D_PNS_HD_NOTIFICATION = 7;
    public static final int H2D_INITIALIZE_SESSION = 8;
    public static final int H2D_TERMINATE_SESSION = 9;
    public static final int H2D_MEDIA_DATA = 18;
    public static final int H2D_MEDIA_PLAYBACK_STATE = 19;

    // PbPFtpDevToHostNotification
    public static final int D2H_FILESYSTEM_MODIFIED = 0;
    public static final int D2H_IDLING = 2;
    public static final int D2H_BATTERY_STATUS = 3;
    public static final int D2H_INACTIVITY_ALERT = 4;
    public static final int D2H_TRAINING_SESSION_STATUS = 5;
    public static final int D2H_SYNC_REQUIRED = 7;
    public static final int D2H_AUTOSYNC_STATUS = 8;
    public static final int D2H_PNS_NOTIFICATION_RESPONSE = 9;
    public static final int D2H_PNS_SETTINGS = 10;
    public static final int D2H_KEEP_BACKGROUND_ALIVE = 13;
    public static final int D2H_MEDIA_CONTROL_REQUEST = 15;
    public static final int D2H_MEDIA_CONTROL_COMMAND = 16;
    public static final int D2H_MEDIA_CONTROL_ENABLED = 17;
    public static final int D2H_REST_API_EVENT = 18;
    public static final int D2H_EXERCISE_STATUS = 19;

    // PbPFtpError, carried in status frames. 0 is also the plain "ok" of a request without payload.
    public static final int ERROR_OPERATION_SUCCEEDED = 0;
    public static final int ERROR_REBOOTING = 1;
    public static final int ERROR_TRY_AGAIN = 2;
    public static final int ERROR_NO_SUCH_FILE_OR_DIRECTORY = 103;
    public static final int ERROR_DIRECTORY_EXISTS = 104;
    public static final int ERROR_SYSTEM_BUSY = 202;

    private PftpConstants() {
    }
}
