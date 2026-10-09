/*  Copyright (C) 2026 Andrei Darashenka

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.location.Location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Gnss;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.GnssAssistRequest;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.GnssLocation;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.webview.CurrentPosition;

/**
 * GNSS assistance requested by watches with a GPS chip. The watch periodically asks for
 * assistance data (AGPS / EPO), which is now not supported, and for the phone location, which
 * lets the GPS chip start from an approximate position.
 */
public class XiaomiGnssService extends AbstractXiaomiService {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiGnssService.class);

    public static final int COMMAND_TYPE = 16;

    private static final int CMD_REQUEST_ONLINE = 0;
    private static final int CMD_REQUEST_OFFLINE = 1;
    private static final int CMD_SEND_LOCATION = 2;
    private static final int CMD_REQUEST_LOCATION = 3;

    public XiaomiGnssService(final XiaomiSupport support) {
        super(support);
    }

    @Override
    public void handleCommand(final XiaomiProto.Command cmd) {
        final Gnss gnss = cmd.getGnss();

        switch (cmd.getSubtype()) {
            case CMD_REQUEST_ONLINE:
                LOG.debug("Watch requested online GNSS assistance data, not supported");
                return;
            case CMD_REQUEST_OFFLINE: {
                final GnssAssistRequest request = gnss.getAssistRequest();
                LOG.debug(
                        "Watch requested offline GNSS assistance data: type={}, source={}, days={}, needLocation={} - assistance data not supported",
                        request.getType(),
                        request.getSource(),
                        request.getDays(),
                        request.getNeedLocation()
                );
                if (request.getNeedLocation()) {
                    sendLocation();
                }
                return;
            }
            case CMD_REQUEST_LOCATION:
                LOG.debug("Watch requested the phone location");
                sendLocation();
                return;
        }

        LOG.warn("Unknown GNSS command {}", cmd.getSubtype());
    }

    private void sendLocation() {
        final CurrentPosition currentPosition = new CurrentPosition();
        final Location location = currentPosition.getLastKnownLocation();
        if (location.getLatitude() == 0 && location.getLongitude() == 0) {
            LOG.info("No location available, not answering GNSS location request");
            return;
        }

        final GnssLocation.Builder gnssLocation = GnssLocation.newBuilder()
                .setTimestamp((int) (System.currentTimeMillis() / 1000L))
                .setLongitude(location.getLongitude())
                .setLatitude(location.getLatitude());
        if (location.hasAltitude()) {
            gnssLocation.setAltitude(location.getAltitude());
        }

        getSupport().sendCommand(
                "send gnss location",
                XiaomiProto.Command.newBuilder()
                        .setType(COMMAND_TYPE)
                        .setSubtype(CMD_SEND_LOCATION)
                        .setGnss(Gnss.newBuilder().setLocation(gnssLocation))
                        .build()
        );
    }
}
