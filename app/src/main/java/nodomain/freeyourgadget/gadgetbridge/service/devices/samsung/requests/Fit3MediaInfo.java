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

import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapPayloadBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * Now-playing metadata and playback-state pushes to the band, on
 * {@code CH_MEDIA} (0x09). Both are variable format WITH a leading
 * param-count byte, and both set the header response bit (bit6) even though
 * nothing here is answering a specific band request - same loose use of
 * that bit seen on other channels in this codebase.
 *
 * <pre>
 *   xx 05                        variable, response, MSG_ID 17 ("now playing"), 5 params
 *      01 xx*8                      param 0x01 = duration (int64 LE, ms)
 *      02 len utf8...               param 0x02 = title (string, 1-byte len)
 *      03 len utf8...               param 0x03 = artist (string, 1-byte len)
 *      04 len utf8...               param 0x04 = album art filename (string, 1-byte len)
 *      05 len utf8...               param 0x05 = app id / package (string, 1-byte len)
 *
 *   xx 04                        variable, response, MSG_ID 18 (playback state), 4 params
 *      01 xx                        param 0x01 = state (byte: 3=playing, 2=everything else -
 *                                                 the source app never sends a distinct
 *                                                 "stopped" value through this message)
 *      02 xx*8                      param 0x02 = position (int64 LE, ms)
 *      04 xx*8                      param 0x04 = active queue item id (int64 LE)
 *      03 len utf8...               param 0x03 = app id / package (string, 1-byte len)
 * </pre>
 *
 * <p>Note the playback-state param order: id 4 is written before id 3 on
 * the wire. This channel is read positionally on the band's side, so the
 * order here is not cosmetic.
 *
 * <p>Neither {@link MusicSpec} nor {@link MusicStateSpec} carries an "app id"
 * (source app package) or an album-art reference, and Gadgetbridge doesn't
 * do the bulk-transfer icon flow this band would need for real album art -
 * both are sent as empty strings. {@code activeQueueItemId} has no
 * Gadgetbridge equivalent either and is always sent as 0.
 */
public final class Fit3MediaInfo {

    /** Channel this message rides on, for the call site. */
    public static final int CHANNEL = SapProtocol.CH_MEDIA;

    public static final int MSG_ID_NOW_PLAYING     = 0x11; // 17
    public static final int MSG_ID_PLAYBACK_STATE  = 0x12; // 18

    private static final int PARAM_DURATION       = 0x01;
    private static final int PARAM_TITLE          = 0x02;
    private static final int PARAM_ARTIST         = 0x03;
    private static final int PARAM_ALBUM_ART_NAME = 0x04;
    private static final int PARAM_APP_ID_META    = 0x05;

    private static final int PARAM_STATE          = 0x01;
    private static final int PARAM_POSITION       = 0x02;
    private static final int PARAM_APP_ID_STATE   = 0x03;
    private static final int PARAM_QUEUE_ITEM_ID  = 0x04;

    private static final int STATE_PAUSED_OR_STOPPED = 2;
    private static final int STATE_PLAYING            = 3;

    private Fit3MediaInfo() {
    }

    /** Now-playing metadata push. */
    public static byte[] buildNowPlaying(MusicSpec spec) {
        String title = spec.getTrack() != null ? spec.getTrack() : "";
        String artist = spec.getArtist() != null ? spec.getArtist() : "";
        long durationMillis = spec.getDuration() > 0 ? spec.getDuration() * 1000L : 0L;

        return SapPayloadBuilder.variableResponse(MSG_ID_NOW_PLAYING)
                .addLong(PARAM_DURATION, durationMillis)
                .addString(PARAM_TITLE, title)
                .addString(PARAM_ARTIST, artist)
                .addString(PARAM_ALBUM_ART_NAME, "")
                .addString(PARAM_APP_ID_META, "")
                .build();
    }

    /** Playback-state push. */
    public static byte[] buildPlaybackState(MusicStateSpec spec) {
        int state = spec.getState() == MusicStateSpec.STATE_PLAYING ? STATE_PLAYING : STATE_PAUSED_OR_STOPPED;
        long positionMillis = spec.getPosition() > 0 ? spec.getPosition() * 1000L : 0L;

        return SapPayloadBuilder.variableResponse(MSG_ID_PLAYBACK_STATE)
                .addByte(PARAM_STATE, state)
                .addLong(PARAM_POSITION, positionMillis)
                .addLong(PARAM_QUEUE_ITEM_ID, 0L)
                .addString(PARAM_APP_ID_STATE, "")
                .build();
    }
}
