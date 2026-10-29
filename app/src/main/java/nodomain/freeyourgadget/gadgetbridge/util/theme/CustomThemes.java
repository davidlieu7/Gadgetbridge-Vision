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

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;

/**
 * Imported user themes (.gbtheme files). The colors replace the custom_theme_* and chart color
 * resources at runtime with a {@link ResourcesLoader}, which requires Android 11.
 */
public final class CustomThemes {
    private static final Logger LOG = LoggerFactory.getLogger(CustomThemes.class);

    public static final String PREF_CUSTOM_THEME = "pref_key_custom_theme";

    private static final String THEMES_DIR = "themes";
    private static final String THEME_JSON = "theme.json";
    private static final String SLOT_FILE_SUFFIX = ".img";
    private static final long MAX_THEME_FILE_SIZE = 32 * 1024 * 1024;
    private static final long MAX_JSON_SIZE = 256 * 1024;
    private static final long MAX_IMAGE_SIZE = 12 * 1024 * 1024;

    private static final Map<String, Integer> COLOR_RESOURCES = new HashMap<>();
    private static final Map<String, int[]> CHART_RESOURCES = new HashMap<>();
    // defaults of the optional colors, light and dark
    private static final Map<String, int[]> DEFAULT_COLOR_RESOURCES = new HashMap<>();

    static {
        COLOR_RESOURCES.put(CustomTheme.COLOR_BACKGROUND, R.color.custom_theme_background);
        COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE, R.color.custom_theme_surface);
        COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER_LOWEST, R.color.custom_theme_surface_container_lowest);
        COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER_LOW, R.color.custom_theme_surface_container_low);
        COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER, R.color.custom_theme_surface_container);
        COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER_HIGH, R.color.custom_theme_surface_container_high);
        COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER_HIGHEST, R.color.custom_theme_surface_container_highest);
        COLOR_RESOURCES.put(CustomTheme.COLOR_ON_SURFACE, R.color.custom_theme_on_surface);
        COLOR_RESOURCES.put(CustomTheme.COLOR_ON_SURFACE_VARIANT, R.color.custom_theme_on_surface_variant);
        COLOR_RESOURCES.put(CustomTheme.COLOR_OUTLINE, R.color.custom_theme_outline);
        COLOR_RESOURCES.put(CustomTheme.COLOR_OUTLINE_VARIANT, R.color.custom_theme_outline_variant);
        COLOR_RESOURCES.put(CustomTheme.COLOR_PRIMARY, R.color.custom_theme_primary);
        COLOR_RESOURCES.put(CustomTheme.COLOR_ON_PRIMARY, R.color.custom_theme_on_primary);
        COLOR_RESOURCES.put(CustomTheme.COLOR_PRIMARY_CONTAINER, R.color.custom_theme_primary_container);
        COLOR_RESOURCES.put(CustomTheme.COLOR_ON_PRIMARY_CONTAINER, R.color.custom_theme_on_primary_container);
        COLOR_RESOURCES.put(CustomTheme.COLOR_TOOLBAR, R.color.custom_theme_toolbar);

        DEFAULT_COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER_LOWEST, new int[]{R.color.surface_container_lowest_light, R.color.surface_container_lowest_dark});
        DEFAULT_COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER_LOW, new int[]{R.color.surface_container_low_light, R.color.surface_container_low_dark});
        DEFAULT_COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER, new int[]{R.color.surface_container_light, R.color.surface_container_dark});
        DEFAULT_COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER_HIGH, new int[]{R.color.surface_container_high_light, R.color.surface_container_high_dark});
        DEFAULT_COLOR_RESOURCES.put(CustomTheme.COLOR_SURFACE_CONTAINER_HIGHEST, new int[]{R.color.surface_container_highest_light, R.color.surface_container_highest_dark});
        DEFAULT_COLOR_RESOURCES.put(CustomTheme.COLOR_OUTLINE, new int[]{R.color.outline_light, R.color.outline_dark});
        DEFAULT_COLOR_RESOURCES.put(CustomTheme.COLOR_OUTLINE_VARIANT, new int[]{R.color.outline_variant_light, R.color.outline_variant_dark});

        CHART_RESOURCES.put("activity", new int[]{R.color.chart_activity_light, R.color.chart_activity_dark});
        CHART_RESOURCES.put("notWorn", new int[]{R.color.chart_not_worn_light, R.color.chart_not_worn_dark});
        CHART_RESOURCES.put("deepSleep", new int[]{R.color.chart_deep_sleep_light, R.color.chart_deep_sleep_dark});
        CHART_RESOURCES.put("lightSleep", new int[]{R.color.chart_light_sleep_light, R.color.chart_light_sleep_dark});
        CHART_RESOURCES.put("remSleep", new int[]{R.color.chart_rem_sleep_light, R.color.chart_rem_sleep_dark});
        CHART_RESOURCES.put("awakeSleep", new int[]{R.color.chart_awake_sleep_light, R.color.chart_awake_sleep_dark});
        CHART_RESOURCES.put("heartRate", new int[]{R.color.chart_heartrate, R.color.chart_line_heart_rate});
        CHART_RESOURCES.put("heartRateResting", new int[]{R.color.chart_heartrate_resting});
        CHART_RESOURCES.put("heartRateMinimum", new int[]{R.color.chart_heartrate_minimum});
        CHART_RESOURCES.put("heartRateMaximum", new int[]{R.color.chart_heartrate_maximum});
        CHART_RESOURCES.put("steps", new int[]{R.color.steps_color});
        CHART_RESOURCES.put("calories", new int[]{R.color.calories_color});
        CHART_RESOURCES.put("stressRelaxed", new int[]{R.color.chart_stress_relaxed});
        CHART_RESOURCES.put("stressMild", new int[]{R.color.chart_stress_mild});
        CHART_RESOURCES.put("stressModerate", new int[]{R.color.chart_stress_moderate});
        CHART_RESOURCES.put("stressHigh", new int[]{R.color.chart_stress_high});
        CHART_RESOURCES.put("paiToday", new int[]{R.color.chart_pai_today});
        CHART_RESOURCES.put("paiWeekly", new int[]{R.color.chart_pai_weekly});
        CHART_RESOURCES.put("batteryLevel", new int[]{R.color.chart_battery_level});
    }

    // the active theme, reloaded when the preference changes
    private static String loadedId = null;
    @Nullable
    private static CustomTheme activeTheme;
    @Nullable
    private static File activeDir;
    // a ResourcesLoader, typed as Object to keep this class loadable before Android 11
    @Nullable
    private static Object activeLoader;
    private static final Set<Object> createdLoaders = new HashSet<>();
    private static final Map<String, Bitmap> slotBitmaps = new HashMap<>();

    private CustomThemes() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static synchronized boolean isActive() {
        return getActive() != null;
    }

    public static synchronized boolean isDark() {
        final CustomTheme theme = getActive();
        return theme != null && theme.isDark();
    }

    @Nullable
    public static synchronized CustomTheme getActive() {
        if (!isSupported()) {
            return null;
        }
        final String id = GBApplication.getPrefs().getString(PREF_CUSTOM_THEME, "");
        if (!id.equals(loadedId)) {
            load(GBApplication.getContext(), id);
        }
        return activeTheme;
    }

    private static void load(final Context context, final String id) {
        loadedId = id;
        activeTheme = null;
        activeDir = null;
        activeLoader = null;
        slotBitmaps.clear();
        if (id.isEmpty()) {
            return;
        }
        try {
            final File dir = getThemeDir(context, id);
            final CustomTheme theme = readTheme(dir);
            final Object loader = createLoader(context, theme);
            if (loader == null) {
                LOG.warn("Failed to create the resources loader of theme {}, using the default theme", id);
                return;
            }
            createdLoaders.add(loader);
            activeTheme = theme;
            activeDir = dir;
            activeLoader = loader;
            LOG.info("Loaded custom theme {}", id);
        } catch (final Exception e) {
            LOG.error("Failed to load custom theme {}, using the default theme", id, e);
        }
    }

    /**
     * Installs the colors of the active theme in these resources and in the application
     * resources, and removes the colors of a previous theme.
     */
    public static synchronized void applyToResources(@NonNull final Resources resources) {
        if (!isSupported()) {
            return;
        }
        getActive();
        updateLoaders(resources);
        updateLoaders(GBApplication.getContext().getResources());
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void updateLoaders(final Resources resources) {
        // removing a loader that is not present, or adding one already present, does nothing
        for (final Object loader : createdLoaders) {
            if (loader != activeLoader) {
                resources.removeLoaders((ResourcesLoader) loader);
            }
        }
        if (activeLoader != null) {
            resources.addLoaders((ResourcesLoader) activeLoader);
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Nullable
    private static Object createLoader(final Context context, final CustomTheme theme) throws IOException {
        final Map<Integer, Integer> colorMapping = new HashMap<>();
        final Map<String, Integer> colors = theme.getColors();
        for (final Map.Entry<String, Integer> entry : COLOR_RESOURCES.entrySet()) {
            Integer color = colors.get(entry.getKey());
            if (color == null) {
                color = getDefaultColor(context, theme, entry.getKey());
            }
            colorMapping.put(entry.getValue(), color);
        }
        for (final Map.Entry<String, Integer> entry : theme.getCharts().entrySet()) {
            for (final int resId : CHART_RESOURCES.get(entry.getKey())) {
                colorMapping.put(resId, entry.getValue());
            }
        }

        final byte[] table = ColorResourcesTableCreator.create(context, colorMapping);
        FileDescriptor arscFile = null;
        try {
            arscFile = Os.memfd_create("gbtheme.arsc", 0);
            // must not be closed through the stream
            final OutputStream out = new FileOutputStream(arscFile);
            out.write(table);
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(arscFile)) {
                final ResourcesLoader loader = new ResourcesLoader();
                loader.addProvider(ResourcesProvider.loadFromTable(pfd, null));
                return loader;
            }
        } catch (final Exception e) {
            LOG.error("Failed to create the theme resources table", e);
            return null;
        } finally {
            if (arscFile != null) {
                try {
                    Os.close(arscFile);
                } catch (final Exception e) {
                    LOG.warn("Failed to close the theme resources table", e);
                }
            }
        }
    }

    /**
     * A color of the active theme, for the code that does not resolve the theme attributes.
     */
    public static synchronized int getColor(@NonNull final String key) {
        final CustomTheme theme = getActive();
        if (theme == null) {
            throw new IllegalStateException("No active custom theme");
        }
        final Integer color = theme.getColors().get(key);
        return color != null ? color : getDefaultColor(GBApplication.getContext(), theme, key);
    }

    private static int getDefaultColor(final Context context, final CustomTheme theme, final String key) {
        if (CustomTheme.COLOR_TOOLBAR.equals(key)) {
            return theme.getColors().get(CustomTheme.COLOR_BACKGROUND);
        }
        final int[] resIds = DEFAULT_COLOR_RESOURCES.get(key);
        if (resIds == null) {
            throw new IllegalStateException("No default for required color " + key);
        }
        return context.getColor(resIds[theme.isDark() ? 1 : 0]);
    }

    /**
     * Sets the background slot of the active theme as the window background, over the background color.
     */
    public static synchronized void applyWindowBackground(@NonNull final Activity activity) {
        final CustomTheme theme = getActive();
        if (theme == null) {
            return;
        }
        final Drawable image = getSlotDrawable(activity, CustomTheme.SLOT_BACKGROUND);
        if (image != null) {
            final int color = theme.getColors().get(CustomTheme.COLOR_BACKGROUND);
            activity.getWindow().setBackgroundDrawable(new LayerDrawable(new Drawable[]{new ColorDrawable(color), image}));
        }
    }

    /**
     * Sets the image of a slot of the active theme as the background of a view, if the slot is filled.
     */
    public static synchronized void applySlot(@NonNull final View view, @NonNull final String slot) {
        final Drawable image = getSlotDrawable(view.getContext(), slot);
        if (image != null) {
            view.setBackground(image);
        }
    }

    @Nullable
    private static Drawable getSlotDrawable(final Context context, final String slot) {
        final CustomTheme theme = getActive();
        if (theme == null || activeDir == null || !theme.getSlots().containsKey(slot)
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        final int alpha = Math.round(theme.getSlotOpacity(slot) * 255);
        // A time-varying slot (byTime) resolves to the current period's image; a normal slot to <slot>.img
        final File file;
        final String cacheKey;
        final Map<String, String> times = theme.getSlotTimes(slot);
        if (times != null) {
            final File resolved = resolveTimedSlotFile(slot, times);
            if (resolved == null) {
                return null;
            }
            file = resolved;
            cacheKey = resolved.getName();
        } else {
            file = new File(activeDir, slot + SLOT_FILE_SUFFIX);
            cacheKey = slot;
        }
        Bitmap bitmap = slotBitmaps.get(cacheKey);
        if (bitmap != null) {
            final CenterCropDrawable drawable = new CenterCropDrawable(bitmap);
            drawable.setAlpha(alpha);
            return drawable;
        }
        final Drawable image = decodeScaled(file, context.getResources().getDisplayMetrics());
        if (image instanceof AnimatedImageDrawable) {
            // an animated image draws its own frames, so it can not be shared between views
            final AnimatedImageDrawable animated = (AnimatedImageDrawable) image;
            if (ValueAnimator.areAnimatorsEnabled()) {
                animated.start();
            }
            final CenterCropAnimatedDrawable drawable = new CenterCropAnimatedDrawable(animated);
            drawable.setAlpha(alpha);
            return drawable;
        }
        if (!(image instanceof BitmapDrawable) || ((BitmapDrawable) image).getBitmap() == null) {
            LOG.warn("Failed to decode the {} image of the theme", slot);
            return null;
        }
        bitmap = ((BitmapDrawable) image).getBitmap();
        slotBitmaps.put(cacheKey, bitmap);
        final CenterCropDrawable drawable = new CenterCropDrawable(bitmap);
        drawable.setAlpha(alpha);
        return drawable;
    }

    @Nullable
    private static File resolveTimedSlotFile(final String slot, final Map<String, String> times) {
        final java.util.List<String> order = new java.util.ArrayList<>();
        final String current = currentPeriod();
        order.add(current);
        for (final String period : CustomTheme.PERIOD_KEYS) {
            if (!period.equals(current)) {
                order.add(period);
            }
        }
        for (final String period : order) {
            if (times.containsKey(period)) {
                final File file = new File(activeDir, slot + "_" + period + SLOT_FILE_SUFFIX);
                if (file.isFile()) {
                    return file;
                }
            }
        }
        return null;
    }

    /**
     * The time-of-day period for the current wall-clock time, matching the "byTime" keys of a slot.
     */
    private static String currentPeriod() {
        final int hour = java.time.LocalTime.now().getHour();
        if (hour >= 5 && hour < 11) {
            return CustomTheme.PERIOD_MORNING;
        }
        if (hour >= 11 && hour < 17) {
            return CustomTheme.PERIOD_DAY;
        }
        if (hour >= 17 && hour < 21) {
            return CustomTheme.PERIOD_EVENING;
        }
        return CustomTheme.PERIOD_NIGHT;
    }

    /**
     * Whether the active theme fills [slot] with an image, for views that only exist when the slot is set
     * (e.g. the dashboard hero banner).
     */
    public static synchronized boolean isSlotFilled(@NonNull final String slot) {
        final CustomTheme theme = getActive();
        return theme != null && activeDir != null && theme.getSlots().containsKey(slot)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * Decodes a slot image, an animated webp as an {@link AnimatedImageDrawable}, a still image as
     * a {@link BitmapDrawable}.
     */
    @Nullable
    @RequiresApi(Build.VERSION_CODES.P)
    private static Drawable decodeScaled(final File file, final DisplayMetrics metrics) {
        // no need for more pixels than the screen
        final int target = Math.max(metrics.widthPixels, metrics.heightPixels);
        try {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(file), (decoder, info, source) -> {
                int sampleSize = 1;
                while (Math.max(info.getSize().getWidth(), info.getSize().getHeight()) / (sampleSize * 2) >= target) {
                    sampleSize *= 2;
                }
                decoder.setTargetSampleSize(sampleSize);
                // still images are cached and may be drawn on a software canvas, animated images ignore this
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (final IOException e) {
            LOG.warn("Failed to decode {}", file, e);
            return null;
        }
    }

    /**
     * Imports a .gbtheme file, a zip holding theme.json and the images of the slots. A theme
     * with the same name is replaced.
     */
    public static synchronized CustomTheme importTheme(final Context context, final Uri uri)
            throws IOException, CustomTheme.InvalidThemeException {
        final File tempFile = File.createTempFile("gbtheme", ".zip", context.getCacheDir());
        try {
            FileUtils.copyURItoFile(context, uri, tempFile);
            if (tempFile.length() > MAX_THEME_FILE_SIZE) {
                throw new CustomTheme.InvalidThemeException("The theme file is too large");
            }

            final String json;
            final Map<String, byte[]> images = new HashMap<>();
            final CustomTheme theme;
            try (ZipFile zip = new ZipFile(tempFile)) {
                final ZipEntry jsonEntry = zip.getEntry(THEME_JSON);
                if (jsonEntry == null) {
                    throw new CustomTheme.InvalidThemeException("No " + THEME_JSON + " in the theme file");
                }
                json = new String(readEntry(zip, jsonEntry, MAX_JSON_SIZE), StandardCharsets.UTF_8);
                theme = CustomTheme.parse(json);
                for (final String slot : theme.getSlots().keySet()) {
                    final Map<String, String> times = theme.getSlotTimes(slot);
                    if (times != null) {
                        for (final Map.Entry<String, String> period : times.entrySet()) {
                            images.put(slot + "_" + period.getKey(), readSlotImageEntry(zip, slot, period.getValue()));
                        }
                    } else {
                        images.put(slot, readSlotImageEntry(zip, slot, theme.getSlots().get(slot)));
                    }
                }
            }

            final File dir = getThemeDir(context, theme.getId());
            deleteRecursively(dir);
            if (!dir.mkdirs()) {
                throw new IOException("Failed to create " + dir);
            }
            writeFile(new File(dir, THEME_JSON), json.getBytes(StandardCharsets.UTF_8));
            for (final Map.Entry<String, byte[]> image : images.entrySet()) {
                writeFile(new File(dir, image.getKey() + SLOT_FILE_SUFFIX), image.getValue());
            }

            // reload, in case the replaced theme is the active one
            loadedId = null;
            LOG.info("Imported custom theme {}", theme.getId());
            return theme;
        } finally {
            if (!tempFile.delete()) {
                LOG.warn("Failed to delete {}", tempFile);
            }
        }
    }

    private static byte[] readSlotImageEntry(final ZipFile zip, final String slot, final String path)
            throws IOException, CustomTheme.InvalidThemeException {
        final ZipEntry imageEntry = zip.getEntry(path);
        if (imageEntry == null) {
            throw new CustomTheme.InvalidThemeException("Missing image " + path + " of slot " + slot);
        }
        final byte[] image = readEntry(zip, imageEntry, MAX_IMAGE_SIZE);
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new CustomTheme.InvalidThemeException("Not an image: " + path);
        }
        return image;
    }

    /**
     * The installed theme with this id, or null if it is missing or invalid. For the theme editor to load a theme
     * for editing.
     */
    @Nullable
    public static synchronized CustomTheme getTheme(final Context context, final String id) {
        try {
            return readTheme(getThemeDir(context, id));
        } catch (final Exception e) {
            LOG.warn("Failed to read theme {}", id, e);
            return null;
        }
    }

    /**
     * The raw image bytes of a slot of an installed theme, or null when the slot is empty. For the theme editor to
     * keep the images it did not change when it saves.
     */
    @Nullable
    public static synchronized byte[] readSlotImage(final Context context, final String id, final String slot) {
        final File file = new File(getThemeDir(context, id), slot + SLOT_FILE_SUFFIX);
        if (!file.isFile()) {
            return null;
        }
        try (final InputStream in = new FileInputStream(file)) {
            return readStream(in, MAX_IMAGE_SIZE);
        } catch (final Exception e) {
            LOG.warn("Failed to read slot image {}/{}", id, slot, e);
            return null;
        }
    }

    /**
     * @return the installed themes, as id and name, sorted by name
     */
    public static List<Pair<String, String>> list(final Context context) {
        final List<Pair<String, String>> themes = new ArrayList<>();
        final File[] dirs = new File(context.getFilesDir(), THEMES_DIR).listFiles(File::isDirectory);
        if (dirs == null) {
            return themes;
        }
        for (final File dir : dirs) {
            try {
                themes.add(Pair.create(dir.getName(), readTheme(dir).getName()));
            } catch (final Exception e) {
                LOG.warn("Ignoring invalid theme in {}", dir, e);
            }
        }
        Collections.sort(themes, (a, b) -> a.second.compareToIgnoreCase(b.second));
        return themes;
    }

    public static synchronized void delete(final Context context, final String id) {
        deleteRecursively(getThemeDir(context, id));
        if (id.equals(GBApplication.getPrefs().getString(PREF_CUSTOM_THEME, ""))) {
            GBApplication.getPrefs().getPreferences().edit().putString(PREF_CUSTOM_THEME, "").apply();
        }
        loadedId = null;
    }

    /**
     * Saves a theme built in the theme editor: its theme.json and the image bytes of the changed slots. Images of
     * slots already present and not in [newImages] are kept (editing), so the editor only re-reads images the user
     * changed. The active theme is reloaded on the next use.
     *
     * @param newImages the raw image bytes by slot key, for the slots whose image was set or changed
     */
    public static synchronized CustomTheme saveTheme(final Context context, final CustomTheme theme,
                                                     final Map<String, byte[]> newImages) throws IOException {
        final File dir = getThemeDir(context, theme.getId());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create " + dir);
        }
        writeFile(new File(dir, THEME_JSON), theme.toJson().getBytes(StandardCharsets.UTF_8));
        for (final Map.Entry<String, byte[]> image : newImages.entrySet()) {
            writeFile(new File(dir, image.getKey() + SLOT_FILE_SUFFIX), image.getValue());
        }
        // Drop the image of a slot that is no longer part of the theme
        for (final String slot : CustomTheme.SLOT_KEYS) {
            if (!theme.getSlots().containsKey(slot)) {
                final File old = new File(dir, slot + SLOT_FILE_SUFFIX);
                if (old.exists() && !old.delete()) {
                    LOG.warn("Failed to delete {}", old);
                }
            }
        }
        for (final String slot : theme.getSlots().keySet()) {
            if (theme.getSlotTimes(slot) == null && !new File(dir, slot + SLOT_FILE_SUFFIX).exists()) {
                throw new IOException("Missing image for slot " + slot);
            }
        }
        loadedId = null; // reload, in case this is the active theme
        LOG.info("Saved custom theme {}", theme.getId());
        return theme;
    }

    private static File getThemeDir(final Context context, final String id) {
        if (!id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid theme id " + id);
        }
        return new File(new File(context.getFilesDir(), THEMES_DIR), id);
    }

    private static CustomTheme readTheme(final File dir) throws IOException, CustomTheme.InvalidThemeException {
        final File file = new File(dir, THEME_JSON);
        try (InputStream in = new FileInputStream(file)) {
            return CustomTheme.parse(new String(readStream(in, MAX_JSON_SIZE), StandardCharsets.UTF_8));
        }
    }

    private static byte[] readEntry(final ZipFile zip, final ZipEntry entry, final long maxSize)
            throws IOException, CustomTheme.InvalidThemeException {
        try (InputStream in = zip.getInputStream(entry)) {
            return readStream(in, maxSize);
        }
    }

    private static byte[] readStream(final InputStream in, final long maxSize)
            throws IOException, CustomTheme.InvalidThemeException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > maxSize) {
                throw new CustomTheme.InvalidThemeException("File too large in the theme");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static void writeFile(final File file, final byte[] content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
    }

    private static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            LOG.warn("Failed to delete {}", file);
        }
    }

    /**
     * Scales the image to fill the bounds, keeping its aspect ratio, and crops what overflows.
     */
    private static class CenterCropDrawable extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();

        CenterCropDrawable(final Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        protected void onBoundsChange(@NonNull final Rect bounds) {
            final float scale = Math.max(
                    bounds.width() / (float) bitmap.getWidth(),
                    bounds.height() / (float) bitmap.getHeight()
            );
            matrix.setScale(scale, scale);
            matrix.postTranslate(
                    bounds.left + (bounds.width() - bitmap.getWidth() * scale) / 2f,
                    bounds.top + (bounds.height() - bitmap.getHeight() * scale) / 2f
            );
        }

        @Override
        public void draw(@NonNull final Canvas canvas) {
            canvas.save();
            canvas.clipRect(getBounds());
            canvas.drawBitmap(bitmap, matrix, paint);
            canvas.restore();
        }

        @Override
        public void setAlpha(final int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable final ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Same as {@link CenterCropDrawable} for an animated image, which is drawn scaled to its bounds:
     * its bounds are made larger than ours and clipped.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private static class CenterCropAnimatedDrawable extends DrawableWrapper {
        private final Rect imageBounds = new Rect();

        CenterCropAnimatedDrawable(final AnimatedImageDrawable image) {
            super(image);
        }

        /**
         * Runs the animation only while the view shows it: views hide their background when their window goes to
         * the background or they are detached, so screens under the current one don't keep decoding frames.
         */
        @Override
        public boolean setVisible(final boolean visible, final boolean restart) {
            final boolean changed = super.setVisible(visible, restart);
            final Drawable image = getDrawable();
            if (image instanceof AnimatedImageDrawable) {
                final AnimatedImageDrawable animated = (AnimatedImageDrawable) image;
                if (!visible) {
                    animated.stop();
                } else if (!animated.isRunning() && ValueAnimator.areAnimatorsEnabled()) {
                    animated.start();
                }
            }
            return changed;
        }

        @Override
        protected void onBoundsChange(@NonNull final Rect bounds) {
            final Drawable image = getDrawable();
            if (image == null) {
                return;
            }
            final int width = Math.max(1, image.getIntrinsicWidth());
            final int height = Math.max(1, image.getIntrinsicHeight());
            final float scale = Math.max(bounds.width() / (float) width, bounds.height() / (float) height);
            final int scaledWidth = Math.round(width * scale);
            final int scaledHeight = Math.round(height * scale);
            final int left = bounds.left + (bounds.width() - scaledWidth) / 2;
            final int top = bounds.top + (bounds.height() - scaledHeight) / 2;
            imageBounds.set(left, top, left + scaledWidth, top + scaledHeight);
            image.setBounds(imageBounds);
        }

        @Override
        public void draw(@NonNull final Canvas canvas) {
            final Drawable image = getDrawable();
            if (image == null) {
                return;
            }
            canvas.save();
            canvas.clipRect(getBounds());
            image.draw(canvas);
            canvas.restore();
        }

        @Override
        public int getIntrinsicWidth() {
            // fills the view, like CenterCropDrawable
            return -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return -1;
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
