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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Interconnection;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;

/**
 * Reverse engineering of the GB dump:
 * Requests from the watch for phone state and for devices it could control through the phone.
 * None of them are answered: the meaning of the phone status and phone usage values is not
 * known, and wrong values could affect sleep detection, which uses phone usage at night.
 * Maybe some of them can be set correctly in next PRs
 */
public class XiaomiInterconnectionService extends AbstractXiaomiService {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiInterconnectionService.class);

    public static final int COMMAND_TYPE = 23;

    private static final int CMD_REQUEST_PHONE_STATUS = 0;
    private static final int CMD_REQUEST_SHARE_DEVICES = 2;
    private static final int CMD_REGISTER_PHONE_USAGE = 17;
    private static final int CMD_UNREGISTER_PHONE_USAGE = 18;

    public XiaomiInterconnectionService(final XiaomiSupport support) {
        super(support);
    }

    @Override
    public void handleCommand(final XiaomiProto.Command cmd) {
        final Interconnection interconnection = cmd.getInterconnection();

        switch (cmd.getSubtype()) {
            case CMD_REQUEST_PHONE_STATUS:
                LOG.debug("Watch requested phone status (screen={}), not supported",
                        interconnection.getPhoneStatus().getScreen());
                return;
            case CMD_REQUEST_SHARE_DEVICES:
                LOG.debug("Watch requested controllable devices (onlyAudio={}), not supported",
                        interconnection.getRequestInfo().getOnlyAudio());
                return;
            case CMD_REGISTER_PHONE_USAGE:
                LOG.debug("Watch registered for phone usage reports, not supported");
                return;
            case CMD_UNREGISTER_PHONE_USAGE:
                LOG.debug("Watch unregistered from phone usage reports");
                return;
        }

        LOG.warn("Unknown interconnection command {}", cmd.getSubtype());
    }
}
