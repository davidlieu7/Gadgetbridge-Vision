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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.SapProtocol;

/**
 * The three user-arrangeable menus on the band, each on its own channel but
 * sharing one idea: the band reports every item it has and where it sits,
 * and the phone sends back a new arrangement.
 *
 * <pre>
 *   TILES (0x0f) - the screens swiped through from the watchface
 *     table  B-&gt;P: 00 01 [n] n x (03 [id] 02 [slot])      slot 1-based, ff = hidden
 *     set    P-&gt;B: 01 01 [n] n x (03 [id] 02 [slot])      shown tiles only, by id
 *     request P-&gt;B: 00
 *   QUICK_PANEL (0x13) - the buttons in the pull-down panel
 *     table  B-&gt;P: 40 01 [n] n x (03 [id] 02 [slot])      slot 1-based, ff = hidden
 *     set    P-&gt;B: 01 01 [n] n x (03 [id] 02 [slot])      shown buttons only, by slot
 *     request P-&gt;B: 00
 *   APPS (0x18) - the app list behind the button; every app, order only
 *     table  B-&gt;P: c0 02 01 03 [n] n x (00 [id] 01 [pos])  pos 1-based
 *     set    P-&gt;B: 01 02 01 03 [n] n x (00 [id] 01 [pos])  pos 0-based, all apps
 * </pre>
 *
 * All the set forms are confirmed against a capture of the vendor app
 * re-arranging each menu (including the empty tile set, {@code 01 01 00}),
 * and the band's tables against captures of it reporting them - including
 * re-sending the whole tile table after each change made on the band itself.
 * The band acks none of the sets.
 *
 * <p>The tile table looks like a request (MSG_ID 0, type bit clear), so it
 * must not be answered on MSG_ID 0: that is a new request, and the band
 * answers it with the table again. The apps table has no request at all;
 * the band sends it after the license agreement on connect.
 */
public final class Fit3MenuOrder {

    public enum Menu {
        TILES(SapProtocol.CH_TILES, "samsung_fit3_tiles_sortable"),
        QUICK_PANEL(SapProtocol.CH_QUICK_PANEL, "samsung_fit3_quick_panel_sortable"),
        APPS(SapProtocol.CH_APPS, "samsung_fit3_apps_sortable");

        public final int channel;
        /** The device preference holding the shown items, in order, comma-separated. */
        public final String prefKey;

        Menu(int channel, String prefKey) {
            this.channel = channel;
            this.prefKey = prefKey;
        }

        @Nullable
        public static Menu forChannel(int channel) {
            for (Menu m : values()) {
                if (m.channel == channel) {
                    return m;
                }
            }
            return null;
        }

        @Nullable
        public static Menu forPrefKey(String key) {
            for (Menu m : values()) {
                if (m.prefKey.equals(key)) {
                    return m;
                }
            }
            return null;
        }

        /** Whether items can be hidden, or only reordered. */
        public boolean canHide() {
            return this != APPS;
        }
    }

    private static final int MSG_ID_TABLE = 0x00;
    private static final int MSG_ID_SET = 0x01;
    private static final int SLOT_HIDDEN = 0xff;

    private Fit3MenuOrder() {
    }

    /** One item and where it sits. */
    public static final class Entry {
        public final int id;
        /** 1-based position, or 0 if the item isn't shown. */
        public final int slot;

        public Entry(int id, int slot) {
            this.id = id;
            this.slot = slot;
        }

        public boolean isShown() {
            return slot > 0;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d%s", id, isShown() ? "@" + slot : "");
        }
    }

    /** Ask the band for its table; {@code null} for menus with no request (apps). */
    @Nullable
    public static byte[] buildRequest(Menu menu) {
        return menu == Menu.APPS ? null : new byte[]{MSG_ID_TABLE};
    }

    /** @return every item the band listed, or {@code null} if this isn't the menu's table. */
    @Nullable
    public static List<Entry> parse(Menu menu, byte[] body) {
        if (body == null || body.length < 3 || (body[0] & 0x3f) != MSG_ID_TABLE) {
            return null;
        }
        int off;
        int idTag;
        int slotTag;
        if (menu == Menu.APPS) {
            // [02 01][03 n] then (00 id 01 pos)
            if (body.length < 5 || (body[1] & 0xff) != 0x02 || (body[3] & 0xff) != 0x03) {
                return null;
            }
            off = 4;
            idTag = 0x00;
            slotTag = 0x01;
        } else {
            // [01 n] then (03 id 02 slot)
            if ((body[1] & 0xff) != 0x01) {
                return null;
            }
            off = 2;
            idTag = 0x03;
            slotTag = 0x02;
        }
        int count = body[off] & 0xff;
        off++;
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count && off + 4 <= body.length; i++, off += 4) {
            if ((body[off] & 0xff) != idTag || (body[off + 2] & 0xff) != slotTag) {
                break;
            }
            int slot = body[off + 3] & 0xff;
            entries.add(new Entry(body[off + 1] & 0xff, slot == SLOT_HIDDEN ? 0 : slot));
        }
        return entries;
    }

    /** The shown items' ids, in display order. */
    public static List<Integer> shownIds(List<Entry> entries) {
        List<Entry> shown = new ArrayList<>();
        for (Entry e : entries) {
            if (e.isShown()) {
                shown.add(e);
            }
        }
        Collections.sort(shown, (a, b) -> Integer.compare(a.slot, b.slot));
        List<Integer> ids = new ArrayList<>(shown.size());
        for (Entry e : shown) {
            ids.add(e.id);
        }
        return ids;
    }

    /**
     * Build the set message for a new arrangement.
     *
     * @param shownInOrder the items to show, first to last
     * @param allIds       every item the band has - for {@link Menu#APPS},
     *                     which can't hide anything, whatever isn't in
     *                     {@code shownInOrder} is appended in this order
     */
    public static byte[] buildSet(Menu menu, List<Integer> shownInOrder, List<Integer> allIds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MSG_ID_SET);
        if (menu == Menu.APPS) {
            List<Integer> order = new ArrayList<>(shownInOrder);
            for (Integer id : allIds) {
                if (!order.contains(id)) {
                    order.add(id);
                }
            }
            out.write(0x02);
            out.write(0x01);
            out.write(0x03);
            out.write(order.size());
            for (int i = 0; i < order.size(); i++) {
                out.write(0x00);
                out.write(order.get(i));
                out.write(0x01);
                out.write(i);
            }
            return out.toByteArray();
        }

        List<Entry> entries = new ArrayList<>(shownInOrder.size());
        for (int i = 0; i < shownInOrder.size(); i++) {
            entries.add(new Entry(shownInOrder.get(i), i + 1));
        }
        if (menu == Menu.TILES) {
            // The vendor lists tiles by id rather than by position; match it.
            Collections.sort(entries, (a, b) -> Integer.compare(a.id, b.id));
        }
        out.write(0x01);
        out.write(entries.size());
        for (Entry e : entries) {
            out.write(0x03);
            out.write(e.id);
            out.write(0x02);
            out.write(e.slot);
        }
        return out.toByteArray();
    }

    /** A readable name for an item id, from the band's own catalogue of each menu. */
    public static String name(Menu menu, int id) {
        switch (menu) {
            case TILES:
                return tileName(id);
            case QUICK_PANEL:
                return quickPanelName(id);
            default:
                return appName(id);
        }
    }

    private static String tileName(int id) {
        switch (id) {
            case 1: return "Alarm";
            case 2: return "Weather";
            case 3: return "Monthly calendar";
            case 4: return "Timer";
            case 5: return "World clock";
            case 6: return "Media controller";
            case 7: return "Weather forecast";
            case 8: return "Today's events";
            case 9: return "Battery";
            case 20: return "Steps";
            case 21: return "Multi workout";
            case 22: return "Stress";
            case 23: return "Daily activity";
            case 24: return "Cycle tracking";
            case 25: return "Heart rate";
            case 26: return "Water";
            case 27: return "Food";
            case 28: return "Sleep";
            case 29: return "Health summary";
            case 30: return "Blood oxygen";
            case 31: return "Together";
            default: return "Tile " + id;
        }
    }

    private static String quickPanelName(int id) {
        switch (id) {
            case 1: return "Always on display";
            case 2: return "Brightness";
            case 3: return "Do not disturb";
            case 4: return "Find my phone";
            case 5: return "Vibrate/mute";
            case 6: return "Water lock";
            case 10: return "Sleep mode";
            case 13: return "Power off";
            case 14: return "Settings";
            case 15: return "Flashlight";
            case 16: return "Theater mode";
            case 17: return "Airplane mode";
            case 18: return "Power saving";
            default: return "Button " + id;
        }
    }

    private static String appName(int id) {
        switch (id) {
            case 1: return "Samsung Health";
            case 2: return "Media controller";
            case 3: return "Find my phone";
            case 4: return "Weather";
            case 5: return "Calendar";
            case 6: return "Settings";
            case 7: return "Timer";
            case 8: return "Alarm";
            case 9: return "Stopwatch";
            case 10: return "World clock";
            case 11: return "Camera controller";
            case 12: return "Calculator";
            default: return "App " + id;
        }
    }
}
