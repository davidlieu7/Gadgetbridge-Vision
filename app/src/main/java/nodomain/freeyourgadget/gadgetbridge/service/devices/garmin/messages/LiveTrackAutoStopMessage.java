/*  Copyright (C) 2026 Thomas Kuehne

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages;

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status.GenericStatusMessage;

public class LiveTrackAutoStopMessage extends GFDIMessage {
    public LiveTrackAutoStopMessage() {
        this.garminMessage = GarminMessage.LIVE_TRACK_AUTO_STOP;
        this.statusMessage = new GenericStatusMessage(garminMessage, Status.UNSUPPORTED);
    }

    public static LiveTrackAutoStopMessage parseIncoming(MessageReader reader, GarminMessage originalGarminMessage) {
        // TODO: this is only a stub to improve debugging
        return new LiveTrackAutoStopMessage();
    }

    @Override
    protected boolean generateOutgoing() {
        return false;
    }
}
