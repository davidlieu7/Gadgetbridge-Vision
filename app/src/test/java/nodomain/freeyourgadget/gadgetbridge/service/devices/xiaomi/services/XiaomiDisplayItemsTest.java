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

package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.devices.huami.HuamiConst;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiSystem;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiPreferences;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiServiceTestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class XiaomiDisplayItemsTest extends XiaomiServiceTestBase {
    private XiaomiSystemService service;

    @Before
    public void setUpService() {
        service = new XiaomiSystemService(support);
    }

    private XiaomiSystem.DisplayItem.Builder item(final String code) {
        return XiaomiSystem.DisplayItem.newBuilder().setCode(code).setName(code).setUnknown5(1);
    }

    private void receive(final XiaomiSystem.DisplayItem... items) {
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(2).setSubtype(29)
                .setSystem(XiaomiSystem.System.newBuilder().setDisplayItems(
                        XiaomiSystem.DisplayItems.newBuilder().addAllDisplayItem(List.of(items)))).build());
    }

    private List<XiaomiSystem.DisplayItem> sendOrder(final String order) {
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                .putString(HuamiConst.PREF_DISPLAY_ITEMS_SORTABLE, order).commit();
        sent.clear();
        service.onSendConfiguration(HuamiConst.PREF_DISPLAY_ITEMS_SORTABLE, GBApplication.getDevicePrefs(device));
        assertEquals(1, sent.size());
        assertEquals(2, sent.get(0).getType());
        assertEquals(30, sent.get(0).getSubtype());
        return sent.get(0).getSystem().getDisplayItems().getDisplayItemList();
    }

    @Test
    public void reorderPreservesCachedMetadataAndKeepsSettingsEnabled() throws Exception {
        // Field 127 is unknown to the schema and must survive the cache and menu reorder.
        final XiaomiSystem.DisplayItem stats = XiaomiSystem.DisplayItem.parseFrom(
                item("stats").setName("Stats, today").setUnknown5(7).build().toByteString()
                        .concat(ByteString.copyFrom(new byte[]{(byte) 0xf8, 0x07, 42})));
        final XiaomiSystem.DisplayItem camera = item("camera").setDisabled(true).setInMoreSection(true).build();
        receive(stats, item("workout").build(), item("settings").setIsSettings(1).build(), camera);
        service = new XiaomiSystemService(support);

        final List<XiaomiSystem.DisplayItem> items = sendOrder("workout,stats");
        assertEquals(List.of("workout", "stats", "settings", "camera"),
                items.stream().map(XiaomiSystem.DisplayItem::getCode).collect(Collectors.toList()));
        assertEquals(stats, items.get(1));
        assertFalse(items.get(2).getDisabled());
        assertEquals(1, items.get(2).getIsSettings());
        assertEquals(camera, items.get(3));

        sent.clear();
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(2).setSubtype(30).build());
        assertEquals(1, sent.size());
        assertEquals(2, sent.get(0).getType());
        assertEquals(29, sent.get(0).getSubtype());
    }

    @Test
    public void band8And9KeepLegacyMenuPayloadAndAckHandling() {
        final XiaomiSystem.DisplayItem stats = item("stats").setName("Stats, today").setUnknown5(7).build();
        for (final DeviceType type : new DeviceType[]{DeviceType.MIBAND8, DeviceType.MIBAND9}) {
            useDevice(type);
            service = new XiaomiSystemService(support);
            receive(stats);
            assertFalse(GBApplication.getDeviceSpecificSharedPrefs(device.getAddress())
                    .contains(XiaomiPreferences.PREF_DISPLAY_ITEMS));

            // Ignore metadata cached by a previous device-type selection.
            GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                    .putString(XiaomiPreferences.PREF_DISPLAY_ITEMS, GB.hexdump(
                            XiaomiSystem.DisplayItems.newBuilder().addDisplayItem(stats).build().toByteArray())).commit();
            final List<XiaomiSystem.DisplayItem> items = sendOrder("stats");
            assertEquals(2, items.size());
            assertEquals(item("stats").setName("Stats today").build(), items.get(0));
            assertEquals("more", items.get(1).getCode());
            assertTrue(items.get(1).getDisabled());

            sent.clear();
            service.handleCommand(XiaomiProto.Command.newBuilder().setType(2).setSubtype(30).build());
            assertTrue(sent.isEmpty());
        }
    }

    @Test
    public void movingAppsBetweenSectionsClearsOldFlags() {
        receive(item("stats").setInMoreSection(true).build(), item("workout").build(),
                item("camera").setDisabled(true).setInMoreSection(true).build());

        final List<XiaomiSystem.DisplayItem> items = sendOrder("stats,camera,more,workout");
        assertFalse(items.get(0).getInMoreSection());
        assertFalse(items.get(1).getDisabled());
        assertFalse(items.get(1).getInMoreSection());
        assertTrue(items.get(2).getInMoreSection());
        assertEquals(3, items.size());
    }

    @Test
    public void missingOrMalformedMetadataStillExcludesMore() {
        receive(item("stats").build(), item("workout").build());
        for (final String cachedHex : new String[]{null, "0", "00"}) {
            GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                    .putString(XiaomiPreferences.PREF_DISPLAY_ITEMS, cachedHex).commit();
            assertEquals(2, sendOrder("workout,stats").size());
        }
    }
}
