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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3MenuOrder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3MenuOrder.Menu;

/**
 * Tiles, quick panel and apps arrangement, against a capture of the vendor
 * app re-arranging each menu and the band reporting them.
 */
public class Fit3MenuOrderTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    // ---- tiles ----

    private static final String TILE_TABLE = "000114 03170201 03150202 031c0203 03020204 03080205 03060206"
            + " 03190207 03180208 030102ff 030302ff 030402ff 030702ff 030902ff 031402ff 031602ff"
            + " 031a02ff 031b02ff 031d02ff 031e02ff 031f02ff";

    @Test
    public void parsesTileTable() {
        List<Fit3MenuOrder.Entry> entries = Fit3MenuOrder.parse(Menu.TILES, hex(TILE_TABLE));
        assertNotNull(entries);
        assertEquals(20, entries.size());
        assertEquals(Arrays.asList(23, 21, 28, 2, 8, 6, 25, 24), Fit3MenuOrder.shownIds(entries));
        assertEquals("Daily activity", Fit3MenuOrder.name(Menu.TILES, 23));
    }

    @Test
    public void tileSetMatchesCapture() {
        // Captured: shown tiles listed by id, 1-based slots.
        assertArrayEquals(hex("01 01 08 03020204 03060206 03080205 03150201 03170202 03180208 03190207 031c0203"),
                Fit3MenuOrder.buildSet(Menu.TILES, Arrays.asList(21, 23, 28, 2, 8, 6, 25, 24),
                        Collections.emptyList()));
        assertArrayEquals("no tiles at all", hex("01 01 00"),
                Fit3MenuOrder.buildSet(Menu.TILES, Collections.emptyList(), Collections.emptyList()));
    }

    // ---- quick panel ----

    @Test
    public void parsesQuickPanelTable() {
        List<Fit3MenuOrder.Entry> entries = Fit3MenuOrder.parse(Menu.QUICK_PANEL, hex(
                "40 01 0c 030a0201 030d0202 03030203 03010204 030e0205 03050206 030f0207 03040208"
                        + " 03100209 0311020a 0306020b 0302020c"));
        assertNotNull(entries);
        assertEquals(Arrays.asList(10, 13, 3, 1, 14, 5, 15, 4, 16, 17, 6, 2), Fit3MenuOrder.shownIds(entries));
        assertEquals("Sleep mode", Fit3MenuOrder.name(Menu.QUICK_PANEL, 10));
    }

    @Test
    public void quickPanelSetMatchesCapture() {
        // Captured: shown buttons only, by slot.
        assertArrayEquals(hex("01 01 03 03110201 03060202 03020203"),
                Fit3MenuOrder.buildSet(Menu.QUICK_PANEL, Arrays.asList(17, 6, 2), Collections.emptyList()));
    }

    // ---- apps ----

    @Test
    public void parsesAppsTable() {
        List<Fit3MenuOrder.Entry> entries = Fit3MenuOrder.parse(Menu.APPS, hex(
                "c0 0201 030a 00010101 00020102 00030103 00040104 00050105 00070106 00080107"
                        + " 00090108 000c0109 0006010a"));
        assertNotNull(entries);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 7, 8, 9, 12, 6), Fit3MenuOrder.shownIds(entries));
        assertEquals("Calculator", Fit3MenuOrder.name(Menu.APPS, 12));
    }

    @Test
    public void appsSetMatchesCapture() {
        // Captured: every app, 0-based positions.
        List<Integer> all = Arrays.asList(1, 2, 3, 4, 5, 7, 8, 9, 12, 6);
        byte[] expected = hex("01 0201 030a 00020100 00030101 00040102 00050103 00070104 00080105"
                + " 00090106 000c0107 00010108 00060109");
        assertArrayEquals(expected, Fit3MenuOrder.buildSet(Menu.APPS,
                Arrays.asList(2, 3, 4, 5, 7, 8, 9, 12, 1, 6), all));
        assertArrayEquals("apps can't be hidden: missing ones go to the end", expected,
                Fit3MenuOrder.buildSet(Menu.APPS, Arrays.asList(2, 3, 4, 5, 7, 8, 9, 12), all));
    }

    // ---- shared ----

    @Test
    public void requestsAndRouting() {
        assertArrayEquals(hex("00"), Fit3MenuOrder.buildRequest(Menu.TILES));
        assertArrayEquals(hex("00"), Fit3MenuOrder.buildRequest(Menu.QUICK_PANEL));
        assertNull("the band sends the app list unasked", Fit3MenuOrder.buildRequest(Menu.APPS));
        assertEquals(Menu.TILES, Menu.forChannel(0x0f));
        assertEquals(Menu.QUICK_PANEL, Menu.forChannel(0x13));
        assertEquals(Menu.APPS, Menu.forChannel(0x18));
        assertNull(Menu.forChannel(0x07));
        assertNull("a request body is not a table", Fit3MenuOrder.parse(Menu.TILES, hex("00")));
        assertNull("a set is not a table", Fit3MenuOrder.parse(Menu.TILES, hex("01 01 00")));
    }
}
