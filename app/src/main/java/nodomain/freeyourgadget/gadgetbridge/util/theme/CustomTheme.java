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
package nodomain.freeyourgadget.gadgetbridge.util.theme;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A user theme, read from the theme.json of a .gbtheme file (a zip holding the json and the
 * images of the slots).
 */
public class CustomTheme {
    public static final int FORMAT = 1;

    public static final String BASE_LIGHT = "light";
    public static final String BASE_DARK = "dark";

    // colors of the app
    public static final String COLOR_BACKGROUND = "background";
    public static final String COLOR_SURFACE = "surface";
    public static final String COLOR_SURFACE_CONTAINER_LOWEST = "surfaceContainerLowest";
    public static final String COLOR_SURFACE_CONTAINER_LOW = "surfaceContainerLow";
    public static final String COLOR_SURFACE_CONTAINER = "surfaceContainer";
    public static final String COLOR_SURFACE_CONTAINER_HIGH = "surfaceContainerHigh";
    public static final String COLOR_SURFACE_CONTAINER_HIGHEST = "surfaceContainerHighest";
    public static final String COLOR_ON_SURFACE = "onSurface";
    public static final String COLOR_ON_SURFACE_VARIANT = "onSurfaceVariant";
    public static final String COLOR_OUTLINE = "outline";
    public static final String COLOR_OUTLINE_VARIANT = "outlineVariant";
    public static final String COLOR_PRIMARY = "primary";
    public static final String COLOR_ON_PRIMARY = "onPrimary";
    public static final String COLOR_PRIMARY_CONTAINER = "primaryContainer";
    public static final String COLOR_ON_PRIMARY_CONTAINER = "onPrimaryContainer";
    public static final String COLOR_TOOLBAR = "toolbar";

    public static final List<String> COLOR_KEYS = Collections.unmodifiableList(Arrays.asList(
            COLOR_BACKGROUND, COLOR_SURFACE,
            COLOR_SURFACE_CONTAINER_LOWEST, COLOR_SURFACE_CONTAINER_LOW, COLOR_SURFACE_CONTAINER,
            COLOR_SURFACE_CONTAINER_HIGH, COLOR_SURFACE_CONTAINER_HIGHEST,
            COLOR_ON_SURFACE, COLOR_ON_SURFACE_VARIANT, COLOR_OUTLINE, COLOR_OUTLINE_VARIANT,
            COLOR_PRIMARY, COLOR_ON_PRIMARY, COLOR_PRIMARY_CONTAINER, COLOR_ON_PRIMARY_CONTAINER,
            COLOR_TOOLBAR
    ));

    /** The other colors are derived from these or from the default light / dark theme */
    public static final List<String> REQUIRED_COLOR_KEYS = Collections.unmodifiableList(Arrays.asList(
            COLOR_BACKGROUND, COLOR_SURFACE, COLOR_ON_SURFACE, COLOR_ON_SURFACE_VARIANT,
            COLOR_PRIMARY, COLOR_ON_PRIMARY, COLOR_PRIMARY_CONTAINER, COLOR_ON_PRIMARY_CONTAINER
    ));

    // colors of the charts, all optional
    public static final List<String> CHART_KEYS = Collections.unmodifiableList(Arrays.asList(
            "activity", "notWorn",
            "deepSleep", "lightSleep", "remSleep", "awakeSleep",
            "heartRate", "heartRateResting", "heartRateMinimum", "heartRateMaximum",
            "steps", "calories",
            "stressRelaxed", "stressMild", "stressModerate", "stressHigh",
            "paiToday", "paiWeekly",
            "batteryLevel"
    ));

    // images, all optional
    public static final String SLOT_BACKGROUND = "background";
    public static final String SLOT_DRAWER_HEADER = "drawerHeader";
    public static final String SLOT_DASHBOARD_HERO = "dashboardHero";
    public static final List<String> SLOT_KEYS = Collections.unmodifiableList(Arrays.asList(
            SLOT_BACKGROUND, SLOT_DRAWER_HEADER, SLOT_DASHBOARD_HERO
    ));

    // times of day a slot's image can change with, via "byTime" in the slot
    public static final String PERIOD_MORNING = "morning";
    public static final String PERIOD_DAY = "day";
    public static final String PERIOD_EVENING = "evening";
    public static final String PERIOD_NIGHT = "night";
    public static final List<String> PERIOD_KEYS = Collections.unmodifiableList(Arrays.asList(
            PERIOD_MORNING, PERIOD_DAY, PERIOD_EVENING, PERIOD_NIGHT
    ));

    private static final Pattern COLOR_PATTERN = Pattern.compile("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    private static final Pattern SLOT_PATH_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]+(/[A-Za-z0-9_\\-]+)*\\.(png|jpg|jpeg|webp)");

    private final String name;
    private final String author;
    private final String description;
    private final boolean dark;
    private final Map<String, Integer> colors;
    private final Map<String, Integer> charts;
    private final Map<String, String> slots;
    private final Map<String, Float> slotOpacity;
    private final Map<String, Map<String, String>> slotTimes;

    private CustomTheme(final String name, final String author, final String description, final boolean dark,
                        final Map<String, Integer> colors, final Map<String, Integer> charts,
                        final Map<String, String> slots, final Map<String, Float> slotOpacity,
                        final Map<String, Map<String, String>> slotTimes) {
        this.name = name;
        this.author = author;
        this.description = description;
        this.dark = dark;
        this.colors = Collections.unmodifiableMap(colors);
        this.charts = Collections.unmodifiableMap(charts);
        this.slots = Collections.unmodifiableMap(slots);
        this.slotOpacity = Collections.unmodifiableMap(slotOpacity);
        this.slotTimes = Collections.unmodifiableMap(slotTimes);
    }

    /**
     * Builds a theme in memory, for the theme editor. Only the given colors and slots are set; the rest fall back
     * to the base light / dark theme.
     *
     * @param slots    the file name of each filled slot's image, by slot key
     * @param opacity  the opacity (0..1) of each slot, by slot key; missing means fully opaque
     */
    public static CustomTheme of(final String name, final String author, final boolean dark,
                                 final Map<String, Integer> colors, final Map<String, String> slots,
                                 final Map<String, Float> opacity, final Map<String, Map<String, String>> slotTimes) {
        return new CustomTheme(name, author, "", dark,
                new LinkedHashMap<>(colors), new LinkedHashMap<>(), new LinkedHashMap<>(slots), new LinkedHashMap<>(opacity),
                new LinkedHashMap<>(slotTimes));
    }

    public String getName() {
        return name;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    public boolean isDark() {
        return dark;
    }

    /** Only the colors present in the theme, by color key */
    public Map<String, Integer> getColors() {
        return colors;
    }

    /** Only the chart colors present in the theme, by chart key */
    public Map<String, Integer> getCharts() {
        return charts;
    }

    /** The path in the theme file of the image of each filled slot, by slot key */
    public Map<String, String> getSlots() {
        return slots;
    }

    /** The opacity (0..1) of a slot's image, 1 (fully opaque) when not set */
    public float getSlotOpacity(final String slot) {
        final Float opacity = slotOpacity.get(slot);
        return opacity != null ? opacity : 1f;
    }

    /** The period-to-image-path map of a time-varying slot, or null for a normal single-image slot. */
    public Map<String, String> getSlotTimes(final String slot) {
        return slotTimes.get(slot);
    }

    /**
     * A name usable as a file name, the same theme name always gives the same id so that
     * importing a theme again replaces it.
     */
    public String getId() {
        final String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return id.isEmpty() ? "theme" : id;
    }

    /**
     * The theme.json of this theme, as written by the theme editor. Slots reference {@code <slot>.png} (the actual
     * bytes are saved beside the json); a slot with reduced opacity becomes an object.
     */
    public String toJson() {
        try {
            final JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("name", name);
            if (!author.isEmpty()) {
                root.put("author", author);
            }
            root.put("base", dark ? BASE_DARK : BASE_LIGHT);

            final JSONObject colorsJson = new JSONObject();
            for (final Map.Entry<String, Integer> entry : colors.entrySet()) {
                colorsJson.put(entry.getKey(), String.format(Locale.ROOT, "#%08X", entry.getValue()));
            }
            root.put("colors", colorsJson);

            if (!slots.isEmpty()) {
                final JSONObject slotsJson = new JSONObject();
                for (final Map.Entry<String, String> entry : slots.entrySet()) {
                    final String key = entry.getKey();
                    final float opacity = getSlotOpacity(key);
                    final Map<String, String> times = slotTimes.get(key);
                    if (times != null) {
                        final JSONObject slot = new JSONObject();
                        if (opacity < 1f) {
                            slot.put("opacity", Math.round(opacity * 100) / 100.0);
                        }
                        final JSONObject byTime = new JSONObject();
                        for (final Map.Entry<String, String> period : times.entrySet()) {
                            byTime.put(period.getKey(), period.getValue());
                        }
                        slot.put("byTime", byTime);
                        slotsJson.put(key, slot);
                    } else if (opacity < 1f) {
                        final JSONObject slot = new JSONObject();
                        slot.put("image", entry.getValue());
                        slot.put("opacity", Math.round(opacity * 100) / 100.0);
                        slotsJson.put(key, slot);
                    } else {
                        slotsJson.put(key, entry.getValue());
                    }
                }
                root.put("slots", slotsJson);
            }
            return root.toString(2);
        } catch (final JSONException e) {
            throw new IllegalStateException("Failed to serialize the theme", e);
        }
    }

    public static CustomTheme parse(final String json) throws InvalidThemeException {
        try {
            final JSONObject root = new JSONObject(json);
            final int format = root.optInt("format", -1);
            if (format != FORMAT) {
                throw new InvalidThemeException("Unsupported format " + root.opt("format") + ", expected " + FORMAT);
            }
            final String name = root.optString("name", "").trim();
            if (name.isEmpty()) {
                throw new InvalidThemeException("Missing name");
            }
            final String base = root.optString("base", "");
            if (!BASE_LIGHT.equals(base) && !BASE_DARK.equals(base)) {
                throw new InvalidThemeException("base must be \"" + BASE_LIGHT + "\" or \"" + BASE_DARK + "\"");
            }

            final Map<String, Integer> colors = parseColors(root.optJSONObject("colors"), "colors", COLOR_KEYS);
            for (final String key : REQUIRED_COLOR_KEYS) {
                if (!colors.containsKey(key)) {
                    throw new InvalidThemeException("Missing colors." + key);
                }
            }
            final Map<String, Integer> charts = parseColors(root.optJSONObject("charts"), "charts", CHART_KEYS);

            final Map<String, String> slots = new LinkedHashMap<>();
            final Map<String, Float> slotOpacity = new LinkedHashMap<>();
            final Map<String, Map<String, String>> slotTimes = new LinkedHashMap<>();
            final JSONObject slotsJson = root.optJSONObject("slots");
            if (slotsJson != null) {
                final Iterator<String> keys = slotsJson.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    if (!SLOT_KEYS.contains(key)) {
                        throw new InvalidThemeException("Unknown slot " + key);
                    }
                    if (slotsJson.isNull(key)) {
                        continue; // empty slot
                    }
                    // A slot is the image path, an object {"image": path, "opacity": 0..1}, or an object with
                    // "byTime": {period: path} for a banner that changes with the time of day.
                    final JSONObject slotObject = slotsJson.optJSONObject(key);
                    if (slotObject != null && slotObject.has("opacity")) {
                        final double opacity = slotObject.getDouble("opacity");
                        if (opacity < 0 || opacity > 1) {
                            throw new InvalidThemeException("slots." + key + ".opacity must be between 0 and 1");
                        }
                        slotOpacity.put(key, (float) opacity);
                    }
                    final JSONObject byTime = slotObject != null ? slotObject.optJSONObject("byTime") : null;
                    final String representative;
                    if (byTime != null) {
                        final Map<String, String> periods = new LinkedHashMap<>();
                        final Iterator<String> periodKeys = byTime.keys();
                        while (periodKeys.hasNext()) {
                            final String period = periodKeys.next();
                            if (!PERIOD_KEYS.contains(period)) {
                                throw new InvalidThemeException("Unknown time period " + period + " in slot " + key);
                            }
                            final String p = byTime.getString(period);
                            if (!SLOT_PATH_PATTERN.matcher(p).matches()) {
                                throw new InvalidThemeException("Invalid image path for slot " + key + " period " + period + ": " + p);
                            }
                            periods.put(period, p);
                        }
                        if (periods.isEmpty()) {
                            throw new InvalidThemeException("slots." + key + ".byTime is empty");
                        }
                        slotTimes.put(key, periods);
                        representative = periods.containsKey(PERIOD_DAY) ? periods.get(PERIOD_DAY) : periods.values().iterator().next();
                    } else {
                        representative = slotObject != null ? slotObject.optString("image", "") : slotsJson.getString(key);
                        if (!SLOT_PATH_PATTERN.matcher(representative).matches()) {
                            throw new InvalidThemeException("Invalid image path for slot " + key + ": " + representative);
                        }
                    }
                    slots.put(key, representative);
                }
            }

            return new CustomTheme(
                    name,
                    root.optString("author", ""),
                    root.optString("description", ""),
                    BASE_DARK.equals(base),
                    colors,
                    charts,
                    slots,
                    slotOpacity,
                    slotTimes
            );
        } catch (final JSONException e) {
            throw new InvalidThemeException("Invalid json: " + e.getMessage());
        }
    }

    private static Map<String, Integer> parseColors(final JSONObject json, final String section, final List<String> allowedKeys)
            throws JSONException, InvalidThemeException {
        final Map<String, Integer> colors = new LinkedHashMap<>();
        if (json == null) {
            return colors;
        }
        final Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (!allowedKeys.contains(key)) {
                throw new InvalidThemeException("Unknown " + section + "." + key);
            }
            colors.put(key, parseColor(section + "." + key, json.getString(key)));
        }
        return colors;
    }

    /**
     * @param value #RRGGBB or #AARRGGBB
     */
    static int parseColor(final String key, final String value) throws InvalidThemeException {
        if (!COLOR_PATTERN.matcher(value).matches()) {
            throw new InvalidThemeException("Invalid color for " + key + ": " + value + ", expected #RRGGBB or #AARRGGBB");
        }
        final long color = Long.parseLong(value.substring(1), 16);
        return value.length() == 7 ? (int) (0xFF000000L | color) : (int) color;
    }

    public static class InvalidThemeException extends Exception {
        public InvalidThemeException(final String message) {
            super(message);
        }
    }
}
