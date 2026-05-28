/*  Copyright (C) 2017-2024 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti, Pavel Elagin

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
package nodomain.freeyourgadget.gadgetbridge.webview;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.RemoteException;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.e175.klaus.solarpositioning.DeltaT;
import net.e175.klaus.solarpositioning.SPA;
import net.e175.klaus.solarpositioning.SunriseResult;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.internet.InternetFirewall;
import nodomain.freeyourgadget.gadgetbridge.internet.InternetRequestType;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.model.weather.WeatherMapper;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.InternetHelperSingleton;
import nodomain.freeyourgadget.internethelper.aidl.http.HttpRequest;

public class GBWebClient extends WebViewClient {
    private static final Logger LOG = LoggerFactory.getLogger(GBWebClient.class);

    private final InternetFirewall firewall;

    private final String[] LocallySupportedDomains = new String[]{
            "openweathermap.org",   //for weather :)
            "rawgit.com",           //for trekvolle
            "open-meteo.com",       // for even more weather
            "openstreetmap.org",    // for reverse geocoding
    };

    private final Map<String, List<Entry>> postData = new HashMap<>();
    private record Entry(long timestamp, String body) {}

    public GBWebClient(final InternetRequestType type, @NonNull final GBDevice device) {
        super();
        this.firewall = new InternetFirewall(type, device);
    }

    /**
     * Stores HTTP POST body strings for later retrieval in mimicReply()
     */
    public synchronized void storePostBody(String url, String body) {
        long now = System.currentTimeMillis();

        // Cleanup expired entries (more than one minute old)
        Iterator<Map.Entry<String, List<Entry>>> mapIt = postData.entrySet().iterator();
        while (mapIt.hasNext()) {
            Map.Entry<String, List<Entry>> mapEntry = mapIt.next();
            List<Entry> list = mapEntry.getValue();
            list.removeIf(e -> now - e.timestamp > 60*1000);
            if (list.isEmpty()) {
                mapIt.remove();
            }
        }

        // Add new entry
        postData
                .computeIfAbsent(url, k -> new ArrayList<>())
                .add(new Entry(now, body));
    }

    /**
     * Returns the earliest known HTTP POST body for a certain URL
     */
    private synchronized String getFirstPostForUrl(String url) {
        List<Entry> list = postData.get(url);
        if (list == null || list.isEmpty()) {
            return null;
        }

        Entry first = list.remove(0);
        if (list.isEmpty()) {
            postData.remove(url);
        }

        return first.body;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri parsedUri = request.getUrl();
        LOG.debug("WEBVIEW shouldInterceptRequest URL: {} (method {})", parsedUri.toString(), request.getMethod());
        WebResourceResponse mimickedReply = mimicReply(parsedUri, request.getMethod(), request.getRequestHeaders());
        if (mimickedReply != null)
            return mimickedReply;
        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        LOG.debug("WEBVIEW shouldInterceptRequest URL (legacy): {}", url);
        Uri parsedUri = Uri.parse(url);
        WebResourceResponse mimickedReply = mimicReply(parsedUri, "GET", new HashMap<>());
        if (mimickedReply != null)
            return mimickedReply;
        return super.shouldInterceptRequest(view, url);
    }

    private WebResourceResponse mimicReply(Uri requestedUri, String method, Map<String, String> requestHeaders) {
        GBPrefs prefs = GBApplication.getPrefs();
        boolean locallySupported = StringUtils.indexOfAny(requestedUri.getHost(), LocallySupportedDomains) != -1;
        boolean urlIsAllowed = locallySupported;
        boolean matchFound = false;

        // Allow full access to internet when available
        boolean directInternetAccess = GBApplication.hasDirectInternetAccess();
        if (directInternetAccess && !locallySupported) {
            return null;
        }

        // Handle local schemes locally
        if (requestedUri.toString().startsWith("file://") || requestedUri.toString().startsWith("gadgetbridge://")) {
            return null;
        }

        // Handle predefined groups
        urlIsAllowed = firewall.isAllowed(requestedUri);

        // Handle OpenWeatherMap locally
        boolean forceLocal = false;
        if (locallySupported && prefs.getBoolean("pref_key_internethelper_force_local", true)) {
            urlIsAllowed = true;
            forceLocal = true;
        }

        // Handle request
        if (requestedUri.getHost() != null && urlIsAllowed) {
            if (!forceLocal && !directInternetAccess && InternetHelperSingleton.INSTANCE.ensureInternetHelperBound()) {
                LOG.debug("WEBVIEW forwarding request to the internet helper");
                try {
                    HttpRequest.Method requestMethod = HttpRequest.Method.valueOf(method);
                    String body = null;
                    if (requestMethod == HttpRequest.Method.POST) {
                        body = getFirstPostForUrl(requestedUri.toString());
                        LOG.debug("WEBVIEW POSTing with body: {}", body);
                    }
                    WebResourceResponse wrr = InternetHelperSingleton.INSTANCE.send(requestedUri, requestMethod, requestHeaders, body != null ? body.getBytes(StandardCharsets.UTF_8) : null, false);
                    if (wrr != null && wrr.getStatusCode() < 400)
                        return wrr;
                    else
                        return null;
                } catch (RemoteException | InterruptedException e) {
                    LOG.warn("Error downloading data from {}", requestedUri, e);
                }
            } else {
                if (StringUtils.endsWith(requestedUri.getHost(), "openweathermap.org")){
                    LOG.debug("WEBVIEW request to openweathermap.org detected of type: {} params: {}", requestedUri.getPath(), requestedUri.getQuery());
                    return mimicOpenWeatherMapResponse(requestedUri.getPath(), requestedUri.getQueryParameter("units"));
                } else if (StringUtils.endsWith(requestedUri.getHost(), "rawgit.com")) {
                    LOG.debug("WEBVIEW request to rawgit.com detected of type: {} params: {}", requestedUri.getPath(), requestedUri.getQuery());
                    return mimicRawGitResponse(requestedUri.getPath());
                } else if (requestedUri.getHost().equals("api.open-meteo.com")) {
                    LOG.debug("WEBVIEW request to api.open-meteo.org with params: {}", requestedUri.getQuery());
                    return mimicOpenMeteoApiResponse(requestedUri);
                } else if (requestedUri.getHost().equals("air-quality-api.open-meteo.com")) {
                    LOG.debug("WEBVIEW request to air-quality-api.open-meteo.org with params: {}", requestedUri.getQuery());
                    return mimicOpenMeteoAirQualityResponse(requestedUri);
                } else if (requestedUri.getHost().equals("nominatim.openstreetmap.org")) {
                    LOG.debug("WEBVIEW request to nominatim.openstreetmap.org with params: {}", requestedUri.getQuery());
                    return mimicNominatimResponse(requestedUri.getPath(), requestedUri.getQueryParameter("format"));
                } else {
                    LOG.debug("WEBVIEW request to allowed domain detected but not intercepted: {}", requestedUri);
                }
            }
        } else {
            LOG.debug("WEBVIEW request not intercepted: {}", requestedUri);
        }
        return null;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        Uri parsedUri = Uri.parse(url);

        if (parsedUri.getScheme().startsWith("http")) {
            if (GBApplication.hasDirectInternetAccess() || (GBApplication.hasInternetAccess() && firewall.isAllowed(parsedUri))) {
                view.loadUrl(url);
            } else {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                GBApplication.getContext().startActivity(i);
            }
        } else if (parsedUri.getScheme().startsWith("gadgetbridge")) {
            url = url.replaceFirst("^gadgetbridge://.*json=", "file:///android_asset/app_config/configure.html?config=true&json=");
            view.loadUrl(url);
        } else if (parsedUri.getScheme().startsWith("pebblejs")) {
            url = url.replaceFirst("^pebblejs://close#", "file:///android_asset/app_config/configure.html?config=true&json=");
            view.loadUrl(url);
        } else if (parsedUri.getScheme().equals("data")) { //clay
            view.loadUrl(url);
        } else {
            LOG.debug("WEBVIEW Ignoring unhandled scheme: {}", parsedUri.getScheme());
        }

        return true;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return shouldOverrideUrlLoading(view, request.getUrl().toString());
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        injectPostInterceptor(view);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        injectPostInterceptor(view);
    }

    private void injectPostInterceptor(WebView view) {
        // Inject JavaScript for capturing POST body data, which we need when forwarding
        // requests to the internet helper
        view.evaluateJavascript(
            """
            (function () {
               if (window.__p) return; window.__p = 1;
               const sb = b => b instanceof FormData
                 ? [...b].map(e => e.map(encodeURIComponent).join('=')).join('&')
                 : b ? b.toString() : null;

               const send = (u, b) => GBReqInt.onPostBody(new URL(u, location.href).toString(), sb(b));

               const xo = XMLHttpRequest.prototype.open;
               const xs = XMLHttpRequest.prototype.send;
               XMLHttpRequest.prototype.open = function (m, u) {
                 this._m = m; this._u = u; return xo.apply(this, arguments);
               };
               XMLHttpRequest.prototype.send = function (b) {
                 if (this._m === 'POST') send(this._u, b);
                 return xs.apply(this, arguments);
               };

               const f = window.fetch;
               window.fetch = function (i, o = {}) {
                 if ((o.method || 'GET') === 'POST')
                   send(typeof i === 'string' ? i : i.url, o.body);
                 return f.apply(this, arguments);
               };

               document.addEventListener('submit', e => {
                 const f = e.target;
                 if (f.method && f.method.toUpperCase() === 'POST')
                   send(f.action || location.href, new FormData(f));
               }, true);
            })();
            """,
            null
        );
    }

    private WebResourceResponse mimicRawGitResponse(String path) {
        if("/aHcVolle/TrekVolle/master/online.html".equals(path)) { //TrekVolle online check
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK",
                    headers,
                    new ByteArrayInputStream("1".getBytes())
            );
        }

        return null;
    }

    /**
     * Creates a web response similar to nominatim.openstreetmap.org, just to give the location name
     * @param format Format name for response, only "jsonv2" is implemented
     * @return Web response object, or null if no weather is available or JSON fails
     */
    @Nullable
    private WebResourceResponse mimicNominatimResponse(String path, String format) {
        if (! "/reverse".equals(path)) {
            LOG.warn("WEBVIEW - nominatim mimic does not support path {}", path);
            return null;
        }

        WeatherSpec currWeather = Weather.getWeatherSpec();
        if (currWeather == null) {
            LOG.warn("WEBVIEW - no weather spec, cannot give response as nominatim");
            return null;
        }

        if (currWeather.getLocation() == null) {
            LOG.warn("WEBVIEW - no weather location, cannot reply as nominatim");
            return null;
        }

        if ("jsonv2".equals(format)) {
            try {
                JSONObject responseJson = new JSONObject();
                responseJson.put("name", currWeather.getLocation());
                LOG.info("WEBVIEW - mimicked nominatim response: {}", responseJson.toString());
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                return new WebResourceResponse("application/json", "utf-8", 200, "OK",
                        headers,
                        new ByteArrayInputStream(responseJson.toString().getBytes())
                );
            } catch (JSONException e) {
                LOG.warn("WEBVIEW error building the JSON message for nominatim.", e);
            }
        } else {
            LOG.warn("WEBVIEW nominatim reverse search mimic does not support format {}", format);
        }
        return null;
    }

    /**
     * Creates a web response similar to an api.open-meteo.com response
     * @param requestedUri URI being requested, which may have many parameters
     * @return Web response object, or null if no weather is available or JSON fails
     */
    @Nullable
    private WebResourceResponse mimicOpenMeteoApiResponse(@NonNull Uri requestedUri) {

        WeatherSpec weather = Weather.getWeatherSpec();
        if (weather == null) {
            LOG.warn("WEBVIEW - no weather data, cannot give response as api.open-meteo");
            return null;
        }
        ZoneId responseTZ;
        String timezoneString = requestedUri.getQueryParameter("timezone");
        try {
            if (timezoneString.equals("auto")) {  // if null, catch to UTC
                responseTZ = ZoneId.systemDefault();
            } else {
                responseTZ = ZoneId.of(timezoneString);
            }
        } catch (Exception e) {
            responseTZ = ZoneId.of("UTC");
        }

        try {
            JSONObject responseJson = new JSONObject();
            populateOmwCurrentJson(responseJson, weather, requestedUri, responseTZ);
            populateOmwDailyJson(responseJson, weather, requestedUri, responseTZ);
            LOG.info("WEBVIEW - mimicked open-meteo weather response: {}", responseJson.toString());

            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse("application/json", "utf-8", 200, "OK",
                        headers,
                        new ByteArrayInputStream(responseJson.toString().getBytes())
            );
        } catch (JSONException e) {
            LOG.warn("Error building the JSON weather message.", e);
        }
        return null;
    }

    /**
     * Fills in the JSON structure of an open-meteo response using the given weather info
     * @param responseJson The JSON object holding the top level response data
     * @param currWeather The relevant weather spec
     * @param requestUri The URI of the request
     * @param responseTZ Zone ID to use for formatting timestamps
     * @throws JSONException Exception if JSON can't be manipulated
     */
    private static void populateOmwCurrentJson(JSONObject responseJson, WeatherSpec currWeather, @NonNull Uri requestUri, ZoneId responseTZ) throws JSONException {
        JSONObject units = new JSONObject();
        populateOmwUnitsFromUrlKey(units, requestUri, "current");
        if (units.length() < 1) return;

        JSONObject values = new JSONObject();

        Iterator<String> unitIterator = units.keys();

        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone( responseTZ );

        while (unitIterator.hasNext()) {
            String item = unitIterator.next();
            String unit = units.getString(item);

            String value = null;
            switch (item) {
                case "time" ->
                        value = dateTimeFormatter.format(Instant.ofEpochSecond(currWeather.getTimestamp()));
                case "temperature_2m" -> {
                    // The API returns a single-decimal float, but we only have integer precision
                    if (unit.equals("°F")) {
                        value = String.valueOf(currWeather.getCurrentTempInFahrenheit());
                    } else {
                        value = String.valueOf(currWeather.getCurrentTempInCelsius());
                    }
                }
                case "cloud_cover" ->
                        value = String.valueOf(currWeather.getCloudCover());
                case "precipitation" ->
                        value = "0.00"; // we have no precipitation data
                case "weather_code" ->
                        value = String.valueOf(WeatherMapper.mapToWMOCondition(currWeather.getCurrentConditionCode()));
                case "wind_speed_10m" ->
                        value = switch (unit) {
                            // These are returned with a single decimal place, always with "." as the decimal separator
                            case "m/s" -> String.format(Locale.US, "%.1f", currWeather.getWindSpeedMetersPerSecond());
                            case "mph" -> String.format(Locale.US, "%.1f", currWeather.getWindSpeedMilesPerHour());
                            case "kn" -> String.format(Locale.US, "%.1f", currWeather.getWindSpeedKnots());
                            default -> String.format(Locale.US, "%.1f", currWeather.getWindSpeed());
                        };
            }

            if ( value != null ) {
                values.put(item, value);
            } else {
                LOG.debug("WEBVIEW open-meteo mimic: TODO support {} for current weather", item);
            }
        }

        responseJson.put("current_units", units);
        responseJson.put("current", values);
    }

    /**
     * Populates the units for the elements request for a Open-Meteo URL
     * @param units JSONObject where unit keys will be stored, like "time": "iso8601"
     * @param requestUri URL for the request, including parameters like "daily" or "temperature_unit"
     * @param urlParamForScope URL parameter with the request scope, like "current" or "daily"
     * @throws JSONException Exception if JSON cannot be manipulated
     */
    private static void populateOmwUnitsFromUrlKey(JSONObject units, @NonNull Uri requestUri, @NonNull String urlParamForScope) throws JSONException {
        String requestedElementsDelimList = requestUri.getQueryParameter(urlParamForScope);
        if (requestedElementsDelimList == null) return;

        // Always include time
        units.put("time", "iso8601");
        String[] requestedItems = requestedElementsDelimList.split(",");

        // Populate the units for all requested items
        for (String item : requestedItems) {
            if (item.equals("cloud_cover")) {
                units.put(item, "%");
            } else if (item.startsWith("temperature_")) {
                if ("fahrenheit".equals(requestUri.getQueryParameter("temperature_unit"))) {
                    units.put(item, "°F");
                } else {
                    units.put(item, "°C");
                }
            } else if (item.equals("sunrise")) {
                units.put(item, "iso8601");
            } else if (item.equals("sunset")) {
                units.put(item, "iso8601");
            } else if (item.equals("precipitation")) {
                String precipitation_unit = requestUri.getQueryParameter("precipitation_unit");
                if ("inch".equals(precipitation_unit)) {
                    units.put(item, "inch");
                } else {
                    units.put(item, "mm");
                }
            } else if (item.equals("weather_code")) {
                units.put(item, "wmo code");
            } else if (item.startsWith("wind_speed_")) {
                String windSpeedUnit = requestUri.getQueryParameter("wind_speed_unit");
                if ("ms".equals(windSpeedUnit)) {
                    units.put(item, "m/s");
                } else if ("mph".equals(windSpeedUnit)) {
                    units.put(item, "mp/h");
                } else if ("kn".equals(windSpeedUnit)) {
                    units.put(item, "kn");
                } else {
                    units.put(item, "km/h");
                }
            } else {
                LOG.debug("WEBVIEW open-meteo mimic: TODO recognize units for {} in {}", item, urlParamForScope);
            }
        }
    }

    /**
     * Fills in the JSON structure of an open-meteo response using the given weather info
     * @param responseJson The JSON object holding the top level response data
     * @param currWeather The relevant weather spec
     * @param requestUri The URI of the request
     * @param responseTZ Time zone to use for response timestamps
     * @throws JSONException Exception if JSON can't be manipulated
     */
    private static void populateOmwDailyJson(JSONObject responseJson, WeatherSpec currWeather, @NonNull Uri requestUri, ZoneId responseTZ) throws JSONException {
        // Determine forecast range, default to 7 days
        int forecastDays = 7;
        String requestedDayCount = requestUri.getQueryParameter("forecast_days");
        if ( requestedDayCount != null) {
            try {
                forecastDays = Integer.parseInt(requestedDayCount);
            } catch (NumberFormatException ignored) {
                LOG.info("WEBVIEW open-meteo mimic: cannot parse requested forecast days: {}", requestedDayCount);
                return;
            }
        }

        if ( (forecastDays-1) > currWeather.getForecasts().size()) {
            LOG.debug("WEBVIEW open-meteo mimic: decreasing daily forecast count from {} because only {} future days are available", forecastDays, currWeather.getForecasts().size());
            forecastDays = currWeather.getForecasts().size() + 1;
        }

        JSONObject units = new JSONObject();
        populateOmwUnitsFromUrlKey(units, requestUri, "daily");
        if (units.length() < 1) return;

        JSONObject values = new JSONObject();

        Instant weatherInst = Instant.ofEpochSecond(currWeather.getTimestamp());
        // create entry for today as "0", future days starting at "1"
        addOneDaytToOmwDailyJson(units, values, currWeather.todayAsDaily(), weatherInst, 0, responseTZ);
        for (int dayIndex = 1; dayIndex < forecastDays; dayIndex++) {
            addOneDaytToOmwDailyJson(units, values, currWeather.getForecasts().get(dayIndex-1), weatherInst, dayIndex, responseTZ);
        }

        responseJson.put("daily_units", units);
        responseJson.put("daily", values);
    }

    /**
     * Populates one day's measurements from a WeatherSpec.Daily forecast in the open-meteo "daily" response sections
     * @param unitsRoot JSON root where each measurement is given with a string for its units
     * @param dailyValues JSON root where daily values are / will be stored
     * @param daily Daily weather object
     * @param todayInstant Instant containing "today" for the purposes of date generation
     * @param dayIndex JSON key to place this day's values, like 0, 1, 2, ...
     * @throws JSONException If JSON can't be manipulated
     */
    private static void addOneDaytToOmwDailyJson(JSONObject unitsRoot, JSONObject dailyValues, WeatherSpec.Daily daily, @NonNull Instant todayInstant, int dayIndex, ZoneId responseTz) throws JSONException {

        Iterator<String> unitIterator = unitsRoot.keys();

        DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone( responseTz );
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone( responseTz); // note that this includes seconds, which the real open-meteo doesn't include

        while (unitIterator.hasNext()) {
            String item = unitIterator.next();
            String unit = unitsRoot.getString(item);
            // Get the existing JSON group for this item
            JSONObject itemRoot;
            try {
                itemRoot = dailyValues.getJSONObject(item);
            } catch (JSONException e) {
                itemRoot = new JSONObject();
            }

            String value = null;
            switch (item) {
                case "time" ->
                        value = dateFormatter.format(todayInstant.plus(dayIndex, ChronoUnit.DAYS));
                case "sunset" ->
                        value = dateTimeFormatter.format(Instant.ofEpochSecond(daily.getSunSet()));
                case "sunrise" ->
                        value = dateTimeFormatter.format(Instant.ofEpochSecond(daily.getSunRise()));
                case "precipitation" ->
                        value = "0.00"; // we have no precipitation data
                case "temperature_2m_max" -> {
                    if (unit.equals("°F")) {
                        value = String.valueOf(daily.getMaxTempFahrenheit());
                    } else {
                        value = String.valueOf(daily.getMaxTempCelsius());
                    }
                }
                case "temperature_2m_min" -> {
                    if (unit.equals("°F")) {
                        value = String.valueOf(daily.getMinTempFahrenheit());
                    } else {
                        value = String.valueOf(daily.getMinTempCelsius());
                    }
                }
                case "weather_code" ->
                        value = String.valueOf(WeatherMapper.mapToWMOCondition(daily.getConditionCode()));
            }

            if ( value == null ) {
                LOG.debug("WEBVIEW open-meteo daily weather: TODO no daily value for {} on day {}", item, dayIndex);
                continue;
            }

            itemRoot.put(String.valueOf(dayIndex) ,value);
            dailyValues.put(item, itemRoot);
        }
    }

    /**
     * Creates a web response similar to an air-quality-api.open-meteo.com response
     * @param requestedUri URI being requested, which may have many parameters
     * @return Web response object, or null if no weather is available or JSON fails
     */
    @Nullable
    private WebResourceResponse mimicOpenMeteoAirQualityResponse(@NonNull Uri requestedUri) {

        WeatherSpec weather = Weather.getWeatherSpec();
        if (weather == null) {
            LOG.warn("WEBVIEW - no weather data, cannot give response as air-quality-api.open-meteo");
            return null;
        }

        try {
            JSONObject responseJson = new JSONObject();
            populateOmaqCurrentJson(responseJson, weather, requestedUri);
            LOG.info("WEBVIEW - mimicked open-meteo air-quality response: {}", responseJson.toString());

            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse("application/json", "utf-8", 200, "OK",
                    headers,
                    new ByteArrayInputStream(responseJson.toString().getBytes())
            );
        } catch (JSONException e) {
            LOG.warn("Error building the JSON air quality message.", e);
        }
        return null;
    }

    /**
     * Gets current air quality conditions into JSON like air-quality-api.open-meteo.com
     * @param responseJson JSONObject root of JSON data
     * @param currWeather WeatherSpec of current weather data
     * @param requestUri URI being requested
     * @throws JSONException If JSON cannot be manipulated
     */
    private static void populateOmaqCurrentJson(JSONObject responseJson, WeatherSpec currWeather, @NonNull Uri requestUri) throws JSONException {
        if (currWeather.getAirQuality() == null) return;
        String requestedElementsDelimList = requestUri.getQueryParameter("current");
        if (requestedElementsDelimList == null) return;

        JSONObject units = new JSONObject();
        JSONObject values = new JSONObject();

        String[] requestedItems = requestedElementsDelimList.split(",");

        for (String item : requestedItems) {
            if ("us_aqi".equals(item)) {
                units.put(item, "USAQI");
                values.put(item, currWeather.getAirQuality().getAqi());
            } else {
                LOG.debug("WEBVIEW open-meteo mimic: TODO support {} in current air quality", item);
            }
        }

        responseJson.put("current_units", units);
        responseJson.put("current", values);

    }

    private WebResourceResponse mimicOpenWeatherMapResponse(String type, String units) {

        if (Weather.getWeatherSpecs().isEmpty()) {
            LOG.warn("WEBVIEW - WeatherSpecs is empty, cannot update weather");
            return null;
        }

        CurrentPosition currentPosition = new CurrentPosition();
        WeatherSpec current = Weather.getWeatherSpec();

        try {
            JSONObject resp = createReconstructedOWMWeatherReply(current);
            if ("/data/2.5/weather".equals(type) && resp != null) {
                JSONObject main = resp.getJSONObject("main");

                convertTemps(main, units); //caller might want different units
                JSONObject wind = resp.getJSONObject("wind");
                convertSpeeds(wind, units);

                resp.put("cod", 200);
                resp.put("coord", coordObject(currentPosition));
                resp.put("sys", sysObject(currentPosition));
//            } else if ("/data/2.5/forecast".equals(type) && Weather.getWeather2().reconstructedOWMForecast != null) { //this is wrong, as we only have daily data. Unfortunately it looks like daily forecasts cannot be reconstructed
//                resp = new JSONObject(Weather.getWeather2().reconstructedOWMForecast.toString());
//
//                JSONObject city = resp.getJSONObject("city");
//                city.put("coord", coordObject(currentPosition));
//
//                JSONArray list = resp.getJSONArray("list");
//                for (int i = 0, size = list.length(); i < size; i++) {
//                    JSONObject item = list.getJSONObject(i);
//                    JSONObject main = item.getJSONObject("main");
//                    convertTemps(main, units); //caller might want different units
//                }
//
//                resp.put("cod", 200);
            } else {
                LOG.warn("WEBVIEW - cannot mimick request of type {} (unsupported or lack of data)", type);
                return null;
            }

            LOG.info("WEBVIEW - mimic openweather response {}", resp.toString());
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");

            return new WebResourceResponse("application/json", "utf-8", 200, "OK",
                    headers,
                    new ByteArrayInputStream(resp.toString().getBytes())
            );
        } catch (JSONException e) {
            LOG.warn("Error building the JSON weather message.", e);
        }

        return null;

    }

    public JSONObject createReconstructedOWMWeatherReply(WeatherSpec weatherSpec) {
        if (weatherSpec == null) {
            return null;
        }
        JSONObject reconstructedOWMWeather = new JSONObject();
        JSONArray weather = new JSONArray();
        JSONObject condition = new JSONObject();
        JSONObject main = new JSONObject();
        JSONObject wind = new JSONObject();

        try {
            condition.put("id", weatherSpec.getCurrentConditionCode());
            condition.put("main", weatherSpec.getCurrentCondition());
            condition.put("description", weatherSpec.getCurrentCondition());
            condition.put("icon", WeatherMapper.mapToOpenWeatherMapIcon(weatherSpec.getCurrentConditionCode(),weatherSpec.isNight()));
            weather.put(condition);


            main.put("temp", weatherSpec.getCurrentTemp());
            main.put("humidity", weatherSpec.getCurrentHumidity());
            main.put("temp_min", weatherSpec.getTodayMinTemp());
            main.put("temp_max", weatherSpec.getTodayMaxTemp());

            wind.put("speed", (weatherSpec.getWindSpeed() / 3.6f)); //meter per second
            wind.put("deg", weatherSpec.getWindDirection());

            reconstructedOWMWeather.put("weather", weather);
            reconstructedOWMWeather.put("main", main);
            reconstructedOWMWeather.put("name", weatherSpec.getLocation());
            reconstructedOWMWeather.put("wind", wind);

        } catch (JSONException e) {
            LOG.error("Error while reconstructing OWM weather reply");
            return null;
        }
        LOG.debug("Weather JSON for WEBVIEW: {}", reconstructedOWMWeather);
        return reconstructedOWMWeather;
    }


    private static JSONObject sysObject(CurrentPosition currentPosition) throws JSONException {
        final SunriseResult sunriseResult = SPA.calculateSunriseTransitSet(
                ZonedDateTime.now(),
                currentPosition.getLatitude(),
                currentPosition.getLongitude(),
                DeltaT.estimate(LocalDate.now())
        );

        long epochSunrise = 0;
        long epochSunset = 0;
        if (sunriseResult instanceof SunriseResult.RegularDay regularDay) {
            epochSunrise = regularDay.sunrise().toInstant().getEpochSecond();
            epochSunset = regularDay.sunset().toInstant().getEpochSecond();
        }

        JSONObject sys = new JSONObject();
        sys.put("country", "World");
        sys.put("sunrise", epochSunrise);
        sys.put("sunset", epochSunset);

        return sys;
    }

    private static void convertSpeeds(JSONObject wind, String units) throws JSONException {
        if ("metric".equals(units)) {
            wind.put("speed", (wind.getDouble("speed") * 3.6f) );
        } else if ("imperial".equals(units)) { //it's 2018... this is so sad
            wind.put("speed", (wind.getDouble("speed") * 2.237f) );
        }
    }

    private static void convertTemps(JSONObject main, String units) throws JSONException {
        if ("metric".equals(units)) {
            main.put("temp", (int) main.get("temp") - 273);
            main.put("temp_min", (int) main.get("temp_min") - 273);
            main.put("temp_max", (int) main.get("temp_max") - 273);
        } else if ("imperial".equals(units)) { //it's 2017... this is so sad
            main.put("temp", ((int) (main.get("temp")) - 273.15f) * 1.8f + 32);         // lgtm [java/integer-multiplication-cast-to-long]
            main.put("temp_min", ((int) (main.get("temp_min")) - 273.15f) * 1.8f + 32); // lgtm [java/integer-multiplication-cast-to-long]
            main.put("temp_max", ((int) (main.get("temp_max")) - 273.15f) * 1.8f + 32); // lgtm [java/integer-multiplication-cast-to-long]
        }
    }

    private static JSONObject coordObject(CurrentPosition currentPosition) throws JSONException {
        JSONObject coord = new JSONObject();
        coord.put("lat", currentPosition.getLatitude());
        coord.put("lon", currentPosition.getLongitude());
        return coord;
    }
}
