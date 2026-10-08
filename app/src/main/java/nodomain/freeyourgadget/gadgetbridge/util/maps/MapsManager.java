/*  Copyright (C) 2024-2025 José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.util.maps;

import android.content.Context;

import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Dimension;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.util.Accumulator;

public final class MapsManager {
    private static final Logger LOG = LoggerFactory.getLogger(MapsManager.class);

    public static final String PREF_MAPS_FOLDER = "maps_folder";
    public static final String PREF_TRACK_COLOR = "maps_track_color";
    public static final String PREF_MAP_THEME = "maps_theme";
    /** Theme value that uses the user-picked theme file instead of a bundled theme. */
    public static final String MAP_THEME_VALUE_CUSTOM = "custom";
    /** Content URI of the user-picked render theme, used when the theme is custom. */
    public static final String PREF_MAP_THEME_CUSTOM_URI = "maps_theme_custom_uri";

    private final Context mContext;
    private final MapView mapView;
    private final MapDataLoader mapDataLoader;
    /** The track drawn with the configured track color; recolored by {@link #reload()}. */
    private Polyline polyline;
    /** Every track currently on the map, in drawing order. */
    private final List<Polyline> trackPolylines = new ArrayList<>();

    private TileRendererLayer tileRendererLayer;
    private boolean tileRenderedAdded;

    private boolean isMapLoaded = false;

    public MapsManager(final Context context, final MapView mapView) {
        this.mContext = context;
        this.mapView = mapView;
        this.mapDataLoader = new MapDataLoader(context);
    }

    public void loadMaps() {
        if (tileRendererLayer != null) {
            mapView.getLayerManager().getLayers().remove(tileRendererLayer);
            tileRendererLayer.onDestroy();
            tileRendererLayer.getTileCache().purge();
            tileRendererLayer = null;
        }

        mapView.getModel().displayModel.setBackgroundColor(GBApplication.getWindowBackgroundColor(mapView.getContext()));

        isMapLoaded = false;

        AndroidGraphicFactory.createInstance(GBApplication.app());

        final MapLoadResult loadResult = mapDataLoader.loadMultiMapDataStore();
        isMapLoaded = loadResult.getAnyLoaded();

        final TileCache tileCache = AndroidUtil.createTileCache(
                mContext,
                "mapcache",
                mapView.getModel().displayModel.getTileSize(),
                1f,
                mapView.getModel().frameBufferModel.getOverdrawFactor()
        );

        tileRendererLayer = new TileRendererLayer(
                tileCache,
                loadResult.getDataStore(),
                mapView.getModel().mapViewPosition,
                true,
                false,
                false,
                AndroidGraphicFactory.INSTANCE
        );

        tileRendererLayer.setXmlRenderTheme(mapDataLoader.resolveTheme());
        // Do not add the tile renderer layer before setting the bounding box in setTrack,
        // otherwise we load the entire map to memory and might crash with OOM
    }

    public boolean isMapLoaded() {
        return isMapLoaded;
    }

    public void onDestroy() {
        for (final Polyline trackPolyline : trackPolylines) {
            mapView.getLayerManager().getLayers().remove(trackPolyline);
            trackPolyline.onDestroy();
        }
        trackPolylines.clear();
        polyline = null;

        if (tileRendererLayer != null) {
            if (tileRenderedAdded) {
                mapView.getLayerManager().getLayers().remove(tileRendererLayer);
            }
            tileRendererLayer.onDestroy();
            tileRendererLayer.getTileCache().purge();
            tileRendererLayer = null;
        }

        isMapLoaded = false;
    }

    private BoundingBox minimalBoundingBox(double minLat, double minLon, double maxLat, double maxLon) {
        final LatLong center = new LatLong(minLat + (maxLat - minLat) / 2, minLon + (maxLon - minLon) / 2);
        final double minLatDistance = LatLongUtils.latitudeDistance(1000);
        final double minLonDistance = LatLongUtils.longitudeDistance(1000, center.latitude);
        if ((maxLat - minLat) < minLatDistance) {
            maxLat = center.latitude + minLatDistance/2;
            minLat = center.latitude - minLatDistance/2;
        }
        if ((maxLon - minLon) < minLonDistance) {
            maxLon = center.longitude + minLonDistance/2;
            minLon = center.longitude - minLonDistance/2;
        }

        return new BoundingBox(minLat, minLon, maxLat, maxLon);
    }

    public void setTrack(final List<? extends GPSCoordinate> trackPoints) {
        setTracks(Collections.singletonList(trackPoints), null);
    }

    /**
     * Draws several tracks at once, each in its own color, and fits the view to their
     * common bounding box. Pass {@code null} to color every track with the configured
     * track color; otherwise {@code colors[i]} colors {@code tracks.get(i)}.
     */
    public void setTracks(final List<? extends List<? extends GPSCoordinate>> tracks, final int[] colors) {
        for (final Polyline trackPolyline : trackPolylines) {
            mapView.getLayerManager().getLayers().remove(trackPolyline);
            trackPolyline.onDestroy();
        }
        trackPolylines.clear();
        polyline = null;

        final Accumulator latitudeAccumulator = new Accumulator();
        final Accumulator longitudeAccumulator = new Accumulator();
        final List<List<LatLong>> allPoints = new ArrayList<>(tracks.size());
        boolean anyPoints = false;
        for (final List<? extends GPSCoordinate> trackPoints : tracks) {
            final List<LatLong> points = new ArrayList<>(trackPoints.size());
            for (final GPSCoordinate trackPoint : trackPoints) {
                latitudeAccumulator.add(trackPoint.getLatitude());
                longitudeAccumulator.add(trackPoint.getLongitude());
                points.add(new LatLong(trackPoint.getLatitude(), trackPoint.getLongitude()));
                anyPoints = true;
            }
            allPoints.add(points);
        }
        if (!anyPoints) {
            return;
        }

        final double maxLat = latitudeAccumulator.getMax();
        final double minLat = latitudeAccumulator.getMin();
        final double maxLon = longitudeAccumulator.getMax();
        final double minLon = longitudeAccumulator.getMin();
        final LatLong center = new LatLong(minLat + (maxLat - minLat) / 2, minLon + (maxLon - minLon) / 2);

        for (int i = 0; i < allPoints.size(); i++) {
            final Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
            final int trackColor = colors != null && i < colors.length
                    ? colors[i]
                    : mapDataLoader.resolveTrackColor();
            paint.setColor(trackColor);
            paint.setStrokeWidth(8);
            paint.setStyle(Style.STROKE);

            final Polyline trackPolyline = new Polyline(paint, AndroidGraphicFactory.INSTANCE);
            trackPolyline.setPoints(allPoints.get(i));
            mapView.addLayer(trackPolyline);
            trackPolylines.add(trackPolyline);
            if (colors == null && polyline == null) {
                polyline = trackPolyline;
            }
        }

        mapView.setCenter(center);

        final byte zoom = LatLongUtils.zoomForBounds(
                new Dimension(mapView.getWidth(), mapView.getHeight()),
                minimalBoundingBox(minLat, minLon, maxLat, maxLon),
                mapView.getModel().displayModel.getTileSize()
        );
        mapView.setZoomLevel(zoom);

        if (!tileRenderedAdded) {
            mapView.getLayerManager().getLayers().add(0, tileRendererLayer);
            tileRenderedAdded = true;
        }
    }

    public void reload() {
        if (polyline != null) {
            final int trackColor = mapDataLoader.resolveTrackColor();
            polyline.getPaintStroke().setColor(trackColor);
            polyline.requestRedraw();
        }

        loadMaps();
    }
}
