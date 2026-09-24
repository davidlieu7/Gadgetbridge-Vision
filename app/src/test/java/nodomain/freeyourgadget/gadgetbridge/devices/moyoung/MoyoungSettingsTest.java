/*  Copyright (C) 2026 oddballza

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
package nodomain.freeyourgadget.gadgetbridge.devices.moyoung;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.devices.moyoung.settings.MoyoungSetting;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

/**
 * The settings are queried by walking the whole list and decoding every setting whose query command
 * matches the answer, so two settings sharing one query command make one of them decode a payload
 * it cannot read.
 */
public class MoyoungSettingsTest extends TestBase {
    private static final byte NOT_QUERIED = -1;

    private static MoyoungSetting<?>[] settings() {
        return new MT55Coordinator().getSupportedSettings();
    }

    @Test
    public void noTwoSettingsShareAQueryCommand() {
        final Map<Byte, String> byCommand = new HashMap<>();
        final List<String> clashes = new ArrayList<>();
        for (final MoyoungSetting<?> setting : settings()) {
            if (setting.cmdQuery == NOT_QUERIED) {
                continue;
            }
            final String other = byCommand.put(setting.cmdQuery, setting.name);
            if (other != null) {
                clashes.add(String.format("%s and %s both query %d", other, setting.name, setting.cmdQuery));
            }
        }
        assertTrue("settings sharing a query command: " + clashes, clashes.isEmpty());
    }

    @Test
    public void doNotDisturbOnOffIsStillSettable() {
        for (final MoyoungSetting<?> setting : settings()) {
            if ("DO_NOT_DISTURB_ONOFF".equals(setting.name)) {
                assertEquals(MoyoungConstants.CMD_SET_DO_NOT_DISTURB_TIME, setting.cmdSet);
                return;
            }
        }
        throw new AssertionError("DO_NOT_DISTURB_ONOFF is gone; the ringer mode and the toggle send it");
    }
}
