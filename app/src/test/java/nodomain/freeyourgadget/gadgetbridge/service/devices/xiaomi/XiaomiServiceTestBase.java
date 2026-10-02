/*  Copyright (C) 2026 Ulveon

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

package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import android.content.Context;

import org.junit.Before;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public abstract class XiaomiServiceTestBase extends TestBase {
    protected GBDevice device;
    protected XiaomiSupport support;
    protected final List<XiaomiProto.Command> sent = new ArrayList<>();

    @Before
    public void setUpXiaomi() {
        useDevice(DeviceType.MIBAND10);
    }

    protected void useDevice(final DeviceType type) {
        device = new GBDevice("00:00:00:00:00:10", "Test band", "Test band", "Test folder", type);
        device.setFirmwareVersion("test");
        DBHelper.getDevice(device, daoSession);
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit().clear().commit();
        support = new RecordingSupport();
        sent.clear();
    }

    private class RecordingSupport extends XiaomiSupport {
        RecordingSupport() {
            gbDevice = device;
        }

        @Override
        public Context getContext() {
            return XiaomiServiceTestBase.this.getContext();
        }

        @Override
        public void sendCommand(final String taskName, final XiaomiProto.Command command) {
            sent.add(command);
        }

        @Override
        public void evaluateGBDeviceEvent(final GBDeviceEvent event) {
            event.evaluate(getContext(), getDevice());
        }
    }
}
